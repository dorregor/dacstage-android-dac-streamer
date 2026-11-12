package io.github.dacstage.ui

import io.github.dacstage.model.Spec
import io.github.dacstage.model.SpecSource
import io.github.dacstage.model.TechSpecs
import io.github.dacstage.ui.common.ChipKind
import io.github.dacstage.ui.common.TechFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TechFormatTest {
    @Test fun sampleRates() {
        assertEquals("96 kHz", TechFormat.sampleRate(96_000))
        assertEquals("44.1 kHz", TechFormat.sampleRate(44_100))
        assertEquals("48 kHz", TechFormat.sampleRate(48_000))
        assertEquals("352.8 kHz", TechFormat.sampleRate(352_800))
        assertEquals("88.2 kHz", TechFormat.sampleRate(88_200))
    }

    @Test fun bitsAndBitrate() {
        assertEquals("24 bit", TechFormat.bitDepth(24))
        assertEquals("1411 kbps", TechFormat.bitrate(1411))
    }

    @Test fun noDataNoChips() {
        assertTrue(TechFormat.chips(TechSpecs(), null).isEmpty())
    }

    @Test fun invalidValuesAreNotData() {
        val s = TechSpecs(
            sampleRateHz = Spec(0, SpecSource.SESSION_METADATA),
            bitDepth = Spec(-1, SpecSource.SESSION_METADATA),
            codec = Spec("  ", SpecSource.SESSION_METADATA),
        )
        assertTrue(TechFormat.chips(s, 0).isEmpty())
    }

    @Test fun onlyExistingFieldsProduceChips() {
        val chips = TechFormat.chips(TechSpecs(bitDepth = Spec(24, SpecSource.UAPP_QUEUE_FILE)), null)
        assertEquals(listOf("24 bit"), chips.map { it.label })
        assertEquals(SpecSource.UAPP_QUEUE_FILE, chips[0].source)
    }

    @Test fun bitsUsbNoSonChipDeLaCancion() {
        // Caso real: archivo de 16 bit que UAPP envía al SMSL en 32 bit. El chip dice los bits del archivo.
        val usb = Spec(32, SpecSource.DAC_DESCRIPTOR)
        assertEquals(
            listOf("FLAC", "44.1 kHz", "16 bit"),
            TechFormat.chips(
                TechSpecs(codec = Spec("FLAC", SpecSource.UAPP_LOG), bitDepth = Spec(16, SpecSource.UAPP_QUEUE_FILE), usbBitDepth = usb),
                44_100,
            ).map { it.label },
        )
        // Sin bits del archivo no se muestra ninguno (los del USB no los sustituyen).
        assertEquals(
            listOf("FLAC", "44.1 kHz"),
            TechFormat.chips(TechSpecs(codec = Spec("FLAC", SpecSource.UAPP_LOG), usbBitDepth = usb), 44_100).map { it.label },
        )
    }

    @Test fun dacRateWinsOverQueueRate() {
        val s = TechSpecs(
            sampleRateHz = Spec(44_100, SpecSource.UAPP_QUEUE_FILE),
            bitDepth = Spec(24, SpecSource.UAPP_QUEUE_FILE),
            codec = Spec("FLAC", SpecSource.SESSION_METADATA),
        )
        val chips = TechFormat.chips(s, 96_000)
        assertEquals(listOf("FLAC", "96 kHz", "24 bit"), chips.map { it.label })
        assertEquals(SpecSource.DAC_CLOCK, chips.first { it.kind == ChipKind.SAMPLE_RATE }.source)
    }

    @Test fun queueRateUsedWithoutDac() {
        val s = TechSpecs(sampleRateHz = Spec(44_100, SpecSource.UAPP_QUEUE_FILE))
        assertEquals(listOf("44.1 kHz"), TechFormat.chips(s, null).map { it.label })
    }

    @Test fun timeAndDb() {
        assertEquals("1:23", TechFormat.time(83_000))
        assertEquals("0:00", TechFormat.time(-5))
        assertEquals("1:02:03", TechFormat.time(3_723_000))
        assertEquals("−23 dB", TechFormat.db(-23f))
        assertEquals("−23.5 dB", TechFormat.db(-23.5f))
        assertEquals("0 dB", TechFormat.db(0.02f))
        assertEquals("3 dB", TechFormat.db(3f))
    }
}
