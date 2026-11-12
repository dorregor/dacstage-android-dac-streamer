package io.github.dacstage.players.hiby

import io.github.dacstage.players.PositionMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HibyMediaIdTest {

    @Test
    fun extractsTidalTrackId() {
        assertEquals("123456789", HibyMediaId.tidalTrackId("_SONG_/song_3|[tidal][track][id=123456789][uri=tidal://track/123456789]"))
        assertEquals("42", HibyMediaId.tidalTrackId("_SONG_/song_0|[tidal][track][id=42]"))
    }

    @Test
    fun nonTidalOrMalformedIsNull() {
        assertNull(HibyMediaId.tidalTrackId(null))
        assertNull(HibyMediaId.tidalTrackId(""))
        assertNull(HibyMediaId.tidalTrackId("_SONG_/song_3|/storage/emulated/0/Music/a.flac"))
        assertNull(HibyMediaId.tidalTrackId("_SONG_/song_3|[tidal][album][id=555]"))
        assertNull(HibyMediaId.tidalTrackId("_SONG_/song_3|[qobuz][track][id=555]"))
        assertNull(HibyMediaId.tidalTrackId("_SONG_/song_3|[tidal][track][id=]"))
    }
}

class PositionMathTest {
    @Test
    fun extrapolatesWhilePlaying() {
        assertEquals(15_000L, PositionMath.extrapolate(10_000, 1_000, 1f, 6_000, playing = true, durationMs = 200_000))
    }

    @Test
    fun noExtrapolationWhenPausedAndClamped() {
        assertEquals(10_000L, PositionMath.extrapolate(10_000, 1_000, 1f, 6_000, playing = false, durationMs = null))
        assertEquals(12_000L, PositionMath.extrapolate(10_000, 1_000, 1f, 60_000, playing = true, durationMs = 12_000))
    }
}
