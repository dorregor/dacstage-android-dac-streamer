<#
.SYNOPSIS
    Cliente de pruebas de DAC Stage: se comporta como una TV frente al host (TCP, JSON por líneas).

.DESCRIPTION
    Empareja (muestra un código de 6 dígitos que hay que escribir en el host) y guarda el token en
    hostctl.tokens.json junto a este script (ignorado por git). Compatible con Windows PowerShell 5.1.

    Subcomandos:
      state                 Estado completo (snapshot) en JSON.
      watch                 Snapshot y luego cada parche según llega (Ctrl+C para salir).
      play-pause | play | pause | next | prev | mute | refresh
      jump <key>            Salta a la pista de la cola con esa clave (p. ej. uapp:3:12345).
      vol-up [n] | vol-down [n]   n pasos de 1 dB (repeatCount 0..n-1).
      raw '<json>'          Envía una línea tal cual (tras autenticar) y muestra 3 s de respuestas.
      raw-noauth '<json>'   Igual, pero sin autenticar (solo Hello).
      forget                Borra el token guardado para ese host.

.EXAMPLE
    .\hostctl.ps1 -Host 192.168.1.20 state
    .\hostctl.ps1 -Host 192.168.1.20 vol-down 3
    .\hostctl.ps1 -Host 192.168.1.20 raw '{"type":"cmd","id":"x1","command":{"type":"vol_step","deltaDb":5}}'
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [Parameter(Mandatory = $true)][Alias('Host')][string]$HostAddress,
    [int]$Port = 47820,
    [string]$DeviceName = 'hostctl (PowerShell)',
    [Parameter(Position = 0)][string]$Command = 'state',
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)][string[]]$Rest
)

Set-StrictMode -Version 2
$ErrorActionPreference = 'Stop'
$ProtocolVersion = 1
$StoreFile = Join-Path $PSScriptRoot 'hostctl.tokens.json'
$Utf8 = New-Object System.Text.UTF8Encoding($false)
$script:pending = New-Object System.IO.MemoryStream
$script:readBuf = New-Object byte[] 65536
$script:lastSent = [DateTime]::UtcNow
$script:cmdCounter = 0
$script:state = $null
$script:client = $null
$script:stream = $null

function Fail([string]$Message) {
    Write-Host $Message -ForegroundColor Red
    exit 1
}

# ---------- Persistencia local (deviceId + tokens por hostId) ----------
function Load-Store {
    $s = @{ deviceId = $null; tokens = @{} }
    if (Test-Path $StoreFile) {
        $o = Get-Content -Raw -Encoding UTF8 $StoreFile | ConvertFrom-Json
        if ($o.PSObject.Properties['deviceId']) { $s.deviceId = $o.deviceId }
        if ($o.PSObject.Properties['tokens'] -and $o.tokens) {
            foreach ($p in $o.tokens.PSObject.Properties) { $s.tokens[$p.Name] = [string]$p.Value }
        }
    }
    if (-not $s.deviceId) { $s.deviceId = 'hostctl-' + [guid]::NewGuid().ToString('N').Substring(0, 12) }
    return $s
}

function Save-Store($s) {
    ($s | ConvertTo-Json -Depth 5) | Set-Content -Encoding UTF8 $StoreFile
}

# ---------- Cripto ----------
function Get-HmacHex([string]$Key, [string]$Message) {
    $h = New-Object System.Security.Cryptography.HMACSHA256 -ArgumentList (, $Utf8.GetBytes($Key))
    try { $bytes = $h.ComputeHash($Utf8.GetBytes($Message)) } finally { $h.Dispose() }
    return -join ($bytes | ForEach-Object { $_.ToString('x2') })
}

function New-PairingCode {
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $b = New-Object byte[] 4
    $rng.GetBytes($b)
    $rng.Dispose()
    return '{0:D6}' -f ([BitConverter]::ToUInt32($b, 0) % 1000000)
}

# ---------- Transporte (líneas UTF-8, ping cada 15 s mientras se espera) ----------
function Send-Line([string]$Line) {
    $b = $Utf8.GetBytes($Line + "`n")
    $script:stream.Write($b, 0, $b.Length)
    $script:stream.Flush()
    $script:lastSent = [DateTime]::UtcNow
}

function Send-Msg($Obj) { Send-Line (ConvertTo-Json $Obj -Compress -Depth 10) }

# Devuelve la línea, '' si vence el plazo (0 = sin plazo) o $null si el host cerró.
function Read-Line([int]$TimeoutMs) {
    $deadline = [DateTime]::UtcNow.AddMilliseconds($TimeoutMs)
    while ($true) {
        if ($script:pending.Length -gt 0) {
            $arr = $script:pending.ToArray()
            $idx = [Array]::IndexOf($arr, [byte]10)
            if ($idx -ge 0) {
                $line = $Utf8.GetString($arr, 0, $idx).TrimEnd("`r")
                $script:pending = New-Object System.IO.MemoryStream
                $script:pending.Write($arr, $idx + 1, $arr.Length - $idx - 1)
                return $line
            }
        }
        # El host cierra a los 45 s sin tráfico de la TV, aunque él sí esté enviando parches.
        if (([DateTime]::UtcNow - $script:lastSent).TotalSeconds -ge 15) { Send-Msg ([ordered]@{ type = 'ping' }) }
        try {
            if ($script:client.Client.Poll(50000, [System.Net.Sockets.SelectMode]::SelectRead)) {
                if ($script:client.Client.Available -eq 0) { return $null }
                $n = $script:stream.Read($script:readBuf, 0, $script:readBuf.Length)
                if ($n -le 0) { return $null }
                $script:pending.Write($script:readBuf, 0, $n)
                continue
            }
        } catch [System.IO.IOException] {
            return $null
        } catch [System.Net.Sockets.SocketException] {
            return $null
        }
        if ($TimeoutMs -gt 0 -and [DateTime]::UtcNow -gt $deadline) { return '' }
    }
}

function ConvertFrom-JsonLine([string]$Line) {
    if ($Line.Length -lt 1500000) { return ($Line | ConvertFrom-Json) }
    # ConvertFrom-Json de PS 5.1 tiene un tope de ~2 MB: para snapshots enormes, JavaScriptSerializer.
    Add-Type -AssemblyName System.Web.Extensions
    $ser = New-Object System.Web.Script.Serialization.JavaScriptSerializer
    $ser.MaxJsonLength = [int]::MaxValue
    return $ser.DeserializeObject($Line)
}

# Siguiente mensaje; $null si vence el plazo. Si el host cierra, termina.
function Read-Msg([int]$TimeoutMs = 10000) {
    while ($true) {
        $line = Read-Line $TimeoutMs
        if ($null -eq $line) { Fail 'El host cerró la conexión.' }
        if ($line -eq '') { return $null }
        try { return (ConvertFrom-JsonLine $line) } catch { Write-Warning "Línea no JSON del host: $line" }
    }
}

# ---------- Estado ----------
function Apply-StateMsg($m) {
    if ($m.type -eq 'snapshot') { $script:state = $m.state; return $true }
    if ($m.type -eq 'patch' -and $null -ne $script:state) {
        foreach ($p in $m.changes.PSObject.Properties) { $script:state.($p.Name) = $p.Value }
        return $true
    }
    return $false
}

function Format-Summary {
    $s = $script:state
    if ($null -eq $s) { return '(sin estado)' }
    $parts = @("$($s.status)")
    if ($s.player) { $parts += "$($s.player)" }
    if ($s.track) { $parts += ('{0} - {1}' -f $s.track.artist, $s.track.title) }
    if ($s.specs -and $s.specs.sampleRateHz) { $parts += ('{0} Hz' -f $s.specs.sampleRateHz.value) }
    if ($s.dac -and $null -ne $s.dac.volumeDb) {
        $v = 'vol {0:N1} dB' -f [double]$s.dac.volumeDb
        if ($s.dac.muted) { $v += ' (mute)' }
        if ($null -ne $s.dac.capDb) { $v += (' tope {0:N1}' -f [double]$s.dac.capDb) }
        $parts += $v
    } elseif ($s.dac) { $parts += "DAC $($s.dac.control)" }
    if ($s.art) { $parts += ('art {0}x{1} {2}' -f $s.art.widthPx, $s.art.heightPx, $s.art.source) }
    return ($parts -join ' | ')
}

# ---------- Sesión ----------
function Connect-Host($store) {
    $script:client = New-Object System.Net.Sockets.TcpClient
    $script:client.NoDelay = $true
    $iar = $script:client.BeginConnect($HostAddress, $Port, $null, $null)
    if (-not $iar.AsyncWaitHandle.WaitOne(5000)) { Fail "No se pudo conectar con ${HostAddress}:$Port" }
    try { $script:client.EndConnect($iar) } catch { Fail "No se pudo conectar con ${HostAddress}:${Port}: $($_.Exception.Message)" }
    $script:stream = $script:client.GetStream()

    Send-Msg ([ordered]@{ type = 'hello'; protocolVersion = $ProtocolVersion; deviceId = $store.deviceId; deviceName = $DeviceName })
    $ch = Read-Msg 10000
    if ($null -eq $ch) { Fail 'El host no respondió al Hello.' }
    if ($ch.type -eq 'auth_fail') { Fail "Rechazado: $($ch.reason)" }
    if ($ch.type -ne 'challenge') { Fail "Respuesta inesperada: $($ch.type)" }
    return $ch
}

function Authenticate($store, $ch) {
    $hostId = [string]$ch.hostId
    $nonce = [string]$ch.nonce
    $token = $null
    if ($store.tokens.ContainsKey($hostId)) { $token = $store.tokens[$hostId] }

    if (-not $token) {
        $code = New-PairingCode
        Write-Host ''
        Write-Host ("  Emparejar con '{0}': escribe en el host el código  {1}" -f $ch.hostName, $code) -ForegroundColor Yellow
        Write-Host ''
        Send-Msg ([ordered]@{ type = 'pair_request'; deviceId = $store.deviceId; deviceName = $DeviceName; proof = (Get-HmacHex $code $nonce) })
        while (-not $token) {
            $m = Read-Msg 600000
            if ($null -eq $m) { Fail 'Tiempo de emparejado agotado.' }
            switch ($m.type) {
                'pair_pending' { Write-Host 'Esperando confirmación en el host...' }
                'pair_result' {
                    if (-not $m.ok) { Fail "Emparejado rechazado: $($m.reason)" }
                    $token = [string]$m.token
                    $store.tokens[$hostId] = $token
                    Save-Store $store
                    Write-Host "Emparejado. Token guardado en $StoreFile" -ForegroundColor Green
                }
                'auth_fail' { Fail "Rechazado: $($m.reason)" }
            }
        }
    }

    Send-Msg ([ordered]@{ type = 'auth'; hmac = (Get-HmacHex $token $nonce) })
    while ($true) {
        $m = Read-Msg 10000
        if ($null -eq $m) { Fail 'Sin respuesta a la autenticación.' }
        if ($m.type -eq 'auth_fail') {
            if (@('bad_token', 'unknown_device', 'unpaired') -contains $m.reason) {
                $store.tokens.Remove($hostId)
                Save-Store $store
                Fail "El host no reconoce el token ($($m.reason)). Vuelve a ejecutar para emparejar."
            }
            Fail "Rechazado: $($m.reason)"
        }
        if ($m.type -eq 'auth_ok') { break }
    }
    # Snapshot inicial
    while ($null -eq $script:state) {
        $m = Read-Msg 10000
        if ($null -eq $m) { Fail 'No llegó el snapshot.' }
        [void](Apply-StateMsg $m)
    }
}

function Invoke-Cmd($CommandObj) {
    $script:cmdCounter++
    $id = 'h' + $script:cmdCounter
    Send-Msg ([ordered]@{ type = 'cmd'; id = $id; command = $CommandObj })
    while ($true) {
        $m = Read-Msg 15000
        if ($null -eq $m) { Fail "Sin resultado para $id" }
        if (Apply-StateMsg $m) { continue }
        if ($m.type -eq 'cmd_result' -and $m.id -eq $id) {
            $r = $m.result
            if ($r.ok) { Write-Host ("{0} {1}: OK" -f $id, $CommandObj.type) -ForegroundColor Green }
            else { Write-Host ("{0} {1}: ERROR {2} {3}" -f $id, $CommandObj.type, $r.error, $r.message) -ForegroundColor Red }
            return $r
        }
    }
}

# Recoge parches durante un rato (para ver el efecto de un comando).
function Drain([int]$Ms) {
    $until = [DateTime]::UtcNow.AddMilliseconds($Ms)
    while ([DateTime]::UtcNow -lt $until) {
        $m = Read-Msg 100
        if ($null -ne $m) { [void](Apply-StateMsg $m) }
    }
}

function Show-Raw([int]$Ms) {
    $until = [DateTime]::UtcNow.AddMilliseconds($Ms)
    while ([DateTime]::UtcNow -lt $until) {
        $line = Read-Line 200
        if ($null -eq $line) { Write-Host '<< (el host cerró la conexión)' -ForegroundColor Yellow; return }
        if ($line -ne '') {
            if ($line.Length -gt 400) { $line = $line.Substring(0, 400) + '...' }
            Write-Host "<< $line"
        }
    }
}

# ---------- Principal ----------
$store = Load-Store
$cmd = $Command.ToLowerInvariant()
$arg0 = $null
if ($Rest -and $Rest.Count -gt 0) { $arg0 = $Rest[0] }

if ($cmd -eq 'forget') {
    Write-Host 'Conectando para saber el id del host...'
}

try {
    $ch = Connect-Host $store
    if ($cmd -eq 'forget') {
        $store.tokens.Remove([string]$ch.hostId)
        Save-Store $store
        Write-Host "Token de '$($ch.hostName)' borrado."
        exit 0
    }
    if ($cmd -eq 'raw-noauth') {
        if (-not $arg0) { Fail "Uso: raw-noauth '<json>'" }
        Write-Host ">> $arg0"
        Send-Line $arg0
        Show-Raw 3000
        exit 0
    }

    Authenticate $store $ch

    switch ($cmd) {
        'state' {
            Write-Host (Format-Summary) -ForegroundColor Cyan
            $script:state | ConvertTo-Json -Depth 20
        }
        'watch' {
            Write-Host (Format-Summary) -ForegroundColor Cyan
            while ($true) {
                $m = Read-Msg 0
                if ($null -eq $m) { continue }
                if ($m.type -eq 'patch') {
                    [void](Apply-StateMsg $m)
                    foreach ($p in $m.changes.PSObject.Properties) {
                        $v = ConvertTo-Json $p.Value -Compress -Depth 20
                        if ($null -eq $p.Value) { $v = 'null' }
                        if ($v.Length -gt 300) { $v = $v.Substring(0, 300) + '...' }
                        Write-Host ("[{0}] {1} = {2}" -f $m.seq, $p.Name, $v)
                    }
                    Write-Host ('      ' + (Format-Summary)) -ForegroundColor DarkCyan
                } elseif ($m.type -eq 'snapshot') {
                    [void](Apply-StateMsg $m)
                    Write-Host ("[{0}] snapshot: {1}" -f $m.seq, (Format-Summary)) -ForegroundColor Cyan
                }
            }
        }
        'play-pause' { [void](Invoke-Cmd ([ordered]@{ type = 'play_pause' })) }
        'play' { [void](Invoke-Cmd ([ordered]@{ type = 'play' })) }
        'pause' { [void](Invoke-Cmd ([ordered]@{ type = 'pause' })) }
        'next' { [void](Invoke-Cmd ([ordered]@{ type = 'next' })) }
        'prev' { [void](Invoke-Cmd ([ordered]@{ type = 'prev' })) }
        'mute' { [void](Invoke-Cmd ([ordered]@{ type = 'mute_toggle' })); Drain 500; Write-Host (Format-Summary) }
        'refresh' { [void](Invoke-Cmd ([ordered]@{ type = 'refresh' })); Drain 500; Write-Host (Format-Summary) }
        'jump' {
            if (-not $arg0) { Fail 'Uso: jump <key>' }
            [void](Invoke-Cmd ([ordered]@{ type = 'jump'; key = $arg0 }))
        }
        { $_ -eq 'vol-up' -or $_ -eq 'vol-down' } {
            $n = 1
            if ($arg0) { $n = [int]$arg0 }
            $delta = 1
            if ($cmd -eq 'vol-down') { $delta = -1 }
            for ($i = 0; $i -lt $n; $i++) {
                [void](Invoke-Cmd ([ordered]@{ type = 'vol_step'; deltaDb = $delta; repeatCount = $i }))
            }
            Drain 500
            Write-Host (Format-Summary)
        }
        'raw' {
            if (-not $arg0) { Fail "Uso: raw '<json>'" }
            Write-Host ">> $arg0"
            Send-Line $arg0
            Show-Raw 3000
        }
        default { Fail "Subcomando desconocido: $Command (ver Get-Help $PSCommandPath)" }
    }
} finally {
    if ($script:client) { $script:client.Close() }
}
