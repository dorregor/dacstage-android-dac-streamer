package io.github.dacstage.network

import io.github.dacstage.artwork.ArtworkResolver
import io.github.dacstage.host.HostController
import io.github.dacstage.model.ArtRef
import io.github.dacstage.model.Command
import io.github.dacstage.model.CommandError
import io.github.dacstage.model.CommandOrigin
import io.github.dacstage.model.CommandResult
import io.github.dacstage.model.HostState
import io.github.dacstage.players.ArtCandidate
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/** Núcleo del host simulado: registra comandos y rechaza pasos de volumen que no sean ±1. */
class FakeController(initial: HostState = HostState(hostName = "Tablet")) : HostController {
    override val state = MutableStateFlow(initial)
    val commands: MutableList<Pair<Command, CommandOrigin>> = java.util.Collections.synchronizedList(mutableListOf())

    override suspend fun execute(command: Command, origin: CommandOrigin): CommandResult {
        commands += command to origin
        if (command is Command.VolumeStep && command.deltaDb !in listOf(-1, 1)) {
            return CommandResult.fail(CommandError.INVALID_ARGUMENT, "deltaDb")
        }
        return CommandResult.OK
    }
}

class InMemoryPairingStore(override val hostId: String = "hosthost") : PairingStore {
    private val tvs = ConcurrentHashMap<String, Pair<PairedTv, String>>()
    override fun token(deviceId: String) = tvs[deviceId]?.second
    override fun put(tv: PairedTv, token: String) { tvs[tv.deviceId] = tv to token }
    override fun remove(deviceId: String) { tvs.remove(deviceId) }
    override fun all() = tvs.values.map { it.first }
    override fun allTokens() = tvs.values.map { it.second }
}

class InMemoryTvStore(
    override val deviceId: String = "tvdevice01",
    override val deviceName: String = "TCL Test",
) : TvStore {
    val tokens = ConcurrentHashMap<String, String>()
    override fun token(hostId: String) = tokens[hostId]
    override fun saveToken(hostId: String, token: String) { tokens[hostId] = token }
    override fun clearToken(hostId: String) { tokens.remove(hostId) }
    override fun clearAll() { tokens.clear(); lastHostId = null; lastAddress = null }
    @Volatile override var lastHostId: String? = null
    @Volatile override var lastAddress: String? = null
}

class NoArtwork : ArtworkResolver {
    override suspend fun resolve(trackKey: String, candidates: List<ArtCandidate>): ArtRef? = null
    override fun bytesFor(id: String): Pair<ByteArray, String>? = null
}

/** Cliente TCP crudo que habla el protocolo línea a línea (para probar el servidor). */
class RawClient(port: Int) : AutoCloseable {
    val socket = Socket().apply {
        connect(InetSocketAddress("127.0.0.1", port), 3_000)
        soTimeout = 5_000
    }
    private val reader = BoundedLineReader(socket.getInputStream(), WireCodec.MAX_HOST_LINE_BYTES)
    private val out = BufferedOutputStream(socket.getOutputStream())

    fun sendLine(line: String) = out.writeLine(line)
    fun send(m: WireMessage) = sendLine(WireCodec.encode(m))

    /** Siguiente mensaje, o null si el servidor cerró. */
    fun read(): WireMessage? {
        val line = try {
            reader.readLine()
        } catch (e: IOException) {
            if (e is java.net.SocketTimeoutException) throw AssertionError("timeout esperando mensaje")
            null
        } ?: return null
        val d = WireCodec.decode(line)
        return (d as? Decoded.Ok)?.message ?: throw AssertionError("línea inválida: $line")
    }

    /** Lee hasta encontrar un mensaje del tipo pedido (salta snapshots/parches intermedios). */
    inline fun <reified T : WireMessage> readUntil(): T {
        repeat(50) {
            val m = read() ?: throw AssertionError("conexión cerrada esperando ${T::class.simpleName}")
            if (m is T) return m
        }
        throw AssertionError("no llegó ${T::class.simpleName}")
    }

    /** true si el servidor cerró (EOF o reset) sin mandar más mensajes. */
    fun isClosedByServer(): Boolean = try {
        reader.readLine() == null
    } catch (e: java.net.SocketTimeoutException) {
        false
    } catch (e: IOException) {
        true
    }

    override fun close() = closeQuietly(socket)
}
