package io.github.dacstage.ui

import io.github.dacstage.model.PlaybackStatus
import io.github.dacstage.model.Progress
import io.github.dacstage.ui.common.ProgressAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressAnchorTest {
    private fun anchor(status: PlaybackStatus, speed: Float = 1f, dur: Long? = 200_000) =
        ProgressAnchor.stamp(Progress(10_000, speed), status, dur, nowMs = 1_000)!!

    @Test fun extrapolatesWhilePlaying() {
        assertEquals(12_500, anchor(PlaybackStatus.PLAYING).positionAt(3_500))
    }

    @Test fun frozenWhenNotPlaying() {
        assertEquals(10_000, anchor(PlaybackStatus.PAUSED).positionAt(60_000))
        assertEquals(10_000, anchor(PlaybackStatus.BUFFERING).positionAt(60_000))
    }

    @Test fun respectsSpeed() {
        assertEquals(14_000, anchor(PlaybackStatus.PLAYING, speed = 2f).positionAt(3_000))
    }

    @Test fun clampsToDurationAndClockGoingBack() {
        val a = anchor(PlaybackStatus.PLAYING)
        assertEquals(200_000, a.positionAt(10_000_000))
        assertEquals(10_000, a.positionAt(0))
    }

    @Test fun fraction() {
        assertEquals(0.05f, anchor(PlaybackStatus.PAUSED).fractionAt(0)!!, 1e-6f)
        assertNull(anchor(PlaybackStatus.PLAYING, dur = null).fractionAt(5_000))
    }

    @Test fun noProgressNoAnchor() {
        assertNull(ProgressAnchor.stamp(null, PlaybackStatus.PLAYING, 1000, 0))
    }
}
