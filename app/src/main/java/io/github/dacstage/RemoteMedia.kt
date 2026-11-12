package io.github.dacstage

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import io.github.dacstage.model.Command
import io.github.dacstage.model.PlaybackStatus
import io.github.dacstage.tv.TvViewModel
import io.github.dacstage.ui.common.ArtUrls
import io.github.dacstage.ui.common.NowPlayingUi
import io.github.dacstage.ui.common.ProgressAnchor
import io.github.dacstage.ui.tv.TvScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Control remoto en un teléfono: publica lo que suena en el host como una sesión multimedia de Android, para
 * que aparezca en la notificación, la pantalla de bloqueo y los controles multimedia del sistema, con
 * carátula, progreso y cola. Los botones del sistema envían los mismos comandos que la app, y las teclas de
 * volumen (también con la pantalla bloqueada) mueven el volumen del DAC por pasos, como el mando de la TV.
 * No reproduce audio en el teléfono: la sesión es de reproducción remota.
 */
internal class RemoteMedia private constructor(private val app: Context, val vm: TvViewModel) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val session = MediaSession(app, "DacStageRemote")
    private val nm = app.getSystemService(NotificationManager::class.java)
    private var queueKeys: List<String> = emptyList()
    private var art: Bitmap? = null
    private var artFor: String? = null
    private var artJob: Job? = null
    private var last: Triple<TvScreen, NowPlayingUi, ProgressAnchor?>? = null

    private val volume = object : VolumeProvider(VOLUME_CONTROL_RELATIVE, 100, 0) {
        override fun onAdjustVolume(direction: Int) {
            if (direction != 0) vm.volumeStep(if (direction > 0) 1 else -1, 0)
        }
    }

    init {
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = vm.send(Command.Play)
            override fun onPause() = vm.send(Command.Pause)
            override fun onStop() = vm.send(Command.Pause)
            override fun onSkipToNext() = vm.next()
            override fun onSkipToPrevious() = vm.previous()
            override fun onSkipToQueueItem(id: Long) {
                queueKeys.getOrNull(id.toInt())?.let(vm::jumpTo)
            }
        })
        session.setPlaybackToRemote(volume)
        session.setSessionActivity(openAppIntent(app))
        scope.launch {
            combine(vm.screen, vm.nowPlaying, vm.progress, vm.artBaseUrl) { s, np, p, base -> Quad(s, np, p, base) }
                .collect { (s, np, p, base) -> update(s, np, p, base) }
        }
        scope.launch {
            vm.volume.collect { v -> volume.currentVolume = ((v.fraction ?: 0f) * 100).roundToInt().coerceIn(0, 100) }
        }
        scope.launch {
            vm.queue.collect { q ->
                if (!q.available) {
                    queueKeys = emptyList()
                    session.setQueue(null)
                    return@collect
                }
                queueKeys = q.items.map { it.key }
                session.setQueue(
                    q.items.mapIndexed { i, item ->
                        MediaSession.QueueItem(
                            android.media.MediaDescription.Builder()
                                .setMediaId(item.key)
                                .setTitle(item.title ?: app.getString(R.string.untitled))
                                .setSubtitle(item.artist)
                                .build(),
                            i.toLong(),
                        )
                    },
                )
            }
        }
    }

    private data class Quad(val s: TvScreen, val np: NowPlayingUi, val p: ProgressAnchor?, val base: String?)

    private fun update(screen: TvScreen, np: NowPlayingUi, anchor: ProgressAnchor?, base: String?) {
        last = Triple(screen, np, anchor)
        if (screen != TvScreen.NowPlaying) {
            session.isActive = false
            nm.notify(RemoteKeepAliveService.NOTIFICATION_ID, RemoteKeepAliveService.basicNotification(app))
            return
        }
        // Carátula: se carga una vez por pista (URL pública o servidor del host si hay red).
        val key = np.trackKey + "|" + (np.art?.id ?: np.thumbUrl.orEmpty())
        if (key != artFor) {
            artFor = key
            art = null
            artJob?.cancel()
            val model = ArtUrls.tvModel(np.art, base)?.data ?: np.thumbUrl
            if (model != null) {
                artJob = scope.launch {
                    val bmp = runCatching {
                        SingletonImageLoader.get(app).execute(
                            ImageRequest.Builder(app).data(model).size(ART_PX).allowHardware(false).build(),
                        ).image?.toBitmap()
                    }.getOrNull()
                    if (artFor == key && bmp != null) {
                        art = bmp
                        last?.let { (s, n, p) -> publish(s, n, p) }
                    }
                }
            }
        }
        publish(screen, np, anchor)
    }

    private fun publish(screen: TvScreen, np: NowPlayingUi, anchor: ProgressAnchor?) {
        if (screen != TvScreen.NowPlaying) return
        val meta = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, np.title ?: app.getString(R.string.untitled))
            .putString(MediaMetadata.METADATA_KEY_ARTIST, np.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, np.album)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, np.durationMs ?: -1L)
        art?.let { meta.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
        session.setMetadata(meta.build())

        val now = SystemClock.elapsedRealtime()
        val state = when (np.status) {
            PlaybackStatus.PLAYING -> PlaybackState.STATE_PLAYING
            PlaybackStatus.BUFFERING -> PlaybackState.STATE_BUFFERING
            PlaybackStatus.PAUSED -> PlaybackState.STATE_PAUSED
            PlaybackStatus.STOPPED, PlaybackStatus.NONE -> PlaybackState.STATE_STOPPED
        }
        val playing = state == PlaybackState.STATE_PLAYING
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM or PlaybackState.ACTION_STOP,
                )
                .setState(state, anchor?.positionAt(now) ?: PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (playing) anchor?.speed ?: 1f else 0f, now)
                .build(),
        )
        session.isActive = true
        nm.notify(RemoteKeepAliveService.NOTIFICATION_ID, mediaNotification(np, playing))
    }

    private fun mediaNotification(np: NowPlayingUi, playing: Boolean): Notification {
        fun action(icon: Int, title: Int, cmd: String) = Notification.Action.Builder(
            Icon.createWithResource(app, icon), app.getString(title), RemoteKeepAliveService.commandIntent(app, cmd),
        ).build()
        return Notification.Builder(app, RemoteKeepAliveService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(np.title ?: app.getString(R.string.untitled))
            .setContentText(np.artist)
            .setSubText(np.album)
            .setLargeIcon(art)
            .setContentIntent(openAppIntent(app))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(action(android.R.drawable.ic_media_previous, R.string.action_previous, RemoteKeepAliveService.CMD_PREVIOUS))
            .addAction(
                if (playing) action(android.R.drawable.ic_media_pause, R.string.action_pause, RemoteKeepAliveService.CMD_TOGGLE)
                else action(android.R.drawable.ic_media_play, R.string.action_play, RemoteKeepAliveService.CMD_TOGGLE),
            )
            .addAction(action(android.R.drawable.ic_media_next, R.string.action_next, RemoteKeepAliveService.CMD_NEXT))
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(app, android.R.drawable.ic_menu_close_clear_cancel),
                    app.getString(R.string.remote_service_disconnect),
                    RemoteKeepAliveService.disconnectIntent(app),
                ).build(),
            )
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    /** Desde los botones de la notificación (versiones de Android que los usan en vez de la sesión). */
    fun command(cmd: String) {
        when (cmd) {
            RemoteKeepAliveService.CMD_PREVIOUS -> vm.previous()
            RemoteKeepAliveService.CMD_TOGGLE -> vm.togglePlayPause()
            RemoteKeepAliveService.CMD_NEXT -> vm.next()
        }
    }

    private fun release() {
        scope.cancel()
        session.isActive = false
        session.release()
    }

    companion object {
        private const val ART_PX = 512

        @Volatile var current: RemoteMedia? = null
            private set

        /** Una sola sesión por proceso; si la actividad se recrea con el mismo ViewModel, se reutiliza. */
        fun attach(context: Context, vm: TvViewModel) {
            if (current?.vm === vm) return
            current?.release()
            current = RemoteMedia(context.applicationContext, vm)
        }

        fun release() {
            current?.release()
            current = null
        }

        fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
