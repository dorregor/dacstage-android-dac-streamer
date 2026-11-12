package io.github.dacstage.players.uapp

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.dacstage.model.ArtSource
import io.github.dacstage.model.CommandError
import io.github.dacstage.model.CommandResult
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
import io.github.dacstage.players.ArtCandidate
import io.github.dacstage.players.JumpSilencer
import io.github.dacstage.players.PlayerAdapter
import io.github.dacstage.players.PlayerSnapshot
import io.github.dacstage.players.PositionMath
import io.github.dacstage.players.SessionMeta
import io.github.dacstage.players.TransportAction
import io.github.dacstage.players.isActive
import io.github.dacstage.players.toSessionMeta
import io.github.dacstage.players.toStatus
import io.github.dacstage.players.uapp.UappFileAccess.Companion.PLAY_QUEUE_XML
import io.github.dacstage.players.uapp.UappFileAccess.Companion.POS_A
import io.github.dacstage.players.uapp.UappFileAccess.Companion.POS_B
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * UAPP: transporte y metadatos por su MediaSession; cola, índice vivo y specs por pista desde
 * PlayQueue.xml + PosA/PosB (solo con [UappFileAccess] conectado). Si archivos y sesión no cuadran, la
 * cola se oculta (UAPP_OUT_OF_SYNC). Saltos con N × siguiente/anterior ([UappStepper]).
 */
class UappAdapter(
    private val controller: MediaController,
    private val files: UappFileAccess,
    parentScope: CoroutineScope,
    private val handler: Handler = Handler(Looper.getMainLooper()),
) : PlayerAdapter {

    override val id: PlayerId = PlayerId.UAPP

    private val _snapshot = MutableStateFlow(PlayerSnapshot(PlayerId.UAPP))
    override val snapshot: StateFlow<PlayerSnapshot> = _snapshot.asStateFlow()

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[kotlinx.coroutines.Job]))
    private val transport = UappTransport(controller)
    private val lock = Any()
    private val pollMutex = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    // Estado protegido por [lock].
    @Volatile private var closed = false
    private var playback: PlaybackState? = null
    private var meta: SessionMeta? = null
    private var accessConnected = false
    private var xmlQueue: UappPlayQueue? = null
    private var xmlMissing = false
    private var xmlStamp: UappFileStat? = null
    private var position: UappPosition? = null
    private var posStamp: UappFileStat? = null
    private var jump: JumpProgress? = null
    private var recentChangeUntil = 0L
    private var logInfo: UappLogInfo? = null
    private var logTrackKey: String? = null
    private var logReadAt = 0L
    private var lastQueueItems: List<QueueItem> = emptyList()
    private var lastQueueVersion = 0L

    private var jumpJob: Deferred<UappStepper.Outcome>? = null
    /** Lo que se mostraba al empezar el salto: se mantiene hasta llegar para no enseñar las pistas intermedias. */
    private var frozen: PlayerSnapshot? = null
    /** Sonaba al empezar el salto: se reanuda al terminar (lo hereda un salto que reemplace a otro). */
    private var pendingResume = false
    /** El DAC lo silenció un salto y hay que devolverlo al terminar. */
    private var mutedForJump = false
    /** Estado que se sigue mostrando justo tras el salto, hasta que UAPP lo confirme o venza [heldUntil]. */
    private var heldStatus: PlaybackStatus? = null
    private var heldUntil = 0L
    private var jumpGen = 0L

    /**
     * Arranque: hasta la primera lectura de la cola (o [STARTUP_HOLD_MS]) no se publica nada si los archivos
     * de UAPP van a estar disponibles. Si no, se vería un instante "a lo genérico" (título con número,
     * carátula pequeña, sin formato ni cola) y luego cambiaría todo al llegar PlayQueue.xml.
     */
    @Volatile private var startupSettled = false

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val changed = synchronized(lock) {
                val before = playback.toStatus()
                playback = state
                before != state.toStatus()
            }
            if (changed) markChange()
            rebuild()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            synchronized(lock) { meta = metadata.toSessionMeta() }
            markChange()
            rebuild()
        }

        override fun onSessionDestroyed() = rebuild()
    }

    init {
        synchronized(lock) {
            playback = controller.playbackState
            meta = controller.metadata.toSessionMeta()
        }
        controller.registerCallback(callback, handler)
        markChange()
        rebuild()
        scope.launch(Dispatchers.IO) { pollLoop() }
        scope.launch {
            delay(STARTUP_HOLD_MS)
            settleStartup()
        }
    }

    private fun settleStartup() {
        if (startupSettled) return
        startupSettled = true
        rebuild()
    }

    /** Los archivos de UAPP están conectados o conectándose (merece la pena esperar la primera lectura). */
    private fun filesComing(): Boolean = when (files.status.value) {
        UappAccessStatus.Connected, UappAccessStatus.Connecting -> true
        else -> false
    }

    // ---- Sondeo de archivos ----

    private suspend fun pollLoop() {
        files.status.map { it is UappAccessStatus.Connected }.distinctUntilChanged().collectLatest { connected ->
            synchronized(lock) {
                accessConnected = connected
                if (!connected) {
                    xmlQueue = null; xmlStamp = null; xmlMissing = false; position = null; posStamp = null
                    logInfo = null; logTrackKey = null
                }
            }
            rebuild()
            if (!connected) {
                if (!filesComing()) settleStartup()
                return@collectLatest
            }
            markChange()
            while (true) {
                pollOnce(forcePos = false)
                settleStartup()
                val interval = if (isPlayingOrRecent()) POS_INTERVAL_MS else XML_INTERVAL_MS
                withTimeoutOrNull(interval) { wake.receive() }
            }
        }
    }

    private fun isPlayingOrRecent(): Boolean = synchronized(lock) {
        playback.toStatus().isActive() || SystemClock.elapsedRealtime() < recentChangeUntil
    }

    /** Una pasada: stat de los tres archivos; relee el XML si cambió y PosA/PosB si toca. */
    private suspend fun pollOnce(forcePos: Boolean) = pollMutex.withLock {
        val stats = files.statFiles(listOf(PLAY_QUEUE_XML, POS_A, POS_B)).getOrElse {
            Log.d(TAG, "stat falló: ${it.message}")
            return@withLock
        }.associateBy { it.name }

        val xml = stats[PLAY_QUEUE_XML]
        val prevXml = synchronized(lock) { xmlStamp }
        if (xml == null) {
            synchronized(lock) { xmlQueue = null; xmlMissing = true; xmlStamp = null }
        } else if (xml != prevXml) {
            val parsed = files.readFile(PLAY_QUEUE_XML).getOrNull()?.let { PlayQueueXmlParser.parse(it) }
            // Si se leyó a mitad de escritura, no se da por consumido: se reintenta en la siguiente pasada.
            if (parsed != null) {
                synchronized(lock) { xmlQueue = parsed; xmlStamp = xml; xmlMissing = false }
                markChange(wakeUp = false)
            }
        }

        if (forcePos || isPlayingOrRecent()) {
            // UAPP escribe en el más antiguo de los dos: el más reciente por mtime está completo.
            val ordered = listOfNotNull(stats[POS_A], stats[POS_B]).sortedByDescending { it.mtimeMs }
            val prevPos = synchronized(lock) { posStamp }
            val newest = ordered.firstOrNull()
            if (newest != null && newest != prevPos) {
                for (st in ordered) {
                    val pos = files.readFile(st.name).getOrNull()?.let { PosFileParser.parse(it) } ?: continue
                    synchronized(lock) { position = pos; posStamp = st }
                    break
                }
            }
        }
        maybeReadLog()
        rebuild()
    }

    /**
     * Formato del stream y trama USB, del final de UAPP.txt: al cambiar de pista (una vez UAPP ya la abrió)
     * y cada [LOG_REFRESH_MS] mientras suena. Solo se guardan los valores extraídos.
     */
    private suspend fun maybeReadLog() {
        val now = SystemClock.elapsedRealtime()
        val key = _snapshot.value.trackKey ?: return
        val due = synchronized(lock) {
            (key != logTrackKey && now >= recentChangeUntil - RECENT_CHANGE_MS + LOG_DELAY_MS) ||
                (playback.toStatus() == PlaybackStatus.PLAYING && now - logReadAt > LOG_REFRESH_MS)
        }
        if (!due) return
        val bytes = files.readTail(UappFileAccess.UAPP_LOG, LOG_TAIL_BYTES).getOrNull() ?: return
        val info = UappLogParser.parse(String(bytes, Charsets.UTF_8))
        if (info.sampleRateSetFailed) {
            Log.w(TAG, "UAPP no pudo fijar ${info.usbSampleRateHz} Hz en el DAC (el DAC sigue en la frecuencia anterior)")
        }
        synchronized(lock) {
            // La trama solo se escribe al preparar el USB: si ya no está en el tramo leído, se conserva la última.
            logInfo = info.copy(usbFrameBytes = info.usbFrameBytes ?: logInfo?.usbFrameBytes)
            logTrackKey = key
            logReadAt = now
        }
    }

    private fun markChange(wakeUp: Boolean = true) {
        synchronized(lock) { recentChangeUntil = SystemClock.elapsedRealtime() + RECENT_CHANGE_MS }
        if (wakeUp) wake.trySend(Unit)
    }

    // ---- Snapshot ----

    private fun rebuild() {
        if (closed) return
        synchronized(lock) {
            val status = playback.toStatus()
            val m = meta
            val sync = resolveSync(hint = null)
            val queue = queueState(sync)
            val entry = (sync as? UappQueueSync.Result.InSync)?.let { xmlQueue?.items?.getOrNull(it.currentIndex) }

            val now = SystemClock.elapsedRealtime()
            val pb = playback
            val progress = pb?.takeIf { it.position >= 0 && status != PlaybackStatus.NONE }?.let {
                val pos = PositionMath.extrapolate(
                    it.position, it.lastPositionUpdateTime, it.playbackSpeed, now,
                    status == PlaybackStatus.PLAYING, m?.durationMs,
                )
                Progress(pos, if (status == PlaybackStatus.PLAYING) it.playbackSpeed else 0f)
            }

            val art = buildList<ArtCandidate> {
                val urls = UappArtUrls.candidates(entry?.artUrl)
                if (urls.isNotEmpty()) add(ArtCandidate.Url(urls.first(), ArtSource.PLAYER_QUEUE_URL, urls.drop(1)))
                addAll(m?.sessionArtCandidates().orEmpty())
            }

            val live = PlayerSnapshot(
                player = PlayerId.UAPP,
                status = status,
                // Con la cola en sincronía, el título limpio de PlayQueue.xml (la sesión lo publica como
                // "<TrackNr>. <Título>"); así también lo buscan LRCLIB/MusicBrainz.
                track = m?.let {
                    val title = entry?.title?.takeIf { t -> t.isNotBlank() } ?: it.title?.let(::stripTrackNumber)
                    TrackInfo(title, it.artist, it.album, it.durationMs ?: entry?.durationMs, entry?.id)
                },
                progress = progress,
                progressAtElapsedMs = progress?.let { now },
                trackKey = m?.let { trackKeyOf(it) },
                artCandidates = art,
                specs = (entry?.specs() ?: TechSpecs()).let { s ->
                    var out = logInfo?.codec?.let { s.copy(codec = Spec(it, SpecSource.UAPP_LOG)) } ?: s
                    // Frecuencia que UAPP fijó en el DAC para ESTA pista (solo si el log ya es de esta pista).
                    // Si el DAC la rechazó, sigue en la anterior: no se publica ninguna (no se adivina).
                    val li = logInfo
                    val currentKey = m?.let { trackKeyOf(it) }
                    if (li != null && logTrackKey == currentKey) {
                        out = when {
                            li.sampleRateSetFailed -> out.copy(sampleRateHz = null)
                            li.usbSampleRateHz != null -> out.copy(sampleRateHz = Spec(li.usbSampleRateHz, SpecSource.UAPP_LOG))
                            else -> out
                        }
                    }
                    out
                },
                queue = queue,
                jump = jump,
                usbFrameBytes = logInfo?.usbFrameBytes,
            )
            // Arranque: se espera a la primera lectura de la cola (ver [startupSettled]).
            if (!startupSettled && filesComing()) return
            val f = frozen
            val hold = heldStatus
            if (hold != null && (status == hold || now >= heldUntil)) heldStatus = null
            _snapshot.value = if (jump != null && f != null) {
                // Durante el salto: pista, carátula, progreso (parado), estado y posición en la cola de antes.
                // Las pausas internas no se enseñan (el botón play/pausa no parpadea).
                live.copy(
                    status = f.status,
                    track = f.track,
                    trackKey = f.trackKey,
                    artCandidates = f.artCandidates,
                    progress = f.progress?.copy(speed = 0f),
                    progressAtElapsedMs = f.progress?.let { now },
                    specs = f.specs,
                    usbFrameBytes = f.usbFrameBytes,
                    queue = if (live.queue.available && f.queue.available) live.queue.copy(currentIndex = f.queue.currentIndex) else live.queue,
                )
            } else if (heldStatus != null) {
                // Recién terminado el salto: "reproduciendo" hasta que UAPP confirme la reanudación.
                live.copy(status = heldStatus!!)
            } else {
                live
            }
        }
    }

    private fun trackKeyOf(m: io.github.dacstage.players.SessionMeta) = "uapp:" + m.identity()

    /** Llamar con [lock] tomado. */
    private fun resolveSync(hint: Int?): UappQueueSync.Result? {
        val q = xmlQueue ?: return null
        return UappQueueSync.resolve(q.items, position, q.currentItem, meta?.title, hint)
    }

    /** Llamar con [lock] tomado. */
    private fun queueState(sync: UappQueueSync.Result?): QueueState {
        fun unavailable(r: QueueUnavailableReason) = QueueState(available = false, unavailableReason = r)
        if (!accessConnected) return unavailable(QueueUnavailableReason.UAPP_NO_FILE_ACCESS)
        val q = xmlQueue ?: return unavailable(
            if (xmlMissing) QueueUnavailableReason.EMPTY else QueueUnavailableReason.UAPP_NO_FILE_ACCESS
        )
        return when (sync) {
            null, UappQueueSync.Result.Empty -> unavailable(QueueUnavailableReason.EMPTY)
            is UappQueueSync.Result.OutOfSync -> unavailable(QueueUnavailableReason.UAPP_OUT_OF_SYNC)
            is UappQueueSync.Result.InSync -> {
                val items = q.items.map { it.toQueueItem() }
                if (items != lastQueueItems) {
                    lastQueueItems = items
                    lastQueueVersion = items.fold(1125899906842597L) { h, i -> 31 * h + i.hashCode() }
                }
                QueueState(
                    available = true,
                    unavailableReason = null,
                    items = lastQueueItems,
                    currentIndex = sync.currentIndex,
                    version = lastQueueVersion,
                )
            }
        }
    }

    // ---- Comandos ----

    override suspend fun transport(action: TransportAction): CommandResult {
        if (closed) return CommandResult.fail(CommandError.NO_PLAYER)
        when (action) {
            TransportAction.PLAY -> transport.play()
            TransportAction.PAUSE -> transport.pause()
            TransportAction.NEXT -> transport.next()
            TransportAction.PREVIOUS -> transport.previous()
            TransportAction.TOGGLE -> {
                val playing = synchronized(lock) { playback.toStatus() == PlaybackStatus.PLAYING }
                if (playing) transport.pause() else transport.play()
            }
        }
        markChange()
        return CommandResult.OK
    }

    override suspend fun jumpTo(queueKey: String): CommandResult = jumpTo(queueKey, null)

    override suspend fun jumpTo(queueKey: String, silencer: JumpSilencer?): CommandResult {
        if (closed) return CommandResult.fail(CommandError.NO_PLAYER)
        val target = parseKey(queueKey) ?: return CommandResult.fail(CommandError.INVALID_ARGUMENT, "clave no válida")
        val queue = _snapshot.value.queue
        if (!queue.available) return CommandResult.fail(CommandError.QUEUE_UNAVAILABLE)
        if (queue.items.getOrNull(target)?.key != queueKey) return CommandResult.fail(CommandError.UNKNOWN_QUEUE_ITEM)

        // Se pausa solo si de verdad habrá pasos (no en saltos imposibles o a la pista actual).
        val start = freshIndex(null)
        val willStep = start != null && start != target && abs(target - start) <= MAX_STEPS
        val gen = synchronized(lock) {
            if (frozen == null) frozen = _snapshot.value
            pendingResume = pendingResume || (willStep && playback.toStatus().isActive())
            ++jumpGen
        }
        cancelJump() // el anterior, si lo hay (aún es jumpJob)
        val stepper = UappStepper(
            next = { transport.next(); markChange() },
            previous = { transport.previous(); markChange() },
            readIndex = { hint -> freshIndex(hint) },
        )
        val job = scope.async(Dispatchers.IO) {
            try {
                if (willStep) {
                    // UAPP vuelve a sonar con cada "siguiente" aunque esté en pausa: se silencia el DAC.
                    // Primero la pausa, para que el DAC nunca se reclame con música sonando.
                    pauseAndWait()
                    val needMute = silencer != null && synchronized(lock) { !mutedForJump }
                    if (needMute && silencer.mute(playerPaused = !isSounding())) synchronized(lock) { mutedForJump = true }
                }
                stepper.jump(target) { done, total -> setJump(JumpProgress(queueKey, done, total)) }
            } finally {
                withContext(NonCancellable) { finishJump(gen, silencer) }
            }
        }
        synchronized(lock) { jumpJob = job }
        return try {
            when (val outcome = job.await()) {
                is UappStepper.Outcome.Reached -> CommandResult.OK
                is UappStepper.Outcome.Failed -> when (outcome.reason) {
                    UappStepper.FailReason.NO_INDEX -> CommandResult.fail(CommandError.QUEUE_UNAVAILABLE, "índice actual desconocido")
                    UappStepper.FailReason.TOO_FAR -> CommandResult.fail(CommandError.JUMP_FAILED, "más de $MAX_STEPS pasos")
                    UappStepper.FailReason.TIMEOUT -> CommandResult.fail(CommandError.JUMP_FAILED, "UAPP no avanzó")
                    UappStepper.FailReason.STEP_LIMIT -> CommandResult.fail(CommandError.JUMP_FAILED, "tope de pasos")
                }
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
            CommandResult.fail(CommandError.JUMP_CANCELLED)
        } finally {
            val mine = synchronized(lock) { (jumpGen == gen).also { if (it) { jumpJob = null; frozen = null } } }
            if (mine) setJump(null)
        }
    }

    /**
     * Índice actual para el stepper, sin E/S: título de la sesión (llega por callback al instante tras cada
     * pulsación) contra la cola ya cargada; [hint] desempata títulos repetidos. Leer archivos aquí hacía
     * esperar al salto hasta 5 s por pulsación y provocaba pulsaciones de más. El sondeo de fondo sigue
     * actualizando PosA/PosB y el XML.
     */
    private fun freshIndex(hint: Int?): Int? =
        synchronized(lock) { (resolveSync(hint) as? UappQueueSync.Result.InSync)?.currentIndex }

    private fun isSounding(): Boolean = synchronized(lock) { playback.toStatus().isActive() }

    private suspend fun pauseAndWait() {
        if (!isSounding()) return
        transport.pause()
        withTimeoutOrNull(PAUSE_WAIT_MS) { while (isSounding()) delay(50) }
    }

    /**
     * Cierre de un salto. Solo actúa el último (si otro lo reemplazó, la reanudación y el silencio pasan a
     * ese): quita el silencio con UAPP en pausa y reanuda si sonaba al empezar o UAPP siguió sonando.
     */
    private suspend fun finishJump(gen: Long, silencer: JumpSilencer?) {
        val (latest, unmute, resumeWanted) = synchronized(lock) {
            val latest = jumpGen == gen
            Triple(latest, latest && mutedForJump, latest && pendingResume)
        }
        if (!latest) return
        var resume = resumeWanted
        var restart = false
        if (unmute) {
            // UAPP fija la frecuencia del DAC al empezar la pista: no tocar el DAC hasta que termine (si no,
            // su petición falla con EBUSY y el DAC se queda en la frecuencia anterior). La pista suena en
            // silencio mientras tanto; luego se vuelve a su inicio.
            delay(DAC_SETTLE_MS)
            if (synchronized(lock) { jumpGen != gen }) return // otro salto tomó el relevo (y el silencio)
            if (isSounding()) { resume = true; pauseAndWait() }
            var ok = silencer?.unmute(playerPaused = !isSounding()) == true
            if (!ok) { delay(300); ok = silencer?.unmute(playerPaused = !isSounding()) == true }
            if (!ok) Log.w(TAG, "no se pudo quitar el silencio del DAC tras el salto")
            synchronized(lock) { mutedForJump = false }
            restart = true
        }
        if (restart) transport.seekTo(0)
        synchronized(lock) {
            pendingResume = false
            if (resume) {
                heldStatus = PlaybackStatus.PLAYING
                heldUntil = SystemClock.elapsedRealtime() + RESUME_HOLD_MS
            }
        }
        if (resume) {
            transport.play(); markChange()
            // Si UAPP no llega a confirmar, se vuelve a publicar el estado real al vencer el plazo.
            scope.launch { delay(RESUME_HOLD_MS + 50); rebuild() }
        }
    }

    private fun setJump(value: JumpProgress?) {
        synchronized(lock) { jump = value }
        rebuild()
    }

    override fun cancelJump() {
        val job = synchronized(lock) { jumpJob }
        job?.cancel()
    }

    override fun close() {
        if (closed) return
        cancelJump()
        closed = true
        controller.unregisterCallback(callback)
        scope.cancel()
        wake.close()
    }

    companion object {
        private const val TAG = "UappAdapter"
        const val KEY_PREFIX = "uapp:"
        const val MAX_STEPS = 40
        private const val XML_INTERVAL_MS = 3_000L
        private const val STARTUP_HOLD_MS = 4_000L
        private val TRACK_NUMBER = Regex("^\\d{1,3}\\.\\s+")

        /** UAPP publica el título como "<TrackNr>. <Título>": sin el número (si queda algo). */
        internal fun stripTrackNumber(title: String): String =
            title.replaceFirst(TRACK_NUMBER, "").takeIf { it.isNotBlank() } ?: title
        private const val POS_INTERVAL_MS = 2_000L
        private const val RECENT_CHANGE_MS = 6_000L
        private const val LOG_DELAY_MS = 2_500L
        private const val LOG_REFRESH_MS = 60_000L
        private const val LOG_TAIL_BYTES = 96 * 1024
        private const val PAUSE_WAIT_MS = 1_500L
        private const val RESUME_HOLD_MS = 2_000L
        /** Espera tras llegar a la pista antes de tocar el DAC (UAPP lo está configurando). */
        private const val DAC_SETTLE_MS = 2_500L

        fun keyFor(entry: UappQueueEntry) = "$KEY_PREFIX${entry.index}:${entry.id.orEmpty()}"

        /** "uapp:<índice>:<ID>" → índice. */
        fun parseKey(key: String): Int? {
            if (!key.startsWith(KEY_PREFIX)) return null
            return key.removePrefix(KEY_PREFIX).substringBefore(':').toIntOrNull()?.takeIf { it >= 0 }
        }

        internal fun UappQueueEntry.specs() = TechSpecs(
            sampleRateHz = sampleRateHz?.let { Spec(it, SpecSource.UAPP_QUEUE_FILE) },
            bitDepth = bitDepth?.let { Spec(it, SpecSource.UAPP_QUEUE_FILE) },
        )

        internal fun UappQueueEntry.toQueueItem() = QueueItem(
            key = keyFor(this),
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            artUrl = artUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") },
            specs = specs(),
        )
    }
}
