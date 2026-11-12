package io.github.dacstage.players

import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.dacstage.model.CommandError
import io.github.dacstage.model.CommandResult
import io.github.dacstage.model.PlaybackStatus
import io.github.dacstage.model.PlayerId
import io.github.dacstage.model.Progress
import io.github.dacstage.model.QueueItem
import io.github.dacstage.model.QueueState
import io.github.dacstage.model.QueueUnavailableReason
import io.github.dacstage.model.SystemVolume
import io.github.dacstage.model.TechSpecs
import io.github.dacstage.model.TrackInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reproductor visto solo a través de su MediaSession (lo que da el acceso a notificaciones): metadatos,
 * carátula, progreso, transporte, cola si la publica (salto con skipToQueueItem) y volumen multimedia de
 * Android. No publica formato/bitrate: specs vacías. Base de HiBy y de cualquier otra app.
 *
 * Nunca usa playFromSearch ni playFromMediaId.
 */
open class SessionAdapter(
    protected val controller: MediaController,
    override val id: PlayerId,
    override val displayName: String,
    /** Prefijo de las claves de la cola y de la pista (distingue apps). */
    private val keyPrefix: String,
    private val handler: Handler = Handler(Looper.getMainLooper()),
    private val audioManager: AudioManager? = null,
    /** Respetar PlaybackState.actions (apps desconocidas). HiBy no lo necesita. */
    private val enforceActions: Boolean = true,
) : PlayerAdapter {

    private val _snapshot = MutableStateFlow(PlayerSnapshot(id, playerName = displayName))
    override val snapshot: StateFlow<PlayerSnapshot> = _snapshot.asStateFlow()

    @Volatile private var closed = false
    // Cola cacheada: se reconstruye solo en onQueueChanged (controller.queue copia por binder).
    private var queueList: List<MediaSession.QueueItem>? = null
    private var queueBase: QueueState = QueueState(available = false, unavailableReason = QueueUnavailableReason.NOT_PUBLISHED)

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = rebuild()
        override fun onMetadataChanged(metadata: MediaMetadata?) = rebuild()
        override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) {
            updateQueue(queue)
            rebuild()
        }
        override fun onAudioInfoChanged(info: MediaController.PlaybackInfo) = rebuild()
        override fun onSessionDestroyed() = rebuild()
    }

    private val rebuildRunnable = Runnable { rebuild() }

    init {
        controller.registerCallback(callback, handler)
        handler.post {
            if (!closed) updateQueue(controller.queue)
            rebuild()
        }
    }

    /** ID de pista externo informativo (HiBy: TIDAL). */
    protected open fun externalTrackId(metaMediaId: String?, queueMediaId: String?): String? = null

    private fun updateQueue(queue: List<MediaSession.QueueItem>?) {
        queueList = queue?.toList()
        queueBase = when {
            queue == null -> QueueState(available = false, unavailableReason = QueueUnavailableReason.NOT_PUBLISHED)
            queue.isEmpty() -> QueueState(available = false, unavailableReason = QueueUnavailableReason.EMPTY)
            else -> {
                val items = queue.map { it.toItem() }
                QueueState(
                    available = true,
                    unavailableReason = null,
                    items = items,
                    version = items.fold(1125899906842597L) { h, i -> 31 * h + i.hashCode() },
                )
            }
        }
    }

    private fun rebuild() {
        if (closed) return
        val state = controller.playbackState
        val meta = controller.metadata.toSessionMeta()
        val status = state.toStatus()
        val queue = queueList

        val queueState = if (queue.isNullOrEmpty()) queueBase
        else queueBase.copy(currentIndex = currentIndex(queue, state, meta?.mediaId))
        val currentQueueMediaId = queueState.currentIndex.takeIf { it >= 0 }
            ?.let { queue?.getOrNull(it)?.description?.mediaId }

        val now = SystemClock.elapsedRealtime()
        val progress = state?.takeIf { it.position >= 0 && status != PlaybackStatus.NONE }?.let {
            val pos = PositionMath.extrapolate(
                it.position, it.lastPositionUpdateTime, it.playbackSpeed, now,
                status == PlaybackStatus.PLAYING, meta?.durationMs,
            )
            Progress(pos, if (status == PlaybackStatus.PLAYING) it.playbackSpeed else 0f)
        }

        _snapshot.value = PlayerSnapshot(
            player = id,
            playerName = displayName,
            status = status,
            track = meta?.let {
                TrackInfo(it.title, it.artist, it.album, it.durationMs, externalTrackId(it.mediaId, currentQueueMediaId))
            },
            progress = progress,
            progressAtElapsedMs = progress?.let { now },
            trackKey = meta?.let { "$keyPrefix${controller.packageName}:" + it.identity() },
            artCandidates = meta?.sessionArtCandidates().orEmpty(),
            specs = TechSpecs(),
            queue = queueState,
            jump = null,
            systemVolume = readSystemVolume(),
        )
    }

    /** Volumen multimedia de la sesión si suena en este dispositivo y se puede cambiar. */
    private fun readSystemVolume(): SystemVolume? {
        val info = runCatching { controller.playbackInfo }.getOrNull() ?: return null
        if (info.playbackType != MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL) return null
        if (info.volumeControl == android.media.VolumeProvider.VOLUME_CONTROL_FIXED) return null
        val max = info.maxVolume
        if (max <= 0) return null
        val muted = audioManager?.let { am ->
            runCatching { am.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrNull()
        } ?: false
        return SystemVolume(info.currentVolume.coerceIn(0, max), max, muted)
    }

    private fun currentIndex(queue: List<MediaSession.QueueItem>, state: PlaybackState?, metaMediaId: String?): Int {
        val active = state?.activeQueueItemId ?: MediaSession.QueueItem.UNKNOWN_ID.toLong()
        if (active != MediaSession.QueueItem.UNKNOWN_ID.toLong()) {
            val i = queue.indexOfFirst { it.queueId == active }
            if (i >= 0) return i
        }
        // Algunas apps (HiBy) dejan activeQueueItemId = -1: se identifica por el mediaId de los metadatos.
        if (metaMediaId != null) {
            val matches = queue.indices.filter { queue[it].description?.mediaId == metaMediaId }
            if (matches.size == 1) return matches[0]
        }
        return -1
    }

    private fun MediaSession.QueueItem.toItem(): QueueItem {
        val d = description
        val extras = d?.extras
        val duration = extras?.getLong(MediaMetadata.METADATA_KEY_DURATION, -1L)?.takeIf { it > 0 }
        val icon = d?.iconUri?.toString()?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        // MediaDescription estándar: subtitle = artista, description = álbum.
        return QueueItem(
            key = "$keyPrefix$queueId",
            title = d?.title?.toString()?.takeIf { it.isNotBlank() },
            artist = d?.subtitle?.toString()?.takeIf { it.isNotBlank() },
            album = d?.description?.toString()?.takeIf { it.isNotBlank() },
            durationMs = duration,
            artUrl = icon,
        )
    }

    /** La sesión declara la acción (o no declara ninguna: se intenta igual). */
    private fun supports(vararg actions: Long): Boolean {
        if (!enforceActions) return true
        val declared = controller.playbackState?.actions ?: 0L
        if (declared == 0L) return true
        return actions.any { declared and it != 0L }
    }

    override suspend fun transport(action: TransportAction): CommandResult {
        if (closed) return CommandResult.fail(CommandError.NO_PLAYER)
        val c = controller.transportControls
        val playing = controller.playbackState.toStatus() == PlaybackStatus.PLAYING
        val ok = when (action) {
            TransportAction.PLAY -> supports(PlaybackState.ACTION_PLAY, PlaybackState.ACTION_PLAY_PAUSE).also { if (it) c.play() }
            TransportAction.PAUSE -> supports(PlaybackState.ACTION_PAUSE, PlaybackState.ACTION_PLAY_PAUSE).also { if (it) c.pause() }
            TransportAction.NEXT -> supports(PlaybackState.ACTION_SKIP_TO_NEXT).also { if (it) c.skipToNext() }
            TransportAction.PREVIOUS -> supports(PlaybackState.ACTION_SKIP_TO_PREVIOUS).also { if (it) c.skipToPrevious() }
            TransportAction.TOGGLE -> if (playing) {
                supports(PlaybackState.ACTION_PAUSE, PlaybackState.ACTION_PLAY_PAUSE).also { if (it) c.pause() }
            } else {
                supports(PlaybackState.ACTION_PLAY, PlaybackState.ACTION_PLAY_PAUSE).also { if (it) c.play() }
            }
        }
        return if (ok) CommandResult.OK else CommandResult.fail(CommandError.NOT_SUPPORTED, "La app no lo permite")
    }

    override suspend fun jumpTo(queueKey: String): CommandResult {
        if (closed) return CommandResult.fail(CommandError.NO_PLAYER)
        val queueId = queueKey.removePrefix(keyPrefix).takeIf { queueKey.startsWith(keyPrefix) }?.toLongOrNull()
            ?: return CommandResult.fail(CommandError.INVALID_ARGUMENT, "clave no válida")
        val queue = queueList ?: controller.queue ?: return CommandResult.fail(CommandError.QUEUE_UNAVAILABLE)
        if (queue.none { it.queueId == queueId }) return CommandResult.fail(CommandError.UNKNOWN_QUEUE_ITEM)
        if (!supports(PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM)) {
            return CommandResult.fail(CommandError.NOT_SUPPORTED, "La app no permite saltar en su cola")
        }
        controller.transportControls.skipToQueueItem(queueId)
        controller.transportControls.play()
        return CommandResult.OK
    }

    override suspend fun adjustVolume(direction: Int): CommandResult {
        if (closed) return CommandResult.fail(CommandError.NO_PLAYER)
        if (readSystemVolume() == null) return CommandResult.fail(CommandError.NOT_SUPPORTED, "Sin volumen ajustable")
        val adj = when {
            direction > 0 -> AudioManager.ADJUST_RAISE
            direction < 0 -> AudioManager.ADJUST_LOWER
            else -> AudioManager.ADJUST_TOGGLE_MUTE
        }
        controller.adjustVolume(adj, 0)
        // El volumen local no siempre avisa por onAudioInfoChanged: se relee enseguida.
        handler.removeCallbacks(rebuildRunnable)
        handler.postDelayed(rebuildRunnable, VOLUME_REREAD_MS)
        return CommandResult.OK
    }

    override fun cancelJump() = Unit

    override fun close() {
        if (closed) return
        closed = true
        handler.removeCallbacks(rebuildRunnable)
        controller.unregisterCallback(callback)
    }

    private companion object {
        const val VOLUME_REREAD_MS = 120L
    }
}
