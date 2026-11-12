package io.github.dacstage.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import io.github.dacstage.R
import io.github.dacstage.model.PlaybackStatus
import io.github.dacstage.tv.TvViewModel
import io.github.dacstage.ui.common.AnimatedArtColors
import io.github.dacstage.ui.common.AppIcons
import io.github.dacstage.ui.common.ArtworkState
import io.github.dacstage.ui.common.ArtworkView
import io.github.dacstage.ui.common.ElapsedText
import io.github.dacstage.ui.common.LocalReduceMotion
import io.github.dacstage.ui.common.Motion
import io.github.dacstage.ui.common.NowPlayingUi
import io.github.dacstage.ui.common.OneLine
import io.github.dacstage.ui.common.ProgressLine
import io.github.dacstage.ui.common.TechChipsRow
import io.github.dacstage.ui.common.TechFormat
import io.github.dacstage.ui.common.UiText
import io.github.dacstage.ui.common.VolumeUi
import io.github.dacstage.ui.common.asString
import io.github.dacstage.ui.common.rememberPlaybackClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Requesters del transporte + último control enfocado (para restaurar el foco). */
@Stable
internal class TransportFocus {
    val prev = FocusRequester()
    val play = FocusRequester()
    val next = FocusRequester()
    val volume = FocusRequester()
    val lyrics = FocusRequester()
    var last: FocusRequester = play

    suspend fun restore() {
        repeat(3) {
            if (last.requestSafely()) return
            withFrameNanos { }
        }
        play.requestSafely()
    }
}

@Composable
internal fun TvNowPlaying(vm: TvViewModel, artwork: ArtworkState, colors: AnimatedArtColors, artSize: Dp) {
    val np by vm.nowPlaying.collectAsStateWithLifecycle()
    val jump by vm.jump.collectAsStateWithLifecycle()
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val lyricsOn by vm.lyricsOn.collectAsStateWithLifecycle()
    val lyricsPending by vm.lyricsPending.collectAsStateWithLifecycle()
    // El modo letra se mantiene mientras esté activado: si la pista no tiene letra se avisa ahí mismo en vez
    // de volver a la vista normal (y vuelve a mostrarse la letra en la siguiente que sí tenga).
    val lyricsMode = lyricsOn
    val showLyricsButton = lyrics != null || lyricsOn
    val reduce = LocalReduceMotion.current
    var queueOpen by remember { mutableStateOf(false) }
    var volumeOpen by remember { mutableStateOf(false) }
    var hasFocus by remember { mutableStateOf(false) }
    val focus = remember { TransportFocus() }
    val openT = animateFloatAsState(if (queueOpen) 1f else 0f, Motion.spatial(reduce), label = "queueT")
    /** Zona de los botones de arriba a la derecha (coordenadas de la raíz) para que el texto no pase debajo. */
    var topBar by remember { mutableStateOf(Rect.Zero) }

    LaunchedEffect(volumeOpen) { vm.setVolumePanelOpen(volumeOpen) }

    BackHandler(enabled = jump != null || queueOpen || volumeOpen) {
        when {
            jump != null -> vm.cancelJump()
            volumeOpen -> volumeOpen = false
            queueOpen -> queueOpen = false
        }
    }

    // Foco inicial en play/pausa y restauración al cerrar la cola o el panel de volumen.
    LaunchedEffect(queueOpen, volumeOpen) {
        if (!queueOpen && !volumeOpen) {
            withFrameNanos { }
            focus.restore()
        }
    }
    // Red de seguridad: si el foco se pierde (p. ej. cambia la cola bajo el foco), volver a un control.
    LaunchedEffect(Unit) {
        snapshotFlow { hasFocus }.collectLatest { has ->
            if (!has) {
                delay(250)
                if (!queueOpen && !volumeOpen) focus.restore()
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onFocusChanged { hasFocus = it.hasFocus }
            .focusGroup(),
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = TvDims.overscanH, vertical = TvDims.overscanV)
                .graphicsLayer {
                    val t = openT.value
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    translationY = -t * 18.dp.toPx()
                    scaleX = 1f - 0.12f * t
                    scaleY = 1f - 0.12f * t
                    alpha = 1f - 0.25f * t
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkView(
                artwork.frame,
                Modifier
                    .size(artSize)
                    .shadow(18.dp, RoundedCornerShape(14.dp)),
                corner = 14.dp,
            )
            Spacer(Modifier.width(44.dp))
            Column(Modifier.weight(1f)) {
                // Cambio entre vista normal y modo letra con fundido y ajuste de tamaño (sin saltos).
                AnimatedContent(
                    targetState = lyricsMode,
                    transitionSpec = {
                        if (reduce) (EnterTransition.None togetherWith ExitTransition.None)
                        else (fadeIn(Motion.fade(false, 320, 90)) togetherWith fadeOut(Motion.fade(false, 160)))
                            .using(SizeTransform(clip = false) { _, _ -> Motion.spatial(false) })
                    },
                    label = "lyricsMode",
                ) { lm ->
                    Column {
                        if (lm) {
                            LyricsHeader(np, topBar)
                            Spacer(Modifier.height(18.dp))
                            TvLyricsView(vm, lyrics)
                            Spacer(Modifier.height(14.dp))
                        } else {
                            TrackInfo(np, topBar)
                            Spacer(Modifier.height(14.dp))
                            TechChipsRow(
                                np.chips,
                                animationKey = np.trackKey,
                                textStyle = TvType.chip,
                                background = Color.White.copy(alpha = 0.10f),
                                borderColor = Color.White.copy(alpha = 0.22f),
                            )
                            Spacer(Modifier.height(if (np.chips.isEmpty()) 0.dp else 18.dp))
                        }
                    }
                }
                TvProgress(vm, np, colors)
                Spacer(Modifier.height(20.dp))
                // En modo letra los controles se centran (desplazamiento animado).
                val bias by animateFloatAsState(if (lyricsMode) 0f else -1f, Motion.spatial(reduce), label = "transportBias")
                Box(Modifier.fillMaxWidth()) {
                    TransportRow(
                        vm = vm,
                        np = np,
                        focus = focus,
                        enabled = !queueOpen && !volumeOpen,
                        onOpenQueue = { queueOpen = true },
                        modifier = Modifier.align(BiasAlignment(bias, 0f)),
                    )
                }
                Spacer(Modifier.height(16.dp))
                FooterLine(np, lyricsMode && lyrics != null)
            }
        }

        // Arriba a la derecha (↑ desde los controles): "Letra" si hay, y el volumen, que abre su panel.
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(horizontal = TvDims.overscanH, vertical = TvDims.overscanV)
                .onGloballyPositioned { topBar = it.boundsInRoot() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showLyricsButton) {
                TvLyricsButton(
                    on = lyricsOn,
                    requester = focus.lyrics,
                    downTarget = focus.play,
                    enabled = !queueOpen && !volumeOpen,
                    onFocused = { focus.last = focus.lyrics },
                    onClick = { vm.toggleLyrics() },
                )
                Spacer(Modifier.width(14.dp))
            }
            TvVolumeButton(
                vm = vm,
                requester = focus.volume,
                downTarget = focus.play,
                enabled = !queueOpen && !volumeOpen,
                onFocused = { focus.last = focus.volume },
                onOpen = { volumeOpen = true },
            )
        }

        AnimatedVisibility(
            visible = volumeOpen,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(horizontal = TvDims.overscanH, vertical = TvDims.overscanV),
            enter = if (reduce) EnterTransition.None else fadeIn(Motion.fade(false, 160)) + slideInVertically(Motion.spatial(false)) { -it / 6 },
            exit = if (reduce) ExitTransition.None else fadeOut(Motion.fade(false, 180)),
        ) {
            TvVolumePanel(vm = vm, onClose = { volumeOpen = false })
        }

        AnimatedVisibility(
            visible = queueOpen,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = if (reduce) EnterTransition.None else slideInVertically(Motion.spatial(false)) { it } + fadeIn(Motion.fade(false, 220)),
            exit = if (reduce) ExitTransition.None else slideOutVertically(Motion.spatial(false)) { it } + fadeOut(Motion.fade(false, 180)),
        ) {
            TvQueueBand(vm = vm, playerName = np.playerName, colors = colors, onClose = { queueOpen = false }, open = queueOpen)
        }

        JumpBanner(jump, Modifier.align(Alignment.TopCenter).padding(top = TvDims.overscanV))
    }
}

/**
 * Margen derecho animado para que esta línea no pase por debajo de [bar] (botones superpuestos arriba a la
 * derecha) cuando está a su altura. Las líneas que no se cruzan en vertical usan todo el ancho.
 */
@Composable
private fun Modifier.clearOf(bar: Rect): Modifier {
    val density = LocalDensity.current
    var top by remember { mutableStateOf(Float.NaN) }
    var bottom by remember { mutableStateOf(Float.NaN) }
    val gap = with(density) { 12.dp.toPx() }
    val overlaps = bar.width > 0f && !top.isNaN() && top < bar.bottom + gap && bottom > bar.top - gap
    val reduce = LocalReduceMotion.current
    // Sin rebote: un muelle con rebote pasaba un instante por debajo de 0 y el padding negativo cerraba la app.
    val end by animateDpAsState(
        if (overlaps) with(density) { bar.width.toDp() } + 24.dp else 0.dp,
        androidx.compose.animation.core.tween(if (reduce) 0 else 260),
        label = "clearOfTopBar",
    )
    return this
        .onGloballyPositioned { val b = it.boundsInRoot(); top = b.top; bottom = b.bottom }
        .padding(end = end.coerceAtLeast(0.dp))
}

/** Cabecera del modo letra: título y artista, con fundido al cambiar de pista. */
@Composable
private fun LyricsHeader(np: NowPlayingUi, topBar: Rect) {
    val reduce = LocalReduceMotion.current
    AnimatedContent(
        targetState = np,
        contentKey = { it.trackKey },
        transitionSpec = {
            if (reduce) EnterTransition.None togetherWith ExitTransition.None
            else fadeIn(Motion.fade(false, 360, 100)) togetherWith fadeOut(Motion.fade(false, 180))
        },
        label = "lyricsHeader",
    ) { s ->
        Column {
            OneLine(s.title ?: stringResource(R.string.untitled), TvType.artist.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), Modifier.clearOf(topBar))
            s.artist?.let { OneLine(it, TvType.album, Modifier.clearOf(topBar)) }
        }
    }
}

@Composable
private fun TrackInfo(np: NowPlayingUi, topBar: Rect) {
    val reduce = LocalReduceMotion.current
    AnimatedContent(
        targetState = np,
        contentKey = { it.trackKey },
        transitionSpec = {
            if (reduce) EnterTransition.None togetherWith ExitTransition.None
            else (fadeIn(Motion.fade(false, 380, 120)) + slideInVertically(Motion.spatial(false)) { it / 8 }) togetherWith
                fadeOut(Motion.fade(false, 200))
        },
        label = "trackInfo",
    ) { s ->
        Column {
            val status = UiText.status(s.status)?.asString()
            val playerLine = listOfNotNull(s.playerName?.uppercase(), status?.uppercase()).joinToString("  ·  ")
            if (playerLine.isNotEmpty()) {
                OneLine(playerLine, TvType.playerLine, Modifier.clearOf(topBar))
                Spacer(Modifier.height(8.dp))
            }
            OneLine(s.title ?: stringResource(R.string.untitled), TvType.title, Modifier.clearOf(topBar), maxLines = 2)
            s.artist?.let {
                Spacer(Modifier.height(6.dp))
                OneLine(it, TvType.artist, Modifier.clearOf(topBar))
            }
            s.album?.let {
                Spacer(Modifier.height(4.dp))
                OneLine(it, TvType.album, Modifier.clearOf(topBar))
            }
            s.detailsLine?.let {
                Spacer(Modifier.height(4.dp))
                OneLine(it, TvType.hint)
            }
        }
    }
}

@Composable
private fun TvProgress(vm: TvViewModel, np: NowPlayingUi, colors: AnimatedArtColors) {
    val anchor by vm.progress.collectAsStateWithLifecycle()
    val clock = rememberPlaybackClock(anchor)
    Column(Modifier.fillMaxWidth()) {
        if (np.durationMs != null) {
            ProgressLine(anchor, clock, trackColor = Color.White.copy(alpha = 0.18f), fill = colors.accent, height = 6.dp)
            Spacer(Modifier.height(8.dp))
        }
        Row(Modifier.fillMaxWidth()) {
            ElapsedText(anchor, clock, TvType.time)
            Spacer(Modifier.weight(1f))
            np.durationMs?.let { OneLine(TechFormat.time(it), TvType.time) }
        }
    }
}

@Composable
private fun TransportRow(
    vm: TvViewModel,
    np: NowPlayingUi,
    focus: TransportFocus,
    enabled: Boolean,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playing = np.status == PlaybackStatus.PLAYING || np.status == PlaybackStatus.BUFFERING
    Row(
        modifier.onPreviewKeyEvent { e ->
            if (e.key == Key.DirectionDown) {
                if (e.type == KeyEventType.KeyDown) onOpenQueue()
                true
            } else false
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RoundButton(AppIcons.SkipPrevious, stringResource(R.string.action_previous), 60.dp, false, focus.prev, enabled, focus) { vm.previous() }
        Spacer(Modifier.width(18.dp))
        RoundButton(if (playing) AppIcons.Pause else AppIcons.Play, stringResource(if (playing) R.string.action_pause else R.string.action_play), 76.dp, true, focus.play, enabled, focus) {
            vm.togglePlayPause()
        }
        Spacer(Modifier.width(18.dp))
        RoundButton(AppIcons.SkipNext, stringResource(R.string.action_next), 60.dp, false, focus.next, enabled, focus) { vm.next() }
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    description: String,
    size: Dp,
    primary: Boolean,
    requester: FocusRequester,
    enabled: Boolean,
    focus: TransportFocus,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(size)
            .focusRequester(requester)
            .focusProperties {
                canFocus = enabled
                up = focus.volume
            }
            .onFocusChanged { if (it.isFocused) focus.last = requester },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) Color.White.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.12f),
            contentColor = if (primary) Color(0xFF111111) else Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF111111),
            pressedContainerColor = Color.White.copy(alpha = 0.8f),
            pressedContentColor = Color(0xFF111111),
        ),
        scale = TvFocus.scale(1.08f),
        border = TvFocus.border(CircleShape),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.align(Alignment.Center).size(size * 0.46f))
    }
}

/** Barra de nivel con marca del tope (si se conoce el rango). */
@Composable
internal fun VolumeBar(volume: VolumeUi, modifier: Modifier, accent: Color = Color.White) {
    Canvas(modifier) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(Color.White.copy(alpha = 0.22f), cornerRadius = r)
        val f = volume.fraction ?: return@Canvas
        val fill = if (volume.muted) Color.White.copy(alpha = 0.35f) else accent
        if (f > 0f) drawRoundRect(fill, size = Size(size.width * f, size.height), cornerRadius = r)
        volume.capFraction?.let { c ->
            val x = size.width * c
            drawLine(Color(0xFFFFB74D), Offset(x, -size.height * 0.6f), Offset(x, size.height * 1.6f), strokeWidth = 2.dp.toPx())
        }
    }
}

@Composable
private fun FooterLine(np: NowPlayingUi, lyricsMode: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val dac = when {
            !np.dacConnected -> stringResource(R.string.dac_disconnected)
            np.dacName != null -> stringResource(R.string.dac_named, np.dacName)
            else -> stringResource(R.string.dac_connected)
        }
        val left = if (lyricsMode) dac + "  ·  " + stringResource(R.string.tv_lyrics_source) else dac
        OneLine(left, TvType.hint, Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        OneLine(stringResource(if (np.hasLyrics) R.string.tv_footer_lyrics_volume_queue else R.string.tv_footer_volume_queue), TvType.hint)
    }
}

@Composable
private fun JumpBanner(jump: io.github.dacstage.model.JumpProgress?, modifier: Modifier) {
    val reduce = LocalReduceMotion.current
    AnimatedVisibility(
        visible = jump != null,
        modifier = modifier,
        enter = if (reduce) EnterTransition.None else fadeIn(Motion.fade(false, 200)) + slideInVertically(Motion.spatial(false)) { -it },
        exit = if (reduce) ExitTransition.None else fadeOut(Motion.fade(false, 200)),
    ) {
        var last by remember { mutableStateOf(jump) }
        if (jump != null) last = jump
        val j = last ?: return@AnimatedVisibility
        Row(
            Modifier
                .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(50))
                .padding(horizontal = 24.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OneLine(stringResource(R.string.queue_jump_progress, j.stepsDone, j.stepsTotal), TvType.bodyPrimary)
            Spacer(Modifier.width(18.dp))
            OneLine(stringResource(R.string.tv_back_to_cancel), TvType.hint)
        }
    }
}
