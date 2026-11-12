package io.github.dacstage.ui.tv

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import io.github.dacstage.R
import io.github.dacstage.model.PlayerId
import io.github.dacstage.model.QueueItem
import io.github.dacstage.tv.TvViewModel
import io.github.dacstage.ui.common.AnimatedArtColors
import io.github.dacstage.ui.common.AppIcons
import io.github.dacstage.ui.common.ArtImages
import io.github.dacstage.ui.common.OneLine
import io.github.dacstage.ui.common.TechFormat
import io.github.dacstage.ui.common.UiText
import io.github.dacstage.ui.common.asString
import io.github.dacstage.ui.common.uniqueQueueKeys
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Mantiene el elemento enfocado centrado en la banda. */
@OptIn(ExperimentalFoundationApi::class)
private val CenteredBringIntoView = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset + size / 2f - containerSize / 2f
}

private val CardWidth = 300.dp
private val CardHeight = 104.dp


@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvQueueBand(
    vm: TvViewModel,
    playerName: String?,
    colors: AnimatedArtColors,
    onClose: () -> Unit,
    /** false durante la animación de salida: la banda ya no debe recuperar el foco. */
    open: Boolean = true,
) {
    val isOpen by androidx.compose.runtime.rememberUpdatedState(open)
    val queue by vm.queue.collectAsStateWithLifecycle()
    val jump by vm.jump.collectAsStateWithLifecycle()
    val message = UiText.queueMessage(queue, playerName)?.asString()
    var bandHasFocus by remember { mutableStateOf(false) }
    val currentReq = remember { FocusRequester() }
    val messageReq = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val current = queue.currentIndex.coerceIn(0, (queue.items.size - 1).coerceAtLeast(0))

    suspend fun focusCurrent() {
        if (message != null) {
            withFrameNanos { }
            messageReq.requestSafely()
            return
        }
        listState.scrollToItem(current)
        repeat(3) {
            withFrameNanos { }
            if (currentReq.requestSafely()) return
        }
    }

    // Al abrir: pista actual centrada y enfocada.
    LaunchedEffect(message == null) { focusCurrent() }
    // Si el foco se pierde (la lista cambió bajo el foco), volver a la pista actual.
    LaunchedEffect(Unit) {
        snapshotFlow { bandHasFocus }.collectLatest { has ->
            // Solo con la cola abierta: al cerrarla el foco vuelve a los controles y no hay que robárselo
            // (antes volvía a la banda que se iba y, al desaparecer, caía en "Letra" o el volumen).
            if (!has && isOpen) {
                delay(200)
                if (isOpen) focusCurrent()
            }
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val deep = colors.deep.value
                drawRect(Brush.verticalGradient(0f to deep.copy(alpha = 0f), 0.22f to deep.copy(alpha = 0.94f), 1f to deep))
            }
            .padding(top = 36.dp, bottom = TvDims.overscanV)
            .onPreviewKeyEvent { e ->
                if (e.key == Key.DirectionUp) {
                    if (e.type == KeyEventType.KeyDown) onClose()
                    true
                } else false
            }
            .onFocusChanged { bandHasFocus = it.hasFocus }
            .focusGroup(),
    ) {
        Row(Modifier.padding(horizontal = TvDims.overscanH), verticalAlignment = Alignment.CenterVertically) {
            OneLine(stringResource(R.string.queue_title), TvType.bodyPrimary)
            if (message == null) {
                Spacer(Modifier.width(14.dp))
                OneLine(stringResource(R.string.queue_position, queue.currentIndex + 1, queue.items.size), TvType.hint)
            }
            Spacer(Modifier.width(24.dp))
            OneLine(stringResource(R.string.tv_queue_hint), TvType.hint.copy(textAlign = androidx.compose.ui.text.style.TextAlign.End), Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        if (message != null) {
            Surface(
                onClick = {},
                modifier = Modifier.padding(horizontal = TvDims.overscanH).focusRequester(messageReq),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.08f),
                    focusedContainerColor = Color.White.copy(alpha = 0.14f),
                    contentColor = Color.White,
                    focusedContentColor = Color.White,
                ),
                scale = TvFocus.scale(1.0f),
                border = TvFocus.border(RoundedCornerShape(14.dp)),
            ) {
                OneLine(message, TvType.bodyPrimary, Modifier.padding(horizontal = 24.dp, vertical = 20.dp), maxLines = 2)
            }
        } else {
            val items = queue.items
            val keys = remember(items) { uniqueQueueKeys(items) }
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val side = ((maxWidth - CardWidth) / 2)
                CompositionLocalProvider(LocalBringIntoViewSpec provides CenteredBringIntoView) {
                    LazyRow(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = side),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        modifier = Modifier.fillMaxWidth().height(CardHeight + 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        itemsIndexed(items, key = { i, _ -> keys[i] }, contentType = { _, _ -> "q" }) { i, item ->
                            QueueCard(
                                item = item,
                                isCurrent = i == queue.currentIndex,
                                isTarget = jump?.targetKey == item.key,
                                accent = colors.accent.value,
                                modifier = if (i == current) Modifier.focusRequester(currentReq) else Modifier,
                                onClick = { if (i != queue.currentIndex) vm.jumpTo(item.key) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueCard(
    item: QueueItem,
    isCurrent: Boolean,
    isTarget: Boolean,
    accent: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Surface(
        onClick = onClick,
        modifier = modifier.width(CardWidth).height(CardHeight),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isCurrent) accent.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f),
            contentColor = Color.White,
            focusedContainerColor = if (isCurrent) accent.copy(alpha = 0.40f) else Color.White.copy(alpha = 0.18f),
            focusedContentColor = Color.White,
        ),
        scale = TvFocus.scale(1.07f),
        border = TvFocus.border(shape),
    ) {
        Row(Modifier.fillMaxSize().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            QueueThumb(item.artUrl)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                OneLine(item.title ?: stringResource(R.string.untitled), TvType.cardTitle)
                item.artist?.let { OneLine(it, TvType.cardSub) }
                val meta = when {
                    isTarget -> stringResource(R.string.queue_jumping)
                    isCurrent -> stringResource(R.string.queue_playing)
                    else -> item.durationMs?.let { TechFormat.time(it) }
                }
                meta?.let { OneLine(it, if (isCurrent || isTarget) TvType.cardSub.copy(color = Color.White) else TvType.cardSub) }
            }
        }
    }
}

@Composable
private fun QueueThumb(url: String?) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier.size(64.dp).clip(shape).background(Color.White.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        if (url == null) {
            Image(AppIcons.MusicNote, null, colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.4f)), modifier = Modifier.size(28.dp))
        } else {
            val context = LocalPlatformContext.current
            val request = remember(url) { ArtImages.thumb(context, url) }
            AsyncImage(request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
