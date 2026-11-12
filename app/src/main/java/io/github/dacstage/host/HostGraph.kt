package io.github.dacstage.host

import android.content.Context
import android.util.Log
import io.github.dacstage.artwork.HostArtworkResolver
import io.github.dacstage.dac.DacVolumeManager
import io.github.dacstage.dac.UsbAudioDacController
import io.github.dacstage.enrich.EnrichmentService
import io.github.dacstage.model.PlayerId
import io.github.dacstage.network.HostServer
import io.github.dacstage.players.AndroidSessionMonitor
import io.github.dacstage.players.uapp.LocalAdbUappFileAccess
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Grafo de dependencias del host (un solo proceso). Lo arranca/para [HostService]; la UI del host lee
 * las mismas instancias. Se crea perezosamente: en la TV nunca se instancia.
 */
class HostGraph private constructor(context: Context) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default +
            CoroutineExceptionHandler { _, t -> Log.e(TAG, "error no capturado en el host", t) },
    )

    val settings = HostSettings(app)
    val uappFiles = LocalAdbUappFileAccess(app, settings, scope)
    val sessions = AndroidSessionMonitor(app, uappFiles, scope)
    private val dacController = UsbAudioDacController(
        app,
        // UAPP usa su propio driver USB: si el de Android quedó enganchado, se le quita para devolverle el DAC.
        keepKernelDriverDetached = { sessions.activePlayer.value?.id == PlayerId.UAPP },
    )
    val dac = DacVolumeManager(
        app, dacController, settings, sessions.activePlayer, scope,
        otherAppSounding = { sessions.otherSoundingPackage.value != null },
    )
    val artwork = HostArtworkResolver(app, scope)
    val core = HostCore(
        settings, sessions, dac, artwork, scope,
        dacFormats = dacController.info
            .map { it?.streamFormats.orEmpty() }
            .stateIn(scope, SharingStarted.Eagerly, emptyList()),
        info = EnrichmentService(app),
    )
    val server = HostServer(app, core, artwork, settings, scope)
    /** Controles remotos por Bluetooth: mismo servidor y protocolo, otro canal. */
    internal val bluetooth = io.github.dacstage.network.BluetoothHostServer(app, onLink = server::acceptLink)
    private var bluetoothJob: kotlinx.coroutines.Job? = null

    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        sessions.start()
        server.start()
        bluetoothJob = scope.launch {
            settings.bluetoothRemote.collect { on -> if (on) bluetooth.start(scope) else bluetooth.stop() }
        }
        scope.launch { dac.refresh() }
    }

    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        bluetoothJob?.cancel()
        bluetoothJob = null
        bluetooth.stop()
        server.stop()
        sessions.stop()
    }

    companion object {
        private const val TAG = "DacStageHost"

        @Volatile private var instance: HostGraph? = null

        fun get(context: Context): HostGraph =
            instance ?: synchronized(this) { instance ?: HostGraph(context).also { instance = it } }
    }
}
