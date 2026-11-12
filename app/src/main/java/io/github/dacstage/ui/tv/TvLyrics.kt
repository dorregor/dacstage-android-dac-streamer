package io.github.dacstage.ui.tv

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import io.github.dacstage.R
import io.github.dacstage.enrich.LrcParser
import io.github.dacstage.model.Lyrics
import io.github.dacstage.tv.TvViewModel
import io.github.dacstage.ui.common.ArtFrame
import io.github.dacstage.ui.common.ArtworkView
import io.github.dacstage.ui.common.LocalReduceMotion
import io.github.dacstage.ui.common.Motion
import io.github.dacstage.ui.common.OneLine
import io.github.dacstage.ui.common.rememberPlaybackClock
import kotlin.random.Random
import kotlinx.coroutines.delay

/** Botón "Letra" (arriba a la derecha, junto al volumen). Solo existe si el host tiene letra. */
@Composable
internal fun TvLyricsButton(
    on: Boolean,
    requester: FocusRequester,
    downTarget: FocusRequester,
    enabled: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .height(52.dp)
            .focusRequester(requester)
            .focusProperties {
                canFocus = enabled
                down = downTarget
            }
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (on) Color.White.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.10f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF111111),
        ),
        scale = TvFocus.scale(1.06f),
        border = TvFocus.border(RoundedCornerShape(50)),
    ) {
        Box(Modifier.padding(horizontal = 22.dp).height(52.dp), contentAlignment = Alignment.Center) {
            androidx.tv.material3.Text(stringResource(if (on) R.string.tv_lyrics_hide else R.string.tv_lyrics), fontSize = 18.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * Letra de la pista. Sincronizada: la línea vigente según la posición extrapolada (con un pequeño
 * adelanto). Sin tiempos: avance aproximado proporcional a la duración, y se indica. Instrumental: aviso.
 */
@Composable
internal fun TvLyricsView(vm: TvViewModel, lyrics: Lyrics?, modifier: Modifier = Modifier) {
    // La fuente (LRCLIB) va en la línea inferior: así los controles y la ayuda "▼ Cola" caben en pantalla.
    Column(modifier) {
        CrossfadeLyrics(vm, lyrics, LyricsLook.Normal)
    }
}

/** Tamaños de la letra: en la vista normal o a pantalla completa (reposo). */
internal class LyricsLook(
    val current: TextStyle,
    val other: TextStyle,
    val height: Dp,
    val spacing: Dp,
    val centered: Boolean,
    /** Fondo difuminado solo detrás de la línea destacada (reposo sobre una foto). */
    val highlightBackdrop: Boolean = false,
) {
    /** Mismo aspecto con otro alto (p. ej. el espacio libre en la pantalla del teléfono). */
    fun withHeight(h: Dp) = LyricsLook(current, other, h, spacing, centered, highlightBackdrop)

    companion object {
        val Normal = LyricsLook(Current, Other, WINDOW_HEIGHT, 8.dp, centered = false)
        val Big = LyricsLook(
            current = Current.copy(fontSize = 44.sp, lineHeight = 54.sp, textAlign = TextAlign.Center),
            other = Other.copy(fontSize = 28.sp, lineHeight = 36.sp, textAlign = TextAlign.Center),
            height = 330.dp,
            spacing = 14.dp,
            centered = true,
        )
        val BigOnPhoto = LyricsLook(Big.current, Big.other, Big.height, Big.spacing, centered = true, highlightBackdrop = true)
        /** Control remoto en un teléfono. */
        val Phone = LyricsLook(
            current = Current.copy(fontSize = 24.sp, lineHeight = 31.sp),
            other = Other.copy(fontSize = 18.sp, lineHeight = 24.sp),
            height = 300.dp,
            spacing = 8.dp,
            centered = false,
        )
    }
}

/**
 * Al cambiar de pista (letra anterior → "Buscando letra…" → letra nueva) se funde en lugar de saltar:
 * cada letra tiene su propia lista y posición, así que no se reaprovecha el desplazamiento de la anterior.
 */
@Composable
internal fun CrossfadeLyrics(vm: TvViewModel, lyrics: Lyrics?, look: LyricsLook) {
    val reduce = LocalReduceMotion.current
    Crossfade(targetState = lyrics, animationSpec = Motion.fade(reduce, 380), label = "lyricsSwap") { l ->
        LyricsBody(vm, l, look)
    }
}

/** Líneas según la posición (sincronizada) o avance proporcional (sin tiempos), o el aviso que toque. */
@Composable
private fun LyricsBody(vm: TvViewModel, lyrics: Lyrics?, look: LyricsLook) {
    val anchor by vm.progress.collectAsStateWithLifecycle()
    val clock = rememberPlaybackClock(anchor)
    val synced = lyrics?.synced
    val plainLines = remember(lyrics?.plain) { lyrics?.plain?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty() }
    val wide = if (look.centered) Modifier.fillMaxWidth() else Modifier
    when {
        // Pista nueva, letra en camino: hueco del mismo alto (nada salta) y sin aviso; la letra entra con fundido.
        // Sin letra para esta pista: el mismo hueco con un aviso discreto (el modo letra sigue activo).
        lyrics == null -> Box(
            Modifier.fillMaxWidth().height(look.height),
            contentAlignment = if (look.centered) Alignment.Center else Alignment.CenterStart,
        ) {
            val pending by vm.lyricsPending.collectAsStateWithLifecycle()
            androidx.compose.animation.AnimatedVisibility(
                visible = !pending,
                enter = androidx.compose.animation.fadeIn(Motion.fade(LocalReduceMotion.current, 320, 200)),
                exit = androidx.compose.animation.fadeOut(Motion.fade(LocalReduceMotion.current, 160)),
            ) {
                OneLine(stringResource(R.string.tv_lyrics_none), look.other.copy(color = Color.White.copy(alpha = 0.55f)))
            }
        }
        !synced.isNullOrEmpty() -> {
            val texts = remember(synced) { synced.map { it.text } }
            val idx by remember(synced, anchor) {
                derivedStateOf { LrcParser.indexAt(synced, (anchor?.positionAt(clock.longValue) ?: 0L) + LEAD_MS) }
            }
            LinesWindow(texts, idx, look)
        }
        plainLines.isNotEmpty() -> {
            OneLine(stringResource(R.string.tv_lyrics_unsynced), TvType.hint.copy(textAlign = look.current.textAlign), wide)
            Spacer(Modifier.height(10.dp))
            val idx by remember(plainLines, anchor) {
                derivedStateOf {
                    val f = anchor?.fractionAt(clock.longValue) ?: 0f
                    (f * plainLines.size).toInt().coerceIn(0, plainLines.lastIndex)
                }
            }
            LinesWindow(plainLines, idx, look)
        }
        lyrics.instrumental -> OneLine(stringResource(R.string.tv_lyrics_instrumental), look.current, wide)
    }
}

/**
 * Reposo con letra: letra grande y centrada aprovechando la pantalla, carátula y título pequeños arriba.
 * Todo deriva despacio (anti burn-in); la letra además cambia constantemente.
 */
@Composable
internal fun TvLyricsScreensaver(
    vm: TvViewModel,
    chips: List<io.github.dacstage.ui.common.TechChip>,
    chipsKey: Any,
    lyrics: Lyrics?,
    frame: ArtFrame?,
    title: String?,
    artist: String?,
    photo: io.github.dacstage.tv.EarthPhoto? = null,
    display: io.github.dacstage.model.TvDisplayPrefs = io.github.dacstage.model.TvDisplayPrefs(),
) {
    val reduce = LocalReduceMotion.current
    val dx: () -> Float
    val dy: () -> Float
    if (reduce) {
        var pos by remember { mutableStateOf(0f to 0f) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(60_000)
                pos = (Random.nextFloat() * 2 - 1) to (Random.nextFloat() * 2 - 1)
            }
        }
        dx = { pos.first }
        dy = { pos.second }
    } else {
        val inf = rememberInfiniteTransition(label = "lyricsDrift")
        val x = inf.animateFloat(-1f, 1f, infiniteRepeatable(tween(53_000, easing = LinearEasing), RepeatMode.Reverse), label = "x")
        val y = inf.animateFloat(-1f, 1f, infiniteRepeatable(tween(67_000, easing = LinearEasing), RepeatMode.Reverse), label = "y")
        dx = { x.value }
        dy = { y.value }
    }
    Box(Modifier.fillMaxSize().padding(horizontal = TvDims.overscanH * 2, vertical = TvDims.overscanV * 2)) {
        Column(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .graphicsLayer {
                    // En píxeles enteros para que las letras no se muevan cada una por su lado.
                    translationX = kotlin.math.round(dx() * 28.dp.toPx())
                    translationY = kotlin.math.round(dy() * 18.dp.toPx())
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val artAlpha = LocalBackdropAlpha.current
            if (display.lyricsShowArtwork || display.lyricsShowSongInfo) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (display.lyricsShowArtwork) {
                        ArtworkView(
                            frame,
                            Modifier.size(64.dp).softBackdrop(alpha = { artAlpha }, spread = 8.dp, blur = 18.dp, corner = 14.dp),
                            corner = 8.dp,
                        )
                    }
                    if (display.lyricsShowArtwork && display.lyricsShowSongInfo) Spacer(Modifier.width(16.dp))
                    if (display.lyricsShowSongInfo) {
                        Column {
                            BackdropText(title ?: "", TvType.cardTitle, maxLines = 1)
                            artist?.let { BackdropText(it, TvType.cardSub, maxLines = 1) }
                            if (display.lyricsShowTech && chips.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                ScreensaverChips(chips, chipsKey)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(36.dp))
            }
            CrossfadeLyrics(vm, lyrics, if (photo != null) LyricsLook.BigOnPhoto else LyricsLook.Big)
        }
    }
    // Lugar y créditos de la foto, discretos abajo a la izquierda (el borde inferior de la letra es transparente).
    photo?.takeIf { display.lyricsShowPhotoInfo }?.let {
        Box(Modifier.fillMaxSize()) {
            PhotoCredit(
                it,
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = TvDims.overscanH, bottom = TvDims.overscanV)
                    .widthIn(max = 420.dp),
            )
        }
    }
}

/**
 * Todas las líneas en una lista que se desplaza suavemente hasta dejar la vigente a una línea del borde
 * superior. Mismo tamaño de letra para todas (sin saltos de maquetación): la vigente se ilumina y crece
 * un poco; las demás quedan atenuadas. Bordes superior e inferior difuminados.
 */
@Composable
private fun LinesWindow(texts: List<String>, idx: Int, look: LyricsLook) {
    val reduce = LocalReduceMotion.current
    val state = rememberLazyListState()
    val density = LocalDensity.current
    val anchor = with(density) { look.current.lineHeight.toDp() } + look.spacing
    LaunchedEffect(idx, texts) {
        val target = idx.coerceIn(0, (texts.size - 1).coerceAtLeast(0))
        if (reduce || state.layoutInfo.visibleItemsInfo.none { it.index == target }) {
            state.scrollToItem(target)
        } else {
            state.animateScrollToItem(target)
        }
    }
    val wide = if (look.centered) Modifier.fillMaxWidth() else Modifier
    val origin = TransformOrigin(if (look.centered) 0.5f else 0f, 0.5f)
    LazyColumn(
        state = state,
        userScrollEnabled = false,
        // Con fondo tras la línea, la lista se ensancha [BACKDROP_SIDE] por cada lado solo para dibujar y ese
        // mismo margen vuelve como relleno: el texto conserva su ancho (no se apila más) y el difuminado de
        // una línea larga ya no se corta en seco en los bordes de la lista.
        contentPadding = PaddingValues(
            top = anchor,
            bottom = look.height,
            start = if (look.highlightBackdrop) BACKDROP_SIDE else 0.dp,
            end = if (look.highlightBackdrop) BACKDROP_SIDE else 0.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(look.spacing),
        horizontalAlignment = if (look.centered) Alignment.CenterHorizontally else Alignment.Start,
        modifier = Modifier
            .then(if (look.highlightBackdrop) Modifier.bleedHorizontally(BACKDROP_SIDE) else Modifier)
            .fillMaxWidth()
            .height(look.height)
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.verticalGradient(
                        0f to Color.Transparent, 0.14f to Color.Black, 0.6f to Color.Black, 0.9f to Color.Transparent,
                    ),
                    blendMode = BlendMode.DstIn,
                )
            },
    ) {
        itemsIndexed(texts) { i, t ->
            val current = i == idx
            val alpha by animateFloatAsState(
                when {
                    current -> 1f
                    i < idx -> 0.28f
                    else -> 0.45f
                },
                if (reduce) snap() else tween(LINE_ANIM_MS),
                label = "a",
            )
            val scale by animateFloatAsState(if (current) 1f else 0.9f, if (reduce) snap() else tween(LINE_ANIM_MS), label = "s")
            val backdrop by animateFloatAsState(
                if (current && look.highlightBackdrop) 1f else 0f,
                if (reduce) snap() else tween(LINE_ANIM_MS + 120),
                label = "b",
            )
            BackdropText(
                t.ifBlank { "♪" },
                style = look.current,
                backdrop = look.highlightBackdrop,
                backdropAlpha = { backdrop },
                // Sin límite de renglones: una línea larga de la letra se ajusta completa (nunca "…").
                modifier = (if (look.highlightBackdrop) Modifier else wide)
                    .graphicsLayer {
                        this.alpha = alpha
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = origin
                        // Sin capa intermedia: con alpha < 1 la capa se recorta al tamaño del texto y el
                        // difuminado (que sobresale) se veía como un bloque hasta terminar la transición.
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
            )
        }
    }
}

private const val LINE_ANIM_MS = 320

/** Alcance lateral del difuminado de la línea destacada (separación + desenfoque). */
private val BACKDROP_SIDE = 46.dp

/** Ocupa [side] más por cada lado de lo que le dan (sin mover a los vecinos): sitio para dibujar. */
private fun Modifier.bleedHorizontally(side: Dp): Modifier = layout { measurable, constraints ->
    val extra = side.roundToPx()
    val wide = if (constraints.hasBoundedWidth) constraints.maxWidth + 2 * extra else constraints.maxWidth
    val p = measurable.measure(constraints.copy(minWidth = wide.coerceAtLeast(constraints.minWidth), maxWidth = wide))
    val w = (p.width - 2 * extra).coerceIn(constraints.minWidth, constraints.maxWidth)
    layout(w, p.height) { p.place(-extra, 0) }
}

/** Adelanto con que se muestra cada línea (para leerla al empezar a cantarse). */
private const val LEAD_MS = 400L
private val WINDOW_HEIGHT = 205.dp
private val Current = TextStyle(color = Color.White, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold)
private val Other = TextStyle(color = Color.White.copy(alpha = 0.45f), fontSize = 22.sp, lineHeight = 30.sp)
