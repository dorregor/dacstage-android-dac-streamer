package io.github.dacstage.artwork

import io.github.dacstage.model.ArtSource
import io.github.dacstage.players.ArtCandidate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class HostArtworkResolverTest {
    @get:Rule val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sources = ConcurrentHashMap<String, ByteArray>()
    private val opened = ConcurrentHashMap<String, AtomicInteger>()

    private val opener = ArtOpener { url ->
        opened.getOrPut(url) { AtomicInteger() }.incrementAndGet()
        sources[url]
    }

    /** Medidor sintético: lee el IHDR de una cabecera PNG (como haría BitmapFactory con inJustDecodeBounds). */
    private val pngMeasurer = ImageMeasurer { b ->
        if (b.size < 24 || sniffMime(b) != "image/png") null
        else ImageInfo(ByteBuffer.wrap(b, 16, 4).int, ByteBuffer.wrap(b, 20, 4).int, "image/png")
    }

    private fun png(w: Int, h: Int, salt: String = "$w x $h"): ByteArray {
        val bb = ByteBuffer.allocate(24)
        bb.put(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        bb.putInt(13).put("IHDR".toByteArray()).putInt(w).putInt(h)
        return bb.array() + salt.toByteArray()
    }

    private fun resolver(dir: String = "art") = HostArtworkResolver(
        scope = scope,
        store = ArtStore(tmp.root.resolve(dir)),
        opener = opener,
        measurer = pngMeasurer,
        encoder = BitmapEncoder { null },
    )

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun picksHighestRealResolutionNotTheFirst() = runBlocking {
        sources["https://cdn.example.com/a_640.jpg"] = png(640, 640)
        sources["https://cdn.example.com/a_320.jpg"] = png(320, 320)
        // b_2000 "responde" pero no es una imagen (p. ej. HTML de error) → se prueba la siguiente.
        sources["https://cdn.example.com/b_2000.jpg"] = "<html>404</html>".toByteArray()
        sources["https://cdn.example.com/b_1500.jpg"] = png(1500, 1500)
        sources["https://cdn.example.com/b_1000.jpg"] = png(1000, 1000)
        sources["content://media/art/7"] = png(1200, 1200)

        val candidates = listOf(
            ArtCandidate.Url("https://cdn.example.com/a_640.jpg", ArtSource.SESSION_ART_URI, listOf("https://cdn.example.com/a_320.jpg")),
            ArtCandidate.Url(
                "https://cdn.example.com/b_3000.jpg", ArtSource.PLAYER_QUEUE_URL,
                listOf("https://cdn.example.com/b_2000.jpg", "https://cdn.example.com/b_1500.jpg", "https://cdn.example.com/b_1000.jpg"),
            ),
            ArtCandidate.Url("content://media/art/7", ArtSource.SESSION_ART_URI),
        )
        val r = resolver()
        val ref = r.resolve("uapp:1:42", candidates)!!

        val expected = sources["https://cdn.example.com/b_1500.jpg"]!!
        assertEquals(1500, ref.widthPx)
        assertEquals(1500, ref.heightPx)
        assertEquals(ArtSource.PLAYER_QUEUE_URL, ref.source)
        assertEquals("https://cdn.example.com/b_1500.jpg", ref.url)
        assertEquals(HostArtworkResolver.sha256Hex(expected), ref.id)
        assertEquals("/art/${ref.id}", ref.hostPath)
        assertEquals("image/png", ref.mimeType)
        assertArrayEquals(expected, r.bytesFor(ref.id)!!.first)

        // Se queda con la primera que responde en cada candidata.
        assertNull(opened["https://cdn.example.com/a_320.jpg"])
        assertNull(opened["https://cdn.example.com/b_1000.jpg"])
        // Todas las candidatas se midieron (no se quedó con la primera).
        assertEquals(1, opened["content://media/art/7"]!!.get())

        // Caché por pista: no se vuelve a descargar.
        val again = r.resolve("uapp:1:42", candidates)
        assertEquals(ref, again)
        assertEquals(1, opened["https://cdn.example.com/b_1500.jpg"]!!.get())
    }

    @Test
    fun tieKeepsEarlierCandidateAndPrivateOrContentUrlsAreServedByHost() = runBlocking {
        sources["http://192.168.1.20:8080/art.jpg"] = png(800, 800, "lan")
        sources["content://media/art/1"] = png(800, 800, "content")
        val r = resolver()
        val ref = r.resolve(
            "hiby:1",
            listOf(
                ArtCandidate.Url("http://192.168.1.20:8080/art.jpg", ArtSource.SESSION_ART_URI),
                ArtCandidate.Url("content://media/art/1", ArtSource.SESSION_ART_URI),
            ),
        )!!
        assertArrayEquals(sources["http://192.168.1.20:8080/art.jpg"], r.bytesFor(ref.id)!!.first)
        assertNull("IP de LAN: no es pública", ref.url)
        assertEquals("/art/${ref.id}", ref.hostPath)

        val ref2 = r.resolve("hiby:2", listOf(ArtCandidate.Url("content://media/art/1", ArtSource.SESSION_ART_URI)))!!
        assertNull(ref2.url)
    }

    @Test
    fun nothingAvailableGivesNull() = runBlocking {
        val r = resolver()
        assertNull(r.resolve("t", emptyList()))
        assertNull(r.resolve("t", listOf(ArtCandidate.Url("https://cdn.example.com/missing.jpg", ArtSource.SESSION_ART_URI))))
        // No se cachea el fallo: si aparece la imagen, se resuelve.
        sources["https://cdn.example.com/missing.jpg"] = png(1000, 1000)
        val ref = r.resolve("t", listOf(ArtCandidate.Url("https://cdn.example.com/missing.jpg", ArtSource.SESSION_ART_URI)))
        assertEquals(1000, ref!!.widthPx)
    }

    @Test
    fun newCandidatesForSameTrackAreResolvedAgain() = runBlocking {
        sources["https://cdn.example.com/s.jpg"] = png(300, 300)
        sources["https://cdn.example.com/l.jpg"] = png(1280, 1280)
        val r = resolver()
        val small = r.resolve("k", listOf(ArtCandidate.Url("https://cdn.example.com/s.jpg", ArtSource.SESSION_ART_URI)))!!
        assertEquals(300, small.widthPx)
        val big = r.resolve(
            "k",
            listOf(
                ArtCandidate.Url("https://cdn.example.com/l.jpg", ArtSource.PLAYER_QUEUE_URL),
                ArtCandidate.Url("https://cdn.example.com/s.jpg", ArtSource.SESSION_ART_URI),
            ),
        )!!
        assertEquals(1280, big.widthPx)
    }

    @Test
    fun concurrentResolvesOfSameTrackDownloadOnce() = runBlocking {
        sources["https://cdn.example.com/x.jpg"] = png(1000, 1000)
        val r = resolver()
        val c = listOf(ArtCandidate.Url("https://cdn.example.com/x.jpg", ArtSource.PLAYER_QUEUE_URL))
        val refs = (1..8).map { async(Dispatchers.IO) { r.resolve("same", c) } }.awaitAll()
        assertTrue(refs.all { it == refs[0] && it != null })
        assertEquals(1, opened["https://cdn.example.com/x.jpg"]!!.get())
    }

    @Test
    fun bytesSurviveOnDiskAndIdsAreValidated() = runBlocking {
        sources["https://cdn.example.com/d.jpg"] = png(1100, 1100)
        val ref = resolver("disk").resolve("d", listOf(ArtCandidate.Url("https://cdn.example.com/d.jpg", ArtSource.PLAYER_QUEUE_URL)))!!
        val fresh = resolver("disk")
        val (bytes, mime) = fresh.bytesFor(ref.id)!!
        assertArrayEquals(sources["https://cdn.example.com/d.jpg"], bytes)
        assertEquals("image/png", mime)
        assertNull(fresh.bytesFor("../" + ref.id))
        assertNull(fresh.bytesFor(ref.id.uppercase()))
        assertNull(fresh.bytesFor("0".repeat(64)))
    }

    @Test
    fun memoryAndDiskBudgetsAreRespected() {
        val dir = tmp.root.resolve("budget")
        val store = ArtStore(dir, memoryBudgetBytes = 100, diskBudgetBytes = 250)
        repeat(5) { i -> store.put("%064x".format(i), ByteArray(60) { i.toByte() }, "image/jpeg") }
        val total = dir.listFiles()!!.sumOf { it.length() }
        assertTrue("disco $total <= 250", total <= 250)
        assertTrue(store.contains("%064x".format(4)))
        assertFalse(store.contains("%064x".format(0)))
    }

    @Test
    fun publicUrlDetection() {
        assertTrue(isPublicHttpUrl("https://resources.tidal.com/images/a/b/c/1280x1280.jpg"))
        assertTrue(isPublicHttpUrl("http://8.8.8.8/x.jpg"))
        assertFalse(isPublicHttpUrl("http://192.168.1.10:8080/x.jpg"))
        assertFalse(isPublicHttpUrl("http://10.0.0.2/x.jpg"))
        assertFalse(isPublicHttpUrl("http://127.0.0.1/x.jpg"))
        assertFalse(isPublicHttpUrl("http://localhost/x.jpg"))
        assertFalse(isPublicHttpUrl("http://nas.local/x.jpg"))
        assertFalse(isPublicHttpUrl("http://[::1]/x.jpg"))
        assertFalse(isPublicHttpUrl("http://[fd00::1]/x.jpg"))
        assertFalse(isPublicHttpUrl("content://media/external/1"))
        assertFalse(isPublicHttpUrl("file:///sdcard/a.jpg"))
        assertFalse(isPublicHttpUrl("no es una url"))
    }

    @Test
    fun mimeSniffing() {
        assertEquals("image/jpeg", sniffMime(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0)))
        assertEquals("image/png", sniffMime(png(1, 1)))
        assertEquals("image/webp", sniffMime("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray()))
        assertNull(sniffMime("hola".toByteArray()))
    }
}
