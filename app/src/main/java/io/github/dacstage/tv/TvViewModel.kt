package io.github.dacstage.tv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.dacstage.model.Command
import io.github.dacstage.model.CommandError
import io.github.dacstage.model.HostState
import io.github.dacstage.model.JumpProgress
import io.github.dacstage.model.Lyrics
import io.github.dacstage.model.QueueState
import io.github.dacstage.network.HostLink
import io.github.dacstage.network.LinkStatus
import io.github.dacstage.ui.common.NowPlayingUi
import io.github.dacstage.ui.common.ProgressAnchor
import io.github.dacstage.ui.common.UiText
import io.github.dacstage.ui.common.VolumeNote
import io.github.dacstage.ui.common.VolumeUi
import io.github.dacstage.ui.tv.TvKeyAction
import io.github.dacstage.ui.tv.TvKeyMap
import io.github.dacstage.ui.tv.TvScreen
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Nivel de volumen superpuesto (aparece unos segundos al cambiar). */
@Immutable
data class VolumeHud(val volume: VolumeUi, val note: VolumeNote?, val serial: Long)

/**
 * Estado de la TV. Solo depende de [HostLink]. Flujos granulares para que cada parte de la UI recomponga
 * solo cuando cambia lo suyo (el progreso, que cambia mucho, va aparte y sellado con el reloj local).
 */
class TvViewModel(
    /** Enlace con el host; vive tanto como el ViewModel. */
    val link: HostLink,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    /** false en el control remoto de un teléfono: nunca entra en reposo. */
    private val screensaver: Boolean = true,
) : ViewModel() {

    private val eager = SharingStarted.Eagerly

    val screen: StateFlow<TvScreen> = combine(link.status, link.hostState) { s, h -> TvScreen.from(s, h) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, eager, TvScreen.from(link.status.value, link.hostState.value))

    val nowPlaying: StateFlow<NowPlayingUi> = link.hostState
        .map { it?.let(NowPlayingUi::from) ?: NowPlayingUi.Empty }
        .distinctUntilChanged()
        .stateIn(viewModelScope, eager, NowPlayingUi.Empty)

    /** Progreso sellado con elapsedRealtime() al recibirlo; se extrapola en la UI. */
    val progress: StateFlow<ProgressAnchor?> = link.hostState
        .map { h -> Triple(h?.progress, h?.status, h?.track?.durationMs) }
        .distinctUntilChanged()
        .map { (p, s, d) -> if (s == null) null else ProgressAnchor.stamp(p, s, d, clock()) }
        .stateIn(viewModelScope, eager, null)

    val queue: StateFlow<QueueState> = link.hostState.map { it?.queue ?: QueueState() }
        .distinctUntilChanged().stateIn(viewModelScope, eager, QueueState())

    val jump: StateFlow<JumpProgress?> = link.hostState.map { it?.jump }
        .distinctUntilChanged().stateIn(viewModelScope, eager, null)

    val volume: StateFlow<VolumeUi> = link.hostState.map { (it ?: HostState()).let { h -> VolumeUi.from(h.dac, h.systemVolume) } }
        .distinctUntilChanged().stateIn(viewModelScope, eager, VolumeUi.from(HostState().dac))

    val artBaseUrl: StateFlow<String?> get() = link.artBaseUrl

    /** Preferencias de pantalla elegidas en el host (fondo de fotos del reposo e intervalo). */
    val tvDisplay: StateFlow<io.github.dacstage.model.TvDisplayPrefs> = link.hostState
        .map { it?.tvDisplay ?: io.github.dacstage.model.TvDisplayPrefs() }
        .distinctUntilChanged().stateIn(viewModelScope, eager, io.github.dacstage.model.TvDisplayPrefs())

    /** Letra de la pista actual (si el host la tiene). */
    val lyrics: StateFlow<Lyrics?> = link.hostState.map { it?.lyrics }
        .distinctUntilChanged().stateIn(viewModelScope, eager, null)

    /** El host aún busca la letra de esta pista. */
    val lyricsPending: StateFlow<Boolean> = link.hostState.map { it?.lyricsPending == true }
        .distinctUntilChanged().stateIn(viewModelScope, eager, false)

    private val _lyricsOn = MutableStateFlow(false)
    /** Modo letra en la vista "reproduciendo" (se mantiene entre pistas). */
    val lyricsOn: StateFlow<Boolean> = _lyricsOn.asStateFlow()

    fun toggleLyrics() {
        _lyricsOn.value = !_lyricsOn.value
    }

    private val _hud = MutableStateFlow<VolumeHud?>(null)
    val volumeHud: StateFlow<VolumeHud?> = _hud.asStateFlow()

    private val _message = MutableStateFlow<UiText?>(null)
    /** Aviso breve (sin Context: la UI lo resuelve en el idioma del dispositivo). */
    val message: StateFlow<UiText?> = _message.asStateFlow()

    private val _volumePanelOpen = MutableStateFlow(false)
    /** Con el panel de volumen abierto no se muestra el aviso flotante (el panel ya enseña el nivel). */
    val volumePanelOpen: StateFlow<Boolean> = _volumePanelOpen.asStateFlow()

    fun setVolumePanelOpen(open: Boolean) {
        _volumePanelOpen.value = open
        if (open) _hud.value = null
    }

    private val _idle = MutableStateFlow(false)
    /** Pantalla de reposo (anti burn-in) tras el tiempo configurado en el host sin teclas. */
    val idle: StateFlow<Boolean> = _idle.asStateFlow()

    private var hudJob: Job? = null
    private var messageJob: Job? = null
    private var hudSerial = 0L
    @Volatile private var lastInputAt = clock()
    private var swallowUpKey: Int? = null

    init {
        // Cambios confirmados de volumen/mute (vengan de donde vengan) → mostrar el nivel.
        viewModelScope.launch {
            volume.map { Triple(it.db, it.muted, it.connected) }.distinctUntilChanged().drop(1).collect { showHud(null) }
        }
        viewModelScope.launch {
            link.acks.collect { ack ->
                val e = ack.result.error ?: return@collect
                when (e) {
                    CommandError.VOLUME_ABOVE_CAP -> showHud(VolumeNote.CAP_REACHED)
                    CommandError.VOLUME_UNAVAILABLE -> showHud(VolumeNote.UNAVAILABLE)
                    CommandError.DAC_DISCONNECTED -> showHud(VolumeNote.DISCONNECTED)
                    else -> UiText.commandError(e)?.let(::showMessage)
                }
            }
        }
        viewModelScope.launch {
            // Un cambio del ajuste en el host se aplica en el acto (reinicia la espera con el nuevo plazo).
            tvDisplay.map { it.idleSec.coerceAtLeast(30) * 1000L }.distinctUntilChanged().collectLatest { idleMs ->
                while (true) {
                    val left = idleMs - (clock() - lastInputAt)
                    if (left <= 0) {
                        if (idleAllowed()) _idle.value = true
                        delay(5_000)
                    } else delay(left)
                }
            }
        }
        viewModelScope.launch { screen.collect { if (!idleAllowed()) _idle.value = false } }
    }

    private fun idleAllowed(): Boolean = screensaver && when (screen.value) {
        TvScreen.NowPlaying, is TvScreen.NoPlayback, is TvScreen.Searching, is TvScreen.Disconnected -> true
        else -> false
    }

    private val connected: Boolean get() = link.status.value is LinkStatus.Connected

    fun send(command: Command) {
        link.send(command)
    }

    fun togglePlayPause() = send(Command.TogglePlayPause)
    fun next() = send(Command.Next)
    fun previous() = send(Command.Previous)
    fun jumpTo(key: String) = send(Command.JumpToQueueItem(key))
    fun cancelJump() = send(Command.CancelJump)

    /** Hosts visibles en la red, para elegir otro. */
    val hosts: StateFlow<List<io.github.dacstage.network.DiscoveredHost>> get() = link.discoveredHosts

    fun connectTo(host: io.github.dacstage.network.DiscoveredHost) = link.connectTo(host)

    fun retry() {
        link.stop()
        link.start()
    }

    /**
     * Respaldo si NSD no encuentra el host: "192.168.1.82" o "192.168.1.82:47820".
     * Devuelve false si el texto no es una dirección válida (no se intenta conectar).
     */
    fun connectManual(input: String): Boolean {
        val target = ManualAddress.parse(input) ?: return false
        link.connectManual(target.first, target.second)
        return true
    }

    /** Paso de volumen ±1 dB (teclas de volumen o ←/→ sobre el control). La TV nunca toca el tope. */
    fun volumeStep(delta: Int, repeatCount: Int) {
        val v = volume.value
        if (!v.connected) {
            showHud(VolumeNote.DISCONNECTED); return
        }
        // DAC sin volumen por USB: se avisa y no se envía nada (el host lo rechazaría igualmente).
        if (v.note == VolumeNote.ON_DEVICE || v.note == VolumeNote.NO_SCALE || v.note == VolumeNote.NO_RANGE ||
            v.note == VolumeNote.ANDROID_AUDIO
        ) {
            showHud(v.note); return
        }
        if (delta > 0 && v.atCap) showHud(VolumeNote.CAP_REACHED) else showHud(v.note)
        send(Command.VolumeStep(if (delta >= 0) 1 else -1, repeatCount.coerceAtLeast(0)))
    }

    fun toggleMute() {
        val v = volume.value
        if (!v.connected) {
            showHud(VolumeNote.DISCONNECTED); return
        }
        showHud(v.note)
        send(Command.ToggleMute)
    }

    fun onUserActivity() {
        lastInputAt = clock()
    }

    /**
     * Desde MainActivity.dispatchKeyEvent. Teclas multimedia → comandos (consumidas); teclas de volumen →
     * DAC (consumidas mientras hay conexión con el host, para que la TV no cambie su propio volumen).
     * Si está en reposo, la primera tecla de navegación solo despierta.
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val action = TvKeyMap.actionFor(event.keyCode)
        if (action != null) {
            if (action.isVolume && !connected) return false
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (action) {
                    TvKeyAction.VOLUME_UP -> volumeStep(+1, event.repeatCount)
                    TvKeyAction.VOLUME_DOWN -> volumeStep(-1, event.repeatCount)
                    TvKeyAction.VOLUME_MUTE -> if (event.repeatCount == 0) toggleMute()
                    else -> TvKeyMap.commandFor(action, event.repeatCount)?.let(::send)
                }
            }
            return true
        }
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                onUserActivity()
                if (_idle.value) {
                    _idle.value = false
                    swallowUpKey = event.keyCode
                    return true
                }
                if (swallowUpKey == event.keyCode) return true
            }
            KeyEvent.ACTION_UP -> if (swallowUpKey == event.keyCode) {
                swallowUpKey = null
                return true
            }
        }
        return false
    }

    private fun showHud(note: VolumeNote?) {
        _hud.value = VolumeHud(volume.value, note, ++hudSerial)
        hudJob?.cancel()
        hudJob = viewModelScope.launch {
            // Mantener el HUD al día con el valor confirmado mientras está visible.
            launch { volume.collect { v -> _hud.value?.let { _hud.value = it.copy(volume = v) } } }
            delay(if (note == null) HUD_MS else HUD_NOTE_MS)
            _hud.value = null
            cancel()
        }
    }

    private fun showMessage(text: UiText) {
        _message.value = text
        messageJob?.cancel()
        messageJob = viewModelScope.launch {
            delay(MESSAGE_MS)
            _message.value = null
        }
    }

    /** El enlace vive con el ViewModel: al descartarlo (la actividad termina) se corta la conexión. */
    override fun onCleared() {
        link.stop()
    }

    companion object {
        const val HUD_MS = 2_500L
        const val HUD_NOTE_MS = 3_500L
        const val MESSAGE_MS = 3_500L

        fun factory(link: HostLink): ViewModelProvider.Factory = factory(linkProvider = { link })

        /** El enlace se crea solo si el ViewModel no existe todavía. */
        fun factory(linkProvider: () -> HostLink, screensaver: Boolean = true): ViewModelProvider.Factory = viewModelFactory {
            initializer { TvViewModel(linkProvider(), screensaver = screensaver) }
        }
    }
}
