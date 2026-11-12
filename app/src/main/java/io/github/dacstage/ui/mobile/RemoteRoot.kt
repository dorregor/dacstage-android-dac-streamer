package io.github.dacstage.ui.mobile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.draggable
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dacstage.R
import io.github.dacstage.model.PlaybackStatus
import io.github.dacstage.network.LinkStatus
import io.github.dacstage.tv.TvViewModel
import io.github.dacstage.ui.common.AnimatedArtColors
import io.github.dacstage.ui.common.AppIcons
import io.github.dacstage.ui.common.ArtUrls
import io.github.dacstage.ui.common.ArtworkState
import io.github.dacstage.ui.common.ArtworkView
import io.github.dacstage.ui.common.LocalReduceMotion
import io.github.dacstage.ui.common.Motion
import io.github.dacstage.ui.common.NowPlayingUi
import io.github.dacstage.ui.common.UiText
import io.github.dacstage.ui.common.artBackground
import io.github.dacstage.ui.common.asString
import io.github.dacstage.ui.common.rememberAnimatedArtColors
import io.github.dacstage.ui.common.rememberArtwork
import io.github.dacstage.ui.common.rememberReduceMotion
import io.github.dacstage.ui.tv.CrossfadeLyrics
import io.github.dacstage.ui.tv.LyricsLook
import io.github.dacstage.ui.tv.TvScreen

/**
 * Control remoto en un teléfono o tablet: lo mismo que la TV (ver lo que suena, cola, letra y volumen del
 * DAC por pasos) con controles táctiles y sin salvapantallas. Usa el mismo cliente que la TV, así que se
 * empareja con el host igual que una TV (código en pantalla, se escribe en el host).
 */
@Composable
fun RemoteRoot(vm: TvViewModel, onUseAsHost: () -> Unit, modifier: Modifier = Modifier) {
    // A diferencia de la TV, no se corta al pasar a segundo plano (pantalla apagada): la conexión vive con
    // el ViewModel y RemoteKeepAliveService evita que Android congele la app. Se corta al cerrar la app.
    androidx.compose.runtime.DisposableEffect(vm) {
        vm.link.start()
        onDispose { }
    }
    val reduce = rememberReduceMotion()
    CompositionLocalProvider(LocalReduceMotion provides reduce) {
        MaterialTheme(colorScheme = HostColors) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
                RemoteContent(vm, onUseAsHost, modifier)
            }
        }
    }
}

@Composable
private fun RemoteContent(vm: TvViewModel, onUseAsHost: () -> Unit, modifier: Modifier) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val np by vm.nowPlaying.collectAsStateWithLifecycle()
    val base by vm.artBaseUrl.collectAsStateWithLifecycle()
    val status by vm.link.status.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val res = LocalResources.current
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it.resolve(res)) } }

    val artPx = with(LocalDensity.current) { 420.dp.roundToPx() }
    val model = remember(np.art, base) { ArtUrls.tvModel(np.art, base) }
    val artwork = rememberArtwork(model, np.thumbUrl, artPx)
    val colors = rememberAnimatedArtColors(artwork.colors)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        modifier = modifier,
    ) { inner ->
        Box(Modifier.fillMaxSize().artBackground(colors, Offset(0.3f, 0.25f)).padding(inner)) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                RemoteTopRow(
                    hostName = (status as? LinkStatus.Connected)?.hostName,
                    onUseAsHost = onUseAsHost,
                    onForget = { vm.link.forgetPairing() },
                )
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    if (screen == TvScreen.NowPlaying) RemotePlayer(vm, np, artwork, colors)
                    else RemoteStatus(screen, vm)
                }
            }
        }
    }
}

@Composable
private fun RemoteTopRow(hostName: String?, onUseAsHost: () -> Unit, onForget: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                hostName?.let { stringResource(R.string.remote_subtitle, it) } ?: stringResource(R.string.role_remote),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.action_more)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.remote_use_as_host)) }, onClick = { menu = false; onUseAsHost() })
                DropdownMenuItem(text = { Text(stringResource(R.string.remote_forget)) }, onClick = { menu = false; onForget() })
            }
        }
    }
}

/* ---------- Conexión ---------- */

@Composable
private fun RemoteStatus(screen: TvScreen, vm: TvViewModel) {
    Column(
        Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
    ) {
        when (screen) {
            TvScreen.Searching -> {
                CircularProgressIndicator()
                Heading(stringResource(R.string.tv_searching))
                Body(stringResource(R.string.tv_searching_hint))
                BluetoothHint(vm)
                Spacer(Modifier.height(8.dp))
                ManualIp(vm)
                HostChoices(vm, current = null)
            }
            is TvScreen.Connecting -> {
                CircularProgressIndicator()
                Heading(stringResource(R.string.tv_connecting, screen.host))
            }
            is TvScreen.Pairing -> {
                Heading(stringResource(R.string.tv_pair_with, screen.host))
                Text(
                    screen.code,
                    style = MaterialTheme.typography.displayLarge.copy(letterSpacing = 8.sp),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Body(stringResource(R.string.tv_pair_hint))
                Spacer(Modifier.height(8.dp))
                HostChoices(vm, current = screen.host)
            }
            is TvScreen.Disconnected -> {
                Heading(stringResource(R.string.tv_disconnected))
                screen.reason?.let { UiText.disconnectReason(it).asString() }?.takeIf { it.isNotBlank() }?.let { Body(it) }
                Body(
                    screen.retryInMs?.let { stringResource(R.string.tv_retry_in, (it + 999) / 1000) }
                        ?: stringResource(R.string.tv_retrying),
                )
                Button(onClick = { vm.retry() }) { Text(stringResource(R.string.tv_retry_now)) }
                BluetoothHint(vm)
                Spacer(Modifier.height(8.dp))
                ManualIp(vm)
                HostChoices(vm, current = null)
            }
            is TvScreen.Waiting -> {
                CircularProgressIndicator()
                Heading(stringResource(R.string.tv_connected_to, screen.host))
            }
            is TvScreen.NoPlayback -> {
                Image(AppIcons.MusicNote, null, colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.5f)), modifier = Modifier.size(56.dp))
                Heading(stringResource(R.string.tv_nothing_playing_on, screen.host))
                Body(screen.playerName?.let { stringResource(R.string.hint_press_play_in, it) } ?: stringResource(R.string.tv_hint_play_music))
                Spacer(Modifier.height(8.dp))
                RemoteVolume(vm)
            }
            is TvScreen.UnsupportedPlayer -> {
                Heading(stringResource(R.string.unsupported_player))
                Body(stringResource(R.string.tv_unsupported_playing, screen.packageName, screen.host))
                Body(stringResource(R.string.supported_players))
            }
            TvScreen.NowPlaying -> Unit
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

/** Bluetooth apagado o sin permiso: sin él no se llega al host si la red aísla a los equipos Wi‑Fi. */
@Composable
private fun BluetoothHint(vm: TvViewModel) {
    val flow = vm.link.bluetoothReady ?: return
    val ready by flow.collectAsStateWithLifecycle()
    if (ready) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val perms = remember { io.github.dacstage.network.DacStageBluetooth.clientPermissions() }
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { }
    Body(stringResource(R.string.remote_bt_off))
    if (!io.github.dacstage.network.DacStageBluetooth.granted(context, perms)) {
        OutlinedButton(onClick = { launcher.launch(perms) }) { Text(stringResource(R.string.remote_bt_grant)) }
    }
}

/** Respaldo si no se encuentra el host: escribir su IP. */
@Composable
private fun ManualIp(vm: TvViewModel) {
    var text by rememberSaveable { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    fun go() { invalid = !vm.connectManual(text) }
    Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.tv_manual_ip_prompt), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { v ->
                    text = v.filter { it.isDigit() || it == '.' || it == ':' }.take(21)
                    invalid = false
                },
                singleLine = true,
                isError = invalid,
                placeholder = { Text("192.168.1.x") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = { go() }, enabled = text.isNotBlank()) { Text(stringResource(R.string.remote_connect)) }
        }
        if (invalid) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.tv_manual_ip_invalid), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Otros hosts visibles en la red (elegir uno lo deja como preferido). */
@Composable
private fun HostChoices(vm: TvViewModel, current: String?) {
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val others = hosts.filter { it.serviceName != current }.distinctBy { it.hostId ?: it.serviceName }
    if (others.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.tv_other_hosts), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    others.take(6).forEach { h ->
        OutlinedButton(onClick = { vm.connectTo(h) }) { Text(h.serviceName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/* ---------- Reproducción ---------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemotePlayer(vm: TvViewModel, np: NowPlayingUi, artwork: ArtworkState, colors: AnimatedArtColors) {
    val lyrics by vm.lyrics.collectAsStateWithLifecycle()
    val lyricsOn by vm.lyricsOn.collectAsStateWithLifecycle()
    val anchor by vm.progress.collectAsStateWithLifecycle()
    val reduce = LocalReduceMotion.current
    var queueOpen by rememberSaveable { mutableStateOf(false) }

    // Todo en una pantalla, sin desplazamiento: la carátula (o la letra) ocupa el espacio que sobra y el
    // resto tiene alto fijo.
    Column(
        Modifier.widthIn(max = 560.dp).fillMaxSize().padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AnimatedContent(
            targetState = lyricsOn,
            transitionSpec = {
                if (reduce) EnterTransition.None togetherWith ExitTransition.None
                else fadeIn(Motion.fade(false, 320, 90)) togetherWith fadeOut(Motion.fade(false, 160))
            },
            label = "remoteLyrics",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { on ->
            if (on) {
                // Letra: carátula pequeña arriba y la letra en todo el espacio libre (se mantiene aunque una
                // pista no tenga letra).
                Column(Modifier.fillMaxSize()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ArtworkView(artwork.frame, Modifier.size(56.dp), corner = 10.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(np.title ?: stringResource(R.string.untitled), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            np.artist?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFD0D0D0), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                        CrossfadeLyrics(vm, lyrics, LyricsLook.Phone.withHeight(maxHeight))
                    }
                    if (lyrics != null) {
                        Text(stringResource(R.string.tv_lyrics_source), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val queue by vm.queue.collectAsStateWithLifecycle()
                    val idx = queue.currentIndex.takeIf { queue.available } ?: -1
                    SwipeArtwork(
                        frame = artwork.frame,
                        trackKey = np.trackKey,
                        previousArt = if (idx >= 0) queue.items.getOrNull(idx - 1)?.artUrl else null,
                        nextArt = if (idx >= 0) queue.items.getOrNull(idx + 1)?.artUrl else null,
                        onNext = vm::next,
                        onPrevious = vm::previous,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                    TrackText(np)
                    TrackChips(np)
                }
            }
        }
        ProgressView(anchor, np.durationMs, colors)
        TransportButtons(
            playing = np.status == PlaybackStatus.PLAYING || np.status == PlaybackStatus.BUFFERING,
            onPrevious = vm::previous,
            onToggle = vm::togglePlayPause,
            onNext = vm::next,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = { queueOpen = true }, modifier = Modifier.weight(1f)) {
                Icon(AppIcons.QueueMusic, null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.queue_title))
            }
            if (lyrics != null || lyricsOn) {
                FilledTonalButton(onClick = vm::toggleLyrics, modifier = Modifier.weight(1f)) {
                    Text(stringResource(if (lyricsOn) R.string.tv_lyrics_hide else R.string.tv_lyrics))
                }
            }
        }
        RemoteVolumeRow(vm)
    }

    if (queueOpen) {
        ModalBottomSheet(onDismissRequest = { queueOpen = false }) {
            RemoteQueue(vm, np, colors, Modifier.fillMaxWidth().height(520.dp).padding(horizontal = 16.dp))
        }
    }
}

/**
 * Carrusel de carátulas: la actual en el centro y, a los lados, la anterior y la siguiente de la cola.
 * Arrastrar mueve las tres con el dedo (sin intermediarios, se dibuja en cada fotograma); al soltar pasado
 * un tercio (o con un gesto rápido) la vecina llega al centro, se envía el comando y, cuando el host ya
 * cambió de pista y su carátula está lista, se intercambian sin salto. Si no cambia (p. ej. "anterior" solo
 * vuelve al inicio), regresa a la actual.
 */
@Composable
private fun SwipeArtwork(
    frame: io.github.dacstage.ui.common.ArtFrame?,
    trackKey: String?,
    previousArt: String?,
    nextArt: String?,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var offset by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    val key by androidx.compose.runtime.rememberUpdatedState(trackKey)
    val frameId by androidx.compose.runtime.rememberUpdatedState(frame?.id)
    val reduce = LocalReduceMotion.current
    androidx.compose.foundation.layout.BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = androidx.compose.ui.unit.min(maxWidth, maxHeight)
        val density = LocalDensity.current
        // Distancia entre carátulas: el ancho disponible + un hueco, así en reposo las vecinas no se ven.
        val pagePx = with(density) { (maxWidth + 24.dp).toPx() }.coerceAtLeast(1f)

        suspend fun animateOffset(target: Float, spec: androidx.compose.animation.core.AnimationSpec<Float>, velocity: Float = 0f) {
            androidx.compose.animation.core.animate(offset, target, velocity, spec) { v, _ -> offset = v }
        }

        suspend fun settle(velocity: Float) {
            busy = true
            try {
                val f = offset / pagePx
                // 1 = siguiente (se arrastró a la izquierda), -1 = anterior.
                val dir = when {
                    (f < -0.3f || velocity < -1500f) && f < 0.05f -> 1
                    (f > 0.3f || velocity > 1500f) && f > -0.05f -> -1
                    else -> 0
                }
                if (dir == 0) {
                    animateOffset(0f, androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 420f), velocity)
                    return
                }
                val beforeKey = key
                val beforeFrame = frameId
                animateOffset(
                    -dir * pagePx,
                    if (reduce) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 340f),
                    velocity,
                )
                if (dir > 0) onNext() else onPrevious()
                val changed = kotlinx.coroutines.withTimeoutOrNull(2_500) {
                    androidx.compose.runtime.snapshotFlow { key }.first { it != beforeKey }
                } != null
                if (changed) {
                    // La vecina ya está en el centro: se espera la carátula de la pista nueva para no ver la
                    // anterior un instante al volver a la posición de reposo.
                    kotlinx.coroutines.withTimeoutOrNull(1_500) {
                        androidx.compose.runtime.snapshotFlow { frameId }.first { it != beforeFrame }
                    }
                    offset = 0f
                } else {
                    animateOffset(0f, androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 300f))
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                offset = 0f
                throw e
            } finally {
                busy = false
            }
        }

        val drag = androidx.compose.foundation.gestures.rememberDraggableState { d ->
            if (!busy) offset = (offset + d).coerceIn(-pagePx, pagePx)
        }
        Box(
            Modifier
                .fillMaxSize()
                .draggable(
                    state = drag,
                    orientation = androidx.compose.foundation.gestures.Orientation.Horizontal,
                    enabled = !busy,
                    onDragStopped = { velocity -> scope.launch { settle(velocity) } },
                ),
            contentAlignment = Alignment.Center,
        ) {
            // Cercanía al centro (1 = en el centro): tamaño y opacidad algo menores fuera del centro.
            fun Modifier.page(shift: Float) = graphicsLayer {
                val x = offset + shift * pagePx
                val near = 1f - (kotlin.math.abs(x) / pagePx).coerceIn(0f, 1f)
                translationX = x
                val sc = 0.9f + 0.1f * near
                scaleX = sc
                scaleY = sc
                alpha = 0.55f + 0.45f * near
            }
            NeighborArt(previousArt, Modifier.size(side).page(-1f))
            NeighborArt(nextArt, Modifier.size(side).page(1f))
            ArtworkView(frame, Modifier.size(side).page(0f), corner = 16.dp)
        }
    }
}

/** Carátula de una pista vecina de la cola (o un marcador si no hay). */
@Composable
private fun NeighborArt(url: String?, modifier: Modifier) {
    Box(
        modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
            .background(Color(0xFF1E2230)),
        contentAlignment = Alignment.Center,
    ) {
        if (url == null) {
            Image(AppIcons.MusicNote, null, colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.35f)), modifier = Modifier.size(64.dp))
        } else {
            val context = coil3.compose.LocalPlatformContext.current
            val req = remember(url) { io.github.dacstage.ui.common.ArtImages.thumb(context, url, 512) }
            coil3.compose.AsyncImage(req, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Volumen compacto en una fila: DAC y nivel a la izquierda, −, silencio y + a la derecha. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteVolumeRow(vm: TvViewModel) {
    val v by vm.volume.collectAsStateWithLifecycle()
    val np by vm.nowPlaying.collectAsStateWithLifecycle()
    val hud by vm.volumeHud.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.35f))) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    np.dacName ?: stringResource(if (v.connected) R.string.dac_usb else R.string.dac_none),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(v.label.asString(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (v.muted) {
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.volume_muted), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                    }
                }
                (hud?.note ?: v.note)?.let {
                    Text(UiText.volumeNote(it).asString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            OutlinedIconButton(enabled = v.connected, onClick = { vm.volumeStep(-1, 0) }) {
                Icon(AppIcons.VolumeDown, stringResource(R.string.dac_volume_down))
            }
            FilledTonalIconToggleButton(checked = v.muted, enabled = v.connected, onCheckedChange = { vm.toggleMute() }) {
                Icon(if (v.muted) AppIcons.VolumeOff else AppIcons.VolumeUp, stringResource(R.string.volume_muted))
            }
            OutlinedIconButton(enabled = v.connected, onClick = { vm.volumeStep(+1, 0) }) {
                Icon(AppIcons.VolumeUp, stringResource(R.string.dac_volume_up))
            }
        }
    }
}

@Composable
private fun RemoteQueue(vm: TvViewModel, np: NowPlayingUi, colors: AnimatedArtColors, modifier: Modifier) {
    val queue by vm.queue.collectAsStateWithLifecycle()
    val jump by vm.jump.collectAsStateWithLifecycle()
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.queue_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (queue.available && queue.items.isNotEmpty()) {
                Text(stringResource(R.string.queue_position, queue.currentIndex + 1, queue.items.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        jump?.let { JumpRow(it) { vm.cancelJump() } }
        val message = UiText.queueMessage(queue, np.playerName)?.asString()
        if (message != null) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.06f))) {
                Text(message, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(20.dp))
            }
        } else {
            QueueList(queue, jump, colors) { vm.jumpTo(it) }
        }
    }
}

/** Volumen del DAC por pasos de ±1 dB y silencio, como desde la TV (el tope y el slider son solo del host). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoteVolume(vm: TvViewModel) {
    val v by vm.volume.collectAsStateWithLifecycle()
    val np by vm.nowPlaying.collectAsStateWithLifecycle()
    val hud by vm.volumeHud.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.35f))) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("DAC", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                np.dacName ?: stringResource(if (v.connected) R.string.dac_usb else R.string.dac_none),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    v.label.asString(),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (v.muted) {
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.volume_muted), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                }
            }
            v.fraction?.let { f -> LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth()) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedIconButton(enabled = v.connected, onClick = { vm.volumeStep(-1, 0) }) {
                    Icon(AppIcons.VolumeDown, stringResource(R.string.dac_volume_down))
                }
                FilledTonalIconToggleButton(checked = v.muted, enabled = v.connected, onCheckedChange = { vm.toggleMute() }) {
                    Icon(if (v.muted) AppIcons.VolumeOff else AppIcons.VolumeUp, stringResource(R.string.volume_muted))
                }
                OutlinedIconButton(enabled = v.connected, onClick = { vm.volumeStep(+1, 0) }) {
                    Icon(AppIcons.VolumeUp, stringResource(R.string.dac_volume_up))
                }
            }
            (hud?.note ?: v.note)?.let {
                Text(UiText.volumeNote(it).asString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
