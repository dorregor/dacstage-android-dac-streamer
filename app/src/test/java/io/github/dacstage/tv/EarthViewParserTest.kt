package io.github.dacstage.tv

import io.github.dacstage.enrich.TrackQuery
import io.github.dacstage.model.QueueItem
import io.github.dacstage.model.TrackInfo
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EarthViewParserTest {

    @Test fun catalogKeepsValidSlugsAndColors() {
        val c = EarthViewParser.catalog(
            """[{"slug":"sonora-mexico-11610","primaryColor":[173,155,81,255],"hue":48},
               {"slug":"Bad Slug!"},{"nope":1},{"slug":"tata-province-morocco-14090"}]""",
        )
        assertEquals(listOf("sonora-mexico-11610", "tata-province-morocco-14090"), c.map { it.slug })
        assertEquals(0xFFAD9B51.toInt(), c[0].primaryColor)
        assertNull(c[1].primaryColor)
        assertTrue(EarthViewParser.catalog("not json").isEmpty())
    }

    @Test fun photoMetadata() {
        val entry = EarthCatalogEntry("sonora-mexico-11610", null)
        val p = EarthViewParser.photo(
            """{"id":"11610","name":"Sonora, Mexico","country":"Mexico","region":"Sonora",
               "attribution":"©2019 CNES / Airbus","photoUrl":"https://www.gstatic.com/prettyearth/assets/full/11610.jpg",
               "primaryColor":[173,155,81,255]}""",
            entry,
        )!!
        assertEquals("11610", p.id)
        assertEquals("Sonora, Mexico", p.place)
        assertEquals("©2019 CNES / Airbus", p.attribution)
        assertEquals("https://www.gstatic.com/prettyearth/assets/full/11610.jpg", p.imageUrl)
    }

    @Test fun imageOnlyFromGstatic() {
        val entry = EarthCatalogEntry("x-y-42", null)
        // URL ajena o ausente: se reconstruye por id; id ausente: se toma del slug.
        val p = EarthViewParser.photo("""{"photoUrl":"https://evil.example/a.jpg","region":"R","country":"C"}""", entry)!!
        assertEquals("https://www.gstatic.com/prettyearth/assets/full/42.jpg", p.imageUrl)
        assertEquals("R, C", p.place)
        assertNull(p.name)
        assertEquals(
            "https://www.gstatic.com/prettyearth/assets/full/7.jpg",
            EarthViewParser.normalizeImageUrl("//www.gstatic.com/prettyearth/assets/full/7.jpg"),
        )
        assertNull(EarthViewParser.photo("""{"name":"x"}""", EarthCatalogEntry("no-id-here", null)))
    }

    @Test fun pickAvoidsRecent() {
        val entries = listOf(EarthCatalogEntry("a-1", null), EarthCatalogEntry("b-2", null))
        repeat(20) { assertEquals("b-2", EarthViewParser.pick(entries, listOf("a-1"), Random(it))!!.slug) }
        assertNotNull(EarthViewParser.pick(entries, listOf("a-1", "b-2")))
        assertNull(EarthViewParser.pick(emptyList(), emptyList()))
    }

    @Test fun queueItemQueryMatchesPlayingTrack() {
        val item = QueueItem("uapp:3:1", "Holy, Holy", "Geordie Greep", "The New Sound", 363_000)
        val playing = TrackInfo("Holy, Holy", "Geordie Greep", "The New Sound", 363_400)
        assertEquals(TrackQuery.from(playing)!!.cacheKey, TrackQuery.from(item)!!.cacheKey)
        assertNull(TrackQuery.from(QueueItem("k", title = "Sin artista")))
    }
}
