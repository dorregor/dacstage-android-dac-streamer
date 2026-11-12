package io.github.dacstage.ui.common

import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/** true si el usuario desactivó las animaciones (escala de animación = 0). */
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/** Resortes con amortiguación media; sin animación si se pidió reducirlas. */
object Motion {
    const val CROSSFADE_MS = 450
    const val GRADIENT_MS = 1100

    fun <T> spatial(reduce: Boolean): FiniteAnimationSpec<T> =
        if (reduce) snap() else spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)

    fun <T> fade(reduce: Boolean, ms: Int = CROSSFADE_MS, delayMs: Int = 0): FiniteAnimationSpec<T> =
        if (reduce) snap() else tween(durationMillis = ms, delayMillis = delayMs)

    @Composable
    @ReadOnlyComposable
    fun reduced(): Boolean = LocalReduceMotion.current
}
