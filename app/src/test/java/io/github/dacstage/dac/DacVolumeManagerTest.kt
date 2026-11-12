package io.github.dacstage.dac

import io.github.dacstage.model.CommandError
import io.github.dacstage.model.CommandOrigin
import io.github.dacstage.model.CommandResult
import io.github.dacstage.model.DacControlAvailability
import io.github.dacstage.model.PlaybackStatus
import io.github.dacstage.model.PlayerId
import io.github.dacstage.players.PlayerAdapter
import io.github.dacstage.players.TransportAction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DacVolumeManagerTest {

    private val tv = CommandOrigin.TV

    private fun TestScope.manager(
        dac: FakeDacController,
        settings: FakePolicySettings = FakePolicySettings(),
        player: PlayerAdapter? = null,
    ): DacVolumeManager {
        val m = DacVolumeManager(dac, settings, playerFlow(player), backgroundScope) { testScheduler.currentTime }
        runCurrent()
        return m
    }

    private fun assertError(expected: CommandError, r: CommandResult) {
        assertFalse("se esperaba $expected y fue OK", r.ok)
        assertEquals(expected, r.error)
    }

    @Test
    fun refreshPublishesReadValues() = runTest {
        val dac = FakeDacController(volumeDb = -30f)
        val m = manager(dac)
        val s = m.state.value
        assertTrue(s.connected)
        assertEquals("SMSL USB AUDIO", s.deviceName)
        assertEquals(-30f, s.volumeDb)
        assertEquals(false, s.muted)
        assertEquals(96_000, s.sampleRateHz)
        assertEquals(-127f, s.minDb)
        assertEquals(0f, s.maxDb)
        assertEquals(-10f, s.capDb)
        assertEquals(DacControlAvailability.AVAILABLE, s.control)
        assertTrue(dac.volumeWrites.isEmpty())
    }

    @Test
    fun androidAudioIsNeverTouched() = runTest {
        val dac = FakeDacController(androidAudio = true)
        val settings = FakePolicySettings()
        val player = FakePlayer(PlayerId.GENERIC, PlaybackStatus.PAUSED)
        val m = manager(dac, settings, player)
        assertEquals(DacControlAvailability.ANDROID_AUDIO, m.state.value.control)
        assertNull(m.state.value.volumeDb)
        assertNull(m.state.value.sampleRateHz)
        // Ni con el reproductor en pausa (forzar estaría permitido) se fuerza ni se pide confirmación.
        assertError(CommandError.VOLUME_UNAVAILABLE, m.step(+1, 0, tv))
        assertError(CommandError.VOLUME_UNAVAILABLE, m.toggleMute(tv))
        assertNull(m.pendingConfirmation.value)
        assertEquals(0, dac.forcedClaims)
        assertTrue(dac.volumeWrites.isEmpty() && dac.muteWrites.isEmpty())
        // Si el DAC pasa a otra app (UAPP con su driver), vuelve el control normal.
        dac.androidAudio = false
        assertTrue(m.refresh().ok)
        assertEquals(DacControlAvailability.AVAILABLE, m.state.value.control)
    }

    @Test
    fun defaultCapIsMaxMinusTen() = runTest {
        val dac = FakeDacController(volumeDb = -11f)
        val m = manager(dac)
        assertTrue(m.step(+1, 0, tv).ok)
        assertEquals(-10f, m.state.value.volumeDb)
        advanceTimeBy(1_000)
        assertError(CommandError.VOLUME_ABOVE_CAP, m.step(+1, 0, tv))
        assertEquals(listOf(-10f to false), dac.volumeWrites)
    }

    @Test
    fun configuredCapIsRespected() = runTest {
        val dac = FakeDacController(volumeDb = -20f)
        val m = manager(dac, FakePolicySettings(cap = -20f))
        assertEquals(-20f, m.state.value.capDb)
        assertError(CommandError.VOLUME_ABOVE_CAP, m.step(+1, 0, tv))
        advanceTimeBy(1_000)
        assertTrue(m.step(-1, 0, tv).ok)
        assertEquals(-21f, dac.volumeDb)
    }

    @Test
    fun knobAboveCapIsShownButNotChanged() = runTest {
        val dac = FakeDacController(volumeDb = -5f)
        val m = manager(dac)
        assertEquals(-5f, m.state.value.volumeDb)
        assertTrue(dac.volumeWrites.isEmpty())
        assertError(CommandError.VOLUME_ABOVE_CAP, m.step(+1, 0, tv))
        advanceTimeBy(1_000)
        assertTrue(m.step(-1, 0, tv).ok)
        assertEquals(-6f, m.state.value.volumeDb)
    }

    @Test
    fun hostAbsoluteVolumeRejectsAboveCapAndOutOfRange() = runTest {
        val dac = FakeDacController(volumeDb = -30f)
        val m = manager(dac)
        assertError(CommandError.VOLUME_ABOVE_CAP, m.setVolumeDb(-9f))
        assertError(CommandError.VOLUME_ABOVE_CAP, m.setVolumeDb(-130f))
        assertError(CommandError.VOLUME_ABOVE_CAP, m.setVolumeDb(1f))
        assertError(CommandError.INVALID_ARGUMENT, m.setVolumeDb(Float.NaN))
        assertTrue(dac.volumeWrites.isEmpty())
        assertTrue(m.setVolumeDb(-40f).ok)
        assertEquals(-40f, m.state.value.volumeDb)
        assertEquals(listOf(-40f to false), dac.volumeWrites)
    }

    @Test
    fun onlyUnitStepsAreAccepted() = runTest {
        val dac = FakeDacController()
        val m = manager(dac)
        for (d in listOf(2, 0, -3, 10)) assertError(CommandError.INVALID_ARGUMENT, m.step(d, 0, tv))
        assertTrue(dac.volumeWrites.isEmpty())
    }

    @Test
    fun holdingAcceleratesGraduallyWithCap() = runTest {
        val dac = FakeDacController(volumeDb = -100f)
        val m = manager(dac)
        val start = testScheduler.currentTime
        val accepted = mutableListOf<Long>()
        var limited = 0
        // Tecla mantenida: un evento cada 50 ms durante 2 s (repeatCount creciente).
        for (i in 0 until 40) {
            val r = m.step(+1, i, tv)
            if (r.ok) accepted += testScheduler.currentTime - start
            else { assertEquals(CommandError.RATE_LIMITED, r.error); limited++ }
            advanceTimeBy(50)
        }
        assertTrue(limited > 0)
        // Arranque lento (~4/s).
        assertTrue("al inicio: $accepted", accepted.count { it < 500 } <= 3)
        // Nunca más de 12 en cualquier ventana de 1 s.
        for (t in accepted) assertTrue(accepted.count { it in t until t + 1_000 } <= 12)
        // Ya acelerado: más rápido que al inicio.
        assertTrue("acelerado: $accepted", accepted.count { it in 1_000 until 2_000 } >= 8)
        // Siempre de 1 dB en 1 dB.
        val values = dac.volumeWrites.map { it.first }
        values.zipWithNext().forEach { (a, b) -> assertEquals(1f, b - a, 0f) }
        assertEquals(-100f + accepted.size, dac.volumeDb, 0f)
        // Pulsación nueva tras soltar: aceptada.
        advanceTimeBy(200)
        assertTrue(m.step(-1, 0, tv).ok)
    }

    @Test
    fun loweringCapLowersVolume() = runTest {
        val dac = FakeDacController(volumeDb = -15f)
        val settings = FakePolicySettings()
        val m = manager(dac, settings)
        m.setCapDb(-20f)
        runCurrent()
        assertEquals(-20f, settings.capDb.value)
        assertEquals(-20f, dac.volumeDb)
        assertEquals(-20f, m.state.value.volumeDb)
        assertEquals(-20f, m.state.value.capDb)
    }

    @Test
    fun raisingCapDoesNotRaiseVolume() = runTest {
        val dac = FakeDacController(volumeDb = -30f)
        val m = manager(dac)
        m.setCapDb(-5f)
        runCurrent()
        assertTrue(dac.volumeWrites.isEmpty())
    }

    @Test
    fun playingWithoutConsentNeedsHostConfirmation() = runTest {
        val dac = FakeDacController(volumeDb = -30f, busy = true)
        val player = FakePlayer(status = PlaybackStatus.PLAYING)
        val m = manager(dac, player = player)
        // El refresh inicial no fuerza ni inventa: nada leído.
        assertNull(m.state.value.volumeDb)
        assertEquals(DacControlAvailability.NEEDS_HOST_CONFIRMATION, m.state.value.control)
        assertNull(m.pendingConfirmation.value)

        assertError(CommandError.VOLUME_UNAVAILABLE, m.step(+1, 0, tv))
        val p = m.pendingConfirmation.value
        assertNotNull(p)
        assertEquals(PlayerId.HIBY, p!!.player)
        assertEquals(tv, p.origin)
        assertEquals(0, dac.forcedClaims)
        assertTrue(dac.volumeWrites.isEmpty())

        val r = m.pauseAndApplyPending()
        assertTrue(r.ok)
        assertEquals(listOf(TransportAction.PAUSE, TransportAction.PLAY), player.actions)
        assertEquals(listOf(-29f to true), dac.volumeWrites)
        assertEquals(-29f, m.state.value.volumeDb)
        assertEquals(PlaybackStatus.PLAYING, player.snapshot.value.status)
        assertNull(m.pendingConfirmation.value)
        assertEquals(DacControlAvailability.AVAILABLE, m.state.value.control)
    }

    @Test
    fun pauseTimeoutDoesNotForce() = runTest {
        val dac = FakeDacController(busy = true)
        val player = FakePlayer(status = PlaybackStatus.PLAYING).apply { obeysPause = false }
        val m = manager(dac, player = player)
        m.step(-1, 0, tv)
        assertError(CommandError.VOLUME_UNAVAILABLE, m.pauseAndApplyPending())
        assertTrue(dac.volumeWrites.isEmpty())
        assertEquals(0, dac.forcedClaims)
        assertNotNull(m.pendingConfirmation.value)
        m.dismissPending()
        assertNull(m.pendingConfirmation.value)
    }

    @Test
    fun pausedPlayerAllowsForce() = runTest {
        val dac = FakeDacController(volumeDb = -30f, busy = true)
        val player = FakePlayer(status = PlaybackStatus.PAUSED)
        val m = manager(dac, player = player)
        // La lectura automática nunca fuerza (quitarle la interfaz a UAPP le impide luego fijar la
        // frecuencia del DAC): con la interfaz ocupada no hay lectura...
        assertEquals(null, m.state.value.volumeDb)
        // ...pero una acción del usuario con el reproductor en pausa sí puede forzar.
        assertTrue(m.step(-1, 0, tv).ok)
        assertEquals(listOf(-31f to true), dac.volumeWrites)
        assertTrue(player.actions.isEmpty())
    }

    @Test
    fun consentAllowsForceWhilePlayingAndIsRevocable() = runTest {
        val dac = FakeDacController(volumeDb = -30f, busy = true)
        val player = FakePlayer(status = PlaybackStatus.PLAYING)
        val settings = FakePolicySettings()
        val m = manager(dac, settings, player)
        m.grantForceConsent(PlayerId.HIBY)
        assertTrue("152A:85DD:HIBY" in settings.consents)
        assertTrue(m.step(+1, 0, tv).ok)
        assertEquals(listOf(-29f to true), dac.volumeWrites)
        assertTrue(player.actions.isEmpty())

        m.revokeForceConsent(PlayerId.HIBY)
        advanceTimeBy(1_000)
        assertError(CommandError.VOLUME_UNAVAILABLE, m.step(+1, 0, tv))
        assertEquals(1, dac.volumeWrites.size)
        // El consentimiento de otro reproductor no sirve.
        m.grantForceConsent(PlayerId.UAPP)
        advanceTimeBy(1_000)
        assertError(CommandError.VOLUME_UNAVAILABLE, m.step(+1, 0, tv))
    }

    @Test
    fun muteTogglesWithReadbackEvenAboveCapAndWithoutRateLimit() = runTest {
        val dac = FakeDacController(volumeDb = -5f)
        val m = manager(dac)
        assertTrue(m.toggleMute(tv).ok)
        assertEquals(true, m.state.value.muted)
        assertTrue(m.toggleMute(tv).ok)
        assertEquals(false, m.state.value.muted)
        assertEquals(listOf(true to false, false to false), dac.muteWrites)
    }

    @Test
    fun muteWhilePlayingWithoutConsentIsPending() = runTest {
        val dac = FakeDacController(busy = true)
        val m = manager(dac, player = FakePlayer(status = PlaybackStatus.PLAYING))
        assertError(CommandError.VOLUME_UNAVAILABLE, m.toggleMute(tv))
        assertNotNull(m.pendingConfirmation.value)
        assertTrue(dac.muteWrites.isEmpty())
    }

    @Test
    fun muteNotSupported() = runTest {
        val dac = FakeDacController(hasMute = false)
        val m = manager(dac)
        assertNull(m.state.value.muted)
        assertError(CommandError.NOT_SUPPORTED, m.toggleMute(tv))
    }

    @Test
    fun detachMarksDisconnected() = runTest {
        val dac = FakeDacController()
        val m = manager(dac)
        dac.attachedDevice.value = null
        dac.info.value = null
        runCurrent()
        val s = m.state.value
        assertFalse(s.connected)
        assertNull(s.volumeDb)
        assertEquals(DacControlAvailability.DISCONNECTED, s.control)
        assertError(CommandError.DAC_DISCONNECTED, m.step(-1, 0, tv))
    }

    @Test
    fun volumeControlledOnDacIsNotAnError() = runTest {
        for (mode in listOf(DacVolumeMode.FIXED_ON_DEVICE, DacVolumeMode.NONE, DacVolumeMode.STEPS, DacVolumeMode.UNAVAILABLE)) {
            val dac = FakeDacController(volumeMode = mode)
            val m = manager(dac)
            val s = m.state.value
            assertEquals(mode, m.volumeMode.value)
            assertTrue(s.connected)
            assertEquals(DacControlAvailability.AVAILABLE, s.control)
            assertNull(s.volumeDb)
            assertNull(s.minDb)
            assertNull(s.maxDb)
            assertNull(s.capDb)
            // Mute y frecuencia se siguen leyendo.
            assertEquals(false, s.muted)
            assertEquals(96_000, s.sampleRateHz)
            assertError(CommandError.NOT_SUPPORTED, m.step(+1, 0, tv))
            advanceTimeBy(1_000)
            assertError(CommandError.NOT_SUPPORTED, m.step(-1, 0, tv))
            assertError(CommandError.NOT_SUPPORTED, m.setVolumeDb(-40f))
            m.setCapDb(-20f)
            runCurrent()
            assertTrue(dac.volumeWrites.isEmpty())
            assertTrue(m.toggleMute(tv).ok)
        }
    }

    @Test
    fun hardwareModeIsPublished() = runTest {
        val m = manager(FakeDacController())
        assertEquals(DacVolumeMode.HARDWARE_DB, m.volumeMode.value)
    }

    @Test
    fun nonUniformLevelsStepToRealLevelsUnderCap() = runTest {
        val levels = VolumeScale.Table.dragonFly().levelsDb
        val dac = FakeDacController(volumeDb = -53f, minDb = -53f, maxDb = 0f, levelsDb = levels)
        val m = manager(dac)
        assertNull(m.state.value.stepDb)
        assertTrue(m.step(+1, 0, tv).ok)
        assertEquals(-49.70f, dac.volumeDb, 1e-4f)
        advanceTimeBy(1_000)
        assertTrue(m.step(-1, 0, tv).ok)
        assertEquals(-53f, dac.volumeDb, 1e-4f)
        // Absoluto: nivel ≤ pedido y nunca por encima del tope (−10 dB).
        assertTrue(m.setVolumeDb(-20f).ok)
        assertTrue(dac.volumeDb <= -20f)
        assertError(CommandError.VOLUME_ABOVE_CAP, m.setVolumeDb(-9f))
        assertTrue(dac.volumeWrites.all { it.first <= -10f })
    }

    @Test
    fun stepIntervals() {
        assertEquals(84, DacVolumeManager.minStepIntervalMs(0))
        assertEquals(223, DacVolumeManager.minStepIntervalMs(1))
        assertEquals(84, DacVolumeManager.minStepIntervalMs(100))
    }
}
