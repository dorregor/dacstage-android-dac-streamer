package io.github.dacstage.network

import android.content.Context
import android.util.Log
import io.github.dacstage.artwork.ArtworkResolver
import io.github.dacstage.host.HostController
import io.github.dacstage.host.HostSettings
import io.github.dacstage.model.Command
import io.github.dacstage.model.CommandError
import io.github.dacstage.model.CommandOrigin
import io.github.dacstage.model.CommandResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Parámetros del servidor (ajustables en tests). */
data class ServerConfig(
    val helloTimeoutMs: Int = 10_000,
    /** Cierre sin tráfico entrante (la TV hace ping cada 15 s). */
    val idleTimeoutMs: Int = 45_000,
    /** Coalescencia de parches de estado. */
    val throttleMs: Long = 50,
    val pairingTimeoutMs: Long = 5 * 60_000,
    val maxConnections: Int = 8,
    val maxInvalidLines: Int = 20,
    /** Códigos erróneos por petición de emparejado antes de rechazarla. */
    val maxCodeAttempts: Int = 5,
)

/**
 * Servidor del host: TCP con JSON por líneas en [DEFAULT_PORT] (o el siguiente libre), NSD
 * [NSD_SERVICE_TYPE] con nombre = hostName y atributo `id`, y [ArtHttpServer] en [ART_PORT].
 *
 * Varias TVs a la vez. Cada TV autenticada recibe un snapshot y luego parches coalescidos de
 * [HostController.state]; sus comandos se ejecutan con [CommandOrigin.TV]. Solo tras autenticar se
 * aceptan comandos. Fallos de autenticación/código limitados por IP.
 *
 * Seguridad: el emparejado y las reconexiones usan HMAC-SHA256 (el token no viaja en reconexiones), pero
 * NO hay cifrado de transporte: en la LAN se puede leer el tráfico y, durante el emparejado, el token.
 *
 * Uso (HostService): `HostServer(context, controller, artwork, settings, lifecycleScope).start()` y
 * `stop()` al destruir el servicio.
 */
class HostServer internal constructor(
    private val controller: HostController,
    private val artwork: ArtworkResolver,
    private val hostName: StateFlow<String>,
    private val store: PairingStore,
    parentScope: CoroutineScope,
    private val registrar: ServiceRegistrar? = null,
    private val tcpPorts: List<Int> = portCandidates(DEFAULT_PORT),
    private val artPorts: List<Int> = portCandidates(ART_PORT),
    private val config: ServerConfig = ServerConfig(),
    private val rateLimiter: FailureRateLimiter = FailureRateLimiter(),
) : HostServerApi {

    constructor(
        context: Context,
        controller: HostController,
        artwork: ArtworkResolver,
        settings: HostSettings,
        scope: CoroutineScope,
    ) : this(
        controller = controller,
        artwork = artwork,
        hostName = settings.hostName,
        store = SharedPrefsPairingStore(context),
        parentScope = scope,
        registrar = NsdServiceRegistrar(context),
    )

    private val scope = CoroutineScope(
        parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]) + Dispatchers.IO,
    )

    private val _pairingRequests = MutableStateFlow<List<PairingRequest>>(emptyList())
    override val pairingRequests: StateFlow<List<PairingRequest>> = _pairingRequests.asStateFlow()
    private val _pairedTvs = MutableStateFlow(store.all())
    override val pairedTvs: StateFlow<List<PairedTv>> = _pairedTvs.asStateFlow()
    private val _connected = MutableStateFlow<List<String>>(emptyList())
    override val connectedClients: StateFlow<List<String>> = _connected.asStateFlow()
    private val _port = MutableStateFlow<Int?>(null)
    override val listeningPort: StateFlow<Int?> = _port.asStateFlow()

    /** Puerto real del servidor de carátulas (0 si no arrancó). */
    @Volatile var artPort: Int = 0
        private set

    private val connections: MutableSet<Connection> = Collections.newSetFromMap(ConcurrentHashMap())
    private val pending = ConcurrentHashMap<String, PendingPair>()
    private var runJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var artServer: ArtHttpServer? = null

    private class PendingPair(
        val conn: Connection,
        val request: PairingRequest,
        val nonce: String,
        val proof: String,
        val attempts: AtomicInteger = AtomicInteger(0),
    )

    @Synchronized
    override fun start() {
        if (runJob?.isActive == true) return
        val ss = try {
            bindFirstFree(tcpPorts)
        } catch (e: IOException) {
            Log.e(TAG, "no se pudo abrir el puerto TCP", e)
            return
        }
        serverSocket = ss
        val art = ArtHttpServer(artwork, ::isValidArtToken, artPorts)
        artPort = try {
            art.start(scope)
        } catch (e: IOException) {
            Log.w(TAG, "servidor de carátulas sin puerto: $e")
            0
        }
        artServer = art
        _port.value = ss.localPort
        Log.i(TAG, "escuchando en ${ss.localPort} (carátulas $artPort)")
        runJob = scope.launch {
            launch {
                try {
                    awaitCancellation()
                } finally {
                    closeQuietly(ss)
                    art.stop()
                    registrar?.unregister()
                    connections.forEach { it.closeNow() }
                }
            }
            registrar?.let { r ->
                launch { hostName.collect { name -> r.register(name, ss.localPort, store.hostId) } }
            }
            acceptLoop(ss)
        }
    }

    @Synchronized
    override fun stop() {
        runJob?.cancel()
        runJob = null
        closeQuietly(serverSocket)
        serverSocket = null
        artServer?.stop()
        artServer = null
        registrar?.unregister()
        connections.forEach { it.closeNow() }
        pending.clear()
        _pairingRequests.value = emptyList()
        _port.value = null
    }

    override fun confirmPairing(requestId: String, code: String): Boolean {
        val p = pending[requestId] ?: return false
        val c = p.conn
        if (rateLimiter.isBlocked(c.address)) {
            dropPending(requestId, WireReasons.TOO_MANY_ATTEMPTS)
            return false
        }
        val normalized = code.filter { !it.isWhitespace() }
        val ok = normalized.length == 6 && normalized.all { it in '0'..'9' } &&
            AuthCrypto.constantTimeEquals(AuthCrypto.pairProof(normalized, p.nonce), p.proof)
        if (!ok) {
            rateLimiter.recordFailure(c.address)
            if (p.attempts.incrementAndGet() >= config.maxCodeAttempts) {
                dropPending(requestId, WireReasons.TOO_MANY_ATTEMPTS)
            }
            return false
        }
        if (pending.remove(requestId) == null) return false
        publishPending()
        val token = AuthCrypto.randomHex(32)
        store.put(PairedTv(p.request.deviceId, p.request.deviceName, System.currentTimeMillis()), token)
        _pairedTvs.value = store.all()
        c.pendingRequestId = null
        c.outbound.trySend(WireMessage.PairResult(ok = true, token = token))
        Log.i(TAG, "TV emparejada: ${p.request.deviceName}")
        return true
    }

    override fun rejectPairing(requestId: String) = dropPending(requestId, WireReasons.REJECTED)

    override fun unpair(deviceId: String) {
        store.remove(deviceId)
        _pairedTvs.value = store.all()
        connections.filter { it.deviceId == deviceId && it.authenticated }
            .forEach { it.closeWith(WireMessage.AuthFail(WireReasons.UNPAIRED)) }
    }

    private fun dropPending(requestId: String, reason: String) {
        val p = pending.remove(requestId) ?: return
        publishPending()
        p.conn.pendingRequestId = null
        p.conn.closeWith(WireMessage.PairResult(ok = false, reason = reason))
    }

    private fun isValidArtToken(t: String): Boolean =
        store.allTokens().any { AuthCrypto.constantTimeEquals(AuthCrypto.artTokenFor(it), t) }

    private fun publishPending() {
        _pairingRequests.value = pending.values.map { it.request }.sortedBy { it.requestId }
    }

    private fun publishConnected() {
        _connected.value = connections.filter { it.authenticated }.distinctBy { it.deviceId }.map { it.deviceName }
    }

    private suspend fun acceptLoop(ss: ServerSocket) = coroutineScope {
        while (isActive) {
            val s = try {
                ss.accept()
            } catch (e: IOException) {
                if (ss.isClosed) break else continue
            }
            if (!accept(SocketLink(s), this)) closeQuietly(s)
        }
    }

    /**
     * Conexión que llega por otro canal (Bluetooth): mismo protocolo y mismas reglas que por TCP.
     * false si el servidor no está en marcha o ya hay demasiadas conexiones (el que llama la cierra).
     */
    internal fun acceptLink(link: WireLink): Boolean {
        if (runJob?.isActive != true) return false
        return accept(link, scope)
    }

    private fun accept(link: WireLink, launchIn: CoroutineScope): Boolean {
        if (connections.size >= config.maxConnections) return false
        val c = Connection(link)
        connections += c
        launchIn.launch {
            try {
                c.run()
            } finally {
                connections -= c
                pending.entries.removeAll { it.value.conn === c }
                publishPending()
                publishConnected()
            }
        }
        return true
    }

    /** Una conexión (TV, control remoto o cliente de pruebas) por TCP o Bluetooth. Un lector + un escritor. */
    private inner class Connection(val link: WireLink) {
        val address: String = link.remoteAddress
        val outbound = Channel<WireMessage>(OUTBOUND_CAPACITY)
        @Volatile var deviceId: String? = null
        @Volatile var deviceName: String = ""
        @Volatile var authenticated = false
        @Volatile var pendingRequestId: String? = null
        @Volatile private var nonce: String? = null
        private var invalidLines = 0
        private val forceSnapshot = AtomicBoolean(false)
        private val kick = MutableStateFlow(0)
        private val cmdBucket = TokenBucket(capacity = 40.0, refillPerSec = 25.0)

        suspend fun run() = coroutineScope {
            val connScope = this
            launch { writeLoop() }
            try {
                link.setReadTimeout(config.helloTimeoutMs)
                val reader = BoundedLineReader(link.input, WireCodec.MAX_CLIENT_LINE_BYTES)
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isNotBlank()) handleLine(line, connScope)
                }
            } catch (e: IOException) {
                Log.d(TAG, "conexión $address: $e")
            } finally {
                outbound.close()
                link.close()
                connScope.coroutineContext.job.children.forEach { it.cancel() }
            }
        }

        private suspend fun writeLoop() {
            try {
                val out = BufferedOutputStream(link.output, 16 * 1024)
                for (msg in outbound) out.writeLine(WireCodec.encode(msg))
                // Canal cerrado tras closeWith(): entregar y cerrar (desbloquea al lector).
                link.shutdownOutput()
            } catch (e: IOException) {
                Log.d(TAG, "escritura $address: $e")
            } finally {
                link.close()
            }
        }

        fun closeWith(msg: WireMessage) {
            outbound.trySend(msg)
            outbound.close()
        }

        fun closeNow() {
            outbound.close()
            link.close()
        }

        private suspend fun send(msg: WireMessage) {
            try {
                outbound.send(msg)
            } catch (_: ClosedSendChannelException) {
            }
        }

        private suspend fun handleLine(line: String, connScope: CoroutineScope) {
            when (val d = WireCodec.decode(line)) {
                is Decoded.Invalid -> invalid()
                is Decoded.BadCommand ->
                    if (!authenticated) closeWith(WireMessage.AuthFail(WireReasons.UNAUTHENTICATED))
                    else send(WireMessage.CmdResult(d.id, CommandResult.fail(CommandError.INVALID_ARGUMENT, "comando no válido")))
                is Decoded.Ok -> when (val m = d.message) {
                    is WireMessage.Hello -> onHello(m)
                    is WireMessage.PairRequest -> onPairRequest(m, connScope)
                    is WireMessage.AuthResponse -> onAuth(m, connScope)
                    is WireMessage.Cmd -> onCmd(m, connScope)
                    WireMessage.Ping -> send(WireMessage.Pong)
                    else -> invalid()
                }
            }
        }

        private fun invalid() {
            if (++invalidLines > config.maxInvalidLines) closeWith(WireMessage.AuthFail(WireReasons.BAD_REQUEST))
        }

        private suspend fun onHello(m: WireMessage.Hello) {
            if (deviceId != null) return invalid()
            if (m.protocolVersion != PROTOCOL_VERSION) return closeWith(WireMessage.AuthFail(WireReasons.PROTOCOL_VERSION))
            if (!DEVICE_ID.matches(m.deviceId)) return closeWith(WireMessage.AuthFail(WireReasons.BAD_REQUEST))
            if (rateLimiter.isBlocked(address)) return closeWith(WireMessage.AuthFail(WireReasons.RATE_LIMITED))
            deviceId = m.deviceId
            deviceName = m.deviceName.trim().take(64).ifBlank { "TV" }
            val n = AuthCrypto.randomHex(32)
            nonce = n
            link.setReadTimeout(config.idleTimeoutMs)
            send(WireMessage.Challenge(n, store.hostId, hostName.value))
        }

        private suspend fun onPairRequest(m: WireMessage.PairRequest, connScope: CoroutineScope) {
            val id = deviceId
            val n = nonce
            if (id == null || n == null || authenticated || m.deviceId != id || pendingRequestId != null ||
                !AuthCrypto.isHex(m.proof, 64)
            ) return closeWith(WireMessage.AuthFail(WireReasons.BAD_REQUEST))
            if (rateLimiter.isBlocked(address)) return closeWith(WireMessage.AuthFail(WireReasons.RATE_LIMITED))
            val reqId = AuthCrypto.randomHex(6)
            pending[reqId] = PendingPair(this, PairingRequest(reqId, id, deviceName, address), n, m.proof)
            pendingRequestId = reqId
            publishPending()
            send(WireMessage.PairPending)
            connScope.launch {
                delay(config.pairingTimeoutMs)
                dropPending(reqId, WireReasons.TIMEOUT)
            }
        }

        private suspend fun onAuth(m: WireMessage.AuthResponse, connScope: CoroutineScope) {
            val id = deviceId
            val n = nonce
            if (id == null || n == null || authenticated) return closeWith(WireMessage.AuthFail(WireReasons.BAD_REQUEST))
            if (rateLimiter.isBlocked(address)) return closeWith(WireMessage.AuthFail(WireReasons.RATE_LIMITED))
            val token = store.token(id) ?: return closeWith(WireMessage.AuthFail(WireReasons.UNKNOWN_DEVICE))
            if (!AuthCrypto.constantTimeEquals(AuthCrypto.authProof(token, n), m.hmac)) {
                rateLimiter.recordFailure(address)
                return closeWith(WireMessage.AuthFail(WireReasons.BAD_TOKEN))
            }
            nonce = null
            // No se expulsan otras conexiones del mismo dispositivo (p. ej. hostctl watch + comandos);
            // un socket zombi tras un cambio de red se cierra solo a los 45 s sin tráfico.
            authenticated = true
            publishConnected()
            Log.i(TAG, "TV autenticada: $deviceName ($address)")
            send(WireMessage.AuthOk(AuthCrypto.artTokenFor(token), artPort))
            connScope.launch { stateLoop() }
        }

        private suspend fun onCmd(m: WireMessage.Cmd, connScope: CoroutineScope) {
            if (!authenticated) return closeWith(WireMessage.AuthFail(WireReasons.UNAUTHENTICATED))
            val id = m.id.take(64)
            if (!cmdBucket.tryTake()) {
                return send(WireMessage.CmdResult(id, CommandResult.fail(CommandError.RATE_LIMITED)))
            }
            if (m.command == Command.RefreshState) {
                forceSnapshot.set(true)
                kick.update { it + 1 }
            }
            // UNDISPATCHED: los comandos empiezan en el orden de llegada (p. ej. pasos de volumen).
            connScope.launch(start = CoroutineStart.UNDISPATCHED) {
                val result = try {
                    controller.execute(m.command, CommandOrigin.TV)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "comando ${m.command} falló", e)
                    CommandResult.fail(CommandError.INTERNAL, e.message)
                }
                send(WireMessage.CmdResult(id, result))
            }
        }

        /** Snapshot inicial y luego solo las claves cambiadas, como mucho uno cada throttleMs. */
        private suspend fun stateLoop() {
            var last: JsonObject? = null
            var seq = 0L
            combine(controller.state, kick) { s, _ -> s }.conflate().collect { s ->
                // Posición re-sellada al instante del envío (la TV la sella al recibir).
                val obj = StateCodec.encode(s.progressAt(android.os.SystemClock.elapsedRealtime()))
                val prev = last
                val msg = if (prev == null || forceSnapshot.getAndSet(false)) {
                    WireMessage.Snapshot(++seq, obj)
                } else {
                    StateCodec.diff(prev, obj).takeIf { it.isNotEmpty() }?.let { WireMessage.Patch(++seq, it) }
                }
                last = obj
                if (msg != null) {
                    send(msg)
                    delay(config.throttleMs)
                }
            }
        }
    }

    private companion object {
        const val TAG = "DacStageNet"
        const val OUTBOUND_CAPACITY = 64
        val DEVICE_ID = Regex("^[A-Za-z0-9_-]{8,64}$")
    }
}

/** Cubo de fichas para limitar la tasa de comandos por conexión. */
internal class TokenBucket(private val capacity: Double, private val refillPerSec: Double) {
    private var tokens = capacity
    private var lastNs = System.nanoTime()

    @Synchronized
    fun tryTake(): Boolean {
        val now = System.nanoTime()
        tokens = minOf(capacity, tokens + (now - lastNs) / 1e9 * refillPerSec)
        lastNs = now
        if (tokens < 1) return false
        tokens -= 1
        return true
    }
}
