package io.github.dacstage.host

import android.util.Log
import io.github.dacstage.artwork.ArtworkResolver
import io.github.dacstage.dac.DacVolumeControl
import io.github.dacstage.enrich.InfoLookup
import io.github.dacstage.enrich.MbInfo
import io.github.dacstage.enrich.TrackQuery
import io.github.dacstage.model.ArtRef
import io.github.dacstage.model.ArtSource
import io.github.dacstage.model.Lyrics
import io.github.dacstage.model.Command
import io.github.dacstage.model.CommandError
import io.github.dacstage.model.CommandOrigin
import io.github.dacstage.model.CommandResult
import io.github.dacstage.model.HostState
import io.github.dacstage.model.QueueState
import io.github.dacstage.model.QueueUnavailableReason
import io.github.dacstage.players.ArtCandidate
import io.github.dacstage.players.PlayerAdapter
import io.github.dacstage.players.PlayerSnapshot
import io.github.dacstage.players.SessionMonitor
import io.github.dacstage.players.TransportAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Núcleo del host: une el reproductor activo (HiBy, UAPP u otra app), el DAC y la carátula verificada en un único
 * [HostState]. Todo comando (TV o UI del host) pasa por [execute]; la política de volumen la aplica
 * [DacVolumeControl] igual para ambos orígenes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HostCore(
    private val settings: HostSettings,
    private val sessions: SessionMonitor,
    private val dac: DacVolumeControl,
    private val artwork: ArtworkResolver,
    private val scope: CoroutineScope,
    /** Formatos de streaming que declara el DAC (descriptores). */
    private val dacFormats: StateFlow<List<io.github.dacstage.dac.UsbStreamFormat>> = MutableStateFlow(emptyList()),
    /** Letras/MusicBrainz (solo se consulta si el ajuste correspondiente está activado). */
    private val info: InfoLookup? = null,
) : HostController {

    /** Información en línea de una pista concreta: solo se publica mientras esa pista sigue sonando. */
    private data class Enrichment(
        val query: TrackQuery,
        val lyrics: Lyrics? = null,
        val mb: MbInfo? = null,
        val lyricsPending: Boolean = false,
    )

    private val enrichment = MutableStateFlow<Enrichment?>(null)

    private fun enrichmentFor(snap: PlayerSnapshot?, e: Enrichment?): Enrichment? =
        e?.takeIf { snap != null && it.query == TrackQuery.from(snap.track) }

    /**
     * Portada de Cover Art Archive como candidata más, solo si el reproductor no da ya una URL de CDN
     * (UAPP/TIDAL a 1280 px). La cadena de carátulas se queda con la de mayor tamaño real.
     */
    private fun coverArtCandidates(snap: PlayerSnapshot, e: Enrichment?): List<ArtCandidate> {
        if (snap.artCandidates.any { it is ArtCandidate.Url && it.source == ArtSource.PLAYER_QUEUE_URL }) return emptyList()
        val urls = enrichmentFor(snap, e)?.mb?.coverUrls.orEmpty()
        if (urls.isEmpty()) return emptyList()
        return listOf(ArtCandidate.Url(urls.first(), ArtSource.COVER_ART_ARCHIVE, urls.drop(1)))
    }

    /** Última foto con pista; sirve para cubrir el hueco entre canciones. */
    private var lastWithTrack: PlayerSnapshot? = null

    /**
     * Al cambiar de canción, HiBy/UAPP publican un instante sin metadatos (o la sesión se recrea): sin
     * esto la TV y el host mostrarían "Nada sonando" un momento. Durante [TRACK_GAP_GRACE_MS] se mantiene
     * la última pista; si el hueco persiste (de verdad no suena nada), se publica el estado real.
     */
    private val snapshot: StateFlow<PlayerSnapshot?> = sessions.activePlayer
        .flatMapLatest { it?.snapshot ?: flowOf(null) }
        .transformLatest { snap ->
            if (snap?.track != null) {
                lastWithTrack = snap
                emit(snap)
                return@transformLatest
            }
            val last = lastWithTrack
            if (last != null && (snap == null || snap.player == last.player) &&
                sessions.unsupportedActivePackage.value == null
            ) {
                emit(
                    snap?.copy(track = last.track, trackKey = last.trackKey, artCandidates = last.artCandidates,
                        progress = last.progress, progressAtElapsedMs = last.progressAtElapsedMs)
                        ?: last,
                )
                delay(TRACK_GAP_GRACE_MS)
            }
            lastWithTrack = null
            emit(snap)
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val art = MutableStateFlow<ArtRef?>(null)
    private var artJob: Job? = null
    private var enrichJob: Job? = null
    private var trackRefreshJob: Job? = null
    private var lyricsPrefetchJob: Job? = null

    /** El DAC está en silencio por un salto en la cola (no por el usuario). */
    private val jumpMuted = MutableStateFlow(false)

    override val state: StateFlow<HostState> = combine(
        snapshot,
        sessions.unsupportedActivePackage,
        combine(dac.state, dacFormats, jumpMuted) { d, f, jm ->
            // El silencio interno del salto no se enseña como "Silencio" del usuario.
            (if (jm && d.muted == true) d.copy(muted = false) else d) to f
        },
        combine(settings.hostName, settings.tvDisplay) { n, d -> n to d },
        combine(art, enrichment) { a, e -> a to e },
    ) { snap, unsupported, (dacState, formats), (hostName, tvDisplay), (artRef, enrich) ->
        // Entre el cambio de pista y la consulta de su letra (milisegundos), la pista nueva cuenta como
        // "pendiente": la TV no sale del modo letra ni enseña la letra anterior.
        val e = enrichmentFor(snap, enrich)
            ?: TrackQuery.from(snap?.track)?.takeIf { settings.lyricsEnabled.value }?.let { Enrichment(it, lyricsPending = true) }
        HostState(
            hostName = hostName,
            player = snap?.player,
            playerName = snap?.playerName,
            unsupportedPlayerPackage = unsupported,
            status = snap?.status ?: io.github.dacstage.model.PlaybackStatus.NONE,
            track = snap?.track,
            progress = snap?.let(::currentProgress),
            progressStampMs = android.os.SystemClock.elapsedRealtime(),
            art = artRef,
            specs = (snap?.specs ?: io.github.dacstage.model.TechSpecs()).let { s ->
                // Bits por USB: trama que preparó el reproductor × formatos del DAC. Sin coincidencia única, nada.
                val bits = if (dacState.connected) {
                    io.github.dacstage.dac.UsbStreamFormat.bitsForFrame(formats, snap?.usbFrameBytes)
                } else null
                bits?.let { s.copy(usbBitDepth = io.github.dacstage.model.Spec(it, io.github.dacstage.model.SpecSource.DAC_DESCRIPTOR)) } ?: s
            },
            queue = snap?.queue ?: QueueState(available = false, unavailableReason = QueueUnavailableReason.NO_PLAYER),
            jump = snap?.jump,
            dac = dacState,
            tvDisplay = tvDisplay,
            systemVolume = snap?.systemVolume?.takeIf {
                dacState.control == io.github.dacstage.model.DacControlAvailability.ANDROID_AUDIO
            },
            lyrics = e?.lyrics,
            lyricsPending = e?.lyricsPending == true,
            details = e?.mb?.details,
        )
    }.stateIn(scope, SharingStarted.Eagerly, HostState(hostName = settings.hostName.value))

    init {
        // Carátula: se resuelve una vez por pista (y de nuevo si el reproductor publica candidatas mejores).
        combine(snapshot, enrichment) { snap, e ->
            snap?.trackKey?.let { key -> ArtRequest(key, snap.artCandidates + coverArtCandidates(snap, e)) }
        }
            .distinctUntilChanged { a, b -> a?.signature == b?.signature }
            .onEach { req ->
                artJob?.cancel()
                if (req == null) {
                    art.value = null
                    return@onEach
                }
                artJob = scope.launch {
                    val resolved = runCatching { artwork.resolve(req.signature, req.candidates) }
                        .onFailure { Log.w(TAG, "art: fallo resolviendo ${req.trackKey}", it) }
                        .getOrNull()
                    // Mantener la carátula anterior solo si la nueva no se pudo resolver y es la misma pista.
                    art.value = resolved
                }
            }
            .launchIn(scope)

        // Información en línea: una consulta por pista (y de nuevo si se activa un ajuste).
        if (info != null) {
            combine(
                snapshot.map { TrackQuery.from(it?.track) }.distinctUntilChanged(),
                settings.lyricsEnabled,
                settings.musicBrainzEnabled,
            ) { q, ly, mb -> Triple(q, ly, mb) }
                .distinctUntilChanged()
                .onEach { (q, ly, mb) ->
                    enrichJob?.cancel()
                    // Letra ya guardada (la cola se precarga): se publica en el acto, sin estado "buscando".
                    val cached = if (q != null && ly) runCatching { info?.cachedLyrics(q) }.getOrNull() else null
                    enrichment.value = q?.let { Enrichment(it, lyrics = cached?.value, lyricsPending = ly && cached == null) }
                    if (q == null || (!ly && !mb)) return@onEach
                    enrichJob = scope.launch {
                        // Pequeña espera: al saltar varias pistas seguidas no se consulta cada una.
                        delay(ENRICH_DELAY_MS)
                        if (ly && cached == null) launch {
                            val l = runCatching { info.lyrics(q) }.getOrNull()
                            enrichment.update { if (it?.query == q) it.copy(lyrics = l, lyricsPending = false) else it }
                        }
                        if (mb) launch {
                            val m = runCatching { info.musicBrainz(q) }.getOrNull()
                            enrichment.update { if (it?.query == q) it.copy(mb = m) else it }
                        }
                    }
                }
                .launchIn(scope)
        }

        // Letras de toda la cola por adelantado (desde la siguiente pista hasta el final, luego las anteriores),
        // de una en una y espaciadas: al llegar a cada canción la letra sale de la caché al instante. Solo con
        // las letras activadas; las ya guardadas no se vuelven a pedir.
        if (info != null) {
            combine(
                snapshot.map { it?.queue?.takeIf { q -> q.available && q.items.isNotEmpty() } }
                    .distinctUntilChanged { a, b -> a?.version == b?.version },
                settings.lyricsEnabled,
            ) { q, ly -> if (ly) q else null }
                .distinctUntilChanged { a, b -> a?.version == b?.version }
                .onEach { q ->
                    lyricsPrefetchJob?.cancel()
                    if (q == null) return@onEach
                    lyricsPrefetchJob = scope.launch {
                        delay(LYRICS_PREFETCH_START_MS) // primero la letra de la pista que suena
                        val start = (q.currentIndex + 1).coerceIn(0, q.items.size)
                        val order = (q.items.drop(start) + q.items.take(start)).take(LYRICS_PREFETCH_MAX)
                        val queries = order.mapNotNull { TrackQuery.from(it) }.distinctBy { it.cacheKey }
                        var fetched = 0
                        for (tq in queries) {
                            val network = runCatching { info.prefetchLyrics(tq) }.getOrDefault(false)
                            if (network) {
                                fetched++
                                delay(LYRICS_PREFETCH_GAP_MS)
                            }
                        }
                        Log.i(TAG, "letras de la cola: ${queries.size} pistas, $fetched consultadas a LRCLIB")
                    }
                }
                .launchIn(scope)
        }

        // Al cambiar de pista se relee el DAC (frecuencia real del Clock Source, volumen, mute), pero
        // DESPUÉS de que el reproductor lo configure: al empezar una pista UAPP fija la frecuencia del DAC
        // por la interfaz de control, y si en ese instante la tenemos reclamada su petición falla (EBUSY) y
        // el DAC se queda en la frecuencia anterior. Por eso se espera y una pista nueva cancela la lectura.
        snapshot
            .map { it?.trackKey }
            .distinctUntilChanged()
            .onEach { key ->
                trackRefreshJob?.cancel()
                if (key != null) trackRefreshJob = scope.launch {
                    delay(TRACK_REFRESH_DELAY_MS)
                    dac.refresh()
                }
            }
            .launchIn(scope)
    }

    /** Silencia el DAC solo si estaba sin silenciar (si el usuario ya lo tenía en silencio, no se toca). */
    private val jumpSilencer = object : io.github.dacstage.players.JumpSilencer {
        override suspend fun mute(playerPaused: Boolean): Boolean {
            if (dac.state.value.muted != false) return false
            jumpMuted.value = true
            return dac.trySetMute(true, playerPaused).also { if (!it) jumpMuted.value = false }
        }

        override suspend fun unmute(playerPaused: Boolean): Boolean =
            // Si falla, se deja de ocultar: el usuario verá "Silencio" y podrá quitarlo.
            dac.trySetMute(false, playerPaused).also { jumpMuted.value = false }
    }

    override suspend fun execute(command: Command, origin: CommandOrigin): CommandResult {
        val player: PlayerAdapter? = sessions.activePlayer.value
        return when (command) {
            Command.TogglePlayPause -> player.transport(TransportAction.TOGGLE)
            Command.Play -> player.transport(TransportAction.PLAY)
            Command.Pause -> player.transport(TransportAction.PAUSE)
            Command.Next -> player.transport(TransportAction.NEXT)
            Command.Previous -> player.transport(TransportAction.PREVIOUS)
            is Command.JumpToQueueItem -> when {
                player == null -> CommandResult.fail(CommandError.NO_PLAYER)
                !state.value.queue.available -> CommandResult.fail(CommandError.QUEUE_UNAVAILABLE)
                state.value.queue.items.none { it.key == command.key } ->
                    CommandResult.fail(CommandError.UNKNOWN_QUEUE_ITEM)
                else -> player.jumpTo(command.key, jumpSilencer)
            }.also { r ->
                if (!r.ok) Log.w(TAG, "salto a ${command.key}: ${r.error} ${r.message.orEmpty()}")
            }
            Command.CancelJump -> {
                player?.cancelJump(); CommandResult.OK
            }
            is Command.VolumeStep -> volume(player, command.deltaDb, command.repeatCount) {
                dac.step(command.deltaDb, command.repeatCount, origin)
            }
            Command.ToggleMute -> volume(player, 0, 0) { dac.toggleMute(origin) }
            Command.RefreshState -> dac.refresh()
        }
    }

    @Volatile private var lastSystemStepMs = 0L

    /**
     * Volumen: por USB en el DAC ([viaDac]); si el DAC lo usa el audio de Android (no se toca por USB), con
     * el volumen multimedia de Android de la sesión activa. [direction] 0 = alternar silencio.
     */
    private suspend fun volume(
        player: PlayerAdapter?,
        direction: Int,
        repeatCount: Int,
        viaDac: suspend () -> CommandResult,
    ): CommandResult {
        val androidAudio = io.github.dacstage.model.DacControlAvailability.ANDROID_AUDIO
        if (dac.state.value.control != androidAudio) {
            val r = viaDac()
            if (r.ok || dac.state.value.control != androidAudio) return r
        }
        if (player == null) return CommandResult.fail(CommandError.NO_PLAYER)
        if (direction != 0) {
            val now = android.os.SystemClock.elapsedRealtime()
            val minGap = maxOf(io.github.dacstage.dac.DacVolumeManager.minStepIntervalMs(repeatCount), SYSTEM_STEP_MIN_MS)
            if (now - lastSystemStepMs < minGap) return CommandResult.fail(CommandError.RATE_LIMITED)
            lastSystemStepMs = now
        }
        return player.adjustVolume(direction)
    }

    /** Posición extrapolada al instante actual (la TV vuelve a sellarla al recibir). */
    private fun currentProgress(snap: PlayerSnapshot): io.github.dacstage.model.Progress? {
        val p = snap.progress ?: return null
        val at = snap.progressAtElapsedMs ?: return p
        if (snap.status != io.github.dacstage.model.PlaybackStatus.PLAYING) return p
        val elapsed = (android.os.SystemClock.elapsedRealtime() - at).coerceAtLeast(0)
        val pos = p.positionMs + (elapsed * p.speed).toLong()
        val max = snap.track?.durationMs
        return p.copy(positionMs = if (max != null && max > 0) pos.coerceAtMost(max) else pos)
    }

    private suspend fun PlayerAdapter?.transport(action: TransportAction): CommandResult =
        this?.transport(action) ?: CommandResult.fail(CommandError.NO_PLAYER)

    private data class ArtRequest(val trackKey: String, val candidates: List<ArtCandidate>) {
        /** Identidad de la petición: pista + candidatas (URL o identidad del bitmap). */
        val signature: String = buildString {
            append(trackKey)
            candidates.forEach { c ->
                append('|')
                when (c) {
                    is ArtCandidate.Url -> append(c.url)
                    // Solo el tamaño: la sesión puede reenviar el mismo bitmap como objeto nuevo.
                    is ArtCandidate.Embedded -> append("bmp:").append(c.bitmap.width).append('x')
                        .append(c.bitmap.height)
                }
            }
        }
    }

    private companion object {
        /** Los pasos del volumen de Android son gruesos (~15): al mantener, no más de ~7 por segundo. */
        private const val SYSTEM_STEP_MIN_MS = 140L
        private const val LYRICS_PREFETCH_START_MS = 4_000L
        private const val LYRICS_PREFETCH_GAP_MS = 1_200L
        private const val LYRICS_PREFETCH_MAX = 300
        const val TAG = "DacStageHost"
        const val TRACK_GAP_GRACE_MS = 2_500L
        private const val ENRICH_DELAY_MS = 1_200L
        /** Margen para que el reproductor termine de configurar el DAC (frecuencia) al empezar una pista. */
        const val TRACK_REFRESH_DELAY_MS = 3_000L
    }
}
