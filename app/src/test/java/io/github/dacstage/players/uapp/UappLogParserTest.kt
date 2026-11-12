package io.github.dacstage.players.uapp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UappLogParserTest {
    // Formato real de UAPP.txt (token inventado). Ojo: UAPP a veces pega dos entradas en una línea.
    private val log = """
        2026-11-11 15:27:42.612: fq Dedo chico, Tidal, sr = 48000
        2026-11-11 15:27:42.632: HTTP seek 0 for https://lgf.audio.tidal.com/mediatracks/AAAA/0.flac?token=111~ZmFrZQ==2026:11:11 15:27:42.738 OutputDevice::preparePlayback sendSeek
        2026:11:11 15:27:42.739 USB audio sample rate = 48000
        2026:11:11 15:24:17.307 Prepare: maxPacketSize = 776, minPacketSize = 48 bytes, frame size = 8, i_bufferSizeFrames = 9600, wanted nr transfers = 2
        2026-11-11 15:30:29.867: HTTP seek 0 for https://lgf.audio.tidal.com/mediatracks/BBBB/0.flac?token=222~ZmFrZQ==
    """.trimIndent()

    @Test fun extraeFormatoYTrama() {
        val info = UappLogParser.parse(log)
        assertEquals("FLAC", info.codec)
        assertEquals(8, info.usbFrameBytes)
    }

    @Test fun formatosDistintosNoSeAfirman() {
        val mixed = log + "\n2026-11-11 15:31:00.000: HTTP seek 0 for https://example.com/x/0.mp3?token=1"
        assertNull(UappLogParser.parse(mixed).codec)
    }

    @Test fun contenedorAmbiguoNoDaCodec() {
        assertNull(UappLogParser.codecOf("https://host/a/b/track.mp4"))
        assertEquals("FLAC", UappLogParser.codecOf("https://host/a/0.flac"))
    }

    @Test fun frecuenciaFijadaEnElDac() {
        val info = UappLogParser.parse(log)
        assertEquals(48_000, info.usbSampleRateHz)
        assertEquals(false, info.sampleRateSetFailed)
    }

    @Test fun frecuenciaRechazadaPorElDac() {
        // Caso real del 2026-11-11: EBUSY al fijar 48000 → el DAC siguió en 96000.
        val failed = log + """

            2026:11:11 18:48:07.377 USB audio sample rate = 96000
            2026:11:11 18:48:27.897 OutputDevice::preparePlayback sendSeek done 0xb400006e819fa760
            2026:11:11 18:48:27.898 Couldn't set sample rate: res = -1, val = 48000!
            2026:11:11 18:48:27.898 USB audio sample rate = 48000
            2026:11:11 18:48:27.898 OutputDevice::setActualDeviceSampleRate done 48000
        """.trimIndent()
        val info = UappLogParser.parse(failed)
        assertEquals(48_000, info.usbSampleRateHz)
        assertEquals(true, info.sampleRateSetFailed)
        // Una preparación posterior correcta deja de marcar el fallo.
        val ok = UappLogParser.parse(failed + "\n2026:11:11 18:50:00.000 USB audio sample rate = 96000")
        assertEquals(96_000, ok.usbSampleRateHz)
        assertEquals(false, ok.sampleRateSetFailed)
    }

    @Test fun sinDatos() {
        val info = UappLogParser.parse("nada útil aquí")
        assertNull(info.codec)
        assertNull(info.usbFrameBytes)
    }
}
