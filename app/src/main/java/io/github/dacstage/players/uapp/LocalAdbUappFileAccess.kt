package io.github.dacstage.players.uapp

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.dacstage.host.HostSettings
import io.github.dacstage.players.uapp.UappFileAccess.Companion.PLAY_QUEUE_XML
import io.github.dacstage.players.uapp.UappFileAccess.Companion.UAPP_DIR
import io.github.dacstage.players.uapp.UappFileAccess.Companion.UAPP_FILES
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.android.AdbMdns
import io.github.muntashirakon.adb.android.AndroidUtils
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.net.ConnectException
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.TimeUnit

/**
 * [UappFileAccess] mediante la depuración inalámbrica de la propia tablet: la app actúa como cliente
 * ADB local (libadb-android) con una identidad propia emparejada una vez. Solo ejecuta comandos fijos de
 * lectura (`cat`/`stat` sobre PlayQueue.xml, PosA.txt y PosB.txt) por el servicio `exec:` (sin PTY,
 * binario íntegro). Una conexión persistente; reconexión con espera creciente si se cae.
 */
class LocalAdbUappFileAccess(
    context: Context,
    private val settings: HostSettings,
    private val scope: CoroutineScope,
) : UappFileAccess {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("uapp_adb", Context.MODE_PRIVATE)
    private val keyStore = AdbKeyStore(File(appContext.noBackupFilesDir, "uapp_adb"))
    private val adbMutex = Mutex()
    private val reconnect = Channel<Unit>(Channel.CONFLATED)

    @Volatile private var manager: LocalAdbManager? = null

    private val _status = MutableStateFlow(initialStatus())
    override val status: StateFlow<UappAccessStatus> = _status.asStateFlow()

    private var paired: Boolean
        get() = prefs.getBoolean(KEY_PAIRED, false)
        set(v) { prefs.edit().putBoolean(KEY_PAIRED, v).apply() }

    init {
        scope.launch(Dispatchers.IO) {
            settings.uappAdbEnabled.collectLatest { enabled ->
                if (!enabled) {
                    adbMutex.withLock { disconnectLocked() }
                    _status.value = UappAccessStatus.Disabled
                } else {
                    supervise()
                }
            }
        }
    }

    private fun initialStatus(): UappAccessStatus = when {
        !settings.uappAdbEnabled.value -> UappAccessStatus.Disabled
        !paired -> UappAccessStatus.NotPaired
        else -> UappAccessStatus.Connecting
    }

    /** Mantiene la conexión mientras esté activado: reintenta con espera creciente. */
    private suspend fun supervise() {
        var attempt = 0
        while (true) {
            if (!paired) {
                _status.value = UappAccessStatus.NotPaired
                reconnect.receive() // pair() avisa
                continue
            }
            if (_status.value !is UappAccessStatus.Connected) {
                if (connectInternal().isFailure) {
                    if (_status.value is UappAccessStatus.NotPaired) continue
                    delay(BACKOFF_MS[attempt.coerceAtMost(BACKOFF_MS.lastIndex)])
                    attempt++
                    continue
                }
                attempt = 0
            }
            reconnect.receive() // una lectura detectó la caída
        }
    }

    override suspend fun pair(code: String, pairingPort: Int?): Result<Unit> = withContext(Dispatchers.IO) {
        if (!settings.uappAdbEnabled.value) return@withContext Result.failure(UappAccessException(UappAccessProblem.DISABLED, "Desactivado en Ajustes"))
        val clean = code.filter { it.isDigit() }
        if (clean.length != 6) return@withContext Result.failure(UappAccessException(UappAccessProblem.BAD_CODE, "El código tiene 6 dígitos"))
        val result = adbMutex.withLock {
            runCatching {
                val m = managerLocked()
                val targets: List<Pair<String, Int>> = if (pairingPort != null) {
                    localHosts().map { it to pairingPort }
                } else {
                    val found = discover(AdbMdns.SERVICE_TYPE_TLS_PAIRING)
                        ?: throw UappAccessException(
                            UappAccessProblem.PAIRING_PORT_NOT_FOUND,
                            "No se encontró el puerto de vinculación (mantén abierto el diálogo \"Vincular con código\")",
                        )
                    listOf(found)
                }
                var lastError: Throwable? = null
                for ((host, port) in targets) {
                    try {
                        if (m.pair(host, port, clean)) return@runCatching
                        lastError = UappAccessException(UappAccessProblem.PAIRING_REJECTED, "Vinculación rechazada")
                        break
                    } catch (e: ConnectException) {
                        lastError = e // prueba la siguiente dirección local
                    }
                }
                throw lastError ?: UappAccessException(UappAccessProblem.PAIRING_FAILED, "Vinculación fallida")
            }
        }
        result.onSuccess {
            paired = true
            _status.value = UappAccessStatus.Connecting
            reconnect.trySend(Unit)
        }.onFailure { Log.w(TAG, "pair: ${it.message}") }
        if (result.isFailure) return@withContext result
        connect()
    }

    override suspend fun connect(): Result<Unit> = connectInternal()

    private suspend fun connectInternal(): Result<Unit> = withContext(Dispatchers.IO) {
        if (!settings.uappAdbEnabled.value) return@withContext Result.failure(UappAccessException(UappAccessProblem.DISABLED, "Desactivado en Ajustes"))
        if (!paired) {
            _status.value = UappAccessStatus.NotPaired
            return@withContext Result.failure(UappAccessException(UappAccessProblem.NOT_PAIRED, "Sin emparejar"))
        }
        adbMutex.withLock {
            val m = managerLocked()
            if (m.isConnected && _status.value is UappAccessStatus.Connected) return@withLock Result.success(Unit)
            _status.value = UappAccessStatus.Connecting
            try {
                runCatching { m.disconnect() }
                // Puerto TLS por mDNS (_adb-tls-connect._tcp de este dispositivo); si no se anuncia,
                // se prueba el último conocido.
                val discovered = discover(AdbMdns.SERVICE_TYPE_TLS_CONNECT)
                val lastKnown = lastKnownTarget()
                val targets = listOfNotNull(discovered, lastKnown).distinct()
                if (targets.isEmpty()) throw WirelessOff()
                var ok = false
                var lastError: Exception? = null
                for ((host, port) in targets) {
                    try {
                        if (m.connect(host, port)) {
                            prefs.edit().putString(KEY_LAST_HOST, host).putInt(KEY_LAST_PORT, port).apply()
                            ok = true
                            break
                        }
                    } catch (e: AdbPairingRequiredException) {
                        throw e
                    } catch (e: Exception) {
                        lastError = e
                        runCatching { m.disconnect() }
                    }
                }
                if (!ok && discovered == null) throw WirelessOff()
                if (!ok && lastError != null) throw lastError
                if (!ok) throw UappAccessException(UappAccessProblem.CONNECTION_REFUSED, "adbd rechazó la conexión")
                if (!canReadUappDir(m)) {
                    _status.value = UappAccessStatus.Error(
                        "Conectado, pero no se puede leer la carpeta de UAPP",
                        UappAccessProblem.CONNECTED_FOLDER_UNREADABLE,
                    )
                    return@withLock Result.failure(UappAccessException(UappAccessProblem.FOLDER_UNREADABLE, "Carpeta de UAPP no legible"))
                }
                _status.value = UappAccessStatus.Connected
                Result.success(Unit)
            } catch (e: AdbPairingRequiredException) {
                paired = false
                _status.value = UappAccessStatus.NotPaired
                Result.failure(e)
            } catch (e: WirelessOff) {
                _status.value = UappAccessStatus.WirelessDebuggingOff
                Result.failure(e)
            } catch (e: Exception) {
                Log.w(TAG, "connect: $e")
                _status.value = UappAccessStatus.Error(e.message ?: e.javaClass.simpleName, e.uappProblem)
                Result.failure(e)
            }
        }
    }

    private fun lastKnownTarget(): Pair<String, Int>? {
        val port = prefs.getInt(KEY_LAST_PORT, -1).takeIf { it > 0 } ?: return null
        val host = prefs.getString(KEY_LAST_HOST, null) ?: return null
        return host to port
    }

    override fun forget() {
        _status.value = if (settings.uappAdbEnabled.value) UappAccessStatus.NotPaired else UappAccessStatus.Disabled
        scope.launch(Dispatchers.IO) {
            adbMutex.withLock {
                disconnectLocked()
                manager = null
                keyStore.delete()
                prefs.edit().clear().apply()
            }
            reconnect.trySend(Unit)
        }
    }

    override suspend fun readFile(name: String): Result<ByteArray> {
        require(name in UAPP_FILES) { "Archivo no permitido: $name" }
        return exec("cat '$UAPP_DIR/$name' 2>/dev/null")
    }

    override suspend fun readTail(name: String, maxBytes: Int): Result<ByteArray> {
        require(name in UappFileAccess.UAPP_TAIL_FILES) { "Archivo no permitido: $name" }
        val n = maxBytes.coerceIn(1, 256 * 1024)
        return exec("tail -c $n '$UAPP_DIR/$name' 2>/dev/null")
    }

    override suspend fun statFiles(names: List<String>): Result<List<UappFileStat>> {
        require(names.all { it in UAPP_FILES }) { "Archivo no permitido" }
        if (names.isEmpty()) return Result.success(emptyList())
        val list = names.distinct().joinToString(" ") { "'$it'" }
        return exec("cd '$UAPP_DIR' && stat -c '%n|%Y|%y|%s' $list 2>/dev/null")
            .map { UappStatParser.parse(String(it, Charsets.UTF_8)) }
    }

    // ---- ADB ----

    private suspend fun exec(command: String): Result<ByteArray> = withContext(Dispatchers.IO) {
        if (_status.value !is UappAccessStatus.Connected) {
            return@withContext Result.failure(IllegalStateException("Sin conexión ADB local"))
        }
        adbMutex.withLock {
            val m = manager ?: return@withLock Result.failure(IllegalStateException("Sin conexión ADB local"))
            runCatching { execLocked(m, command) }.onFailure {
                if (!m.isConnected) {
                    Log.i(TAG, "conexión perdida: ${it.message}")
                    _status.value = UappAccessStatus.Connecting
                    reconnect.trySend(Unit)
                }
            }
        }
    }

    /**
     * Ejecuta por `exec:` (sin PTY: no hay conversión de fin de línea) con un tope de tiempo.
     *
     * Se lee directamente de AdbStream y NO de su AdbInputStream: en libadb-android 3.1.1 éste devuelve
     * -1 en cuanto el canal se marca cerrado aunque queden datos en cola (adbd envía la salida y cierra
     * casi a la vez en comandos cortos como `stat`), y AdbStream.read lanza "Stream closed." al agotar
     * la cola de un canal cerrado. Aquí eso es el fin normal de la salida, salvo que lo cause el watchdog.
     */
    private fun execLocked(m: LocalAdbManager, command: String): ByteArray {
        // Marca de fin propia: el aviso de cierre de libadb a veces no llega y la lectura se quedaba
        // esperando hasta el watchdog. Con la marca, la salida termina en cuanto llega completa.
        val marker = endMarker()
        val stream = m.openStream("exec:$command; echo '$marker'")
        val markerBytes = "$marker\n".toByteArray(Charsets.US_ASCII)
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = scope.launch(Dispatchers.IO) {
            delay(EXEC_TIMEOUT_MS)
            timedOut.set(true)
            runCatching { stream.close() }
        }
        try {
            val out = OutBuffer()
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = try {
                    stream.read(buf, 0, buf.size)
                } catch (e: java.io.IOException) {
                    if (timedOut.get()) throw java.io.IOException("Tiempo agotado ejecutando el comando", e)
                    -1 // canal cerrado por adbd
                }
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.endsWith(markerBytes)) return out.withoutSuffix(markerBytes.size)
            }
            if (timedOut.get()) throw java.io.IOException("Tiempo agotado ejecutando el comando")
            // Cerrado sin la marca: la salida puede estar incompleta; no se usa.
            throw java.io.IOException("Salida incompleta")
        } finally {
            watchdog.cancel()
            runCatching { stream.close() }
        }
    }

    private fun canReadUappDir(m: LocalAdbManager): Boolean = runCatching {
        String(execLocked(m, "test -r '$UAPP_DIR/$PLAY_QUEUE_XML' -o -d '$UAPP_DIR' && echo ok"), Charsets.UTF_8)
            .trim() == "ok"
    }.getOrDefault(false)

    private fun disconnectLocked() {
        runCatching { manager?.disconnect() }
    }

    private fun managerLocked(): LocalAdbManager {
        manager?.let { return it }
        val id = keyStore.loadOrCreate()
        return LocalAdbManager(id.privateKey, id.certificate).also { manager = it }
    }

    private fun localHosts(): List<String> =
        listOfNotNull(runCatching { AndroidUtils.getHostIpAddress(appContext) }.getOrNull(), "127.0.0.1").distinct()

    /** Descubre por mDNS un servicio ADB anunciado por ESTE dispositivo (AdbMdns filtra por IP local). */
    private suspend fun discover(serviceType: String): Pair<String, Int>? {
        val found = CompletableDeferred<Pair<String, Int>>()
        val mdns = AdbMdns(appContext, serviceType) { host, port ->
            if (host != null && port > 0) found.complete(host.hostAddress.orEmpty() to port)
        }
        mdns.start()
        return try {
            withTimeoutOrNull(MDNS_TIMEOUT_MS) { found.await() }
        } finally {
            runCatching { mdns.stop() }
        }
    }

    private class WirelessOff : UappAccessException(UappAccessProblem.WIRELESS_OFF, "Depuración inalámbrica no disponible")

    /** Marca de fin con un valor aleatorio por comando (no puede aparecer por casualidad al final de un archivo). */
    private fun endMarker(): String = "__DACSTAGE_END_%016x__".format(java.util.concurrent.ThreadLocalRandom.current().nextLong())

    private class OutBuffer : java.io.ByteArrayOutputStream() {
        fun endsWith(suffix: ByteArray): Boolean {
            if (count < suffix.size) return false
            val off = count - suffix.size
            for (i in suffix.indices) if (buf[off + i] != suffix[i]) return false
            return true
        }

        fun withoutSuffix(n: Int): ByteArray = buf.copyOf(count - n)
    }

    /** Gestor de libadb con nuestra identidad. Nunca se llama a close(): destruiría la clave privada. */
    private class LocalAdbManager(
        private val key: PrivateKey,
        private val cert: Certificate,
    ) : AbsAdbConnectionManager() {
        init {
            api = Build.VERSION.SDK_INT
            setTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        }

        override fun getPrivateKey(): PrivateKey = key
        override fun getCertificate(): Certificate = cert
        override fun getDeviceName(): String = "DAC Stage"
    }

    private companion object {
        const val TAG = "UappAdb"
        const val KEY_PAIRED = "paired"
        const val KEY_LAST_PORT = "last_port"
        const val KEY_LAST_HOST = "last_host"
        const val MDNS_TIMEOUT_MS = 8_000L
        const val CONNECT_TIMEOUT_S = 10L
        const val EXEC_TIMEOUT_MS = 5_000L
        val BACKOFF_MS = longArrayOf(5_000, 15_000, 30_000, 60_000)
    }
}
