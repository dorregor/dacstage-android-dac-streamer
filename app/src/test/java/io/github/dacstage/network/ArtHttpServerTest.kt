package io.github.dacstage.network

import io.github.dacstage.artwork.ArtworkResolver
import io.github.dacstage.model.ArtRef
import io.github.dacstage.players.ArtCandidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket

class ArtHttpServerTest {
    private val id = "ab".repeat(32)
    private val token = "0123456789abcdef0123456789abcdef"
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3, 4)
    private lateinit var scope: CoroutineScope
    private lateinit var server: ArtHttpServer
    private var port = 0

    private val resolver = object : ArtworkResolver {
        override suspend fun resolve(trackKey: String, candidates: List<ArtCandidate>): ArtRef? = null
        override fun bytesFor(id: String) = if (id == this@ArtHttpServerTest.id) jpeg to "image/jpeg" else null
    }

    private class Response(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun request(raw: String): Response {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 3_000)
            s.soTimeout = 5_000
            s.getOutputStream().apply { write(raw.toByteArray(Charsets.ISO_8859_1)); flush() }
            val all = ByteArrayOutputStream().also { s.getInputStream().copyTo(it) }.toByteArray()
            val text = String(all, Charsets.ISO_8859_1)
            val headEnd = text.indexOf("\r\n\r\n")
            val lines = text.substring(0, headEnd).split("\r\n")
            val status = lines[0].split(' ')[1].toInt()
            val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
            return Response(status, headers, all.copyOfRange(headEnd + 4, all.size))
        }
    }

    private fun get(path: String, extra: String = "") = request("GET $path HTTP/1.1\r\nHost: x\r\n$extra\r\n")

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        server = ArtHttpServer(resolver, { it == token }, listOf(0))
        port = server.start(scope)
    }

    @After
    fun tearDown() {
        server.stop()
        scope.cancel()
    }

    @Test
    fun servesBytesWithCacheHeadersInBothForms() {
        for (path in listOf("/art/$id?t=$token", "/t/$token/art/$id")) {
            val r = get(path)
            assertEquals(200, r.status)
            assertArrayEquals(jpeg, r.body)
            assertEquals("image/jpeg", r.headers["content-type"])
            assertEquals("max-age=31536000, immutable", r.headers["cache-control"])
            assertEquals("\"$id\"", r.headers["etag"])
            assertEquals(jpeg.size.toString(), r.headers["content-length"])
        }
    }

    @Test
    fun forbiddenWithoutValidToken() {
        assertEquals(403, get("/art/$id?t=ffffffffffffffffffffffffffffffff").status)
        assertEquals(403, get("/t/ffffffffffffffffffffffffffffffff/art/$id").status)
        // Sin token la ruta no es válida.
        assertEquals(404, get("/art/$id").status)
    }

    @Test
    fun notFoundForUnknownIdOrBadPaths() {
        assertEquals(404, get("/art/${"cd".repeat(32)}?t=$token").status)
        assertEquals(404, get("/").status)
        assertEquals(404, get("/art/").status)
        assertEquals(404, get("/art/../../etc/passwd?t=$token").status)
        assertEquals(404, get("/art/$id?t=$token&x=1").status)
        assertEquals(404, get("/art/${id.uppercase()}?t=$token").status)
    }

    @Test
    fun onlyGetIsAllowed() {
        assertEquals(405, request("POST /art/$id?t=$token HTTP/1.1\r\nContent-Length: 0\r\n\r\n").status)
        assertEquals(405, request("DELETE /art/$id?t=$token HTTP/1.1\r\n\r\n").status)
        assertEquals(400, request("GARBAGE\r\n\r\n").status)
    }

    @Test
    fun notModifiedWithMatchingEtag() {
        val r = get("/art/$id?t=$token", "If-None-Match: \"$id\"\r\n")
        assertEquals(304, r.status)
        assertEquals(0, r.body.size)
    }

    @Test
    fun parseTargetIsStrict() {
        assertEquals(id to token, ArtHttpServer.parseTarget("/art/$id?t=$token"))
        assertEquals(id to token, ArtHttpServer.parseTarget("/t/$token/art/$id"))
        assertNull(ArtHttpServer.parseTarget("/art/$id?t=$token/"))
        assertNull(ArtHttpServer.parseTarget("//art/$id?t=$token"))
    }
}
