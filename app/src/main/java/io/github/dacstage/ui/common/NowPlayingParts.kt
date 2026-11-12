package io.github.dacstage.ui.common

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.runtime.withFrameMillis

/* ---------- Fondo dinámico ---------- */

@Stable
class AnimatedArtColors(val glow: State<Color>, val deep: State<Color>, val accent: State<Color>)

@Composable
fun rememberAnimatedArtColors(target: ArtColors): AnimatedArtColors {
    val reduce = LocalReduceMotion.current
    val spec = Motion.fade<Color>(reduce, Motion.GRADIENT_MS)
    val glow = animateColorAsState(Color(target.glow), spec, label = "glow")
    val deep = animateColorAsState(Color(target.deep), spec, label = "deep")
    val accent = animateColorAsState(Color(target.accent), spec, label = "accent")
    return remember(glow, deep, accent) { AnimatedArtColors(glow, deep, accent) }
}

/**
 * Degradado: base [deep] + halo [glow] centrado en [glowCenter] (fracciones del tamaño). Los colores se
 * leen en la fase de dibujo: la transición entre pistas no recompone.
 */
fun Modifier.artBackground(c: AnimatedArtColors, glowCenter: Offset = Offset(0.28f, 0.45f)): Modifier = drawBehind {
    val deep = c.deep.value
    val glow = c.glow.value
    drawRect(deep)
    drawRect(
        Brush.radialGradient(
            0f to glow,
            0.55f to glow.copy(alpha = 0.55f),
            1f to deep.copy(alpha = 0f),
            center = Offset(size.width * glowCenter.x, size.height * glowCenter.y),
            radius = size.maxDimension * 0.75f,
        ),
    )
    drawRect(Brush.verticalGradient(0.6f to Color.Transparent, 1f to deep.copy(alpha = 0.7f)))
}

/* ---------- Carátula ---------- */

@Composable
fun ArtworkView(
    frame: ArtFrame?,
    modifier: Modifier = Modifier,
    corner: Dp = 12.dp,
    placeholderColor: Color = Color(0xFF1E2230),
) {
    val reduce = LocalReduceMotion.current
    val shape = RoundedCornerShape(corner)
    AnimatedContent(
        targetState = frame,
        contentKey = { it?.id },
        transitionSpec = {
            if (reduce) EnterTransition.None togetherWith ExitTransition.None
            else (fadeIn(Motion.fade(false, 450)) + scaleIn(Motion.spatial(false), initialScale = 0.94f)) togetherWith
                (fadeOut(Motion.fade(false, 320)) + scaleOut(Motion.fade(false, 320), targetScale = 1.03f))
        },
        modifier = modifier,
        label = "art",
    ) { f ->
        Box(Modifier.fillMaxSize().clip(shape).background(placeholderColor), contentAlignment = Alignment.Center) {
            if (f == null) {
                Image(
                    AppIcons.MusicNote,
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxSize(0.32f),
                )
            } else {
                // Miniatura → alta dentro de la misma pista.
                Crossfade(f, animationSpec = Motion.fade(reduce, 260), label = "artQuality") { q ->
                    Image(q.bitmap, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/* ---------- Chips técnicos ---------- */

@Composable
fun TechChipsRow(
    chips: List<TechChip>,
    animationKey: Any,
    textStyle: TextStyle,
    background: Color,
    borderColor: Color,
    modifier: Modifier = Modifier,
    spacing: Dp = 10.dp,
    padH: Dp = 12.dp,
    padV: Dp = 5.dp,
) {
    val reduce = LocalReduceMotion.current
    androidx.compose.foundation.layout.FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalArrangement = Arrangement.spacedBy(spacing * 0.6f),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        chips.forEachIndexed { i, chip ->
            key(animationKey, chip.kind) {
                val a = remember { Animatable(if (reduce) 1f else 0f) }
                LaunchedEffect(Unit) {
                    if (!reduce) {
                        delay(90L * i + 120L)
                        a.animateTo(1f, Motion.spatial(false))
                    }
                }
                BasicText(
                    chip.label,
                    style = textStyle,
                    maxLines = 1,
                    modifier = Modifier
                        .graphicsLayer {
                            alpha = a.value.coerceIn(0f, 1f)
                            translationY = (1f - a.value) * 14.dp.toPx()
                        }
                        .clip(RoundedCornerShape(50))
                        .background(background)
                        .border(1.dp, borderColor, RoundedCornerShape(50))
                        .padding(horizontal = padH, vertical = padV),
                )
            }
        }
    }
}

/* ---------- Progreso ---------- */

/** Reloj local a ~10 Hz mientras suena; se lee en dibujo o vía derivedStateOf. */
@Composable
fun rememberPlaybackClock(anchor: ProgressAnchor?): LongState {
    val now = remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(anchor) {
        now.longValue = SystemClock.elapsedRealtime()
        if (anchor?.playing != true) return@LaunchedEffect
        while (isActive) {
            delay(90)
            withFrameMillis { }
            now.longValue = SystemClock.elapsedRealtime()
        }
    }
    return now
}

@Composable
fun ProgressLine(
    anchor: ProgressAnchor?,
    clock: LongState,
    trackColor: Color,
    fill: State<Color>,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
) {
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(trackColor, cornerRadius = r)
        val f = anchor?.fractionAt(clock.longValue) ?: return@Canvas
        if (f > 0f) drawRoundRect(fill.value, size = Size(size.width * f, size.height), cornerRadius = r)
    }
}

/** Tiempo transcurrido: recompone una vez por segundo, no por fotograma. */
@Composable
fun ElapsedText(anchor: ProgressAnchor?, clock: LongState, style: TextStyle, modifier: Modifier = Modifier) {
    val seconds by remember(anchor) { derivedStateOf { (anchor?.positionAt(clock.longValue) ?: 0L) / 1000L } }
    BasicText(if (anchor == null) "" else TechFormat.time(seconds * 1000L), style = style, modifier = modifier, maxLines = 1)
}

@Composable
fun OneLine(text: String, style: TextStyle, modifier: Modifier = Modifier, maxLines: Int = 1) {
    BasicText(text, style = style, modifier = modifier, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}
