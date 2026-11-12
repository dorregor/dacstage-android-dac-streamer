package io.github.dacstage.network

import android.util.Log
import io.github.dacstage.model.Command
import io.github.dacstage.model.CommandResult
import io.github.dacstage.model.HostState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong

/**
 * Lado TV de UNA conexión ya abierta: handshake (auth o emparejado), estado, comandos y ping.
 * [run] vuelve cuando la conexión termina, con el motivo.
 */
internal class TvSession(
    private val link: WireLink,
    private val store: TvStore,
    private val hostLabel: String,
    private val listener: Listener,
    private val pingIntervalMs: Long = 15_000,
    private val idleTimeoutMs: Int = 45_000,
) {
    interface Listener {
        fun onStatus(status: LinkStatus)
        fun onAuthenticated(hostId: String, hostName: String, address: String, artBaseUrl: String?)
        fun onState(state: HostState)
        fun onCmdResult(id: String, result: CommandResult)
    }

    /**
     * Fin de la conexión. [retrySoon]: reconectar ya (p. ej. token inválido → emparejar de nuevo);
     * [slowDown]: esperar el máximo (rechazos por límite de intentos o versión).
     */
    data class End(
        val reason: DisconnectReason?,
        val authenticated: Boolean,
        val retrySoon: Boolean = false,
        val slowDown: Boolean = false,
    )

    private val outbound = Channel<WireMessage>(64)
    private val assembler = StateAssembler()
    private val refreshIds = AtomicLong()
    private var hostId: String? = null
    private var hostName: String = ""
    private var nonce: String? = null
    @Volatile private var end: End? = null

    @Volatile var authenticated = false
        private set

    val address: String = link.remoteAddress

    /** false = Bluetooth (no depende de la red Wi‑Fi). */
    val isNetwork: Boolean get() = link.isNetwork

    fun send(message: WireMessage): Boolean = outbound.trySend(message).isSuccess

    fun close() {
        outbound.close()
        link.close()
    }

    suspend fun run(): End = coroutineScope {
        val sessionScope = this
        launch { writeLoop() }
        launch {
            while (true) {
                delay(pingIntervalMs)
                outbound.trySend(WireMessage.Ping)
            }
        }
        try {
            link.setReadTimeout(idleTimeoutMs)
            send(WireMessage.Hello(PROTOCOL_VERSION, store.deviceId, store.deviceName))
            val reader = BoundedLineReader(link.input, WireCodec.MAX_HOST_LINE_BYTES)
            while (end == null) {
                val line = reader.readLine() ?: break
                if (line.isNotBlank()) handle(line)
            }
        } catch (e: SocketTimeoutException) {
            if (end == null) end = End(DisconnectReason.NoResponse, authenticated)
        } catch (e: IOException) {
            if (end == null) end = End(DisconnectReason.ConnectionLost(e.message), authenticated)
        } finally {
            outbound.close()
            link.close()
            sessionScope.coroutineContext.job.children.forEach { it.cancel() }
        }
        end ?: End(DisconnectReason.HostClosed, authenticated)
    }

    private suspend fun writeLoop() {
        try {
            val out = BufferedOutputStream(link.output, 8 * 1024)
            for (msg in outbound) out.writeLine(WireCodec.encode(msg))
        } catch (e: IOException) {
            link.close()
        }
    }

    private fun handle(line: String) {
        val d = WireCodec.decode(line)
        if (d !is Decoded.Ok) {
            Log.d(TAG, "línea inválida del host: $d")
            return
        }
        when (val m = d.message) {
            is WireMessage.Challenge -> onChallenge(m)
            WireMessage.PairPending -> Unit
            is WireMessage.PairResult -> onPairResult(m)
            is WireMessage.AuthOk -> onAuthOk(m)
            is WireMessage.AuthFail -> onAuthFail(m)
            is WireMessage.Snapshot -> if (authenticated) deliver(assembler.onSnapshot(m.seq, m.state))
            is WireMessage.Patch -> if (authenticated) deliver(assembler.onPatch(m.seq, m.changes))
            is WireMessage.CmdResult -> listener.onCmdResult(m.id, m.result)
            else -> Unit // Pong y mensajes que no corresponden a la TV
        }
    }

    private fun label() = hostName.ifBlank { hostLabel }

    private fun onChallenge(m: WireMessage.Challenge) {
        if (hostId != null) return
        hostId = m.hostId
        hostName = m.hostName
        nonce = m.nonce
        val token = store.token(m.hostId)
        if (token != null) {
            send(WireMessage.AuthResponse(AuthCrypto.authProof(token, m.nonce)))
        } else {
            val code = AuthCrypto.pairingCode()
            listener.onStatus(LinkStatus.PairingRequired(label(), code))
            send(WireMessage.PairRequest(store.deviceId, store.deviceName, AuthCrypto.pairProof(code, m.nonce)))
        }
    }

    private fun onPairResult(m: WireMessage.PairResult) {
        val id = hostId
        val n = nonce
        val token = m.token
        if (m.ok && id != null && n != null && token != null && AuthCrypto.isHex(token, 64)) {
            store.saveToken(id, token)
            send(WireMessage.AuthResponse(AuthCrypto.authProof(token, n)))
        } else {
            end = End(
                reason = when (m.reason) {
                    WireReasons.REJECTED -> DisconnectReason.PairingRejected
                    WireReasons.TIMEOUT -> DisconnectReason.CodeExpired
                    WireReasons.TOO_MANY_ATTEMPTS -> DisconnectReason.TooManyCodes
                    else -> DisconnectReason.PairingFailed
                },
                authenticated = false,
                slowDown = m.reason == WireReasons.TOO_MANY_ATTEMPTS,
            )
        }
    }

    private fun onAuthOk(m: WireMessage.AuthOk) {
        val id = hostId ?: return
        authenticated = true
        nonce = null
        store.lastHostId = id
        // Por Bluetooth no hay dirección de red reutilizable ni servidor HTTP de carátulas alcanzable: las
        // carátulas con URL pública (p. ej. TIDAL) se siguen viendo; las locales del host no.
        if (link.isNetwork) store.lastAddress = "$address:${link.remotePort}"
        val base = if (link.isNetwork && m.artPort in 1..65535 && AuthCrypto.isHex(m.artToken, m.artToken.length) && m.artToken.isNotEmpty()) {
            "http://${urlHost(address)}:${m.artPort}/t/${m.artToken}"
        } else null
        listener.onAuthenticated(id, hostName, address, base)
        listener.onStatus(LinkStatus.Connected(label(), address))
    }

    private fun onAuthFail(m: WireMessage.AuthFail) {
        end = when (m.reason) {
            WireReasons.BAD_TOKEN, WireReasons.UNKNOWN_DEVICE, WireReasons.UNPAIRED -> {
                hostId?.let(store::clearToken)
                End(DisconnectReason.Unpaired, authenticated, retrySoon = true)
            }
            WireReasons.RATE_LIMITED -> End(DisconnectReason.RateLimited, authenticated, slowDown = true)
            WireReasons.PROTOCOL_VERSION -> End(DisconnectReason.VersionMismatch, authenticated, slowDown = true)
            else -> End(DisconnectReason.Rejected(m.reason.take(40)), authenticated)
        }
    }

    private fun deliver(r: StateAssembler.Result) {
        r.state?.let(listener::onState)
        if (r.needsRefresh) send(WireMessage.Cmd("refresh-${refreshIds.incrementAndGet()}", Command.RefreshState))
    }

    companion object {
        private const val TAG = "DacStageNet"

        /** IPv6 entre corchetes (con el % de zona escapado) para usarla en una URL. */
        fun urlHost(address: String): String =
            if (address.contains(':')) "[" + address.replace("%", "%25") + "]" else address
    }
}
