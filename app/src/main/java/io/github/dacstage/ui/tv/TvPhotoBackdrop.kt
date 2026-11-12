package io.github.dacstage.ui.tv

import android.util.Log
import androidx.compose.animation.Crossfade
import kotlinx.coroutines.launch
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.toBitmap
import io.github.dacstage.R
import io.github.dacstage.tv.EarthPhoto
import io.github.dacstage.tv.EarthViewClient
import io.github.dacstage.ui.common.LocalReduceMotion
import io.github.dacstage.ui.common.OneLine
import kotlinx.coroutines.delay

/**
 * Opacidad del fondo difuminado de los textos en el reposo: la elegida en el host × la claridad de la foto
 * (más en fotos claras, menos en oscuras). 0 = sin fondo.
 */
internal val LocalBackdropAlpha = androidx.compose.runtime.compositionLocalOf { 0f }

/** Opacidad efectiva: [base] (ajuste del host) adaptada a la luminosidad de la foto. */
internal fun adaptiveBackdropAlpha(base: Float, photo: EarthPhoto?): Float {
    if (photo == null || base <= 0f) return 0f
    val lum = photo.primaryColor?.let { Color(it).luminance() } ?: 0.5f
    return (base * (0.55f + 0.9f * lum)).coerceIn(0f, 0.85f)
}

/** Foto ya decodificada (se muestra solo cuando está lista: nunca un fondo a medio cargar). */
@Immutable
internal class PhotoFrame(val photo: EarthPhoto, val image: ImageBitmap)

/**
 * Fotos de Earth View que rotan cada [intervalSec] mientras dure el reposo ([running]). La primera se
 * descarga y decodifica antes, fuera del reposo; la siguiente, mientras se ve la actual. Sin red o si el
 * servicio falla: null (el reposo de siempre, con el degradado de la carátula), también si falla al tocar
 * el cambio; se reintenta cada [RETRY_MS].
 */
@Composable
internal fun rememberEarthPhoto(enabled: Boolean, intervalSec: Int, running: Boolean): PhotoFrame? {
    val context = LocalContext.current
    val density = LocalDensity.current
    var current by remember { mutableStateOf<PhotoFrame?>(null) }
    /** Foto ya descargada y decodificada para el próximo reposo. */
    var ready by remember { mutableStateOf<PhotoFrame?>(null) }
    LaunchedEffect(enabled, intervalSec, running) {
        if (!enabled) {
            current = null
            ready = null
            return@LaunchedEffect
        }
        val client = EarthViewClient.get(context)
        val loader = SingletonImageLoader.get(context)
        val (w, h) = with(density) { 960.dp.roundToPx() to 540.dp.roundToPx() }
        suspend fun fetch(): PhotoFrame? {
            val photo = runCatching { client.nextPhoto() }.getOrNull() ?: return null
            val req = ImageRequest.Builder(context)
                .data(photo.imageUrl)
                .size(w, h)
                .precision(Precision.INEXACT)
                .allowHardware(true)
                .build()
            val result = loader.execute(req) as? SuccessResult ?: run {
                Log.d("EarthView", "imagen ${photo.slug}: no se pudo cargar")
                return null
            }
            return PhotoFrame(photo, result.image.toBitmap().asImageBitmap())
        }
        if (!running) {
            // Fuera del reposo: tras el fundido de salida se suelta la última y se deja lista la primera del
            // próximo (una distinta cada vez).
            delay(EXIT_FADE_MS)
            current = null
            while (ready == null) {
                ready = fetch()
                if (ready == null) delay(RETRY_MS)
            }
            return@LaunchedEffect
        }
        // En reposo: la preparada se muestra en el acto y luego rotan cada [intervalSec].
        var next = ready ?: fetch()
        ready = null
        while (true) {
            val shown = next
            if (shown == null) {
                // La API (o la imagen) falló: vuelve el reposo de siempre, con su degradado, y se reintenta.
                current = null
                delay(RETRY_MS)
                next = fetch()
                continue
            }
            current = shown
            val shownAt = System.currentTimeMillis()
            next = fetch()
            val wait = intervalSec * 1000L - (System.currentTimeMillis() - shownAt)
            if (wait > 0) delay(wait)
        }
    }
    // Al entrar, la preparada se muestra desde el primer fotograma (sin pasar por el degradado).
    return if (running) current ?: ready else current
}

private const val RETRY_MS = 30_000L
private const val EXIT_FADE_MS = 600L

/**
 * Fondo a pantalla completa con la foto tal cual (sin oscurecer): fundido entre fotos y acercamiento lento
 * con un leve desplazamiento (anti burn-in). El contraste lo ponen los textos con [softBackdrop].
 */
@Composable
internal fun PhotoBackdrop(frame: PhotoFrame?, intervalSec: Int) {
    val reduce = LocalReduceMotion.current
    Crossfade(targetState = frame, animationSpec = tween(if (reduce) 0 else 1800), label = "earthPhoto") { f ->
        if (f == null) return@Crossfade
        key(f.photo.slug) {
            // Acercamiento lento con un leve desplazamiento en una dirección distinta por foto (anti burn-in).
            val zoom = remember { Animatable(1.02f) }
            val drift = remember { Animatable(0f) }
            val dir = remember { (f.photo.slug.hashCode() and 3) }
            LaunchedEffect(Unit) {
                if (!reduce) {
                    val ms = (intervalSec * 1000).coerceAtLeast(20_000)
                    launch { zoom.animateTo(1.09f, tween(ms, easing = LinearEasing)) }
                    drift.animateTo(1f, tween(ms, easing = LinearEasing))
                }
            }
            Image(
                f.image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom.value
                    scaleY = zoom.value
                    val d = drift.value * size.width * 0.02f
                    translationX = if (dir and 1 == 0) d else -d
                    translationY = if (dir and 2 == 0) d * 0.5f else -d * 0.5f
                },
            )
        }
    }
}

/**
 * Fondo difuminado precalculado: la figura se desenfoca UNA vez en un bitmap de software a media resolución
 * (BlurMaskFilter en software es síncrono) y en cada fotograma solo se pinta ese bitmap. Así sale completo
 * desde el primer fotograma (la versión por hardware llegaba tarde y se veía un bloque) y no cuesta nada
 * mientras el texto no cambia. Se rehace solo si cambia la figura ([key]).
 */
private class BakedFeather {
    private var bitmap: android.graphics.Bitmap? = null
    private var key: Any? = null
    private var margin = 0f
    private val drawPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val dst = android.graphics.RectF()

    /** [shape] añade al Path la figura (coordenadas del elemento); [feather] = radio del desenfoque (px). */
    fun draw(
        canvas: android.graphics.Canvas,
        key: Any,
        width: Float,
        height: Float,
        feather: Float,
        color: Color,
        alpha: Float,
        shape: (android.graphics.Path) -> Unit,
    ) {
        val a = (color.alpha * alpha).coerceIn(0f, 1f)
        if (a <= 0.01f || width <= 0f || height <= 0f) return
        if (key != this.key || bitmap == null) {
            bitmap = bake(width, height, feather, shape)
            this.key = key
        }
        val bmp = bitmap ?: return
        dst.set(-margin, -margin, width + margin, height + margin)
        drawPaint.colorFilter = android.graphics.PorterDuffColorFilter(
            color.copy(alpha = 1f).toArgb(), android.graphics.PorterDuff.Mode.SRC_IN,
        )
        drawPaint.alpha = (a * 255).toInt()
        canvas.drawBitmap(bmp, null, dst, drawPaint)
    }

    private fun bake(width: Float, height: Float, feather: Float, shape: (android.graphics.Path) -> Unit): android.graphics.Bitmap? {
        margin = feather * 2f + 2f
        val scale = BAKE_SCALE
        val w = ((width + 2 * margin) * scale).toInt().coerceIn(1, 2048)
        val h = ((height + 2 * margin) * scale).toInt().coerceIn(1, 2048)
        val bmp = runCatching { android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ALPHA_8) }.getOrNull() ?: return null
        val c = android.graphics.Canvas(bmp)
        c.scale(w / (width + 2 * margin), h / (height + 2 * margin))
        c.translate(margin, margin)
        val path = android.graphics.Path()
        shape(path)
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.BLACK
            if (feather > 0f) maskFilter = android.graphics.BlurMaskFilter(feather, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        c.drawPath(path, paint)
        return bmp
    }

    private companion object {
        const val BAKE_SCALE = 0.5f
    }
}

/**
 * Fondo oscuro difuminado (bordes muy suaves, sin caja visible) detrás de un texto o de la carátula, para
 * que se lean sobre cualquier foto sin tocar la foto. [alpha] permite animarlo.
 */
internal fun Modifier.softBackdrop(
    alpha: () -> Float = { 1f },
    color: Color = Color.Black,
    spread: Dp = 14.dp,
    blur: Dp = 22.dp,
    corner: Dp = 24.dp,
): Modifier = drawWithCache {
    val e = spread.toPx() - blur.toPx() / 2f
    val feather = blur.toPx()
    val r = corner.toPx()
    val baked = BakedFeather()
    val w = size.width
    val h = size.height
    onDrawBehind {
        drawIntoCanvas { c ->
            baked.draw(c.nativeCanvas, w to h, w, h, feather, color, alpha()) { path ->
                path.addRoundRect(-e, -e, w + e, h + e, r, r, android.graphics.Path.Direction.CW)
            }
        }
    }
}

/**
 * Fondo difuminado que sigue cada renglón del texto (solo donde hay caracteres, con su ancho real): en un
 * párrafo de dos renglones, el corto no oscurece el hueco de al lado. Todos los renglones forman una sola
 * figura, así que donde se juntan no se oscurece el doble.
 */
internal fun Modifier.lineBackdrop(
    layout: () -> TextLayoutResult?,
    alpha: () -> Float = { 1f },
    color: Color = Color.Black,
    padH: Dp = 10.dp,
    padV: Dp = 2.dp,
    blur: Dp = 14.dp,
): Modifier = drawWithCache {
    val ph = padH.toPx()
    val pv = padV.toPx()
    val feather = blur.toPx()
    val baked = BakedFeather()
    val w = size.width
    val h = size.height
    onDrawBehind {
        val l = layout() ?: return@onDrawBehind
        drawIntoCanvas { c ->
            baked.draw(c.nativeCanvas, l, w, h, feather, color, alpha()) { path ->
                for (i in 0 until l.lineCount) {
                    val left = l.getLineLeft(i)
                    val right = l.getLineRight(i)
                    if (right - left < 1f) continue
                    val top = l.getLineTop(i) - pv
                    val bottom = l.getLineBottom(i) + pv
                    val rr = (bottom - top) / 2f
                    path.addRoundRect(left - ph, top, right + ph, bottom, rr, rr, android.graphics.Path.Direction.CW)
                }
            }
        }
    }
}

/**
 * Ocupa [pad] más por cada lado de lo que le dan (sin mover a los vecinos) y lo devuelve como relleno a su
 * contenido: las capas de las animaciones (fundidos) incluyen ese margen y no recortan el difuminado.
 */
internal fun Modifier.bleed(pad: Dp): Modifier = bleedOut(pad).padding(pad)

/**
 * Solo la parte exterior de [bleed]: el elemento se mide y dibuja [pad] más grande por cada lado sin mover a
 * los vecinos. El relleno lo pone el contenido (p. ej. la raíz de cada estado de un AnimatedContent), así la
 * capa del fundido, que es del tamaño de ese contenido, incluye el difuminado y no lo corta en bloque.
 */
internal fun Modifier.bleedOut(pad: Dp): Modifier = layout { measurable, constraints ->
    val p = pad.roundToPx()
    fun grow(v: Int, bounded: Boolean) = if (bounded) v + 2 * p else v
    val placeable = measurable.measure(
        constraints.copy(
            minWidth = constraints.minWidth + 2 * p,
            maxWidth = grow(constraints.maxWidth, constraints.hasBoundedWidth),
            minHeight = constraints.minHeight + 2 * p,
            maxHeight = grow(constraints.maxHeight, constraints.hasBoundedHeight),
        ),
    )
    val w = (placeable.width - 2 * p).coerceIn(constraints.minWidth, constraints.maxWidth)
    val h = (placeable.height - 2 * p).coerceIn(constraints.minHeight, constraints.maxHeight)
    layout(w, h) { placeable.place(-p, -p) }
}

/** Alcance del difuminado fuera del texto (relleno + desenfoque): lo que hay que dejar libre alrededor. */
internal val BACKDROP_REACH = 44.dp

/** Texto con [lineBackdrop] (si [backdrop]). */
@Composable
internal fun BackdropText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    backdrop: Boolean = true,
    backdropAlpha: () -> Float = { 1f },
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val base = LocalBackdropAlpha.current
    val on = backdrop && base > 0.005f
    androidx.compose.foundation.text.BasicText(
        text,
        style = style,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        onTextLayout = { layout = it },
        modifier = if (on) modifier.lineBackdrop({ layout }, { backdropAlpha() * base }) else modifier,
    )
}

private val TextShadow = Shadow(Color.Black.copy(alpha = 0.75f), Offset(0f, 2f), blurRadius = 10f)

private val SongTitle = TextStyle(color = Color.White, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, shadow = TextShadow)
private val SongAlbum = TextStyle(color = Color.White.copy(alpha = 0.85f), fontSize = 21.sp, lineHeight = 27.sp, shadow = TextShadow)
private val PlaceStyle = TextStyle(color = Color.White.copy(alpha = 0.9f), fontSize = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp, shadow = TextShadow)
private val CreditStyle = TextStyle(color = Color.White.copy(alpha = 0.62f), fontSize = 14.sp, shadow = TextShadow)

/**
 * Abajo a la izquierda: la canción (título y álbum, grandes) y, separada por una raya fina, la foto
 * (lugar con icono y créditos, en pequeño y más tenue). Solo lo que hay; nada inventado.
 */
@Composable
internal fun PhotoInfoBlock(
    title: String?,
    album: String?,
    photo: EarthPhoto?,
    modifier: Modifier = Modifier,
    chips: List<io.github.dacstage.ui.common.TechChip> = emptyList(),
    chipsKey: Any = Unit,
) {
    val reduce = LocalReduceMotion.current
    Column(modifier) {
        AnimatedContent(
            targetState = title to album,
            modifier = Modifier.bleedOut(BACKDROP_REACH),
            transitionSpec = {
                if (reduce) EnterTransition.None togetherWith ExitTransition.None
                else (fadeIn(tween(700, delayMillis = 250)) + slideInVertically(tween(700, delayMillis = 250)) { it / 4 }) togetherWith
                    (fadeOut(tween(350)) + slideOutVertically(tween(350)) { -it / 6 })
            },
            label = "photoSong",
        ) { (t, a) ->
            // El relleno va dentro del estado: la capa del fundido lo incluye (ver bleedOut).
            Column(Modifier.padding(BACKDROP_REACH)) {
                if (t != null) BackdropText(t, SongTitle, maxLines = 2)
                a?.let {
                    Spacer(Modifier.height(4.dp))
                    BackdropText(it, SongAlbum, maxLines = 1)
                }
                if (chips.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    ScreensaverChips(chips, chipsKey)
                }
                if ((t != null || a != null || chips.isNotEmpty()) && photo != null) {
                    Spacer(Modifier.height(16.dp))
                    Box(Modifier.width(40.dp).height(2.dp).background(Color.White.copy(alpha = 0.45f)))
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
        if (photo != null) AnimatedContent(
            targetState = photo,
            contentKey = { it.slug },
            modifier = Modifier.bleedOut(BACKDROP_REACH),
            transitionSpec = {
                if (reduce) EnterTransition.None togetherWith ExitTransition.None
                else (fadeIn(tween(900, delayMillis = 700)) + slideInHorizontally(tween(900, delayMillis = 700)) { -it / 8 }) togetherWith
                    fadeOut(tween(600))
            },
            label = "photoCredit",
        ) { p -> PhotoCredit(p, Modifier.padding(BACKDROP_REACH)) }
    }
}

/**
 * Formato y calidad en el reposo: los mismos datos que la vista normal (nada calculado aparte), con fondo
 * propio para leerse sobre cualquier foto.
 */
@Composable
internal fun ScreensaverChips(chips: List<io.github.dacstage.ui.common.TechChip>, key: Any) {
    val base = LocalBackdropAlpha.current
    io.github.dacstage.ui.common.TechChipsRow(
        chips,
        animationKey = key,
        textStyle = TextStyle(color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium),
        background = Color.Black.copy(alpha = (0.25f + base * 0.6f).coerceIn(0.25f, 0.7f)),
        borderColor = Color.White.copy(alpha = 0.3f),
        spacing = 8.dp,
        padH = 10.dp,
        padV = 4.dp,
    )
}

/** Lugar y créditos de la foto. */
@Composable
internal fun PhotoCredit(photo: EarthPhoto, modifier: Modifier = Modifier) {
    val iconAlpha = LocalBackdropAlpha.current
    Column(modifier) {
        photo.place?.let {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Place,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f),
                    modifier = Modifier.size(20.dp).softBackdrop(alpha = { iconAlpha }, spread = 4.dp, blur = 10.dp, corner = 12.dp),
                )
                Spacer(Modifier.width(6.dp))
                BackdropText(it, PlaceStyle, maxLines = 1)
            }
            Spacer(Modifier.height(2.dp))
        }
        BackdropText(photo.attribution?.let { stringResource(R.string.tv_photo_credit, it) } ?: "Earth View", CreditStyle, maxLines = 2)
    }
}
