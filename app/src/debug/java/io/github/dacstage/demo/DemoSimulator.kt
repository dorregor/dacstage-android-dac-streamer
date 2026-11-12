package io.github.dacstage.demo

import android.content.Context
import android.os.SystemClock
import io.github.dacstage.model.ArtRef
import io.github.dacstage.model.ArtSource
import io.github.dacstage.model.Command
import io.github.dacstage.model.CommandError
import io.github.dacstage.model.CommandResult
import io.github.dacstage.model.DacControlAvailability
import io.github.dacstage.model.DacState
import io.github.dacstage.model.HostState
import io.github.dacstage.model.JumpProgress
import io.github.dacstage.model.PlaybackStatus
import io.github.dacstage.model.PlayerId
import io.github.dacstage.model.Progress
import io.github.dacstage.model.QueueItem
import io.github.dacstage.model.QueueState
import io.github.dacstage.model.QueueUnavailableReason
import io.github.dacstage.model.Spec
import io.github.dacstage.model.SpecSource
import io.github.dacstage.model.TechSpecs
import io.github.dacstage.model.TrackInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Host simulado para el modo demo (solo debug). Escenarios: play (por defecto), uapp, noqueue, dacoff,
 * nospecs, noplay, unsupported, pairing, search, disconnected, noaccess.
 */
internal class DemoSimulator(private val context: Context, val scenario: String) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private data class Track(
        val title: String, val artist: String, val album: String, val durationMs: Long,
        val specs: TechSpecs, val dacRate: Int?,
    )

    private fun spec(rate: Int?, bits: Int?, codec: String?, kbps: Int?, src: SpecSource = SpecSource.SESSION_METADATA) = TechSpecs(
        sampleRateHz = rate?.let { Spec(it, src) },
        bitDepth = bits?.let { Spec(it, src) },
        codec = codec?.let { Spec(it, src) },
        bitrateKbps = kbps?.let { Spec(it, src) },
    )

    private val tracks = listOf(
        Track("Luz de invierno", "Marea Alta", "Costas lejanas", 254_000, spec(96_000, 24, "FLAC", null), 96_000),
        Track("Ciudad dormida", "Los Satélites del Sur", "Órbitas", 198_000, spec(44_100, 16, "FLAC", 1411), 44_100),
        Track("Nocturno para cuerdas en re menor", "Orquesta de Cámara Aurora", "Nocturnos", 412_000, spec(192_000, 24, "FLAC", null), 192_000),
        Track("Kilómetro cero", "Bruma", "Kilómetro cero", 221_000, spec(null, null, "MQA", null), 88_200),
        Track("Señales", "Delta Norte", "Frecuencias", 187_000, TechSpecs(), null),
        Track("Último tren", "La Verbena Eléctrica", "Andenes", 239_000, spec(48_000, 24, "ALAC", null, SpecSource.UAPP_QUEUE_FILE), 48_000),
    )

    private val player = when (scenario) {
        "uapp", "noqueue" -> PlayerId.UAPP
        else -> PlayerId.HIBY
    }

    private var index = 0
    private var playing = scenario != "paused"
    private var posMs = 37_000L
    private var stampedAt = SystemClock.elapsedRealtime()
    private var volumeDb = -23f
    private var muted = false
    private var capDb = -10f
    private var jumpJob: Job? = null
    private val files = HashMap<Int, DemoArt.Files>()

    private val _state = MutableStateFlow(HostState(hostName = "Tablet del salón"))
    val state: StateFlow<HostState> = _state.asStateFlow()

    fun start() {
        scope.launch {
            tracks.indices.forEach { files[it] = DemoArt.ensure(context, it) }
            publish()
            // Parches periódicos como haría el host (posición cada 5 s) + avance automático.
            while (true) {
                delay(1_000)
                if (playing && currentPos() >= tracks[index].durationMs) {
                    index = (index + 1) % tracks.size; posMs = 0; stampedAt = SystemClock.elapsedRealtime(); publish()
                } else if ((SystemClock.elapsedRealtime() / 1000) % 5 == 0L) publish()
            }
        }
    }

    private fun currentPos(): Long =
        if (playing) posMs + (SystemClock.elapsedRealtime() - stampedAt) else posMs

    private fun rebase() {
        posMs = currentPos(); stampedAt = SystemClock.elapsedRealtime()
    }

    private fun art(i: Int): ArtRef? {
        val f = files[i] ?: return null
        return ArtRef(id = "demo-$i", widthPx = 1000, heightPx = 1000, source = ArtSource.PLAYER_QUEUE_URL, url = f.full.toURI().toString(), mimeType = "image/jpeg")
    }

    @Synchronized
    private fun publish() {
        rebase()
        val t = tracks[index]
        val dacOn = scenario != "dacoff"
        val queue = when (scenario) {
            "noqueue" -> QueueState(available = false, unavailableReason = QueueUnavailableReason.UAPP_NO_FILE_ACCESS)
            else -> QueueState(
                available = true,
                unavailableReason = null,
                items = tracks.mapIndexed { i, q ->
                    QueueItem(
                        key = if (player == PlayerId.UAPP) "uapp:$i:${1000 + i}" else "hiby:$i",
                        title = q.title, artist = q.artist, album = q.album, durationMs = q.durationMs,
                        artUrl = files[i]?.thumb?.toURI()?.toString(), specs = q.specs,
                    )
                },
                currentIndex = index,
                version = 1,
            )
        }
        val specs = if (scenario == "nospecs") TechSpecs() else t.specs
        _state.update {
            it.copy(
                player = if (scenario == "noplay" || scenario == "unsupported") null else player,
                unsupportedPlayerPackage = if (scenario == "unsupported") "com.spotify.music" else null,
                status = if (playing) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED,
                track = if (scenario == "noplay" || scenario == "unsupported") null
                else TrackInfo(t.title, t.artist, t.album, t.durationMs),
                progress = Progress(posMs),
                art = art(index),
                specs = specs,
                queue = queue,
                dac = if (!dacOn) DacState(connected = false, control = DacControlAvailability.DISCONNECTED)
                else DacState(
                    connected = true, deviceName = "FiiO K7", sampleRateHz = if (scenario == "nospecs") null else t.dacRate,
                    volumeDb = volumeDb, muted = muted, minDb = -127f, maxDb = 0f, stepDb = 0.5f, capDb = capDb,
                    control = DacControlAvailability.AVAILABLE,
                ),
            )
        }
    }

    suspend fun execute(c: Command): CommandResult {
        delay(60) // latencia del host
        val s = _state.value
        if (s.player == null && c !is Command.VolumeStep && c != Command.ToggleMute && c != Command.RefreshState) {
            return CommandResult.fail(CommandError.NO_PLAYER)
        }
        when (c) {
            Command.TogglePlayPause -> { rebase(); playing = !playing }
            Command.Play -> { rebase(); playing = true }
            Command.Pause -> { rebase(); playing = false }
            Command.Next -> { index = (index + 1) % tracks.size; posMs = 0 }
            Command.Previous -> if (currentPos() > 3_000) posMs = 0 else { index = (index - 1 + tracks.size) % tracks.size; posMs = 0 }
            is Command.JumpToQueueItem -> {
                val target = s.queue.items.indexOfFirst { it.key == c.key }
                if (target < 0) return CommandResult.fail(CommandError.UNKNOWN_QUEUE_ITEM)
                if (player == PlayerId.UAPP) {
                    jumpJob?.cancel()
                    jumpJob = scope.launch {
                        val steps = kotlin.math.abs(target - index)
                        for (k in 1..steps) {
                            _state.update { it.copy(jump = JumpProgress(c.key, k - 1, steps)) }
                            delay(700)
                            index += if (target > index) 1 else -1
                            posMs = 0
                            stampedAt = SystemClock.elapsedRealtime()
                            publish()
                        }
                        _state.update { it.copy(jump = null) }
                    }
                    return CommandResult.OK
                }
                index = target; posMs = 0
            }
            Command.CancelJump -> {
                jumpJob?.cancel(); _state.update { it.copy(jump = null) }
                return CommandResult.fail(CommandError.JUMP_CANCELLED)
            }
            is Command.VolumeStep -> {
                if (!s.dac.connected) return CommandResult.fail(CommandError.DAC_DISCONNECTED)
                val step = if (c.repeatCount > 10) 2 else 1
                val next = volumeDb + c.deltaDb * step
                if (next > capDb) {
                    if (volumeDb < capDb) volumeDb = capDb else return CommandResult.fail(CommandError.VOLUME_ABOVE_CAP)
                } else volumeDb = next.coerceAtLeast(-127f)
            }
            Command.ToggleMute -> {
                if (!s.dac.connected) return CommandResult.fail(CommandError.DAC_DISCONNECTED)
                muted = !muted
            }
            Command.RefreshState -> Unit
        }
        stampedAt = SystemClock.elapsedRealtime()
        publish()
        return CommandResult.OK
    }

    fun setVolumeDb(db: Float): CommandResult {
        if (db > capDb) return CommandResult.fail(CommandError.VOLUME_ABOVE_CAP)
        volumeDb = db.roundToInt().toFloat(); publish(); return CommandResult.OK
    }

    fun setCap(db: Float) {
        capDb = db; publish()
    }
}
