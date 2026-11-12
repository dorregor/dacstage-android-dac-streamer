package io.github.dacstage.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import io.github.dacstage.R
import io.github.dacstage.tv.TvViewModel
import io.github.dacstage.ui.common.AppIcons
import io.github.dacstage.ui.common.OneLine
import io.github.dacstage.ui.common.UiText
import io.github.dacstage.ui.common.VolumeNote
import io.github.dacstage.ui.common.asString
import kotlinx.coroutines.delay

/**
 * Botón de volumen (arriba a la derecha). No cambia el volumen por sí mismo: OK abre [TvVolumePanel].
 * Así moverse con el D-pad por los controles nunca toca el volumen sin querer.
 */
@Composable
internal fun TvVolumeButton(
    vm: TvViewModel,
    requester: FocusRequester,
    downTarget: FocusRequester,
    enabled: Boolean,
    onFocused: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val volume by vm.volume.collectAsStateWithLifecycle()
    val dim = if (volume.connected) 1f else 0.5f
    Surface(
        onClick = onOpen,
        modifier = modifier
            .size(52.dp)
            .focusRequester(requester)
            .focusProperties {
                canFocus = enabled
                down = downTarget
            }
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.10f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF111111),
        ),
        scale = TvFocus.scale(1.08f),
        border = TvFocus.border(CircleShape),
    ) {
        Icon(
            if (volume.muted || !volume.connected) AppIcons.VolumeOff else AppIcons.VolumeUp,
            contentDescription = stringResource(R.string.tv_dac_volume),
            modifier = Modifier.align(Alignment.Center).size(24.dp).graphicsLayer { alpha = dim },
        )
    }
}

/**
 * Panel de volumen del DAC. ←/→ o ↑/↓ = ±1 dB (mantener acelera, con tope en el host), OK = silencio,
 * Atrás = cerrar. Se cierra solo tras [AUTO_CLOSE_MS] sin teclas. Muestra el valor CONFIRMADO por el host.
 */
@Composable
internal fun TvVolumePanel(vm: TvViewModel, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val v by vm.volume.collectAsStateWithLifecycle()
    val hud by vm.volumeHud.collectAsStateWithLifecycle()
    val req = remember { FocusRequester() }
    var lastKeyAt by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(Unit) {
        withFrameNanos { }
        req.requestSafely()
    }
    LaunchedEffect(lastKeyAt) {
        delay(AUTO_CLOSE_MS)
        onClose()
    }

    val shape = RoundedCornerShape(22.dp)
    Surface(
        onClick = {
            lastKeyAt = System.currentTimeMillis()
            vm.toggleMute()
        },
        modifier = modifier
            .width(420.dp)
            .shadow(16.dp, shape)
            .focusRequester(req)
            .onPreviewKeyEvent { e ->
                val delta = when (e.key) {
                    Key.DirectionRight, Key.DirectionUp -> 1
                    Key.DirectionLeft, Key.DirectionDown -> -1
                    else -> return@onPreviewKeyEvent false
                }
                if (e.type == KeyEventType.KeyDown) {
                    lastKeyAt = System.currentTimeMillis()
                    vm.volumeStep(delta, e.nativeKeyEvent.repeatCount)
                }
                true
            },
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xF0101216),
            contentColor = Color.White,
            focusedContainerColor = Color(0xF0101216),
            focusedContentColor = Color.White,
            pressedContainerColor = Color(0xF0181B21),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Column(Modifier.padding(horizontal = 30.dp, vertical = 26.dp)) {
            OneLine(stringResource(R.string.tv_dac_volume_header), TvType.playerLine)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (v.muted || !v.connected) AppIcons.VolumeOff else AppIcons.VolumeUp,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                )
                Spacer(Modifier.width(16.dp))
                OneLine(
                    when {
                        !v.connected -> stringResource(R.string.dac_disconnected)
                        v.muted -> stringResource(R.string.volume_muted)
                        else -> v.label.asString()
                    },
                    TvType.hudValue,
                )
            }
            if (v.fraction != null && v.connected) {
                Spacer(Modifier.height(18.dp))
                VolumeBar(v, Modifier.fillMaxWidth().padding(vertical = 4.dp).height(10.dp))
            }
            val note = hud?.note ?: v.note ?: if (v.atCap) VolumeNote.CAP_REACHED else null
            if (note != null) {
                Spacer(Modifier.height(12.dp))
                val c = if (note == VolumeNote.CAP_REACHED) Color(0xFFFFCC80) else Color(0xFFD0D0D0)
                OneLine(UiText.volumeNote(note).asString(), TvType.hint.copy(color = c), maxLines = 2)
            }
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                OneLine(stringResource(R.string.tv_volume_panel_hint), TvType.hint)
            }
        }
    }
}

private const val AUTO_CLOSE_MS = 8_000L
