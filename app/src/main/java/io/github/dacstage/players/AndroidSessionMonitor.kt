package io.github.dacstage.players

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import io.github.dacstage.host.MediaListenerService
import io.github.dacstage.model.PlayerId
import io.github.dacstage.players.hiby.HibyAdapter
import io.github.dacstage.players.uapp.UappAdapter
import io.github.dacstage.players.uapp.UappFileAccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sesiones activas vía MediaListenerService. Elige la sesión que reproduce (si varias, la que empezó a
 * reproducir más tarde); si ninguna reproduce, la que reprodujo más recientemente (o, sin historial,
 * HiBy/UAPP antes que otra app, y si no la primera que da el sistema). HiBy y UAPP tienen adaptador propio;
 * el resto de apps usa [SessionAdapter] con solo lo que da su MediaSession. Todo corre en el hilo principal.
 */
class AndroidSessionMonitor(
    context: Context,
    private val uappFileAccess: UappFileAccess,
    private val scope: CoroutineScope,
) : SessionMonitor {

    private val appContext = context.applicationContext
    private val component = ComponentName(appContext, MediaListenerService::class.java)
    private val sessionManager = appContext.getSystemService(MediaSessionManager::class.java)
    private val notificationManager = appContext.getSystemService(NotificationManager::class.java)
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private val _activePlayer = MutableStateFlow<PlayerAdapter?>(null)
    override val activePlayer: StateFlow<PlayerAdapter?> = _activePlayer.asStateFlow()

    private val _unsupported = MutableStateFlow<String?>(null)
    override val unsupportedActivePackage: StateFlow<String?> = _unsupported.asStateFlow()

    private val _otherSounding = MutableStateFlow<String?>(null)
    override val otherSoundingPackage: StateFlow<String?> = _otherSounding.asStateFlow()

    /** Nombre visible por paquete (PackageManager; el paquete si no es visible). */
    private val labels = HashMap<String, String>()

    private val _hasAccess = MutableStateFlow(false)
    override val hasSessionAccess: StateFlow<Boolean> = _hasAccess.asStateFlow()

    private var started = false
    private var listenerRegistered = false
    private var activeToken: Any? = null

    /** Controladores observados (por token de sesión) para enterarnos de play/pausa. */
    private val watched = HashMap<Any, Watched>()
    /** Orden que da el sistema (más reciente primero). */
    private var order: List<Any> = emptyList()
    /** Último instante (elapsedRealtime) en que cada paquete pasó a reproducir. */
    private val lastPlayStart = HashMap<String, Long>()

    private inner class Watched(val controller: MediaController) {
        var wasPlaying = isPlaying(controller.playbackState)
        val callback = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                val playing = isPlaying(state)
                if (playing && !wasPlaying) lastPlayStart[controller.packageName] = SystemClock.elapsedRealtime()
                if (playing != wasPlaying) {
                    wasPlaying = playing
                    evaluate()
                }
            }

            override fun onSessionDestroyed() = refresh()
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
        onSessions(controllers.orEmpty())
    }

    private val accessRetry = object : Runnable {
        override fun run() {
            if (!started || listenerRegistered) return
            if (accessGranted()) refresh() else handler.postDelayed(this, ACCESS_RETRY_MS)
        }
    }

    override fun start() = runOnMain {
        if (started) return@runOnMain
        started = true
        refresh()
    }

    override fun stop() = runOnMain {
        if (!started) return@runOnMain
        started = false
        handler.removeCallbacks(accessRetry)
        if (listenerRegistered) {
            runCatching { sessionManager.removeOnActiveSessionsChangedListener(sessionsListener) }
            listenerRegistered = false
        }
        clearWatched()
        setActive(null, null)
        _otherSounding.value = null
    }

    /** Relee las sesiones; registra el listener si aún no se pudo (acceso a notificaciones). */
    private fun refresh() {
        if (!started) return
        try {
            if (!listenerRegistered) {
                sessionManager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
                listenerRegistered = true
            }
            val controllers = sessionManager.getActiveSessions(component)
            _hasAccess.value = true
            onSessions(controllers)
        } catch (e: SecurityException) {
            Log.i(TAG, "Sin acceso a notificaciones: ${e.message}")
            _hasAccess.value = false
            listenerRegistered = false
            clearWatched()
            setActive(null, null)
            _otherSounding.value = null
            handler.removeCallbacks(accessRetry)
            handler.postDelayed(accessRetry, ACCESS_RETRY_MS)
        }
    }

    private fun accessGranted(): Boolean =
        runCatching { notificationManager.isNotificationListenerAccessGranted(component) }.getOrDefault(false)

    private fun onSessions(controllers: List<MediaController>) {
        if (!started) return
        val own = appContext.packageName
        val list = controllers.filter { it.packageName != own }
        order = list.map { it.sessionToken }
        val tokens = order.toSet()
        // Quita los que desaparecieron.
        val it = watched.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key !in tokens) {
                runCatching { e.value.controller.unregisterCallback(e.value.callback) }
                it.remove()
            }
        }
        for (c in list) {
            if (c.sessionToken !in watched) {
                val w = Watched(c)
                if (w.wasPlaying) lastPlayStart.putIfAbsent(c.packageName, SystemClock.elapsedRealtime())
                c.registerCallback(w.callback, handler)
                watched[c.sessionToken] = w
            }
        }
        evaluate()
    }

    private fun evaluate() {
        if (!started) return
        val controllers = order.mapNotNull { watched[it]?.controller }
        val playing = controllers.filter { isPlaying(it.playbackState) }
        val chosen = when {
            playing.isNotEmpty() -> pick(playing)
            controllers.isEmpty() -> null
            else -> pick(controllers.filter { lastPlayStart.containsKey(it.packageName) })
                ?: controllers.firstOrNull { it.sessionToken == activeToken }
                ?: pick(controllers.filter { PlayerId.fromPackage(it.packageName) != null })
                ?: pick(controllers)
        }
        setActive(chosen?.sessionToken, chosen)
        _otherSounding.value = playing.firstOrNull { it.packageName != chosen?.packageName }?.packageName
    }

    /**
     * La app que empezó a sonar más tarde y, dentro de ella, la sesión actual si sigue ahí; si no, una con
     * metadatos. Una app puede tener varias sesiones (UAPP publica además "MediaSessionHelper", sin
     * metadatos): cambiar de sesión dentro de la misma app al pausar cerraría el adaptador y cancelaría lo
     * que estuviera haciendo (p. ej. un salto en la cola, que pausa primero).
     */
    private fun pick(candidates: List<MediaController>): MediaController? {
        if (candidates.isEmpty()) return null
        val pkg = candidates.maxBy { lastPlayStart[it.packageName] ?: Long.MIN_VALUE }.packageName
        val same = candidates.filter { it.packageName == pkg }
        return same.firstOrNull { it.sessionToken == activeToken }
            ?: same.firstOrNull { it.metadata != null }
            ?: same.first()
    }

    private fun setActive(token: Any?, controller: MediaController?) {
        if (token == activeToken && (token == null || _activePlayer.value != null)) return
        _activePlayer.value?.close()
        activeToken = token
        Log.i(TAG, "sesión activa: ${controller?.let { "${it.packageName} (${it.tag})" } ?: "ninguna"}")
        _activePlayer.value = controller?.let { createAdapter(it) }
    }

    private fun createAdapter(controller: MediaController): PlayerAdapter =
        when (PlayerId.fromPackage(controller.packageName)) {
            PlayerId.HIBY -> HibyAdapter(controller, handler, audioManager)
            PlayerId.UAPP -> UappAdapter(controller, uappFileAccess, scope, handler)
            PlayerId.GENERIC, null -> SessionAdapter(
                controller = controller,
                id = PlayerId.GENERIC,
                displayName = labelOf(controller.packageName),
                keyPrefix = GENERIC_KEY_PREFIX,
                handler = handler,
                audioManager = audioManager,
            )
        }

    private fun labelOf(pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            val pm = appContext.packageManager
            @Suppress("DEPRECATION") val info = pm.getApplicationInfo(pkg, 0)
            pm.getApplicationLabel(info).toString()
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: pkg
    }

    private fun clearWatched() {
        for (w in watched.values) runCatching { w.controller.unregisterCallback(w.callback) }
        watched.clear()
        order = emptyList()
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    private fun isPlaying(state: PlaybackState?): Boolean = state.toStatus().isActive()

    companion object {
        private const val TAG = "SessionMonitor"
        private const val ACCESS_RETRY_MS = 3_000L
        const val GENERIC_KEY_PREFIX = "app:"
    }
}
