package io.github.dacstage.enrich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrichTest {

    private val q = TrackQuery("Ceremony (Version 2) [2023 Digital Master]", "New Order", "Substance (2023 Expanded Reissue)", 263_000)

    @Test fun titulosTolerantes() {
        assertTrue(TextMatch.titleEq("Ceremony (Version 2) [2023 Digital Master]", "Ceremony"))
        assertTrue(TextMatch.titleEq("Heroes - 2017 Remaster", "Heroes"))
        assertTrue(TextMatch.titleEq("Song feat. Someone", "Song"))
        assertFalse(TextMatch.titleEq("Ceremony", "Temptation"))
        assertFalse(TextMatch.titleEq(null, "x"))
    }

    @Test fun artistas() {
        assertTrue(TextMatch.artistMatches("Simon & Garfunkel", "Simon & Garfunkel"))
        assertTrue(TextMatch.artistMatches("Daft Punk, Pharrell Williams", "Daft Punk feat. Pharrell Williams"))
        assertFalse(TextMatch.artistMatches("New Order", "Joy Division"))
    }

    @Test fun duracion() {
        assertEquals(true, TextMatch.durationOk(263_000, 265_500))
        assertEquals(false, TextMatch.durationOk(263_000, 270_000))
        assertNull(TextMatch.durationOk(null, 270_000))
    }

    @Test fun lrc() {
        val lines = LrcParser.parse("[ar:New Order]\n[00:12.50]Primera\n[00:20.00][01:05.10]Estribillo\n[00:30.123] Tercera ")
        assertEquals(listOf(12_500L, 20_000L, 30_123L, 65_100L), lines.map { it.timeMs })
        assertEquals("Tercera", lines[2].text)
        assertEquals(-1, LrcParser.indexAt(lines, 5_000))
        assertEquals(0, LrcParser.indexAt(lines, 12_500))
        assertEquals(1, LrcParser.indexAt(lines, 25_000))
        assertEquals(3, LrcParser.indexAt(lines, 999_000))
    }

    @Test fun lrclibEligeSoloCoincidencias() {
        val body = """[
          {"trackName":"Ceremony","artistName":"New Order","albumName":"Substance","duration":264.0,"instrumental":false,"plainLyrics":"a","syncedLyrics":null},
          {"trackName":"Ceremony","artistName":"New Order","albumName":"Substance","duration":262.5,"instrumental":false,"plainLyrics":"a","syncedLyrics":"[00:01.00]a"},
          {"trackName":"Ceremony","artistName":"Joy Division","albumName":"Still","duration":263.0,"instrumental":false,"plainLyrics":"x","syncedLyrics":"[00:01.00]x"},
          {"trackName":"Ceremony","artistName":"New Order","albumName":"Live","duration":300.0,"instrumental":false,"plainLyrics":"y","syncedLyrics":"[00:01.00]y"}
        ]"""
        val best = LrclibPick.best(LrclibPick.parseList(body), q)
        assertNotNull(best)
        assertEquals(262_500L, best!!.durationMs) // sincronizada y dentro de ±3 s
        assertNotNull(best.toLyrics().synced)
    }

    @Test fun lrclibSinCoincidenciaNoDevuelveNada() {
        val body = """[{"trackName":"Otra","artistName":"New Order","duration":263.0,"plainLyrics":"a"}]"""
        assertNull(LrclibPick.best(LrclibPick.parseList(body), q))
    }

    @Test fun musicBrainzEligeGrabacionYDisco() {
        val body = """{"recordings":[
          {"id":"bad","score":100,"title":"Ceremony","length":263000,"artist-credit":[{"name":"Joy Division"}],"releases":[]},
          {"id":"rec1","score":98,"title":"Ceremony (version 2)","length":264000,"first-release-date":"1981-03-06",
           "artist-credit":[{"name":"New Order"}],
           "releases":[{"id":"r-other","title":"Singles","status":"Official"},
                       {"id":"r-bootleg","title":"Substance","status":"Bootleg"},
                       {"id":"r-sub","title":"Substance","status":"Official"}]}
        ]}"""
        val p = MbPick.pick(body, q)!!
        assertEquals("rec1", p.recordingMbid)
        assertEquals(1981, p.year)
        assertEquals("r-sub", p.releaseMbid)
    }

    @Test fun musicBrainzSinDuracionExigeDisco() {
        val body = """{"recordings":[{"id":"r","score":100,"title":"Ceremony","artist-credit":[{"name":"New Order"}],
          "releases":[{"id":"x","title":"Otro disco","status":"Official"}]}]}"""
        assertNull(MbPick.pick(body, q))
    }

    @Test fun musicBrainzSelloYPortada() {
        val (label, front) = MbPick.parseRelease("""{"label-info":[{"label":{"name":"Factory"}}],"cover-art-archive":{"front":true}}""")!!
        assertEquals("Factory", label)
        assertTrue(front)
        val info = MbInfo(io.github.dacstage.model.TrackDetails(releaseMbid = "abc"), hasFrontCover = true)
        assertEquals("https://coverartarchive.org/release/abc/front-1200", info.coverUrls.first())
        assertTrue(MbInfo(io.github.dacstage.model.TrackDetails(releaseMbid = "abc")).coverUrls.isEmpty())
    }
}
