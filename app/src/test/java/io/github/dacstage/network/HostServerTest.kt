package io.github.dacstage.network

import io.github.dacstage.model.Command
import io.github.dacstage.model.CommandError
import io.github.dacstage.model.CommandOrigin
import io.github.dacstage.model.PlaybackStatus
import io.github.dacstage.model.TrackInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HostServerTest {
    private lateinit var scope: CoroutineScope
    private lateinit var controller: FakeController
    private lateinit var store: InMemoryPairingStore
    private lateinit var server: HostServer
    private var port = 0

    private fun newServer(limiter: FailureRateLimiter = FailureRateLimiter()) {
        server = HostServer(
            controller = controller,
            artwork = NoArtwork(),
            hostName = kotlinx.coroutines.flow.MutableStateFlow("Tablet"),
            store = store,
            parentScope = scope,
            registrar = null,
            tcpPorts = listOf(0),
            artPorts = listOf(0),
            config = ServerConfig(throttleMs = 10),
            rateLimiter = limiter,
        )
        server.start()
        port = server.listeningPort.value!!
    }

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        controller = FakeController()
        store = InMemoryPairingStore()
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.stop()
        scope.cancel()
    }

    private fun <T> await(flow: Flow<T>, what: String, pred: (T) -> Boolean): T = runBlocking {
        try {
            withTimeout(10_000) { flow.first(pred) }
        } catch (e: Exception) {
            throw AssertionError("timeout: $what", e)
        }
    }

    private fun newClient(tvStore: InMemoryTvStore) = TvHostClient(
        parentScope = scope,
        store = tvStore,
        discovery = null,
        networkMonitor = null,
        config = ClientConfig(discoveryGraceMs = 100, minBackoffMs = 100, maxBackoffMs = 500),
    )

    @Test
    fun pairingStateAndCommandsEndToEnd() {
        newServer()
        val tvStore = InMemoryTvStore()
        val client = newClient(tvStore)
        client.connectManual("127.0.0.1", port)
        client.start()
        try {
            val pairing = await(client.status, "código en la TV") { it is LinkStatus.PairingRequired } as LinkStatus.PairingRequired
            assertEquals("Tablet", pairing.hostLabel)
            assertTrue(pairing.code.matches(Regex("\\d{6}")))
            val req = await(server.pairingRequests, "petición en el host") { it.isNotEmpty() }.single()
            assertEquals("TCL Test", req.deviceName)
            assertEquals(tvStore.deviceId, req.deviceId)

            val wrong = pairing.code.map { if (it == '9') '0' else it + 1 }.joinToString("")
            assertFalse(server.confirmPairing(req.requestId, wrong))
            assertTrue(server.confirmPairing(req.requestId, pairing.code))
            assertTrue(server.pairingRequests.value.isEmpty())

            await(client.status, "conectada") { it is LinkStatus.Connected }
            assertEquals(1, server.pairedTvs.value.size)
            assertEquals(store.token(tvStore.deviceId), tvStore.token(store.hostId))
            await(server.connectedClients, "cliente conectado") { it == listOf("TCL Test") }
            await(client.hostState, "snapshot") { it == controller.state.value }
            assertTrue(client.artBaseUrl.value!!.matches(Regex("^http://127\\.0\\.0\\.1:\\d+/t/[0-9a-f]{32}$")))

            // Parches: cambio y paso a null.
            controller.state.value = controller.state.value.copy(status = PlaybackStatus.PLAYING, track = TrackInfo("A"))
            await(client.hostState, "parche") { it?.track?.title == "A" && it.status == PlaybackStatus.PLAYING }
            controller.state.value = controller.state.value.copy(track = null)
            await(client.hostState, "track a null") { it != null && it.track == null }

            // Comando con ack.
            val ack = runBlocking {
                withTimeout(10_000) {
                    val pendingAck = async { client.acks.first { it.command == Command.Next } }
                    kotlinx.coroutines.delay(50)
                    val id = client.send(Command.Next)
                    pendingAck.await().also { assertEquals(id, it.commandId) }
                }
            }
            assertTrue(ack.result.ok)
            assertTrue(controller.commands.contains(Command.Next to CommandOrigin.TV))
        } finally {
            client.stop()
        }

        // Reconexión con el token guardado: sin código.
        val client2 = newClient(tvStore)
        client2.connectManual("127.0.0.1", port)
        client2.start()
        try {
            val st = await(client2.status, "reconexión") { it is LinkStatus.Connected || it is LinkStatus.PairingRequired }
            assertTrue("no debe pedir código: $st", st is LinkStatus.Connected)

            // Desemparejar en el host: la TV olvida el token y vuelve a mostrar un código.
            server.unpair(tvStore.deviceId)
            await(client2.status, "re-emparejado") { it is LinkStatus.PairingRequired }
            assertNull(tvStore.token(store.hostId))
        } finally {
            client2.stop()
        }
    }

    @Test
    fun sendWithoutConnectionGivesErrorAck() = runBlocking {
        val client = newClient(InMemoryTvStore())
        val ack = withTimeout(5_000) {
            val a = async { client.acks.first() }
            kotlinx.coroutines.delay(50)
            client.send(Command.Play)
            a.await()
        }
        assertFalse(ack.result.ok)
        assertEquals(TvHostClient.NOT_CONNECTED, ack.result.message)
    }

    @Test
    fun rawPairingHandshakeAndRejection() {
        newServer()
        RawClient(port).use { c ->
            c.send(WireMessage.Hello(PROTOCOL_VERSION, "rawdevice1", "Raw"))
            val ch = c.readUntil<WireMessage.Challenge>()
            assertEquals(store.hostId, ch.hostId)
            assertEquals("Tablet", ch.hostName)
            c.send(WireMessage.PairRequest("rawdevice1", "Raw", AuthCrypto.pairProof("123456", ch.nonce)))
            c.readUntil<WireMessage.PairPending>()
            val req = await(server.pairingRequests, "petición") { it.isNotEmpty() }.single()
            assertFalse(server.confirmPairing(req.requestId, "654321"))
            assertTrue(server.confirmPairing(req.requestId, " 123 456 "))
            val res = c.readUntil<WireMessage.PairResult>()
            assertTrue(res.ok)
            val token = res.token!!
            c.send(WireMessage.AuthResponse(AuthCrypto.authProof(token, ch.nonce)))
            val ok = c.readUntil<WireMessage.AuthOk>()
            assertEquals(AuthCrypto.artTokenFor(token), ok.artToken)
            assertEquals(server.artPort, ok.artPort)
            val snap = c.readUntil<WireMessage.Snapshot>()
            assertEquals(controller.state.value, StateCodec.decode(snap.state))
        }
        RawClient(port).use { c ->
            c.send(WireMessage.Hello(PROTOCOL_VERSION, "rawdevice2", "Raw2"))
            val ch = c.readUntil<WireMessage.Challenge>()
            c.send(WireMessage.PairRequest("rawdevice2", "Raw2", AuthCrypto.pairProof("111111", ch.nonce)))
            c.readUntil<WireMessage.PairPending>()
            val req = await(server.pairingRequests, "petición 2") { it.any { r -> r.deviceId == "rawdevice2" } }
                .first { it.deviceId == "rawdevice2" }
            server.rejectPairing(req.requestId)
            val res = c.readUntil<WireMessage.PairResult>()
            assertFalse(res.ok)
            assertEquals(WireReasons.REJECTED, res.reason)
            assertTrue(c.isClosedByServer())
        }
    }

    @Test
    fun commandWithoutAuthIsRejectedAndConnectionClosed() {
        newServer()
        RawClient(port).use { c ->
            c.send(WireMessage.Hello(PROTOCOL_VERSION, "rawdevice1", "Raw"))
            c.readUntil<WireMessage.Challenge>()
            c.send(WireMessage.Cmd("c1", Command.Next))
            assertEquals(WireMessage.AuthFail(WireReasons.UNAUTHENTICATED), c.read())
            assertTrue(c.isClosedByServer())
        }
        RawClient(port).use { c ->
            // Ni siquiera con Hello: comando directo.
            c.sendLine("""{"type":"cmd","id":"x","command":{"type":"next"}}""")
            assertEquals(WireMessage.AuthFail(WireReasons.UNAUTHENTICATED), c.read())
        }
        assertTrue(controller.commands.isEmpty())
    }

    @Test
    fun wrongTokenFailsAndIsRateLimitedPerIp() {
        newServer(FailureRateLimiter(maxFailures = 3, windowMs = 60_000))
        val token = AuthCrypto.randomHex(32)
        store.put(PairedTv("rawdevice1", "Raw", 0), token)
        repeat(3) {
            RawClient(port).use { c ->
                c.send(WireMessage.Hello(PROTOCOL_VERSION, "rawdevice1", "Raw"))
                val ch = c.readUntil<WireMessage.Challenge>()
                c.send(WireMessage.AuthResponse(AuthCrypto.authProof("00".repeat(32), ch.nonce)))
                assertEquals(WireMessage.AuthFail(WireReasons.BAD_TOKEN), c.read())
                assertTrue(c.isClosedByServer())
            }
        }
        RawClient(port).use { c ->
            c.send(WireMessage.Hello(PROTOCOL_VERSION, "rawdevice1", "Raw"))
            assertEquals(WireMessage.AuthFail(WireReasons.RATE_LIMITED), c.read())
        }
        assertTrue(server.connectedClients.value.isEmpty())
    }

    @Test
    fun unknownDeviceAndProtocolMismatch() {
        newServer()
        RawClient(port).use { c ->
            c.send(WireMessage.Hello(PROTOCOL_VERSION, "nobody0001", "X"))
            val ch = c.readUntil<WireMessage.Challenge>()
            c.send(WireMessage.AuthResponse(AuthCrypto.authProof("11".repeat(32), ch.nonce)))
            assertEquals(WireMessage.AuthFail(WireReasons.UNKNOWN_DEVICE), c.read())
        }
        RawClient(port).use { c ->
            c.send(WireMessage.Hello(PROTOCOL_VERSION + 1, "nobody0001", "X"))
            assertEquals(WireMessage.AuthFail(WireReasons.PROTOCOL_VERSION), c.read())
        }
    }

    @Test
    fun authenticatedClientGetsResultsForInvalidCommandsAndPong() {
        newServer()
        val token = AuthCrypto.randomHex(32)
        store.put(PairedTv("rawdevice1", "Raw", 0), token)
        RawClient(port).use { c ->
            c.send(WireMessage.Hello(PROTOCOL_VERSION, "rawdevice1", "Raw"))
            val ch = c.readUntil<WireMessage.Challenge>()
            c.send(WireMessage.AuthResponse(AuthCrypto.authProof(token, ch.nonce)))
            c.readUntil<WireMessage.AuthOk>()
            c.readUntil<WireMessage.Snapshot>()

            c.sendLine("""{"type":"cmd","id":"z1","command":{"type":"self_destruct"}}""")
            val r1 = c.readUntil<WireMessage.CmdResult>()
            assertEquals("z1", r1.id)
            assertEquals(CommandError.INVALID_ARGUMENT, r1.result.error)

            c.send(WireMessage.Cmd("z2", Command.VolumeStep(5)))
            val r2 = c.readUntil<WireMessage.CmdResult>()
            assertEquals("z2", r2.id)
            assertFalse(r2.result.ok)

            c.send(WireMessage.Cmd("z3", Command.RefreshState))
            assertEquals("z3", c.readUntil<WireMessage.CmdResult>().id)

            c.send(WireMessage.Ping)
            c.readUntil<WireMessage.Pong>()

            // El snapshot forzado por RefreshState puede coalescerse con este cambio: vale parche o snapshot.
            controller.state.value = controller.state.value.copy(status = PlaybackStatus.PAUSED)
            var seen = false
            repeat(5) {
                if (seen) return@repeat
                when (val m = c.read()) {
                    is WireMessage.Patch -> if (m.changes.containsKey("status")) {
                        assertEquals(setOf("status"), m.changes.keys)
                        seen = true
                    }
                    is WireMessage.Snapshot -> seen = StateCodec.decode(m.state).status == PlaybackStatus.PAUSED
                    else -> Unit
                }
            }
            assertTrue("llega el cambio de estado", seen)
        }
        assertTrue(controller.commands.any { it.first == Command.VolumeStep(5) && it.second == CommandOrigin.TV })
    }

    @Test
    fun invalidJsonIsIgnoredWithoutClosing() {
        newServer()
        RawClient(port).use { c ->
            c.sendLine("{nope")
            c.sendLine("""{"type":"bogus"}""")
            c.send(WireMessage.Ping)
            assertEquals(WireMessage.Pong, c.read())
        }
    }

    @Test
    fun overlongLineClosesOnlyThatConnection() {
        newServer()
        RawClient(port).use { c ->
            try {
                c.sendLine("a".repeat(WireCodec.MAX_CLIENT_LINE_BYTES + 10))
            } catch (_: java.io.IOException) {
                // el servidor puede cortar mientras aún escribimos
            }
            assertTrue(c.isClosedByServer())
        }
        RawClient(port).use { c ->
            c.send(WireMessage.Hello(PROTOCOL_VERSION, "rawdevice1", "Raw"))
            c.readUntil<WireMessage.Challenge>()
        }
    }

    @Test
    fun portIsPublishedAndReleased() {
        newServer()
        assertTrue(port > 0)
        server.stop()
        assertNull(server.listeningPort.value)
        // El puerto queda libre: se puede volver a abrir.
        java.net.ServerSocket(port).close()
    }
}
