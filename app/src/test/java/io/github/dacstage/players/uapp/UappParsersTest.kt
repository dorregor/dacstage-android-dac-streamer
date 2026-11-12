package io.github.dacstage.players.uapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayQueueXmlParserTest {

    @Test
    fun parsesUtf16WithBom() {
        val bytes = UappFixtures.utf16WithBom()
        assertEquals(0xFE, bytes[0].toInt() and 0xFF)
        assertEquals(0xFF, bytes[1].toInt() and 0xFF)
        val q = PlayQueueXmlParser.parse(bytes)!!
        assertEquals(3, q.items.size)
        assertEquals(1, q.currentItem)
        assertEquals(0, q.shuffleMode)
        assertEquals(2, q.repeatMode)

        val a = q.items[0]
        assertEquals(0, a.index)
        assertEquals("Canción Uno", a.title)
        assertEquals("Artista A", a.artist)
        assertEquals("Álbum & Prueba", a.album)
        assertEquals(1, a.trackNr)
        assertEquals("100000001", a.id)
        assertEquals("2000001", a.albumId)
        assertEquals(245_000L, a.durationMs)
        assertEquals(24, a.bitDepth)
        assertEquals(96_000, a.sampleRateHz)

        val b = q.items[1]
        assertEquals("Tema \"Dos\" (Remastered)", b.title)
        assertEquals(180_500L, b.durationMs)
        assertEquals(16, b.bitDepth)
        assertEquals(44_100, b.sampleRateHz)
    }

    @Test
    fun missingOrZeroAttributesAreNull() {
        val c = PlayQueueXmlParser.parse(UappFixtures.utf16WithBom())!!.items[2]
        assertEquals("Pista 🎵 Tres", c.title)
        assertNull(c.album)
        assertNull(c.trackNr)
        assertNull(c.artUrl)
        assertNull(c.durationMs)
        assertNull(c.bitDepth)
        assertNull(c.sampleRateHz)
        assertNull(c.albumId)
    }

    @Test
    fun toleratesOtherEncodings() {
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + UappFixtures.XML.toByteArray(Charsets.UTF_16LE)
        assertEquals(3, PlayQueueXmlParser.parse(le)!!.items.size)
        val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + UappFixtures.XML.toByteArray()
        assertEquals(3, PlayQueueXmlParser.parse(utf8Bom)!!.items.size)
        val beNoBom = UappFixtures.XML.toByteArray(Charsets.UTF_16BE)
        assertEquals(3, PlayQueueXmlParser.parse(beNoBom)!!.items.size)
        assertEquals(3, PlayQueueXmlParser.parse(UappFixtures.XML.toByteArray())!!.items.size)
    }

    @Test
    fun truncatedDocumentIsRejected() {
        val text = UappFixtures.XML
        val cut = text.substring(0, text.indexOf("<CurrentTrack"))
        assertNull(PlayQueueXmlParser.parse(UappFixtures.utf16WithBom(cut)))
        assertNull(PlayQueueXmlParser.parse(ByteArray(0)))
    }

    @Test
    fun emptyQueueAsWrittenByUapp() {
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-16\"?>\n<PlayQueue>\n" +
            "<CurrentTrack CurrentItem=\"0\" CurrentPos=\"0\"/>\n<Misc ShuffleMode=\"0\" RepeatMode=\"0\"/>\n</PlayQueue>\n"
        val q = PlayQueueXmlParser.parse(UappFixtures.utf16WithBom(xml))!!
        assertEquals(0, q.items.size)
        assertEquals(0, q.currentItem)
    }

    @Test
    fun greaterThanInsideAttributeValue() {
        val xml = "<PlayQueue><Queue><Item Title=\"a > b\" ID=\"7\"/></Queue></PlayQueue>"
        assertEquals("a > b", PlayQueueXmlParser.parseText(xml)!!.items.single().title)
    }
}

class PosFileParserTest {
    @Test
    fun parsesCrlf() {
        val p = PosFileParser.parse("START\r\n213924\r\n0\r\n1\r\nEND\r\n".toByteArray())
        assertEquals(UappPosition(213_924, 0, 1), p)
    }

    @Test
    fun parsesLf() {
        assertEquals(UappPosition(5, 3, 10), PosFileParser.parseText("START\n5\n3\n10\nEND"))
    }

    @Test
    fun rejectsIncompleteOrBroken() {
        assertNull(PosFileParser.parseText("START\r\n213924\r\n0\r\n"))
        assertNull(PosFileParser.parseText(""))
        assertNull(PosFileParser.parseText("BEGIN\n1\n2\n3\nEND"))
        assertNull(PosFileParser.parseText("START\nx\n2\n3\nEND"))
        assertNull(PosFileParser.parseText("START\n1\n-1\n3\nEND"))
    }
}

class UappStatParserTest {
    @Test
    fun parsesToyboxStat() {
        val out = "PlayQueue.xml|1794366265|2026-11-11 00:04:25.560011471 -0300|304\n" +
            "PosA.txt|1794365858|2026-11-10 23:57:38.132011447 -0300|26\n" +
            "PosB.txt|1794365857|2026-11-10 23:57:37.5 -0300|26\n"
        val stats = UappStatParser.parse(out)
        assertEquals(
            listOf(
                UappFileStat("PlayQueue.xml", 1_794_366_265_560, 304),
                UappFileStat("PosA.txt", 1_794_365_858_132, 26),
                UappFileStat("PosB.txt", 1_794_365_857_500, 26),
            ),
            stats,
        )
    }

    @Test
    fun ignoresGarbage() {
        assertEquals(emptyList<UappFileStat>(), UappStatParser.parse("stat: no such file\n\n"))
    }
}

class UappArtUrlsTest {
    private val base = "https://resources.tidal.com/images/aaaa/bbbb/cccc/dddd"

    @Test
    fun rewrites750ToLargerCandidates() {
        assertEquals(
            listOf("$base/1280x1280.jpg", "$base/1080x1080.jpg", "$base/750x750.jpg"),
            UappArtUrls.candidates("$base/750x750.jpg"),
        )
    }

    @Test
    fun noDownscaleOrDuplicates() {
        assertEquals(listOf("$base/1280x1280.jpg", "$base/1080x1080.jpg"), UappArtUrls.candidates("$base/1080x1080.jpg"))
        assertEquals(listOf("$base/1280x1280.jpg"), UappArtUrls.candidates("$base/1280x1280.jpg"))
    }

    @Test
    fun unknownShapesKeptAsIs() {
        assertEquals(listOf("https://example.com/cover.jpg"), UappArtUrls.candidates("https://example.com/cover.jpg"))
        assertEquals(emptyList<String>(), UappArtUrls.candidates(""))
        assertEquals(emptyList<String>(), UappArtUrls.candidates(null))
        assertEquals(emptyList<String>(), UappArtUrls.candidates("/storage/emulated/0/cover.jpg"))
    }
}
