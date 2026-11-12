package io.github.dacstage.players.uapp

import io.github.dacstage.players.uapp.UappQueueSync.Reason
import io.github.dacstage.players.uapp.UappQueueSync.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UappQueueSyncTest {

    private fun items(vararg titles: String) = titles.mapIndexed { i, t -> UappQueueEntry(index = i, title = t, id = "${1000 + i}") }

    private val q = items("Alpha", "Beta", "Gamma", "Beta", "Delta")

    @Test
    fun posIndexMatchingTitleIsInSync() {
        assertEquals(Result.InSync(2), UappQueueSync.resolve(q, UappPosition(0, 2, 5), null, "Gamma"))
    }

    @Test
    fun sessionTitleWithTrackNumberPrefixMatches() {
        // Caso real: la sesión de UAPP publica "10. Familia Garfield"; el XML, "Familia Garfield" con TrackNr=10.
        val album = listOf("Refugio I", "Bonva", "Familia Garfield").mapIndexed { i, t ->
            UappQueueEntry(index = i, title = t, trackNr = listOf(1, 9, 10)[i])
        }
        assertEquals(Result.InSync(2), UappQueueSync.resolve(album, UappPosition(0, 2, 3), null, "10. Familia Garfield"))
        // El prefijo solo vale si coincide con el TrackNr.
        assertEquals(
            Result.OutOfSync(Reason.TITLE_MISMATCH),
            UappQueueSync.resolve(album, UappPosition(0, 2, 3), null, "7. Familia Garfield"),
        )
    }

    @Test
    fun totalMismatchIsOutOfSync() {
        assertEquals(Result.OutOfSync(Reason.TOTAL_MISMATCH), UappQueueSync.resolve(q, UappPosition(0, 2, 6), null, "Gamma"))
    }

    @Test
    fun titleNotInQueueIsOutOfSync() {
        assertEquals(Result.OutOfSync(Reason.TITLE_MISMATCH), UappQueueSync.resolve(q, UappPosition(0, 2, 5), null, "Omega"))
    }

    @Test
    fun noSessionTitleIsOutOfSync() {
        assertEquals(Result.OutOfSync(Reason.NO_SESSION_TITLE), UappQueueSync.resolve(q, UappPosition(0, 2, 5), null, null))
    }

    @Test
    fun stalePosIndexDeducedByUniqueTitle() {
        // PosA aún dice 1 pero la sesión ya va por "Delta".
        assertEquals(Result.InSync(4), UappQueueSync.resolve(q, UappPosition(0, 1, 5), null, "Delta"))
    }

    @Test
    fun repeatedTitleUsesNearestToHintOrPos() {
        assertEquals(Result.InSync(3), UappQueueSync.resolve(q, UappPosition(0, 2, 5), null, "Beta", hintIndex = 3))
        assertEquals(Result.InSync(1), UappQueueSync.resolve(q, UappPosition(0, 0, 5), null, "Beta"))
        assertEquals(Result.OutOfSync(Reason.AMBIGUOUS), UappQueueSync.resolve(q, UappPosition(0, 2, 5), null, "Beta"))
    }

    @Test
    fun xmlCurrentItemIsFallbackWithoutPos() {
        assertEquals(Result.InSync(0), UappQueueSync.resolve(q, null, 0, "alpha"))
    }

    @Test
    fun emptyQueue() {
        assertEquals(Result.Empty, UappQueueSync.resolve(emptyList(), null, null, "x"))
    }

    @Test
    fun titleNormalization() {
        assertTrue(UappQueueSync.titleMatches("Song (Remastered 2011)", "Song - Remastered 2011"))
        assertTrue(UappQueueSync.titleMatches("Café  del Mar", "café del mar"))
        assertTrue(UappQueueSync.titleMatches("Track [Live]", "Track"))
        assertFalse(UappQueueSync.titleMatches("Track One", "Track Two"))
        assertFalse(UappQueueSync.titleMatches(null, "x"))
        assertFalse(UappQueueSync.titleMatches("(x)", "(y)"))
    }
}
