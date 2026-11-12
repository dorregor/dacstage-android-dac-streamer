package io.github.dacstage.ui.mobile

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.dacstage.R
import io.github.dacstage.network.PairedTv
import io.github.dacstage.network.PairingRequest
import io.github.dacstage.players.uapp.UappAccessProblem
import io.github.dacstage.players.uapp.UappAccessStatus
import io.github.dacstage.players.uapp.uappProblem
import io.github.dacstage.ui.common.TechFormat
import io.github.dacstage.ui.common.UiText
import io.github.dacstage.ui.common.asString
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HostSettingsScreen(deps: HostUiDeps, onBack: () -> Unit, onSwitchToRemote: (() -> Unit)? = null) {
    val sessionAccess by deps.hasSessionAccess.collectSlice { it }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back)) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { inner ->
        LazyColumn(
            Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!sessionAccess) item { Narrow { SessionAccessBanner() } }
            item { Narrow { CapSection(deps) } }
            item { Narrow { HostNameSection(deps) } }
            item { Narrow { TvPairingSection(deps) } }
            item { Narrow { BluetoothSection(deps) } }
            item { Narrow { UappSection(deps) } }
            item { Narrow { OnlineInfoSection(deps) } }
            item { Narrow { TvScreensaverSection(deps) } }
            if (onSwitchToRemote != null) item { Narrow { RoleSection(onSwitchToRemote) } }
        }
    }
}

/** Cambiar la función de este dispositivo a control remoto (con confirmación: las TV pierden el host). */
@Composable
private fun RoleSection(onSwitchToRemote: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Section(stringResource(R.string.settings_role_title)) {
        Explain(stringResource(R.string.settings_role_body))
        androidx.compose.material3.OutlinedButton(onClick = { confirm = true }) { Text(stringResource(R.string.settings_role_switch)) }
    }
    if (confirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.role_switch_confirm_title)) },
            text = { Text(stringResource(R.string.role_switch_confirm_body)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { confirm = false; onSwitchToRemote() }) {
                    Text(stringResource(R.string.settings_role_switch))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun Narrow(content: @Composable () -> Unit) {
    Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) { content() }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Explain(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

/* ---------- Tope de volumen (solo aquí) ---------- */

@Composable
private fun CapSection(deps: HostUiDeps) {
    val dac by deps.dacVolume.state.collectSlice { it }
    val storedCap by deps.settings.capDb.collectSlice { it }
    Section(stringResource(R.string.settings_cap_title)) {
        Explain(stringResource(R.string.settings_cap_explain))
        val min = dac.minDb
        val max = dac.maxDb
        val current = dac.capDb ?: storedCap
        if (min == null || max == null || max <= min) {
            Text(
                current?.let { stringResource(R.string.settings_cap_current, TechFormat.db(it)) } ?: stringResource(R.string.settings_cap_default),
                style = MaterialTheme.typography.bodyLarge,
            )
            Explain(stringResource(R.string.settings_cap_connect_dac))
        } else {
            var value by remember(current, min, max) { mutableStateOf((current ?: (max - 10f)).coerceIn(min, max)) }
            Text(stringResource(R.string.settings_cap_value, TechFormat.db(value)), style = MaterialTheme.typography.headlineSmall)
            Slider(
                value = value,
                onValueChange = { value = it.roundToInt().toFloat().coerceIn(min, max) },
                valueRange = min..max,
                steps = ((max - min).roundToInt() - 1).coerceIn(0, 300),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Explain(stringResource(R.string.settings_cap_range, TechFormat.db(min), TechFormat.db(max)), Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                Button(enabled = value != current, onClick = {
                    deps.dacVolume.setCapDb(value)
                    deps.settings.setCapDb(value)
                }) { Text(stringResource(R.string.settings_apply)) }
            }
        }
    }
}

/* ---------- Nombre del host ---------- */

@Composable
private fun HostNameSection(deps: HostUiDeps) {
    val name by deps.settings.hostName.collectSlice { it }
    var edit by rememberSaveable(name) { mutableStateOf(name) }
    Section(stringResource(R.string.settings_host_name_title)) {
        Explain(stringResource(R.string.settings_host_name_explain))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(edit, { edit = it.take(40) }, singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Button(enabled = edit.isNotBlank() && edit.trim() != name, onClick = { deps.settings.setHostName(edit.trim()) }) { Text(stringResource(R.string.settings_save)) }
        }
    }
}

/* ---------- Emparejado con TV ---------- */

@Composable
private fun TvPairingSection(deps: HostUiDeps) {
    val requests by deps.server.pairingRequests.collectSlice { it }
    val paired by deps.server.pairedTvs.collectSlice { it }
    val connected by deps.server.connectedClients.collectSlice { it }
    val port by deps.server.listeningPort.collectSlice { it }
    var forget by remember { mutableStateOf<PairedTv?>(null) }
    Section(stringResource(R.string.settings_pair_title)) {
        Explain(
            if (port != null) stringResource(R.string.settings_pair_explain)
            else stringResource(R.string.settings_server_inactive),
        )
        val context = androidx.compose.ui.platform.LocalContext.current
        val ip = remember(port) { localIpv4(context) }
        if (port != null && ip != null) {
            Explain(stringResource(R.string.settings_manual_address, ip + if (port != io.github.dacstage.network.DEFAULT_PORT) ":$port" else ""))
        }
        requests.forEach { PairingRequestRow(deps, it) }
        if (requests.isEmpty()) Explain(stringResource(R.string.settings_no_pending))
        if (paired.isNotEmpty()) {
            HorizontalDivider()
            Text(stringResource(R.string.settings_paired_tvs), style = MaterialTheme.typography.titleSmall)
            paired.forEach { tv ->
                val online = tv.deviceName in connected
                ListItem(
                    headlineContent = { Text(tv.deviceName) },
                    supportingContent = {
                        val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(tv.pairedAtMs))
                        Text(if (online) stringResource(R.string.settings_tv_online, date) else stringResource(R.string.settings_tv_paired_on, date))
                    },
                    trailingContent = { TextButton(onClick = { forget = tv }) { Text(stringResource(R.string.action_forget)) } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
    forget?.let { tv ->
        AlertDialog(
            onDismissRequest = { forget = null },
            title = { Text(stringResource(R.string.settings_forget_title, tv.deviceName)) },
            text = { Text(stringResource(R.string.settings_forget_body)) },
            confirmButton = { TextButton(onClick = { deps.server.unpair(tv.deviceId); forget = null }) { Text(stringResource(R.string.action_forget)) } },
            dismissButton = { TextButton(onClick = { forget = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun PairingRequestRow(deps: HostUiDeps, r: PairingRequest) {
    var code by rememberSaveable(r.requestId) { mutableStateOf("") }
    var error by remember(r.requestId) { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_wants_to_connect, r.deviceName), style = MaterialTheme.typography.titleSmall)
            Explain(r.address)
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.uppercase().filter { c -> c.isLetterOrDigit() }.take(8); error = false },
                label = { Text(stringResource(R.string.settings_tv_code_label)) },
                singleLine = true,
                isError = error,
                supportingText = if (error) ({ Text(stringResource(R.string.settings_code_mismatch)) }) else null,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = code.isNotBlank(), onClick = { error = !deps.server.confirmPairing(r.requestId, code) }) { Text(stringResource(R.string.settings_pair)) }
                TextButton(onClick = { deps.server.rejectPairing(r.requestId) }) { Text(stringResource(R.string.settings_reject)) }
            }
        }
    }
}

/* ---------- Cola de UAPP ---------- */

@Composable
private fun UappSection(deps: HostUiDeps) {
    val enabled by deps.settings.uappAdbEnabled.collectSlice { it }
    val status by deps.uappFiles.status.collectSlice { it }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var lastError by remember { mutableStateOf<UiText?>(null) }
    Section(stringResource(R.string.settings_uapp_title)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Explain(
                stringResource(R.string.settings_uapp_explain),
                Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Switch(checked = enabled, onCheckedChange = { deps.settings.setUappAdbEnabled(it) })
        }
        if (!enabled) return@Section
        val (statusText, statusColor) = when (val s = status) {
            UappAccessStatus.Disabled -> stringResource(R.string.uapp_status_disabled) to MaterialTheme.colorScheme.onSurfaceVariant
            UappAccessStatus.NotPaired -> stringResource(R.string.uapp_status_not_paired) to MaterialTheme.colorScheme.onSurfaceVariant
            UappAccessStatus.WirelessDebuggingOff -> stringResource(R.string.uapp_status_wireless_off) to MaterialTheme.colorScheme.error
            UappAccessStatus.Connecting -> stringResource(R.string.uapp_status_connecting) to MaterialTheme.colorScheme.onSurfaceVariant
            UappAccessStatus.Connected -> stringResource(R.string.uapp_status_connected) to Color(0xFFA5D6A7)
            is UappAccessStatus.Error -> {
                val detail = s.problem?.let { stringResource(it.textRes) } ?: s.message
                stringResource(R.string.uapp_status_error, detail) to MaterialTheme.colorScheme.error
            }
        }
        Text(statusText, style = MaterialTheme.typography.bodyLarge, color = statusColor)
        when (status) {
            UappAccessStatus.Connected -> OutlinedButton(onClick = { deps.uappFiles.forget() }) { Text(stringResource(R.string.uapp_forget_link)) }
            UappAccessStatus.WirelessDebuggingOff, is UappAccessStatus.Error -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        lastError = deps.uappFiles.connect().exceptionOrNull()?.let(::uappErrorText)
                        busy = false
                    }
                }) { Text(stringResource(R.string.action_retry)) }
                OutlinedButton(onClick = { openDevOptions(context) }) { Text(stringResource(R.string.uapp_developer_options)) }
                TextButton(onClick = { deps.uappFiles.forget() }) { Text(stringResource(R.string.action_forget)) }
            }
            else -> {}
        }
        if (status == UappAccessStatus.NotPaired || status is UappAccessStatus.Error) {
            HorizontalDivider()
            UappPairForm(deps, busy, onBusy = { busy = it }, onError = { lastError = it })
        }
        lastError?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun UappPairForm(deps: HostUiDeps, busy: Boolean, onBusy: (Boolean) -> Unit, onError: (UiText?) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var code by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("") }
    Text(stringResource(R.string.uapp_link_title), style = MaterialTheme.typography.titleSmall)
    Explain(stringResource(R.string.uapp_link_steps))
    OutlinedButton(onClick = { openDevOptions(context) }) { Text(stringResource(R.string.uapp_open_developer_options)) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit).take(6) },
            label = { Text(stringResource(R.string.uapp_code)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter(Char::isDigit).take(5) },
            label = { Text(stringResource(R.string.uapp_port_optional)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
    }
    Button(enabled = code.length == 6 && !busy, onClick = {
        onBusy(true)
        onError(null)
        scope.launch {
            val r = deps.uappFiles.pair(code, port.toIntOrNull())
            if (r.isSuccess) {
                onError(deps.uappFiles.connect().exceptionOrNull()?.let(::uappErrorText))
                code = ""
            } else onError(r.exceptionOrNull()?.let(::uappErrorText) ?: UiText.Res(R.string.uapp_link_failed))
            onBusy(false)
        }
    }) { Text(stringResource(if (busy) R.string.uapp_linking else R.string.uapp_link)) }
}

/** Texto de un fallo de vinculación/conexión: traducido si el motivo es conocido; si no, el del sistema. */
private fun uappErrorText(t: Throwable): UiText? =
    t.uappProblem?.let { UiText.Res(it.textRes) } ?: t.message?.let(UiText::Raw)

@get:StringRes
private val UappAccessProblem.textRes: Int
    get() = when (this) {
        UappAccessProblem.DISABLED -> R.string.uapp_error_disabled
        UappAccessProblem.BAD_CODE -> R.string.uapp_error_bad_code
        UappAccessProblem.PAIRING_PORT_NOT_FOUND -> R.string.uapp_error_pairing_port
        UappAccessProblem.PAIRING_REJECTED -> R.string.uapp_error_pairing_rejected
        UappAccessProblem.PAIRING_FAILED -> R.string.uapp_error_pairing_failed
        UappAccessProblem.NOT_PAIRED -> R.string.uapp_error_not_paired
        UappAccessProblem.CONNECTION_REFUSED -> R.string.uapp_error_connection_refused
        UappAccessProblem.FOLDER_UNREADABLE -> R.string.uapp_error_folder_unreadable
        UappAccessProblem.CONNECTED_FOLDER_UNREADABLE -> R.string.uapp_error_connected_folder_unreadable
        UappAccessProblem.WIRELESS_OFF -> R.string.uapp_error_wireless_off
    }

private fun openDevOptions(context: android.content.Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/* ---------- Información en línea ---------- */

/** Controles remotos por Bluetooth (para redes que aíslan a los equipos Wi‑Fi entre sí). */
@Composable
private fun BluetoothSection(deps: HostUiDeps) {
    val enabled by deps.settings.bluetoothRemote.collectSlice { it }
    val active by deps.bluetoothActive.collectSlice { it }
    val context = androidx.compose.ui.platform.LocalContext.current
    val perms = remember { io.github.dacstage.network.DacStageBluetooth.hostPermissions() }
    var granted by remember { mutableStateOf(io.github.dacstage.network.DacStageBluetooth.granted(context, perms)) }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted = io.github.dacstage.network.DacStageBluetooth.granted(context, perms) }
    Section(stringResource(R.string.settings_bt_title)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_bt_switch), style = MaterialTheme.typography.bodyLarge)
                Explain(stringResource(R.string.settings_bt_explain))
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = enabled, onCheckedChange = {
                deps.settings.setBluetoothRemote(it)
                if (it && !granted) launcher.launch(perms)
            })
        }
        if (enabled) {
            val status = when {
                !granted -> R.string.settings_bt_no_permission
                active -> R.string.settings_bt_active
                else -> R.string.settings_bt_waiting
            }
            Explain(stringResource(status))
            if (!granted) {
                androidx.compose.material3.OutlinedButton(onClick = { launcher.launch(perms) }) {
                    Text(stringResource(R.string.settings_bt_grant))
                }
            }
        }
    }
}

@Composable
private fun OnlineInfoSection(deps: HostUiDeps) {
    val lyrics by deps.settings.lyricsEnabled.collectSlice { it }
    val mb by deps.settings.musicBrainzEnabled.collectSlice { it }
    Section(stringResource(R.string.settings_online_title)) {
        Explain(stringResource(R.string.settings_online_explain))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_lyrics_title), style = MaterialTheme.typography.bodyLarge)
                Explain(stringResource(R.string.settings_lyrics_explain))
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = lyrics, onCheckedChange = { deps.settings.setLyricsEnabled(it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_musicbrainz_title), style = MaterialTheme.typography.bodyLarge)
                Explain(stringResource(R.string.settings_musicbrainz_explain))
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = mb, onCheckedChange = { deps.settings.setMusicBrainzEnabled(it) })
        }
    }
}

/* ---------- Reposo de la TV ---------- */

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun TvScreensaverSection(deps: HostUiDeps) {
    val prefs by deps.settings.tvDisplay.collectSlice { it }
    Section(stringResource(R.string.settings_tv_screensaver_title)) {
        Text(stringResource(R.string.settings_tv_idle_after), style = MaterialTheme.typography.bodyLarge)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            io.github.dacstage.model.TvDisplayPrefs.IDLE_OPTIONS_SEC.forEach { sec ->
                androidx.compose.material3.FilterChip(
                    selected = prefs.idleSec == sec,
                    onClick = { deps.settings.setTvIdleSec(sec) },
                    label = { Text(stringResource(R.string.settings_minutes, sec / 60)) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_earth_view_title), style = MaterialTheme.typography.bodyLarge)
                Explain(stringResource(R.string.settings_earth_view_explain))
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = prefs.earthView, onCheckedChange = { deps.settings.setTvPhotosEnabled(it) })
        }
        Text(stringResource(R.string.settings_tv_show_title), style = MaterialTheme.typography.bodyLarge)
        ShowChips(
            label = stringResource(R.string.settings_tv_show_plain),
            artwork = prefs.showArtwork,
            song = prefs.showSongInfo,
            photo = prefs.showPhotoInfo.takeIf { prefs.earthView },
            songLabel = stringResource(R.string.settings_tv_show_song),
            tech = prefs.showTech,
            onTech = { deps.settings.setTvShow(lyrics = false, tech = it) },
            onArtwork = { deps.settings.setTvShow(lyrics = false, artwork = it) },
            onSong = { deps.settings.setTvShow(lyrics = false, songInfo = it) },
            onPhoto = { deps.settings.setTvShow(lyrics = false, photoInfo = it) },
        )
        ShowChips(
            label = stringResource(R.string.settings_tv_show_lyrics),
            artwork = prefs.lyricsShowArtwork,
            song = prefs.lyricsShowSongInfo,
            photo = prefs.lyricsShowPhotoInfo.takeIf { prefs.earthView },
            songLabel = stringResource(R.string.settings_tv_show_song_artist),
            tech = prefs.lyricsShowTech,
            onTech = { deps.settings.setTvShow(lyrics = true, tech = it) },
            onArtwork = { deps.settings.setTvShow(lyrics = true, artwork = it) },
            onSong = { deps.settings.setTvShow(lyrics = true, songInfo = it) },
            onPhoto = { deps.settings.setTvShow(lyrics = true, photoInfo = it) },
        )
        var scale by remember(prefs.textScale) { mutableStateOf(prefs.textScale) }
        Text(
            stringResource(R.string.settings_tv_text_scale, (scale * 100).roundToInt()),
            style = MaterialTheme.typography.bodyLarge,
        )
        Slider(
            value = scale,
            // Se guarda en cada cambio (no depende de que llegue el fin del arrastre) y la TV lo ve en vivo.
            onValueChange = {
                scale = (it * 10).roundToInt() / 10f
                deps.settings.setTvTextScale(scale)
            },
            valueRange = io.github.dacstage.model.TvDisplayPrefs.MIN_TEXT_SCALE..io.github.dacstage.model.TvDisplayPrefs.MAX_TEXT_SCALE,
            steps = 6,
        )
        if (prefs.earthView) {
            var opacity by remember(prefs.backdropOpacity) { mutableStateOf(prefs.backdropOpacity) }
            Text(
                stringResource(R.string.settings_tv_backdrop, (opacity * 100).roundToInt()),
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = opacity,
                onValueChange = {
                    opacity = it
                    deps.settings.setTvBackdropOpacity(it)
                },
                valueRange = 0f..io.github.dacstage.model.TvDisplayPrefs.MAX_BACKDROP,
                steps = 15,
            )
            Explain(stringResource(R.string.settings_tv_backdrop_explain))
            Text(stringResource(R.string.settings_earth_view_interval), style = MaterialTheme.typography.bodyLarge)
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                io.github.dacstage.model.TvDisplayPrefs.PHOTO_INTERVALS_SEC.forEach { sec ->
                    androidx.compose.material3.FilterChip(
                        selected = prefs.photoIntervalSec == sec,
                        onClick = { deps.settings.setTvPhotoIntervalSec(sec) },
                        label = { Text(stringResource(R.string.settings_minutes, sec / 60)) },
                    )
                }
            }
        }
    }
}

/** Un grupo de "Mostrar en el reposo": etiqueta y un chip por elemento ([photo] null = sin fotos). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ShowChips(
    label: String,
    artwork: Boolean,
    song: Boolean,
    photo: Boolean?,
    songLabel: String,
    tech: Boolean,
    onTech: (Boolean) -> Unit,
    onArtwork: (Boolean) -> Unit,
    onSong: (Boolean) -> Unit,
    onPhoto: (Boolean) -> Unit,
) {
    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        androidx.compose.material3.FilterChip(
            selected = artwork,
            onClick = { onArtwork(!artwork) },
            label = { Text(stringResource(R.string.settings_tv_show_artwork)) },
        )
        androidx.compose.material3.FilterChip(selected = song, onClick = { onSong(!song) }, label = { Text(songLabel) })
        androidx.compose.material3.FilterChip(
            selected = tech,
            onClick = { onTech(!tech) },
            label = { Text(stringResource(R.string.settings_tv_show_tech)) },
        )
        if (photo != null) {
            androidx.compose.material3.FilterChip(
                selected = photo,
                onClick = { onPhoto(!photo) },
                label = { Text(stringResource(R.string.settings_tv_show_photo)) },
            )
        }
    }
}

/** IPv4 de la red activa (Wi-Fi/Ethernet) para la conexión manual desde la TV; null si no hay. */
private fun localIpv4(context: android.content.Context): String? {
    val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return null
    val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
    return lp.linkAddresses.map { it.address }
        .firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }?.hostAddress
}
