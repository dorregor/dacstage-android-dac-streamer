package io.github.dacstage.dac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VolumeSanityTest {

    private fun decide(min: Int, max: Int, res: Int, q: DacQuirks = DacQuirks.NONE) =
        VolumeSanity.decide(RawVolumeRange(min, max, res), q)

    @Test
    fun standardRangeIsDb() {
        val d = decide(-127 * 256, 0, 256)
        assertEquals(DacVolumeMode.HARDWARE_DB, d.mode)
        val s = d.scale as VolumeScale.Db256
        assertEquals(-127f, s.minDb)
        assertEquals(0f, s.maxDb)
        assertEquals(1f, s.stepDb)
        assertNull(s.levelsDb)
        assertEquals(Float.NEGATIVE_INFINITY, s.toDb(-32768))
    }

    @Test
    fun resZeroBecomesOne() {
        assertEquals(1, decide(-100 * 256, 0, 0).raw!!.res)
    }

    @Test
    fun minAboveMaxIsFixedLikeLinux() {
        // min −20 dB, max −30 dB → se asume max = 0 dB.
        val d = decide(-20 * 256, -30 * 256, 256)
        assertEquals(DacVolumeMode.HARDWARE_DB, d.mode)
        assertEquals(-20f, d.scale!!.minDb)
        assertEquals(0f, d.scale!!.maxDb)
    }

    @Test
    fun bogusRangesHaveNoDb() {
        assertEquals(DacVolumeMode.STEPS, decide(256, 12 * 256, 256).mode) // ambos positivos
        assertEquals(DacVolumeMode.STEPS, decide(-127 * 256, -96 * 256, 256).mode) // max ≤ −96 dB
        assertEquals(DacVolumeMode.STEPS, decide(0, 50, 1).mode) // 0,2 dB en total
        assertEquals(DacVolumeMode.STEPS, decide(0, 1000, 1).mode)
        assertNull(decide(0, 50, 1).scale)
        assertEquals(DacVolumeMode.UNAVAILABLE, VolumeSanity.decide(null, DacQuirks.NONE).mode)
    }

    @Test
    fun essUnitsAreNotDb() {
        val q = DacQuirkTable.forDevice(0x0495, 0x3042)
        val d = decide(-32768, 0, 1, q)
        assertEquals(RawVolumeRange(-128, 0, 1), d.raw)
        assertEquals(DacVolumeMode.STEPS, d.mode)
    }

    @Test
    fun minOverrideNeverLowersReportedMinimum() {
        val q = DacQuirkTable.forDevice(0x31B2, 0x0111) // JU Jiu −10880
        assertEquals(-10880 / 256f, decide(-32768, 0, 256, q).scale!!.minDb)
        assertEquals(-40f, decide(-40 * 256, 0, 256, q).scale!!.minDb)
        assertEquals(-11264, DacQuirkTable.forDevice(0x12D1, 0x3A07).minRawOverride)
        assertEquals(-256, DacQuirkTable.forDevice(0x0D8C, 0x0103).minRawOverride)
    }

    @Test
    fun quirkTableLookups() {
        assertEquals(DacQuirks.NONE, DacQuirkTable.forDevice(0x1234, 0x5678))
        assertTrue(DacQuirkTable.forDevice(0x152A, 0x85DD).trustedVolume)
        assertFalse(DacQuirkTable.forDevice(0x152A, 0x1234).trustedVolume)
        assertEquals(20L, DacQuirkTable.forDevice(0x2FC6, 0xF06B).controlSpacingMs)
        assertEquals(2L, DacQuirkTable.forDevice(0x2FC6, 0xF0B5).controlSpacingMs)
        assertEquals(2L, DacQuirkTable.forDevice(0x2FC6, 0xF0B7).controlSpacingMs)
        assertEquals(2L, DacQuirkTable.forDevice(0x2FC6, 0x0001).controlSpacingMs)
        val samsung = DacQuirkTable.forDevice(0x04E8, 0xA051)
        assertTrue(samsung.skipClockSelector)
        assertEquals(5L, samsung.controlSpacingMs)
        assertTrue(DacQuirkTable.forDevice(0x30BE, 0x0101).ignoreControlErrors)
        assertTrue(DacQuirkTable.forDevice(0x84EF, 0x0082).linearVolume)
        assertTrue(DacQuirkTable.forDevice(0x21B4, 0x0081).skipSampleRate)
        for (pid in listOf(0x3FD2, 0x3FD3, 0x3FD4)) assertTrue(DacQuirkTable.forDevice(0x2A39, pid).fixedVolume)
    }

    @Test
    fun dragonFlyTableMatchesLinuxEndpoints() {
        val t = VolumeScale.Table.dragonFly()
        assertEquals(-53f, t.toDb(0)!!, 1e-4f)
        assertEquals(-49.70f, t.toDb(1)!!, 1e-4f)
        assertEquals(-25.60f, t.toDb(14)!!, 1e-4f)
        assertEquals(-4.41f, t.toDb(41)!!, 1e-4f)
        assertEquals(0f, t.toDb(50)!!, 1e-4f)
        assertNull(t.toDb(51))
        // Monótona.
        (0 until 50).forEach { assertTrue(t.toDb(it)!! < t.toDb(it + 1)!!) }
        assertEquals(0, t.toRaw(-53f))
        assertNull(t.toRaw(-60f))
    }

    @Test
    fun nextLevelPrefersUpToOneDbElseNearest() {
        val levels = VolumeScale.Table.dragonFly().levelsDb
        // Desde −53: el siguiente nivel está a 3,3 dB → se va a él.
        assertEquals(-49.70f, DacVolumeManager.nextLevel(levels, -53f, +1)!!, 1e-4f)
        // Zona densa (≈0,49 dB/paso): el más lejano a ≤ 1 dB.
        val from = levels[41]
        val up = DacVolumeManager.nextLevel(levels, from, +1)!!
        assertTrue(up > from && up <= from + 1f + 1e-3f)
        assertEquals(levels[43], up, 1e-4f)
        assertNull(DacVolumeManager.nextLevel(levels, 0f, +1))
        assertNull(DacVolumeManager.nextLevel(levels, -53f, -1))
        assertEquals(-53f, DacVolumeManager.nextLevel(levels, -49.7f, -1)!!, 1e-4f)
    }
}
