package io.github.dacstage.ui.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceScale
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import io.github.dacstage.ui.common.ArtColors

/** Márgenes de overscan (5 %) sobre 960×540 dp. */
internal object TvDims {
    val overscanH = 48.dp
    val overscanV = 27.dp
}

/** Tipografía legible a 3 m: nada secundario por debajo de 18 sp. */
internal object TvType {
    private val primary = Color(ArtColors.TEXT_PRIMARY)
    private val secondary = Color(ArtColors.TEXT_SECONDARY)

    val playerLine = TextStyle(color = secondary, fontSize = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.6.sp)
    val title = TextStyle(color = primary, fontSize = 36.sp, lineHeight = 42.sp, fontWeight = FontWeight.SemiBold)
    val artist = TextStyle(color = primary, fontSize = 25.sp, lineHeight = 31.sp, fontWeight = FontWeight.Normal)
    val album = TextStyle(color = secondary, fontSize = 20.sp, lineHeight = 26.sp)
    val chip = TextStyle(color = primary, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    val time = TextStyle(color = secondary, fontSize = 18.sp, fontFamily = FontFamily.Default)
    val body = TextStyle(color = secondary, fontSize = 20.sp, lineHeight = 28.sp)
    val bodyPrimary = TextStyle(color = primary, fontSize = 22.sp, lineHeight = 30.sp)
    val hint = TextStyle(color = secondary, fontSize = 18.sp)
    val heading = TextStyle(color = primary, fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold)
    val code = TextStyle(color = primary, fontSize = 88.sp, fontWeight = FontWeight.Bold, letterSpacing = 18.sp, fontFamily = FontFamily.Monospace)
    val cardTitle = TextStyle(color = primary, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    val cardSub = TextStyle(color = secondary, fontSize = 18.sp)
    val hudValue = TextStyle(color = primary, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
}

/** Indicador de foco común: escala 1.06 + borde blanco (no cambia el layout). */
internal object TvFocus {
    fun scale(focused: Float = 1.06f): ClickableSurfaceScale = ClickableSurfaceDefaults.scale(focusedScale = focused)

    @Composable
    fun border(shape: Shape = RoundedCornerShape(50)): ClickableSurfaceBorder = ClickableSurfaceDefaults.border(
        focusedBorder = Border(BorderStroke(3.dp, Color.White), shape = shape),
    )
}

internal fun FocusRequester.requestSafely(): Boolean = try {
    requestFocus()
    true
} catch (_: IllegalStateException) {
    false
}

@Composable
internal fun TvTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = content)
}
