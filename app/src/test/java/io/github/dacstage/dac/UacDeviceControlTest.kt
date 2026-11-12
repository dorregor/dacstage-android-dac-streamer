package io.github.dacstage.dac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UacDeviceControlTest {

    private val smsl = DacQuirkTable.forDevice(0x152A, 0x85DD)
    private val unknown = DacQuirks.NONE

    private fun control(dev: FakeUacDevice, topo: Uac2Topology = topology(), quirks: DacQuirks = unknown) =
        UacDeviceControl(topo, quirks, dev.clock)

    private inline fun <reified T : Throwable> assertThrows(block: () -> Unit): T {
        try { block() } catch (t: Throwable) { if (t is T) return t; throw t }
        fail("se esperaba ${T::class.simpleName}")
        throw AssertionError()
    }

    // --- SMSL: sin cambios de comportamiento ---

    @Test
    fun smslIsTrustedAndNeverProbed() {
        val dev = FakeUacDevice()
        val c = control(dev, quirks = smsl)
        val m = c.ensureVolumeModel(dev)
        assertEquals(DacVolumeMode.HARDWARE_DB, m.mode)
        assertEquals(VolumeProbeResult.SKIPPED, c.probeResult)
        assertTrue("sin escrituras al conectar", dev.sets.isEmpty())
        val info = c.toInfo("SMSL", 0x152A, 0x85DD, emptyList())!!
        assertEquals(-127f, info.minDb)
        assertEquals(0f, info.maxDb)
        assertEquals(1f, info.stepDb)
        assertNull(info.levelsDb)
        assertTrue(info.hasMute)
        assertEquals(-30f, c.readVolumeDb(dev))
        assertEquals(-31f, c.setVolumeDb(dev, -31f))
        assertEquals(listOf(-31 * 256), dev.volumeSets)
        // Mismas peticiones que antes: canal master, GET_RANGE de un subrango.
        assertTrue(dev.log.all { it.ch == 0 })
        assertEquals(1, dev.log.count { it.request == Uac2Requests.RANGE })
        assertTrue(c.setMute(dev, true))
        assertEquals(96_000, c.readSampleRateHz(dev))
    }

    // --- 1. Canales ---

    @Test
    fun perChannelOnlyVolumeDrivesAllChannelsAndReadsRangeFromFirst() {
        val dev = FakeUacDevice(volumeChannels = setOf(1, 2), muteChannels = setOf(1, 2))
        val c = control(dev, topology(volumeChannels = listOf(1, 2), muteChannels = listOf(1, 2)))
        assertEquals(DacVolumeMode.HARDWARE_DB, c.ensureVolumeModel(dev).mode)
        assertEquals(1, dev.log.first { it.request == Uac2Requests.RANGE }.ch)
        // La prueba solo toca el primer canal y lo restaura.
        assertEquals(-30 * 256, dev.cur[1])
        assertEquals(-30 * 256, dev.cur[2])
        assertTrue(dev.log.filter { it.set }.all { it.ch == 1 })
        assertEquals(-20f, c.setVolumeDb(dev, -20f))
        assertEquals(-20 * 256, dev.cur[1])
        assertEquals(-20 * 256, dev.cur[2])
        assertTrue(c.setMute(dev, true))
        assertEquals(true, dev.mute[1])
        assertEquals(true, dev.mute[2])
    }

    // --- 2. GET_RANGE ---

    @Test
    fun missingRangeRetriesOnceThenUnavailableWithoutInventingDb() {
        val dev = FakeUacDevice(rangeFailures = 99)
        val c = control(dev)
        val m = c.ensureVolumeModel(dev)
        assertEquals(DacVolumeMode.UNAVAILABLE, m.mode)
        assertEquals(2, dev.log.count { it.request == Uac2Requests.RANGE })
        assertTrue(dev.clock.sleeps.contains(UacDeviceControl.RANGE_RETRY_DELAY_MS))
        assertTrue(dev.sets.isEmpty())
        val info = c.toInfo("x", 1, 2, emptyList())!!
        assertNull(info.minDb); assertNull(info.maxDb); assertNull(info.stepDb)
        val e = assertThrows<DacVolumeNotControllableException> { c.readVolumeDb(dev) }
        assertEquals(DacVolumeMode.UNAVAILABLE, e.mode)
        assertThrows<DacVolumeNotControllableException> { c.setVolumeDb(dev, -40f) }
        // Al refrescar se reintenta.
        dev.rangeFailures = 0
        assertEquals(DacVolumeMode.HARDWARE_DB, c.ensureVolumeModel(dev, retryUnavailable = true).mode)
    }

    @Test
    fun rangeRetrySucceedsOnSecondAttempt() {
        val dev = FakeUacDevice(rangeFailures = 1)
        val c = control(dev, quirks = smsl)
        assertEquals(DacVolumeMode.HARDWARE_DB, c.ensureVolumeModel(dev).mode)
        assertEquals(-127f, c.toInfo("x", 1, 2, emptyList())!!.minDb)
    }

    @Test
    fun onlyFirstSubrangeIsUsed() {
        val bytes = byteArrayOf(0x02, 0x00, 0x00, 0x81.toByte(), 0x00, 0xC4.toByte(), 0x00, 0x01, 0x00, 0xC4.toByte(), 0x00, 0x00, 0x80.toByte(), 0x00)
        assertEquals(RawVolumeRange(-127 * 256, -60 * 256, 256), DbScaling.parseFirstSubrangeRaw(bytes, bytes.size))
        assertNull(DbScaling.parseFirstSubrangeRaw(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0), 8))
        assertNull(DbScaling.parseFirstSubrangeRaw(byteArrayOf(1, 0, 0), 3))
    }

    @Test
    fun uac1UsesGetMinMaxRes() {
        val dev = FakeUacDevice(uac1 = true, range = RawVolumeRange(-100 * 256, 0, 128))
        val c = control(dev, topology(uacVersion = 1, clockEntityId = null, clockSources = emptySet()))
        assertEquals(DacVolumeMode.HARDWARE_DB, c.ensureVolumeModel(dev).mode)
        val reqs = dev.log.filter { !it.set }.map { it.request }.toSet()
        assertTrue(reqs.containsAll(setOf(Uac2Requests.UAC1_GET_MIN, Uac2Requests.UAC1_GET_MAX, Uac2Requests.UAC1_GET_RES)))
        assertFalse(Uac2Requests.RANGE in reqs)
        assertEquals(-100f, c.toInfo("x", 1, 2, emptyList())!!.minDb)
        assertEquals(-30f, c.readVolumeDb(dev))
        // UAC1: la frecuencia está en el endpoint de streaming → no se lee.
        val before = dev.log.size
        assertThrows<DacUnsupportedException> { c.readSampleRateHz(dev) }
        assertEquals(before, dev.log.size)
    }

    // --- 3. Rangos diminutos / DragonFly ---

    @Test
    fun tinyRangeIsStepsWithoutDb() {
        val dev = FakeUacDevice(range = RawVolumeRange(0, 50, 1), initialRaw = 25)
        val c = control(dev)
        val m = c.ensureVolumeModel(dev)
        assertEquals(DacVolumeMode.STEPS, m.mode)
        assertEquals(RawVolumeRange(0, 50, 1), m.raw)
        assertTrue(dev.sets.isEmpty())
        val info = c.toInfo("x", 1, 2, emptyList())!!
        assertNull(info.minDb); assertNull(info.maxDb)
        assertThrows<DacVolumeNotControllableException> { c.readVolumeDb(dev) }
    }

    @Test
    fun dragonFlyUsesLinuxTableAndNeverReadsRate() {
        val q = DacQuirkTable.forDevice(0x21B4, 0x0081)
        val dev = FakeUacDevice(range = RawVolumeRange(0, 50, 1), initialRaw = 25)
        val c = control(dev, quirks = q)
        assertEquals(DacVolumeMode.HARDWARE_DB, c.ensureVolumeModel(dev).mode)
        val info = c.toInfo("DragonFly", 0x21B4, 0x0081, emptyList())!!
        assertEquals(-53f, info.minDb!!, 1e-4f)
        assertEquals(0f, info.maxDb!!, 1e-4f)
        assertNull(info.stepDb)
        assertEquals(51, info.levelsDb!!.size)
        assertEquals(-49.70f, info.levelsDb!![1], 1e-4f)
        assertEquals(25, dev.cur[0]) // prueba restaurada
        // Nunca más alto que lo pedido: −49 dB → nivel 1 (−49,7 dB).
        assertEquals(-49.70f, c.setVolumeDb(dev, -49f), 1e-4f)
        assertEquals(1, dev.cur[0])
        val before = dev.log.size
        assertThrows<DacUnsupportedException> { c.readSampleRateHz(dev) }
        assertEquals(before, dev.log.size)
    }

    @Test
    fun dragonFlyNarrowVariantIsSteps() {
        val dev = FakeUacDevice(range = RawVolumeRange(0, 53, 1), initialRaw = 20)
        val c = control(dev, quirks = DacQuirkTable.forDevice(0x21B4, 0x0081))
        assertEquals(DacVolumeMode.STEPS, c.ensureVolumeModel(dev).mode)
        assertTrue(dev.sets.isEmpty())
    }

    // --- 4. Quirks ---

    @Test
    fun quark2MinimumOverride() {
        val dev = FakeUacDevice(range = RawVolumeRange(-32768, 0, 128))
        val c = control(dev, quirks = DacQuirkTable.forDevice(0x3302, 0x12DB))
        c.ensureVolumeModel(dev)
        val info = c.toInfo("Quark2", 0x3302, 0x12DB, emptyList())!!
        assertEquals(-14208 / 256f, info.minDb!!, 0f)
        assertEquals(-55.5f, info.minDb!!, 0f)
        assertThrows<IllegalArgumentException> { c.setVolumeDb(dev, -60f) }
    }

    @Test
    fun linearVolumeDeviceConvertsAmplitudeToDb() {
        val dev = FakeUacDevice(range = RawVolumeRange(0, 4096, 1), initialRaw = 2048)
        val c = control(dev, quirks = DacQuirkTable.forDevice(0x05FC, 0x0231))
        assertEquals(DacVolumeMode.HARDWARE_DB, c.ensureVolumeModel(dev).mode)
        assertEquals(-6.0206f, c.readVolumeDb(dev), 1e-3f)
        val info = c.toInfo("JBL", 0x05FC, 0x0231, emptyList())!!
        assertEquals(0f, info.maxDb!!, 1e-6f)
        assertEquals(-72.2472f, info.minDb!!, 1e-3f) // 1/4096
        assertNotNull(info.levelsDb)
        val confirmed = c.setVolumeDb(dev, -12f)
        assertTrue("nunca más alto que lo pedido: $confirmed", confirmed <= -12f)
        assertEquals(1028, dev.cur[0])
        dev.cur[0] = 0
        assertEquals(Float.NEGATIVE_INFINITY, c.readVolumeDb(dev))
    }

    @Test
    fun mvSiliconIsLinearOnlyFor4096Range() {
        val q = DacQuirkTable.forDevice(0x1234, 0x5678, "MV-SILICON")
        assertEquals(DacVolumeMode.HARDWARE_DB, VolumeSanity.decide(RawVolumeRange(0, 4096, 1), q).mode)
        assertTrue(VolumeSanity.decide(RawVolumeRange(0, 4096, 1), q).scale is VolumeScale.Linear)
        assertEquals(DacVolumeMode.STEPS, VolumeSanity.decide(RawVolumeRange(0, 100, 1), q).mode)
    }

    @Test
    fun fixedVolumeQuirkNeverTouchesVolume() {
        val dev = FakeUacDevice()
        val c = control(dev, quirks = DacQuirkTable.forDevice(0x2A39, 0x3FD4))
        assertEquals(DacVolumeMode.FIXED_ON_DEVICE, c.ensureVolumeModel(dev).mode)
        assertTrue(dev.log.none { it.cs == Uac2Requests.FU_VOLUME })
    }

    @Test
    fun ignoreControlErrorsRetriesOnce() {
        val hel = DacQuirkTable.forDevice(0x30BE, 0x0101)
        val dev = FakeUacDevice()
        val c = control(dev, quirks = hel)
        dev.failNext = 1
        assertFalse(c.readMute(dev))
        val dev2 = FakeUacDevice()
        val c2 = control(dev2)
        dev2.failNext = 1
        val e = assertThrows<DacAccessException> { c2.readMute(dev2) }
        assertTrue(e.access is DacAccess.Failed) // nunca "desconectado"
    }

    // --- 5/6. Volumen pegado y resolución real ---

    @Test
    fun stickyVolumeIsFixedOnDeviceAndNeverSetAboveOriginal() {
        val original = -30 * 256
        val dev = FakeUacDevice(sticky = true, initialRaw = original)
        val c = control(dev)
        assertEquals(DacVolumeMode.FIXED_ON_DEVICE, c.ensureVolumeModel(dev).mode)
        assertEquals(VolumeProbeResult.STICKY, c.probeResult)
        assertTrue(dev.volumeSets.isNotEmpty())
        assertTrue("nunca por encima del original: ${dev.volumeSets}", dev.volumeSets.all { it <= original })
        assertEquals(original, dev.volumeSets.last()) // restaurado
        assertNull(c.toInfo("x", 1, 2, emptyList())!!.minDb)
        assertThrows<DacVolumeNotControllableException> { c.setVolumeDb(dev, -40f) }
    }

    @Test
    fun workingVolumeProbeStepsDownOnceAndRestores() {
        val original = -30 * 256
        val dev = FakeUacDevice(initialRaw = original)
        val c = control(dev)
        assertEquals(DacVolumeMode.HARDWARE_DB, c.ensureVolumeModel(dev).mode)
        assertEquals(VolumeProbeResult.MOVES, c.probeResult)
        assertEquals(listOf(original - 256, original), dev.volumeSets)
        assertEquals(original, dev.cur[0])
        // Una sola vez por conexión.
        c.ensureVolumeModel(dev)
        c.readVolumeDb(dev)
        assertEquals(2, dev.volumeSets.size)
    }

    @Test
    fun coarseResolutionIsDoubledSteppingDown() {
        val min = -127 * 256
        val original = min + 48 * 512
        val dev = FakeUacDevice(initialRaw = original, alignTo = 512)
        val c = control(dev)
        c.ensureVolumeModel(dev)
        assertEquals(VolumeProbeResult.MOVES, c.probeResult)
        val info = c.toInfo("x", 1, 2, emptyList())!!
        assertEquals(2f, info.stepDb)
        assertNotNull(info.levelsDb)
        assertTrue(dev.volumeSets.all { it <= original })
        assertEquals(original, dev.cur[0])
    }

    @Test
    fun probeSkippedAtMinimumOrSilence() {
        val dev = FakeUacDevice(initialRaw = -127 * 256)
        val c = control(dev)
        assertEquals(DacVolumeMode.HARDWARE_DB, c.ensureVolumeModel(dev).mode)
        assertEquals(VolumeProbeResult.SKIPPED, c.probeResult)
        assertTrue(dev.sets.isEmpty())

        val dev2 = FakeUacDevice(initialRaw = -32768)
        val c2 = control(dev2)
        c2.ensureVolumeModel(dev2)
        assertEquals(VolumeProbeResult.SKIPPED, c2.probeResult)
        assertTrue(dev2.sets.isEmpty())
    }

    @Test
    fun readOnlyVolumeIsFixedWithoutProbing() {
        val dev = FakeUacDevice()
        val c = control(dev, topology(volumeWritable = false))
        assertEquals(DacVolumeMode.FIXED_ON_DEVICE, c.ensureVolumeModel(dev).mode)
        assertTrue(dev.log.isEmpty())
    }

    // --- 8. Frecuencia: Clock Selector solo lectura ---

    @Test
    fun clockSelectorIsOnlyRead() {
        val dev = FakeUacDevice(selectorPin = 2, sampleRates = mapOf(41 to 48_000L, 42 to 44_100L))
        val topo = topology(clockEntityId = 40, clockSources = setOf(41, 42), clockSelectors = mapOf(40 to listOf(41, 42)))
        val c = control(dev, topo, smsl)
        assertEquals(44_100, c.readSampleRateHz(dev))
        assertTrue(dev.log.any { !it.set && it.entity == 40 && it.cs == Uac2Requests.CX_CLOCK_SELECTOR })
        assertTrue("nunca SET: ${dev.sets}", dev.sets.isEmpty())
    }

    @Test
    fun samsungNeverTouchesClockSelector() {
        val dev = FakeUacDevice(selectorPin = 2, sampleRates = mapOf(41 to 48_000L, 42 to 44_100L))
        val topo = topology(clockEntityId = 40, clockSources = setOf(41, 42), clockSelectors = mapOf(40 to listOf(41, 42)))
        val c = control(dev, topo, DacQuirkTable.forDevice(0x04E8, 0xA051))
        assertThrows<DacAccessException> { c.readSampleRateHz(dev) }
        assertTrue(dev.log.none { it.entity == 40 })
    }

    // --- 7. Espaciado ---

    @Test
    fun pacingSpacesControlTransfers() {
        val dev = FakeUacDevice()
        val c = control(dev, quirks = DacQuirkTable.forDevice(0x04E8, 0xA051))
        c.ensureVolumeModel(dev)
        c.readVolumeDb(dev)
        c.setVolumeDb(dev, -35f)
        c.readMute(dev)
        assertTrue(dev.log.size >= 6)
        dev.log.zipWithNext().forEach { (a, b) -> assertTrue("${b.atMs - a.atMs} ms", b.atMs - a.atMs >= 5) }
    }

    @Test
    fun noPacingByDefault() {
        val dev = FakeUacDevice()
        val c = control(dev, quirks = smsl)
        c.ensureVolumeModel(dev)
        c.setVolumeDb(dev, -35f)
        assertTrue(dev.clock.sleeps.isEmpty())
    }

    // --- 9. Sin Feature Unit ---

    @Test
    fun noFeatureUnitIsNoneButRateStillReadable() {
        val dev = FakeUacDevice()
        val c = control(dev, topology(featureUnitId = null))
        assertEquals(DacVolumeMode.NONE, c.ensureVolumeModel(dev).mode)
        val info = c.toInfo("Chord", 1, 2, emptyList())!!
        assertEquals(DacVolumeMode.NONE, info.volumeMode)
        assertFalse(info.hasMute)
        assertNull(info.minDb)
        assertEquals(DacVolumeMode.NONE, assertThrows<DacVolumeNotControllableException> { c.readVolumeDb(dev) }.mode)
        assertThrows<DacUnsupportedException> { c.readMute(dev) }
        assertEquals(96_000, c.readSampleRateHz(dev))
        assertTrue(dev.log.none { it.entity == 10 })
    }
}
