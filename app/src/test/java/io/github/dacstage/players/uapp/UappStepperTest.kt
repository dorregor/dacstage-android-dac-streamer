package io.github.dacstage.players.uapp

import io.github.dacstage.players.uapp.UappStepper.FailReason
import io.github.dacstage.players.uapp.UappStepper.Outcome
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UappStepperTest {

    /** Reproductor falso: cambia de índice [lagMs] después de cada pulsación. */
    private class FakePlayer(
        var index: Int,
        val size: Int,
        val scope: CoroutineScope,
        val lagMs: Long = 700,
        var ignoreNext: Boolean = false,
        /** Pulsaciones de "anterior" que solo reinician la pista. */
        var previousRestarts: Int = 0,
    ) {
        var nextPresses = 0
        var previousPresses = 0

        fun next() {
            nextPresses++
            if (ignoreNext) return
            scope.launch { delay(lagMs); if (index < size - 1) index++ }
        }

        fun previous() {
            previousPresses++
            if (previousRestarts > 0) { previousRestarts--; return }
            scope.launch { delay(lagMs); if (index > 0) index-- }
        }

        fun stepper(maxSteps: Int = 40) = UappStepper(
            next = { next() },
            previous = { previous() },
            readIndex = { index },
            maxSteps = maxSteps,
        )
    }

    @Test
    fun forwardSuccess() = runTest {
        val p = FakePlayer(index = 2, size = 20, scope = backgroundScope)
        val progress = mutableListOf<Pair<Int, Int>>()
        val out = p.stepper().jump(5) { d, t -> progress += d to t }
        assertEquals(Outcome.Reached(3), out)
        assertEquals(5, p.index)
        assertEquals(3, p.nextPresses)
        assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), progress)
    }

    @Test
    fun backwardSuccess() = runTest {
        val p = FakePlayer(index = 6, size = 20, scope = backgroundScope)
        assertEquals(Outcome.Reached(4), p.stepper().jump(2))
        assertEquals(2, p.index)
        assertEquals(4, p.previousPresses)
        assertEquals(0, p.nextPresses)
    }

    @Test
    fun backwardFirstPressOnlyRestartsTrack() = runTest {
        val p = FakePlayer(index = 4, size = 20, scope = backgroundScope, previousRestarts = 1)
        assertEquals(Outcome.Reached(3), p.stepper().jump(2))
        assertEquals(2, p.index)
        assertEquals(3, p.previousPresses)
    }

    @Test
    fun alreadyThere() = runTest {
        val p = FakePlayer(index = 3, size = 20, scope = backgroundScope)
        assertEquals(Outcome.Reached(0), p.stepper().jump(3))
        assertEquals(0, p.nextPresses)
    }

    @Test
    fun timeoutWhenIndexDoesNotChange() = runTest {
        val p = FakePlayer(index = 0, size = 20, scope = backgroundScope, ignoreNext = true)
        val start = testScheduler.currentTime
        assertEquals(Outcome.Failed(FailReason.TIMEOUT, 0), p.stepper().jump(3))
        assertEquals(1, p.nextPresses)
        assertTrue(testScheduler.currentTime - start in 4_000L..4_400L)
    }

    @Test
    fun tooFarIsRejectedWithoutPressing() = runTest {
        val p = FakePlayer(index = 0, size = 100, scope = backgroundScope)
        assertEquals(Outcome.Failed(FailReason.TOO_FAR, 0), p.stepper(maxSteps = 40).jump(41))
        assertEquals(0, p.nextPresses)
        assertEquals(Outcome.Reached(40), p.stepper(maxSteps = 40).jump(40))
    }

    @Test
    fun noIndex() = runTest {
        val s = UappStepper(next = {}, previous = {}, readIndex = { null })
        assertEquals(Outcome.Failed(FailReason.NO_INDEX, 0), s.jump(3))
    }

    @Test
    fun cancellationStopsPressing() = runTest {
        val p = FakePlayer(index = 0, size = 50, scope = backgroundScope)
        val job = async { p.stepper().jump(10) }
        advanceTimeBy(2_500) // ~2 pasos
        job.cancel()
        val pressesAtCancel = p.nextPresses
        advanceTimeBy(20_000)
        assertTrue(job.isCancelled)
        assertEquals(pressesAtCancel, p.nextPresses)
        assertTrue(p.index < 10)
    }
}
