package io.github.dacstage.network

import android.content.Context
import io.github.dacstage.model.Command
import io.github.dacstage.model.CommandError
import io.github.dacstage.model.CommandResult
import io.github.dacstage.model.HostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/** Parámetros del cliente de TV (ajustables en tests). */
data class ClientConfig(
    val connectTimeoutMs: Int = 5_000,
    val pingIntervalMs: Long = 15_000,
    val idleTimeoutMs: Int = 45_000,
    /** Espera a NSD antes de probar la última dirección conocida. */
    val discoveryGraceMs: Long = 5_000,
    /** Espera antes de conectar a un host sin emparejar (por si aparece el emparejado). */
    val unpairedGraceMs: Long = 8_000,
    val minBackoffMs: Long = 500,
    val maxBackoffMs: Long = 30_000,
)

/**
 * Lado TV de [HostLink]: un único socket con el host. Descubre por NSD (con conexión manual y última
 * dirección conocida como respaldo), reconecta con backoff exponencial con jitter (0,5 s → 30 s) y al
 * cambiar la red. Sin servicios en segundo plano: la Activity llama a [start]/[stop].
 *
 * [artBaseUrl] = `http://<ip>:<artPort>/t/<artToken>`; la URL de una carátula es
 * `artBaseUrl + ArtRef.hostPath` (ver [artUrlFor]). El token va en la ruta para que la concatenación
 * directa funcione; el host también acepta `/art/<id>?t=<token>`.
 *
 * Sin cifrado de transporte (ver Protocol.kt).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TvHostClient internal constructor(
    parentScope: CoroutineScope,
    private val store: TvStore,
    private val discovery: HostDiscovery?,
    private val networkMonitor: NetworkMonitor?,
    private val config: ClientConfig = ClientConfig(),
    /** Búsqueda de hosts por Bluetooth (control remoto en un teléfono); null = solo red. */
    private val bluetooth: BluetoothHostFinder? = null,
) : HostLink {

    constructor(context: Context, scope: CoroutineScope, useBluetooth: Boolean = false) : this(
        parentScope = scope,
        store = SharedPrefsTvStore(context),
        discovery = NsdHostDiscovery(context),
        networkMonitor = AndroidNetworkMonitor(context),
        bluetooth = if (useBluetooth) BluetoothHostFinder(context) else null,
    )

    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO,
    )

    private val _status = MutableStateFlow<LinkStatus>(LinkStatus.Disconnected(null, null))
    override val status: StateFlow<LinkStatus> = _status.asStateFlow()
    private val _hostState = MutableStateFlow<HostState?>(null)
    override val hostState: StateFlow<HostState?> = _hostState.asStateFlow()
    private val _acks = MutableSharedFlow<CommandAck>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val acks: SharedFlow<CommandAck> = _acks.asSharedFlow()
    private val _artBaseUrl = MutableStateFlow<String?>(null)
    override val artBaseUrl: StateFlow<String?> = _artBaseUrl.asStateFlow()

    private enum class Source { MANUAL, NSD, LAST_KNOWN, BLUETOOTH }
    private data class Target(
        val host: String,
        val port: Int,
        val label: String,
        val source: Source,
        val hostId: String? = null,
        val bt: BluetoothHost? = null,
    )

    override val bluetoothReady: StateFlow<Boolean>? = bluetooth?.ready

    override val discoveredHosts: StateFlow<List<DiscoveredHost>> = discovery?.hosts ?: MutableStateFlow(emptyList())

    private val manualTarget = MutableStateFlow<Target?>(null)
    private val kick = Channel<Unit>(Channel.CONFLATED)
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<String, Command>()
    @Volatile private var session: TvSession? = null
    @Volatile private var running = false
    @Volatile private var currentHostId: String? = null
    private var runJob: Job? = null

    private val sessionListener = object : TvSession.Listener {
        override fun onStatus(status: LinkStatus) = setStatus(status)

        override fun onAuthenticated(hostId: String, hostName: String, address: String, artBaseUrl: String?) {
            if (currentHostId != hostId) _hostState.value = null
            currentHostId = hostId
            _artBaseUrl.value = artBaseUrl
            bluetooth?.setSearching(false)
        }

        override fun onState(state: HostState) {
            _hostState.value = state
        }

        override fun onCmdResult(id: String, result: CommandResult) {
            pending.remove(id)?.let { _acks.tryEmit(CommandAck(id, it, result)) }
        }
    }

    @Synchronized
    override fun start() {
        if (runJob?.isActive == true) return
        running = true
        discovery?.start(scope)
        bluetooth?.start(scope)
        networkMonitor?.start { onNetworkChanged() }
        runJob = scope.launch { connectLoop() }
    }

    @Synchronized
    override fun stop() {
        running = false
        runJob?.cancel()
        runJob = null
        session?.close()
        session = null
        discovery?.stop()
        bluetooth?.stop()
        networkMonitor?.stop()
        failPending()
        _status.value = LinkStatus.Disconnected(null, null)
    }

    override fun send(command: Command): String {
        val id = "c${ids.incrementAndGet()}"
        val s = session
        if (s != null && s.authenticated) {
            pending[id] = command
            if (s.send(WireMessage.Cmd(id, command))) return id
            pending.remove(id)
        }
        _acks.tryEmit(CommandAck(id, command, CommandResult.fail(CommandError.INTERNAL, NOT_CONNECTED)))
        return id
    }

    override fun connectManual(host: String, port: Int) {
        val h = host.trim()
        if (h.isEmpty() || port !in 1..65535) return
        manualTarget.value = Target(h, port, "$h:$port", Source.MANUAL)
        session?.close()
        kick.trySend(Unit)
    }

    override fun connectTo(host: DiscoveredHost) {
        // Elección explícita: pasa a ser el host preferido (también si aún no está emparejado).
        if (host.hostId != null) {
            manualTarget.value = null
            store.lastHostId = host.hostId
        } else {
            manualTarget.value = Target(host.address, host.port, host.serviceName, Source.MANUAL)
        }
        session?.close()
        kick.trySend(Unit)
    }

    override fun forgetPairing() {
        store.clearAll()
        currentHostId = null
        _hostState.value = null
        _artBaseUrl.value = null
        session?.close()
        kick.trySend(Unit)
    }

    private fun onNetworkChanged() {
        // El socket puede haber quedado colgado en la red anterior: reconectar ya (un canal Bluetooth no
        // depende de la red y se mantiene).
        session?.takeIf { it.isNetwork }?.close()
        discovery?.refresh()
        kick.trySend(Unit)
    }

    private fun setStatus(s: LinkStatus) {
        if (running) _status.value = s
    }

    private fun failPending() {
        for (id in pending.keys.toList()) {
            val cmd = pending.remove(id) ?: continue
            _acks.tryEmit(CommandAck(id, cmd, CommandResult.fail(CommandError.INTERNAL, NOT_CONNECTED)))
        }
    }

    private suspend fun connectLoop() {
        var attempt = 0
        var lastKnownFailed = false
        var preferBluetooth = false
        while (currentCoroutineContext().isActive) {
            val target = pickTarget(skipLastKnown = lastKnownFailed, preferBluetooth = preferBluetooth)
            setStatus(LinkStatus.Connecting(target.label))
            val end = runTarget(target)
            failPending()
            lastKnownFailed = target.source == Source.LAST_KNOWN && !end.authenticated
            // Si por la red no se llega al host (p. ej. red que aísla a los equipos Wi‑Fi), la próxima vez se
            // prueba antes por Bluetooth; si falla Bluetooth, se vuelve a dar prioridad a la red.
            if (!end.authenticated) {
                val unreachable = end.reason is DisconnectReason.ConnectFailed || end.reason == DisconnectReason.NoResponse
                preferBluetooth = when {
                    target.source == Source.BLUETOOTH -> false
                    unreachable -> true
                    else -> preferBluetooth
                }
            }
            if (end.authenticated) attempt = 0
            if (target.source == Source.NSD && !end.authenticated) discovery?.refresh()
            val wait = when {
                end.retrySoon -> 300L
                end.slowDown -> config.maxBackoffMs
                else -> backoffMs(attempt++)
            }
            setStatus(LinkStatus.Disconnected(end.reason, wait))
            if (withTimeoutOrNull(wait) { kick.receive() } != null) attempt = 0
        }
    }

    private suspend fun pickTarget(skipLastKnown: Boolean, preferBluetooth: Boolean): Target {
        manualTarget.value?.let { return it }
        setStatus(LinkStatus.Discovering)
        if (preferBluetooth) bluetooth?.hosts?.value?.maxByOrNull { it.seenAtMs }?.let { return bluetoothTarget(it) }
        // Primero la red (más rápida y con carátulas); Bluetooth solo si por la red no aparece nada.
        val network: Flow<Target> = merge(manualTarget.filterNotNull(), discoveredTargets())
        withTimeoutOrNull(config.discoveryGraceMs) { network.first() }?.let { return it }
        if (!skipLastKnown) lastKnownTarget()?.let { return it }
        return merge(network, bluetoothTargets()).first()
    }

    private fun bluetoothTargets(): Flow<Target> {
        val b = bluetooth ?: return emptyFlow()
        return b.hosts.mapNotNull { list -> list.maxByOrNull { it.seenAtMs }?.let(::bluetoothTarget) }
    }

    private fun bluetoothTarget(h: BluetoothHost) = Target(
        host = h.device.address, port = 0, label = "${h.name} · Bluetooth", source = Source.BLUETOOTH, bt = h,
    )

    private fun discoveredTargets(): Flow<Target> {
        val d = discovery ?: return emptyFlow()
        // Primero el host elegido/último y luego cualquiera ya emparejado. Uno sin emparejar solo tras una
        // espera (si en ese rato aparece el emparejado, gana): antes, si otro host respondía antes por NSD,
        // la TV se quedaba pidiendo código a ese y no volvía al de siempre.
        return d.hosts.transformLatest { list ->
            val lastId = store.lastHostId
            val known = list.firstOrNull { it.hostId != null && it.hostId == lastId }
                ?: list.firstOrNull { it.hostId != null && store.token(it.hostId) != null }
            val h = known ?: list.firstOrNull()?.also { delay(config.unpairedGraceMs) }
            h?.let { emit(Target(it.address, it.port, it.serviceName, Source.NSD, it.hostId)) }
        }
    }

    private fun lastKnownTarget(): Target? {
        val s = store.lastAddress ?: return null
        val i = s.lastIndexOf(':')
        if (i <= 0) return null
        val port = s.substring(i + 1).toIntOrNull() ?: return null
        val host = s.substring(0, i)
        return Target(host, port, host, Source.LAST_KNOWN)
    }

    private suspend fun runTarget(t: Target): TvSession.End = try {
        runTargetInner(t)
    } finally {
        bluetooth?.setSearching(true)
    }

    private suspend fun runTargetInner(t: Target): TvSession.End {
        val link: WireLink = if (t.bt != null) {
            val b = bluetooth ?: return TvSession.End(DisconnectReason.ConnectFailed(t.label), authenticated = false)
            try {
                withContext(Dispatchers.IO) { b.connect(t.bt, BT_CONNECT_TIMEOUT_MS) }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                return TvSession.End(DisconnectReason.ConnectFailed(t.label), authenticated = false)
            }
        } else {
            val socket = Socket()
            try {
                withContext(Dispatchers.IO) { socket.connect(InetSocketAddress(t.host, t.port), config.connectTimeoutMs) }
            } catch (e: IOException) {
                closeQuietly(socket)
                return TvSession.End(DisconnectReason.ConnectFailed(t.label), authenticated = false)
            } catch (e: IllegalArgumentException) {
                closeQuietly(socket)
                return TvSession.End(DisconnectReason.InvalidAddress(t.label), authenticated = false)
            }
            SocketLink(socket)
        }
        val s = TvSession(link, store, t.label, sessionListener, config.pingIntervalMs, config.idleTimeoutMs)
        session = s
        if (!running) s.close()
        // Conectado a un host sin emparejar que no se eligió: si aparece uno ya emparejado, se vuelve a ese.
        val watcher = discovery?.takeIf { t.source == Source.NSD && !isPaired(t.hostId) && t.hostId != store.lastHostId }?.let { d ->
            scope.launch {
                d.hosts.first { list -> list.any { it.hostId != null && it.hostId != t.hostId && isPaired(it.hostId) } }
                s.close()
            }
        }
        return try {
            s.run()
        } finally {
            watcher?.cancel()
            if (session === s) session = null
            s.close()
        }
    }

    private fun isPaired(hostId: String?): Boolean = hostId != null && store.token(hostId) != null

    private fun backoffMs(attempt: Int): Long {
        val base = minOf(config.maxBackoffMs, config.minBackoffMs shl minOf(attempt, 16))
        val jittered = (base * Random.nextDouble(0.8, 1.2)).toLong()
        return jittered.coerceIn(config.minBackoffMs, config.maxBackoffMs)
    }

    companion object {
        /** CommandResult.message de los acks generados localmente sin conexión. */
        const val NOT_CONNECTED = "not_connected"
        private const val BT_CONNECT_TIMEOUT_MS = 12_000L

        /** URL completa de una carátula servida por el host. */
        fun artUrlFor(artBaseUrl: String, hostPath: String): String = artBaseUrl.trimEnd('/') + hostPath
    }
}
