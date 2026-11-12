package io.github.dacstage.ui.mobile

import android.content.Intent
import android.content.res.Resources
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.window.core.layout.WindowSizeClass
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import io.github.dacstage.R
import io.github.dacstage.model.playerLabel
import io.github.dacstage.model.Command
import io.github.dacstage.model.CommandOrigin
import io.github.dacstage.model.JumpProgress
import io.github.dacstage.model.PlaybackStatus
import io.github.dacstage.model.QueueState
import io.github.dacstage.model.QueueUnavailableReason
import io.github.dacstage.ui.common.AnimatedArtColors
import io.github.dacstage.ui.common.AppIcons
import io.github.dacstage.ui.common.ArtImages
import io.github.dacstage.ui.common.ArtModel
import io.github.dacstage.ui.common.ArtworkView
import io.github.dacstage.ui.common.ElapsedText
import io.github.dacstage.ui.common.LocalReduceMotion
import io.github.dacstage.ui.common.Motion
import io.github.dacstage.ui.common.NowPlayingUi
import io.github.dacstage.ui.common.ProgressAnchor
import io.github.dacstage.ui.common.ProgressLine
import io.github.dacstage.ui.common.TechChipsRow
import io.github.dacstage.ui.common.TechFormat
import io.github.dacstage.ui.common.UiText
import io.github.dacstage.ui.common.artBackground
import io.github.dacstage.ui.common.asString
import io.github.dacstage.ui.common.rememberAnimatedArtColors
import io.github.dacstage.ui.common.rememberArtwork
import io.github.dacstage.ui.common.rememberPlaybackClock
import io.github.dacstage.ui.common.uniqueQueueKeys
import kotlinx.coroutines.launch

private enum class HostLayout { COMPACT, MEDIUM, EXPANDED }

@Composable
private fun hostLayout(): HostLayout {
    val wsc = currentWindowAdaptiveInfo().windowSizeClass
    return when {
        wsc.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> HostLayout.EXPANDED
        wsc.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> HostLayout.MEDIUM
        else -> HostLayout.COMPACT
    }
}

/** Ejecuta comandos con origen HOST_UI y avisa de errores en un snackbar. */
internal class HostActions(
    private val deps: HostUiDeps,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val snackbar: SnackbarHostState,
    /** Para resolver los avisos (los comandos acaban fuera de la composición). */
    private val res: Resources,
) {
    fun run(command: Command) {
        scope.launch {
            val r = deps.controller.execute(command, CommandOrigin.HOST_UI)
            if (!r.ok) UiText.commandError(r.error)?.let { snackbar.showSnackbar(it.resolve(res)) }
        }
    }

    fun dac(block: suspend () -> io.github.dacstage.model.CommandResult) {
        scope.launch {
            val r = block()
            if (!r.ok) UiText.commandError(r.error)?.let { snackbar.showSnackbar(it.resolve(res)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HostPlayerScreen(deps: HostUiDeps, snackbar: SnackbarHostState, onOpenSettings: () -> Unit) {
    val scope = rememberCoroutineScope()
    val res = LocalResources.current
    val actions = remember(deps, scope, snackbar, res) { HostActions(deps, scope, snackbar, res) }
    val np by deps.controller.state.collectSlice { NowPlayingUi.from(it) }
    val hostName by deps.settings.hostName.collectSlice { it }
    val sessionAccess by deps.hasSessionAccess.collectSlice { it }
    val tvs by deps.server.connectedClients.collectSlice { it }
    val layout = hostLayout()
    var sheetOpen by rememberSaveable { mutableStateOf(false) }

    val density = LocalDensity.current
    val artPx = with(density) { (if (layout == HostLayout.COMPACT) 420.dp else 560.dp).roundToPx() }
    val model = remember(np.art) { np.art?.let { a -> deps.artUrlFor(a)?.let { ArtModel(a.id, it, a.widthPx, a.heightPx) } } }
    val artwork = rememberArtwork(model, np.thumbUrl, artPx)
    val colors = rememberAnimatedArtColors(artwork.colors)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
    ) { inner ->
        Box(Modifier.fillMaxSize().artBackground(colors, androidx.compose.ui.geometry.Offset(0.3f, 0.25f)).padding(inner)) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                TopRow(hostName, tvs, onOpenSettings)
                if (!sessionAccess) SessionAccessBanner(Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                val hasPlayer = np.player != null && np.title != null
                when (layout) {
                    HostLayout.COMPACT -> Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        if (hasPlayer) {
                            ArtworkView(artwork.frame, Modifier.fillMaxWidth().aspectRatio(1f), corner = 16.dp)
                            NowPlayingBlock(deps, np, colors, actions)
                            FilledTonalButton(onClick = { sheetOpen = true }, modifier = Modifier.fillMaxWidth()) {
                                Icon(AppIcons.QueueMusic, null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(R.string.queue_title))
                            }
                        } else NoPlaybackCard(deps)
                        DacPanel(deps, actions)
                        Spacer(Modifier.height(12.dp))
                    }
                    HostLayout.MEDIUM -> Row(Modifier.weight(1f).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Column(
                            Modifier.weight(1.15f).verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            if (hasPlayer) {
                                ArtworkView(artwork.frame, Modifier.fillMaxWidth().aspectRatio(1f), corner = 16.dp)
                                NowPlayingBlock(deps, np, colors, actions)
                            } else NoPlaybackCard(deps)
                            DacPanel(deps, actions)
                            Spacer(Modifier.height(12.dp))
                        }
                        QueuePanel(deps, actions, colors, Modifier.weight(1f).fillMaxHeight(), onConfigure = onOpenSettings)
                    }
                    HostLayout.EXPANDED -> Row(Modifier.weight(1f).padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        BoxWithConstraints(Modifier.weight(1.1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            val side: Dp = min(maxWidth, maxHeight)
                            if (hasPlayer) ArtworkView(artwork.frame, Modifier.size(side), corner = 18.dp)
                            else NoPlaybackCard(deps)
                        }
                        Column(
                            Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(18.dp),
                        ) {
                            if (hasPlayer) NowPlayingBlock(deps, np, colors, actions)
                            DacPanel(deps, actions)
                        }
                        QueuePanel(deps, actions, colors, Modifier.weight(0.9f).fillMaxHeight(), onConfigure = onOpenSettings)
                    }
                }
            }
        }
    }

    if (sheetOpen && layout == HostLayout.COMPACT) {
        ModalBottomSheet(onDismissRequest = { sheetOpen = false }) {
            QueuePanel(deps, actions, colors, Modifier.fillMaxWidth().height(520.dp), onConfigure = {
                sheetOpen = false
                onOpenSettings()
            })
        }
    }
}

@Composable
private fun TopRow(hostName: String, tvs: List<String>, onOpenSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            val tvText = when (tvs.size) {
                0 -> stringResource(R.string.host_no_tv)
                1 -> stringResource(R.string.host_one_tv, tvs[0])
                else -> pluralStringResource(R.plurals.host_tvs_connected, tvs.size, tvs.size)
            }
            Text("$hostName · $tvText", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings)) }
    }
}

@Composable
internal fun SessionAccessBanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.host_notification_access_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(
                    stringResource(R.string.host_notification_access_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text(stringResource(R.string.host_grant)) }
        }
    }
}

@Composable
private fun NoPlaybackCard(deps: HostUiDeps) {
    val state by deps.controller.state.collectSlice { it.playerLabel() to it.unsupportedPlayerPackage }
    val (player, unsupported) = state
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.06f))) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(AppIcons.MusicNote, null, colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.5f)), modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            val (title, body) = when {
                player == null && unsupported != null ->
                    stringResource(R.string.unsupported_player) to stringResource(R.string.host_unsupported_body, unsupported)
                player != null -> stringResource(R.string.nothing_playing) to stringResource(R.string.hint_press_play_in, player)
                else -> stringResource(R.string.nothing_playing) to stringResource(R.string.host_hint_play_music)
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NowPlayingBlock(deps: HostUiDeps, np: NowPlayingUi, colors: AnimatedArtColors, actions: HostActions) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        TrackText(np)
        TrackChips(np)
        HostProgress(deps, np, colors)
        TransportButtons(
            playing = np.status == PlaybackStatus.PLAYING || np.status == PlaybackStatus.BUFFERING,
            onPrevious = { actions.run(Command.Previous) },
            onToggle = { actions.run(Command.TogglePlayPause) },
            onNext = { actions.run(Command.Next) },
        )
    }
}

/** Reproductor, estado, título, artista, álbum y detalles, con fundido al cambiar de pista. */
@Composable
internal fun TrackText(np: NowPlayingUi) {
    val reduce = LocalReduceMotion.current
    AnimatedContent(
            targetState = np,
            contentKey = { it.trackKey },
            transitionSpec = {
                if (reduce) EnterTransition.None togetherWith ExitTransition.None
                else fadeIn(Motion.fade(false, 320, 80)) togetherWith fadeOut(Motion.fade(false, 160))
            },
            label = "hostTrack",
        ) { s ->
            Column {
                val line = listOfNotNull(s.playerName, UiText.status(s.status)?.asString()).joinToString(" · ")
                if (line.isNotEmpty()) Text(line.uppercase(), style = MaterialTheme.typography.labelLarge, color = Color(0xFFD0D0D0), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s.title ?: stringResource(R.string.untitled), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color.White)
                s.artist?.let { Text(it, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color.White) }
                s.album?.let { Text(it, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFFD0D0D0)) }
                s.detailsLine?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFFB0B0B0)) }
            }
        }
}

/** Formato y calidad (FLAC, 44.1 kHz…). */
@Composable
internal fun TrackChips(np: NowPlayingUi) {
        if (np.chips.isNotEmpty()) {
            TechChipsRow(
                np.chips,
                animationKey = np.trackKey,
                textStyle = MaterialTheme.typography.labelLarge.copy(color = Color.White),
                background = Color.White.copy(alpha = 0.10f),
                borderColor = Color.White.copy(alpha = 0.22f),
                spacing = 8.dp,
                padH = 10.dp,
                padV = 4.dp,
            )
        }
}

/** Anterior, reproducir/pausar y siguiente. */
@Composable
internal fun TransportButtons(playing: Boolean, onPrevious: () -> Unit, onToggle: () -> Unit, onNext: () -> Unit) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            FilledTonalIconButton(onClick = onPrevious, modifier = Modifier.size(56.dp)) {
                Icon(AppIcons.SkipPrevious, stringResource(R.string.action_previous))
            }
            Spacer(Modifier.width(20.dp))
            FilledIconButton(
                onClick = onToggle,
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.White, contentColor = Color(0xFF111111)),
            ) { Icon(if (playing) AppIcons.Pause else AppIcons.Play, stringResource(if (playing) R.string.action_pause else R.string.action_play), Modifier.size(34.dp)) }
            Spacer(Modifier.width(20.dp))
            FilledTonalIconButton(onClick = onNext, modifier = Modifier.size(56.dp)) {
                Icon(AppIcons.SkipNext, stringResource(R.string.action_next))
            }
        }
}

@Composable
private fun HostProgress(deps: HostUiDeps, np: NowPlayingUi, colors: AnimatedArtColors) {
    val p by deps.controller.state.collectSlice { Triple(it.progress, it.status, it.track?.durationMs) }
    val anchor = remember(p) { ProgressAnchor.stamp(p.first, p.second, p.third, SystemClock.elapsedRealtime()) }
    ProgressView(anchor, np.durationMs, colors)
}

/** Barra de progreso con tiempo transcurrido y duración. */
@Composable
internal fun ProgressView(anchor: ProgressAnchor?, durationMs: Long?, colors: AnimatedArtColors) {
    val clock = rememberPlaybackClock(anchor)
    val style = MaterialTheme.typography.labelLarge.copy(color = Color(0xFFD0D0D0))
    Column {
        if (durationMs != null) {
            ProgressLine(anchor, clock, Color.White.copy(alpha = 0.18f), colors.accent, height = 5.dp)
            Spacer(Modifier.height(6.dp))
        }
        Row(Modifier.fillMaxWidth()) {
            ElapsedText(anchor, clock, style)
            Spacer(Modifier.weight(1f))
            durationMs?.let { Text(TechFormat.time(it), style = style) }
        }
    }
}

/* ---------- Cola ---------- */

@Composable
private fun QueuePanel(deps: HostUiDeps, actions: HostActions, colors: AnimatedArtColors, modifier: Modifier, onConfigure: () -> Unit) {
    val queue by deps.controller.state.collectSlice { it.queue }
    val jump by deps.controller.state.collectSlice { it.jump }
    val player by deps.controller.state.collectSlice { it.playerLabel() }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.queue_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (queue.available && queue.items.isNotEmpty()) {
                Text(stringResource(R.string.queue_position, queue.currentIndex + 1, queue.items.size), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        jump?.let { JumpRow(it) { actions.run(Command.CancelJump) } }
        val message = UiText.queueMessage(queue, player)?.asString()
        if (message != null) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.06f))) {
                Column(Modifier.padding(20.dp)) {
                    Text(message, style = MaterialTheme.typography.bodyLarge)
                    if (queue.unavailableReason == QueueUnavailableReason.UAPP_NO_FILE_ACCESS) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onConfigure) { Text(stringResource(R.string.host_configure)) }
                    }
                }
            }
        } else {
            QueueList(queue, jump, colors) { actions.run(Command.JumpToQueueItem(it)) }
        }
    }
}

@Composable
internal fun JumpRow(j: JumpProgress, onCancel: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.queue_jump_progress, j.stepsDone, j.stepsTotal), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        }
        LinearProgressIndicator(
            progress = { if (j.stepsTotal > 0) j.stepsDone.toFloat() / j.stepsTotal else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun QueueList(queue: QueueState, jump: JumpProgress?, colors: AnimatedArtColors, onJump: (String) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(queue.version, queue.currentIndex) {
        if (queue.currentIndex >= 0) listState.animateScrollToItem((queue.currentIndex - 2).coerceAtLeast(0))
    }
    val keys = remember(queue.items) { uniqueQueueKeys(queue.items) }
    LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(queue.items, key = { i, _ -> keys[i] }) { i, item ->
            val current = i == queue.currentIndex
            val target = jump?.targetKey == item.key
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (current) colors.accent.value.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable(enabled = !current) { onJump(item.key) }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumb(item.artUrl)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.title ?: stringResource(R.string.untitled), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (current) FontWeight.SemiBold else null)
                    item.artist?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Spacer(Modifier.width(8.dp))
                val meta = when {
                    target -> stringResource(R.string.queue_jumping)
                    current -> stringResource(R.string.queue_playing)
                    else -> item.durationMs?.let { TechFormat.time(it) }
                }
                meta?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
            }
        }
    }
}

@Composable
private fun Thumb(url: String?) {
    Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
        if (url == null) {
            Image(AppIcons.MusicNote, null, colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.4f)), modifier = Modifier.size(22.dp))
        } else {
            val context = LocalPlatformContext.current
            val req = remember(url) { ArtImages.thumb(context, url) }
            AsyncImage(req, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
