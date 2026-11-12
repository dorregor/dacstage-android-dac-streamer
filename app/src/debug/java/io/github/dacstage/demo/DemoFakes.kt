package io.github.dacstage.demo

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import io.github.dacstage.dac.DacVolumeControl
import io.github.dacstage.dac.PendingDacConfirmation
import io.github.dacstage.host.HostController
import io.github.dacstage.host.HostSettings
import io.github.dacstage.model.Command
import io.github.dacstage.model.CommandOrigin
import io.github.dacstage.model.CommandResult
import io.github.dacstage.model.DacState
import io.github.dacstage.model.HostState
import io.github.dacstage.model.PlayerId
import io.github.dacstage.network.CommandAck
import io.github.dacstage.network.HostLink
import io.github.dacstage.network.HostServerApi
import io.github.dacstage.network.DisconnectReason
import io.github.dacstage.network.LinkStatus
import io.github.dacstage.network.PairedTv
import io.github.dacstage.network.PairingRequest
import io.github.dacstage.players.uapp.UappAccessStatus
import io.github.dacstage.players.uapp.UappFileAccess
import io.github.dacstage.ui.common.DemoFactory
import io.github.dacstage.ui.common.DemoHooks
import io.github.dacstage.ui.mobile.HostUiDeps
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** Registra los fakes al arrancar el proceso (solo debug, no exportado). */
class DemoInstaller : ContentProvider() {
    override fun onCreate(): Boolean {
        DemoHooks.factory = DemoFactoryImpl
        return true
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
}

internal object DemoFactoryImpl : DemoFactory {
    override fun tvLink(context: Context, scenario: String?): HostLink = FakeHostLink(DemoSimulator(context, scenario ?: "play"))

    override fun hostDeps(context: Context, scenario: String?): HostUiDeps {
        val sim = DemoSimulator(context, scenario ?: "play").also { it.start() }
        return HostUiDeps(
            controller = FakeHostController(sim),
            dacVolume = FakeDacVolume(sim),
            server = FakeHostServer(),
            settings = HostSettings(context),
            uappFiles = FakeUappFiles(),
            hasSessionAccess = MutableStateFlow(scenario != "noaccess"),
            artUrlFor = { it.url },
        )
    }
}

/** Lado TV: descubre, conecta (o se queda en el escenario pedido) y reenvía comandos al simulador. */
private class FakeHostLink(private val sim: DemoSimulator) : HostLink {
    private val _status = MutableStateFlow<LinkStatus>(LinkStatus.Discovering)
    override val status: StateFlow<LinkStatus> = _status
    private val _host = MutableStateFlow<HostState?>(null)
    override val hostState: StateFlow<HostState?> = _host
    private val _acks = MutableSharedFlow<CommandAck>(extraBufferCapacity = 16)
    override val acks: SharedFlow<CommandAck> = _acks
    override val artBaseUrl: StateFlow<String?> = MutableStateFlow(null)
    private var job: Job? = null
    private var simStarted = false

    override fun start() {
        if (job?.isActive == true) return
        job = sim.scope.launch {
            _status.value = LinkStatus.Discovering
            when (sim.scenario) {
                "search" -> return@launch
                "pairing" -> {
                    delay(800); _status.value = LinkStatus.Connecting("Tablet del salón")
                    delay(600); _status.value = LinkStatus.PairingRequired("Tablet del salón", "482913")
                    return@launch
                }
                "disconnected" -> {
                    delay(600); _status.value = LinkStatus.Disconnected(DisconnectReason.ConnectFailed("Tablet del salón"), 5_000)
                    return@launch
                }
            }
            delay(900); _status.value = LinkStatus.Connecting("Tablet del salón")
            if (!simStarted) {
                simStarted = true; sim.start()
            }
            delay(700); _status.value = LinkStatus.Connected("Tablet del salón", "192.168.1.50")
            sim.state.collect { _host.value = it }
        }
    }

    override fun stop() {
        job?.cancel(); job = null
    }

    override fun send(command: Command): String {
        val id = UUID.randomUUID().toString()
        sim.scope.launch {
            val r = if (_status.value is LinkStatus.Connected) sim.execute(command)
            else CommandResult.fail(io.github.dacstage.model.CommandError.INTERNAL, "sin conexión")
            _acks.emit(CommandAck(id, command, r))
        }
        return id
    }

    override fun connectManual(host: String, port: Int) = Unit
    override fun forgetPairing() = Unit
}

private class FakeHostController(private val sim: DemoSimulator) : HostController {
    override val state: StateFlow<HostState> = sim.state
    override suspend fun execute(command: Command, origin: CommandOrigin): CommandResult = sim.execute(command)
}

private class FakeDacVolume(private val sim: DemoSimulator) : DacVolumeControl {
    override val state: StateFlow<DacState> = sim.state.map { it.dac }.stateIn(sim.scope, SharingStarted.Eagerly, sim.state.value.dac)
    private val _pending = MutableStateFlow<PendingDacConfirmation?>(null)
    override val pendingConfirmation: StateFlow<PendingDacConfirmation?> = _pending
    override suspend fun refresh() = CommandResult.OK
    override suspend fun step(deltaDb: Int, repeatCount: Int, origin: CommandOrigin) = sim.execute(Command.VolumeStep(deltaDb, repeatCount))
    override suspend fun toggleMute(origin: CommandOrigin) = sim.execute(Command.ToggleMute)
    override suspend fun setVolumeDb(db: Float) = sim.setVolumeDb(db)
    override fun setCapDb(capDb: Float) = sim.setCap(capDb)
    override fun grantForceConsent(player: PlayerId) { _pending.value = null }
    override fun revokeForceConsent(player: PlayerId) = Unit
    override suspend fun pauseAndApplyPending(): CommandResult { _pending.value = null; return CommandResult.OK }
    override fun dismissPending() { _pending.value = null }
}

private class FakeHostServer : HostServerApi {
    override val pairingRequests = MutableStateFlow(listOf(PairingRequest("r1", "tv-1", "TCL Salón", "192.168.1.84")))
    override val pairedTvs = MutableStateFlow(listOf(PairedTv("tv-0", "TV Dormitorio", System.currentTimeMillis() - 86_400_000L * 3)))
    override val connectedClients = MutableStateFlow(listOf("TV Dormitorio"))
    override val listeningPort = MutableStateFlow<Int?>(47820)
    override fun start() = Unit
    override fun stop() = Unit
    override fun confirmPairing(requestId: String, code: String): Boolean {
        if (code != "482913") return false
        pairingRequests.update { l -> l.filterNot { it.requestId == requestId } }
        pairedTvs.update { it + PairedTv("tv-1", "TCL Salón", System.currentTimeMillis()) }
        return true
    }
    override fun rejectPairing(requestId: String) = pairingRequests.update { l -> l.filterNot { it.requestId == requestId } }
    override fun unpair(deviceId: String) = pairedTvs.update { l -> l.filterNot { it.deviceId == deviceId } }
}

private class FakeUappFiles : UappFileAccess {
    override val status = MutableStateFlow<UappAccessStatus>(UappAccessStatus.NotPaired)
    override suspend fun pair(code: String, pairingPort: Int?): Result<Unit> {
        delay(600)
        return if (code == "123456") Result.success(Unit) else Result.failure(IllegalStateException("Código incorrecto"))
    }
    override suspend fun connect(): Result<Unit> {
        status.value = UappAccessStatus.Connecting; delay(500); status.value = UappAccessStatus.Connected
        return Result.success(Unit)
    }
    override fun forget() { status.value = UappAccessStatus.NotPaired }
}
