package io.github.dacstage.ui.tv

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.animateFloatAsState
import io.github.dacstage.model.TvDisplayPrefs
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.border
import io.github.dacstage.ui.common.NowPlayingUi
import io.github.dacstage.tv.EarthPhoto
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import io.github.dacstage.R
import io.github.dacstage.network.HostLink
import io.github.dacstage.tv.TvViewModel
import io.github.dacstage.tv.VolumeHud
import io.github.dacstage.ui.common.AnimatedArtColors
import io.github.dacstage.ui.common.AppIcons
import io.github.dacstage.ui.common.ArtFrame
import io.github.dacstage.ui.common.ArtUrls
import io.github.dacstage.ui.common.ArtworkView
import io.github.dacstage.ui.common.LocalReduceMotion
import io.github.dacstage.ui.common.Motion
import io.github.dacstage.ui.common.OneLine
import io.github.dacstage.ui.common.UiText
import io.github.dacstage.ui.common.VolumeNote
import io.github.dacstage.ui.common.artBackground
import io.github.dacstage.ui.common.asString
import io.github.dacstage.ui.common.rememberAnimatedArtColors
import io.github.dacstage.ui.common.rememberArtwork
import io.github.dacstage.ui.common.rememberReduceMotion
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * Punto de montaje de la TV. Arranca/para el enlace con el ciclo de vida (ON_START/ON_STOP).
 * MainActivity debe usar el mismo [viewModel] para reenviar teclas (dispatchKeyEvent → onKeyEvent).
 */
@Composable
fun TvRoot(
    link: HostLink,
    modifier: Modifier = Modifier,
    viewModel: TvViewModel = viewModel(factory = TvViewModel.factory(link)),
) {
    LifecycleStartEffect(link) {
        link.start()
        onStopOrDispose { link.stop() }
    }
    val reduce = rememberReduceMotion()
    CompositionLocalProvider(LocalReduceMotion provides reduce) {
        TvTheme { TvContent(viewModel, modifier) }
    }
}

@Composable
private fun TvContent(vm: TvViewModel, modifier: Modifier) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val np by vm.nowPlaying.collectAsStateWithLifecycle()
    val base by vm.artBaseUrl.collectAsStateWithLifecycle()
    val idle by vm.idle.collectAsStateWithLifecycle()
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val lyricsOn by vm.lyricsOn.collectAsStateWithLifecycle()
    val lyricsPending by vm.lyricsPending.collectAsStateWithLifecycle()
    val display by vm.tvDisplay.collectAsStateWithLifecycle()
    val reduce = LocalReduceMotion.current
    val showsArt = screen == TvScreen.NowPlaying
    val artModel = remember(np.art, base, showsArt) { if (showsArt) ArtUrls.tvModel(np.art, base) else null }

    // Pantalla siempre encendida mientras suena (la pantalla de reposo evita el burn-in).
    val keepOn = showsArt && np.isPlaying
    val view = LocalView.current
    DisposableEffect(keepOn) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }

    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        val artSize = maxHeight * 0.70f
        val artPx = with(LocalDensity.current) { artSize.roundToPx() }
        val artwork = rememberArtwork(artModel, np.thumbUrl, artPx)
        val colors = rememberAnimatedArtColors(artwork.colors)

        Box(Modifier.fillMaxSize().artBackground(colors)) {
            AnimatedContent(
                targetState = screen,
                contentKey = { it::class },
                transitionSpec = {
                    if (reduce) EnterTransition.None togetherWith ExitTransition.None
                    else fadeIn(Motion.fade(false, 420)) togetherWith fadeOut(Motion.fade(false, 260))
                },
                label = "screen",
            ) { s ->
                if (s == TvScreen.NowPlaying) TvNowPlaying(vm, artwork, colors, artSize)
                else TvEmptyState(s, vm)
            }
        }

        // La primera foto se descarga y decodifica antes del reposo: al entrar ya está lista.
        val photo = rememberEarthPhoto(display.earthView, display.photoIntervalSec, running = idle)

        AnimatedVisibility(
            visible = idle,
            enter = if (reduce) EnterTransition.None else fadeIn(tween(1200)),
            exit = if (reduce) ExitTransition.None else fadeOut(tween(350)),
        ) {
            // Fondo: foto de Earth View si está activado y ya cargó; si no, el de la carátula.
            val lyricsMode = lyricsOn && (lyrics != null || lyricsPending)
            Box(Modifier.fillMaxSize().artBackground(colors, Offset(0.5f, 0.5f))) {
                // Sin API (o aún cargando) no dibuja nada: queda el reposo de siempre.
                PhotoBackdrop(photo, display.photoIntervalSec)
                // Opacidad del fondo de los textos: ajuste del host adaptado a la claridad de cada foto.
                val backdropAlpha by animateFloatAsState(
                    adaptiveBackdropAlpha(display.backdropOpacity, photo?.photo),
                    tween(1200),
                    label = "backdropAlpha",
                )
                // Con el modo letra activo al entrar en reposo, la letra sigue (en grande).
                val baseDensity = LocalDensity.current
                val scaled = remember(baseDensity, display.textScale) {
                    androidx.compose.ui.unit.Density(
                        baseDensity.density,
                        baseDensity.fontScale * display.textScale.coerceIn(
                            TvDisplayPrefs.MIN_TEXT_SCALE, TvDisplayPrefs.MAX_TEXT_SCALE,
                        ),
                    )
                }
                CompositionLocalProvider(LocalBackdropAlpha provides backdropAlpha, LocalDensity provides scaled) {
                if (lyricsMode) {
                    TvLyricsScreensaver(vm, np.chips, np.trackKey, lyrics, artwork.frame, np.title, np.artist, photo?.photo, display)
                } else {
                    TvScreensaver(artwork.frame, artSize, np, photo?.photo, display)
                }
                }
            }
        }

        VolumeHudOverlay(vm, Modifier.align(Alignment.TopEnd).padding(horizontal = TvDims.overscanH, vertical = TvDims.overscanV))
        MessageOverlay(vm, Modifier.align(Alignment.BottomCenter).padding(bottom = TvDims.overscanV + 8.dp))
    }
}

/* ---------- Estados vacíos ---------- */

@Composable
private fun TvEmptyState(screen: TvScreen, vm: TvViewModel) {
    Box(
        Modifier.fillMaxSize().padding(horizontal = TvDims.overscanH * 2, vertical = TvDims.overscanV),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 720.dp)) {
            when (screen) {
                TvScreen.Searching -> {
                    Pulse()
                    Spacer(Modifier.height(28.dp))
                    Centered(stringResource(R.string.tv_searching), TvType.heading)
                    Spacer(Modifier.height(12.dp))
                    Centered(stringResource(R.string.tv_searching_hint), TvType.body)
                    Spacer(Modifier.height(36.dp))
                    TvManualIp(vm)
                    Spacer(Modifier.height(28.dp))
                    TvHostChooser(vm, current = null)
                }
                is TvScreen.Connecting -> {
                    Pulse()
                    Spacer(Modifier.height(28.dp))
                    Centered(stringResource(R.string.tv_connecting, screen.host), TvType.heading)
                }
                is TvScreen.Pairing -> {
                    Centered(stringResource(R.string.tv_pair_with, screen.host), TvType.heading)
                    Spacer(Modifier.height(24.dp))
                    OneLine(screen.code, TvType.code)
                    Spacer(Modifier.height(20.dp))
                    Centered(stringResource(R.string.tv_pair_hint), TvType.bodyPrimary)
                    Spacer(Modifier.height(36.dp))
                    TvHostChooser(vm, current = screen.host)
                }
                is TvScreen.Disconnected -> {
                    Centered(stringResource(R.string.tv_disconnected), TvType.heading)
                    screen.reason?.let { UiText.disconnectReason(it).asString() }?.takeIf { it.isNotBlank() }?.let {
                        Spacer(Modifier.height(10.dp))
                        Centered(it, TvType.body)
                    }
                    Spacer(Modifier.height(10.dp))
                    val retry = screen.retryInMs?.let { stringResource(R.string.tv_retry_in, (it + 999) / 1000) }
                        ?: stringResource(R.string.tv_retrying)
                    Centered(retry, TvType.body)
                    Spacer(Modifier.height(28.dp))
                    TvActionButton(stringResource(R.string.tv_retry_now)) { vm.retry() }
                    Spacer(Modifier.height(28.dp))
                    TvManualIp(vm)
                    Spacer(Modifier.height(28.dp))
                    TvHostChooser(vm, current = null)
                }
                is TvScreen.Waiting -> {
                    Pulse()
                    Spacer(Modifier.height(28.dp))
                    Centered(stringResource(R.string.tv_connected_to, screen.host), TvType.heading)
                }
                is TvScreen.NoPlayback -> {
                    Image(
                        AppIcons.MusicNote, null,
                        colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.5f)),
                        modifier = Modifier.size(72.dp),
                    )
                    Spacer(Modifier.height(20.dp))
                    Centered(stringResource(R.string.tv_nothing_playing_on, screen.host), TvType.heading)
                    Spacer(Modifier.height(12.dp))
                    val hint = screen.playerName?.let { stringResource(R.string.hint_press_play_in, it) }
                        ?: stringResource(R.string.tv_hint_play_music)
                    Centered(hint, TvType.body)
                    Spacer(Modifier.height(8.dp))
                    DacStatusLine(vm)
                }
                is TvScreen.UnsupportedPlayer -> {
                    Centered(stringResource(R.string.unsupported_player), TvType.heading)
                    Spacer(Modifier.height(12.dp))
                    Centered(stringResource(R.string.tv_unsupported_playing, screen.packageName, screen.host), TvType.bodyPrimary)
                    Spacer(Modifier.height(8.dp))
                    Centered(stringResource(R.string.supported_players), TvType.body)
                }
                TvScreen.NowPlaying -> Unit
            }
        }
    }
}

@Composable
private fun DacStatusLine(vm: TvViewModel) {
    val volume by vm.volume.collectAsStateWithLifecycle()
    val np by vm.nowPlaying.collectAsStateWithLifecycle()
    val text = when {
        !volume.connected -> stringResource(R.string.dac_disconnected)
        np.dacName != null -> stringResource(R.string.tv_dac_status_named, np.dacName.orEmpty(), volume.label.asString())
        else -> stringResource(R.string.tv_dac_status_connected, volume.label.asString())
    }
    Centered(text, TvType.hint)
}

@Composable
internal fun Centered(text: String, style: TextStyle) {
    OneLine(text, style.copy(textAlign = TextAlign.Center), maxLines = 3)
}

@Composable
private fun TvActionButton(label: String, onClick: () -> Unit) {
    val req = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        req.requestSafely()
    }
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        modifier = Modifier.focusRequester(req),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.14f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF111111),
        ),
        scale = TvFocus.scale(1.06f),
        border = TvFocus.border(shape),
    ) {
        androidx.tv.material3.Text(
            label,
            style = TvType.bodyPrimary.copy(color = Color.Unspecified),
            modifier = Modifier.padding(horizontal = 32.dp, vertical = 14.dp),
        )
    }
}

/** Indicador de búsqueda: pulso lento (estático si se reducen animaciones). */
@Composable
private fun Pulse() {
    val reduce = LocalReduceMotion.current
    val t = if (reduce) null else rememberInfiniteTransition(label = "pulse").animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse), label = "p",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(14.dp)
                    .graphicsLayer {
                        val v = t?.value ?: 1f
                        alpha = if (i == 1) v else 1.35f - v
                    }
                    .background(Color.White, RoundedCornerShape(50)),
            )
        }
    }
}

/* ---------- Superposiciones ---------- */

@Composable
private fun VolumeHudOverlay(vm: TvViewModel, modifier: Modifier) {
    val hud by vm.volumeHud.collectAsStateWithLifecycle()
    val panelOpen by vm.volumePanelOpen.collectAsStateWithLifecycle()
    val reduce = LocalReduceMotion.current
    var last by remember { mutableStateOf<VolumeHud?>(null) }
    hud?.let { last = it }
    AnimatedVisibility(
        visible = hud != null && !panelOpen,
        modifier = modifier,
        enter = if (reduce) EnterTransition.None
        else fadeIn(Motion.fade(false, 160)) + slideInHorizontally(Motion.spatial(false)) { it / 3 },
        exit = if (reduce) ExitTransition.None else fadeOut(Motion.fade(false, 300)),
    ) {
        val h = last ?: return@AnimatedVisibility
        val v = h.volume
        val note = h.note ?: v.note ?: if (v.atCap) VolumeNote.CAP_REACHED else null
        Column(
            Modifier
                .width(300.dp)
                .shadow(12.dp, RoundedCornerShape(18.dp))
                .background(Color(0xE6101216), RoundedCornerShape(18.dp))
                .padding(horizontal = 22.dp, vertical = 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    if (v.muted || !v.connected) AppIcons.VolumeOff else AppIcons.VolumeUp,
                    null,
                    colorFilter = ColorFilter.tint(Color.White),
                    modifier = Modifier.size(30.dp),
                )
                Spacer(Modifier.width(14.dp))
                OneLine(if (v.muted && v.connected) stringResource(R.string.volume_muted) else v.label.asString(), TvType.hudValue)
            }
            if (v.fraction != null && v.connected) {
                Spacer(Modifier.height(14.dp))
                VolumeBar(v, Modifier.fillMaxWidth().padding(vertical = 4.dp).height(8.dp))
            }
            if (note != null) {
                Spacer(Modifier.height(10.dp))
                val c = if (note == VolumeNote.CAP_REACHED) Color(0xFFFFCC80) else Color(0xFFD0D0D0)
                OneLine(UiText.volumeNote(note).asString(), TvType.hint.copy(color = c))
            }
        }
    }
}

@Composable
private fun MessageOverlay(vm: TvViewModel, modifier: Modifier) {
    val msg by vm.message.collectAsStateWithLifecycle()
    val reduce = LocalReduceMotion.current
    var last by remember { mutableStateOf<UiText?>(null) }
    msg?.let { last = it }
    AnimatedVisibility(
        visible = msg != null,
        modifier = modifier,
        enter = if (reduce) EnterTransition.None
        else fadeIn(Motion.fade(false, 200)) + slideInVertically(Motion.spatial(false)) { it },
        exit = if (reduce) ExitTransition.None else fadeOut(Motion.fade(false, 250)),
    ) {
        OneLine(
            last?.asString().orEmpty(),
            TvType.bodyPrimary,
            Modifier
                .background(Color(0xE6101216), RoundedCornerShape(50))
                .padding(horizontal = 28.dp, vertical = 12.dp),
        )
    }
}

/* ---------- Reposo ---------- */

/** Solo carátula + degradado, con deriva lenta (anti burn-in). Sin animación: salta de sitio cada minuto. */
@Composable
private fun TvScreensaver(frame: ArtFrame?, artSize: Dp, np: NowPlayingUi, photo: EarthPhoto?, display: TvDisplayPrefs) {
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
        val inf = rememberInfiniteTransition(label = "drift")
        val x = inf.animateFloat(-1f, 1f, infiniteRepeatable(tween(47_000, easing = LinearEasing), RepeatMode.Reverse), label = "x")
        val y = inf.animateFloat(-1f, 1f, infiniteRepeatable(tween(61_000, easing = LinearEasing), RepeatMode.Reverse), label = "y")
        dx = { x.value }
        dy = { y.value }
    }
    AnimatedContent(
        targetState = photo,
        contentKey = { it != null },
        transitionSpec = {
            if (reduce) EnterTransition.None togetherWith ExitTransition.None
            else (fadeIn(tween(1400, delayMillis = 300)) + scaleIn(tween(1400, delayMillis = 300), initialScale = 0.96f)) togetherWith
                (fadeOut(tween(700)) + scaleOut(tween(700), targetScale = 1.03f))
        },
        label = "screensaverLayout",
    ) { photo ->
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (photo == null) {
            // Sin foto (o sin API): el reposo de siempre, la carátula derivando por toda la pantalla.
            if (!display.showArtwork) return@BoxWithConstraints
            val size = artSize * 0.78f
            val rangeX = (maxWidth - size) / 2 - TvDims.overscanH
            val rangeY = (maxHeight - size) / 2 - TvDims.overscanV
            ArtworkView(
                frame,
                Modifier
                    .align(Alignment.Center)
                    .graphicsLayer {
                        translationX = dx() * rangeX.toPx()
                        translationY = dy() * rangeY.toPx()
                    }
                    .size(size),
                corner = 16.dp,
            )
            return@BoxWithConstraints
        }
        // Con foto: la carátula deriva por toda la pantalla, igual que en el reposo de siempre.
        val chips = if (display.showTech) np.chips else emptyList()
        val showText = display.showSongInfo || display.showPhotoInfo || chips.isNotEmpty()
        val size = artSize * 0.78f
        val rangeX = (maxWidth - size) / 2 - TvDims.overscanH
        val rangeY = (maxHeight - size) / 2 - TvDims.overscanV
        val artAlpha = LocalBackdropAlpha.current
        if (display.showArtwork) ArtworkView(
            frame,
            Modifier
                .align(Alignment.Center)
                .graphicsLayer {
                    translationX = dx() * rangeX.toPx()
                    translationY = dy() * rangeY.toPx()
                }
                .size(size)
                .softBackdrop(alpha = { artAlpha * 1.1f }, spread = 12.dp, blur = 28.dp, corner = 24.dp)
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(16.dp)),
            corner = 16.dp,
        )
        if (showText) PhotoInfoBlock(
            title = np.title.takeIf { display.showSongInfo },
            album = np.album.takeIf { display.showSongInfo },
            photo = photo.takeIf { display.showPhotoInfo },
            chips = chips,
            chipsKey = np.trackKey,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = TvDims.overscanH, bottom = TvDims.overscanV + 4.dp)
                .widthIn(max = maxWidth * 0.55f)
                // Deriva mínima (anti burn-in) sin salir de su esquina.
                .graphicsLayer {
                    // En píxeles enteros: con fracciones cada letra se ajusta a la cuadrícula por separado y
                    // parecen moverse de a una. Así el bloque entero avanza junto.
                    translationX = kotlin.math.round((dx() + 1f) * 5.dp.toPx())
                    translationY = kotlin.math.round((dy() - 1f) * 4.dp.toPx())
                },
        )
    }
    }
}
