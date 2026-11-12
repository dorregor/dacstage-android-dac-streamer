package io.github.dacstage.ui.common

import androidx.compose.runtime.Immutable
import io.github.dacstage.model.PlaybackStatus
import io.github.dacstage.model.Progress

/**
 * Posición sellada con el reloj monotónico local al recibirla. Se extrapola con [speed] solo mientras
 * suena; nunca pasa de la duración ni baja de 0. Sin duración no hay barra (solo tiempo).
 */
@Immutable
data class ProgressAnchor(
    val positionMs: Long,
    val speed: Float,
    val stampedAtMs: Long,
    val playing: Boolean,
    val durationMs: Long?,
) {
    fun positionAt(nowMs: Long): Long {
        val elapsed = if (playing) ((nowMs - stampedAtMs).coerceAtLeast(0L) * speed.coerceAtLeast(0f)).toLong() else 0L
        val pos = (positionMs + elapsed).coerceAtLeast(0L)
        val dur = durationMs?.takeIf { it > 0 }
        return if (dur != null) pos.coerceAtMost(dur) else pos
    }

    /** 0..1 o null si no hay duración conocida. */
    fun fractionAt(nowMs: Long): Float? {
        val dur = durationMs?.takeIf { it > 0 } ?: return null
        return (positionAt(nowMs).toFloat() / dur).coerceIn(0f, 1f)
    }

    companion object {
        fun stamp(progress: Progress?, status: PlaybackStatus, durationMs: Long?, nowMs: Long): ProgressAnchor? {
            progress ?: return null
            return ProgressAnchor(
                positionMs = progress.positionMs,
                speed = progress.speed,
                stampedAtMs = nowMs,
                playing = status == PlaybackStatus.PLAYING,
                durationMs = durationMs,
            )
        }
    }
}
