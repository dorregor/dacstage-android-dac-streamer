package io.github.dacstage.players.uapp

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Salto en la cola de UAPP sin skipToQueueItem (UAPP no lo implementa): N × siguiente/anterior,
 * esperando a que el índice cambie entre pulsaciones. Lógica pura: las acciones llegan como funciones.
 * Cancelable con la corrutina (lanza CancellationException).
 */
class UappStepper(
    private val next: suspend () -> Unit,
    private val previous: suspend () -> Unit,
    /** Índice actual leído en fresco; [hint] = índice esperado. null = desconocido por ahora. */
    private val readIndex: suspend (hint: Int?) -> Int?,
    private val stepTimeoutMs: Long = 4_000,
    private val pollMs: Long = 300,
    private val maxSteps: Int = 40,
    /** Ventana corta para la primera pulsación de "anterior" (puede solo reiniciar la pista). */
    private val firstPreviousWindowMs: Long = 2_000,
) {
    sealed interface Outcome {
        data class Reached(val presses: Int) : Outcome
        data class Failed(val reason: FailReason, val stepsDone: Int) : Outcome
    }

    enum class FailReason { NO_INDEX, TOO_FAR, TIMEOUT, STEP_LIMIT }

    suspend fun jump(target: Int, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Outcome {
        var current = readIndex(null) ?: return Outcome.Failed(FailReason.NO_INDEX, 0)
        val total = abs(target - current)
        if (total == 0) return Outcome.Reached(0)
        if (total > maxSteps) return Outcome.Failed(FailReason.TOO_FAR, 0)
        onProgress(0, total)

        var presses = 0
        var previousRetried = false
        while (current != target) {
            if (presses >= maxSteps) return Outcome.Failed(FailReason.STEP_LIMIT, done(total, target, current))
            val forward = target > current
            val expected = if (forward) current + 1 else current - 1
            if (forward) next() else previous()
            presses++
            val window = if (!forward && !previousRetried) firstPreviousWindowMs else stepTimeoutMs
            val changed = awaitChange(current, expected, window)
            if (changed == null) {
                // "Anterior" con la pista avanzada suele reiniciarla: se permite una segunda pulsación.
                if (!forward && !previousRetried) {
                    previousRetried = true
                    continue
                }
                return Outcome.Failed(FailReason.TIMEOUT, done(total, target, current))
            }
            if (!forward) previousRetried = true
            current = changed
            onProgress(done(total, target, current), total)
        }
        return Outcome.Reached(presses)
    }

    private fun done(total: Int, target: Int, current: Int) = (total - abs(target - current)).coerceIn(0, total)

    private suspend fun awaitChange(from: Int, expected: Int, timeoutMs: Long): Int? =
        withTimeoutOrNull(timeoutMs) {
            var idx: Int?
            do {
                delay(pollMs)
                idx = readIndex(expected)
            } while (idx == null || idx == from)
            idx
        }
}
