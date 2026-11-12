package io.github.dacstage.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgressStampTest {
    private val base = HostState(
        status = PlaybackStatus.PLAYING,
        track = TrackInfo(title = "t", durationMs = 200_000),
        progress = Progress(10_000, 1f),
        progressStampMs = 1_000,
    )

    @Test fun avanzaHastaElEnvio() {
        assertEquals(10_350L, base.progressAt(1_350).progress!!.positionMs)
    }

    @Test fun enPausaOSinSelloNoCambia() {
        assertEquals(10_000L, base.copy(status = PlaybackStatus.PAUSED).progressAt(5_000).progress!!.positionMs)
        assertEquals(10_000L, base.copy(progressStampMs = 0).progressAt(5_000).progress!!.positionMs)
    }

    @Test fun noPasaDeLaDuracion() {
        assertEquals(200_000L, base.progressAt(10_000_000).progress!!.positionMs)
    }

    @Test fun elSelloNoViaja() {
        val json = io.github.dacstage.network.StateCodec.encode(base)
        assertEquals(false, json.containsKey("progressStampMs"))
    }
}
