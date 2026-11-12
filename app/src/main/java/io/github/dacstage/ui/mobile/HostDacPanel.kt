package io.github.dacstage.ui.mobile

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.dacstage.R
import io.github.dacstage.model.CommandOrigin
import io.github.dacstage.model.DacControlAvailability
import io.github.dacstage.model.DacState
import io.github.dacstage.model.SpecSource
import io.github.dacstage.ui.common.AppIcons
import io.github.dacstage.ui.common.TechFormat
import io.github.dacstage.ui.common.UiText
import io.github.dacstage.ui.common.VolumeUi
import io.github.dacstage.ui.common.asString
import io.github.dacstage.ui.common.volumeModeNote
import kotlin.math.roundToInt

private val CapColor = Color(0xFFFFB74D)

@StringRes
internal fun availabilityText(d: DacState): Int? = when (d.control) {
    DacControlAvailability.AVAILABLE -> null
    DacControlAvailability.NEEDS_HOST_CONFIRMATION -> R.string.dac_needs_confirmation
    DacControlAvailability.BUSY -> R.string.dac_busy
    DacControlAvailability.NO_PERMISSION -> R.string.dac_no_permission
    DacControlAvailability.DISCONNECTED -> R.string.dac_connect_usb
    DacControlAvailability.ANDROID_AUDIO -> R.string.dac_android_audio
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DacPanel(deps: HostUiDeps, actions: HostActions) {
    val dac by deps.dacVolume.state.collectSlice { it }
    val usbBits by deps.controller.state.collectSlice { it.specs.usbBitDepth?.value }
    val sys by deps.controller.state.collectSlice { it.systemVolume }
    val vol = remember(dac, sys) { VolumeUi.from(dac, sys) }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.35f))) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("DAC", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(dac.deviceName ?: stringResource(if (dac.connected) R.string.dac_usb else R.string.dac_none), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                AssistChip(
                    onClick = {},
                    label = { Text(stringResource(if (dac.connected) R.string.dac_chip_connected else R.string.dac_chip_disconnected)) },
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = if (dac.connected) Color(0xFFA5D6A7) else MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
            val playerRate by deps.controller.state.collectSlice { it.specs.sampleRateHz?.takeIf { s -> s.source == SpecSource.UAPP_LOG }?.value }
            val clockRate = dac.sampleRateHz?.takeIf { dac.connected && it > 0 }
            when {
                clockRate != null ->
                    Text(stringResource(R.string.dac_sample_rate, TechFormat.sampleRate(clockRate)), style = MaterialTheme.typography.bodyMedium)
                // Sin leer el reloj (el reproductor tiene la interfaz de control): la que fijó el reproductor.
                dac.connected && playerRate != null ->
                    Text(stringResource(R.string.dac_sample_rate_player, TechFormat.sampleRate(playerRate!!)), style = MaterialTheme.typography.bodyMedium)
            }
            // Bits del enlace USB (lo que recibe el DAC; el reproductor puede rellenar, p. ej. 16 → 32 bit).
            usbBits?.takeIf { dac.connected && it > 0 }?.let {
                Text(stringResource(R.string.dac_usb_link, TechFormat.bitDepth(it)), style = MaterialTheme.typography.bodyMedium)
            }
            if (dac.connected) {
                Row(verticalAlignment = Alignment.Bottom) {
                    // Siempre el valor CONFIRMADO por el DAC.
                    Text(
                        vol.label.asString(),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (vol.muted) {
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(R.string.volume_muted), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                    }
                }
                VolumeSlider(deps, dac, actions)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val canAct = dac.control != DacControlAvailability.DISCONNECTED &&
                        dac.control != DacControlAvailability.ANDROID_AUDIO
                    // DAC sin volumen controlable por USB: ±, slider y tope no aplican (el mute sí, si existe).
                    val canStep = canAct && volumeModeNote(dac.volumeMode) == null
                    OutlinedIconButton(enabled = canStep, onClick = { actions.dac { deps.dacVolume.step(-1, 0, CommandOrigin.HOST_UI) } }) {
                        Icon(AppIcons.VolumeDown, stringResource(R.string.dac_volume_down))
                    }
                    FilledTonalIconToggleButton(
                        checked = vol.muted,
                        enabled = canAct && dac.muted != null,
                        onCheckedChange = { actions.dac { deps.dacVolume.toggleMute(CommandOrigin.HOST_UI) } },
                    ) { Icon(if (vol.muted) AppIcons.VolumeOff else AppIcons.VolumeUp, stringResource(R.string.volume_muted)) }
                    OutlinedIconButton(enabled = canStep, onClick = { actions.dac { deps.dacVolume.step(+1, 0, CommandOrigin.HOST_UI) } }) {
                        Icon(AppIcons.VolumeUp, stringResource(R.string.dac_volume_up))
                    }
                    Spacer(Modifier.weight(1f))
                    dac.capDb?.takeIf { canStep }?.let {
                        Text(stringResource(R.string.dac_cap, TechFormat.db(it)), style = MaterialTheme.typography.labelLarge, color = CapColor, maxLines = 1)
                    }
                }
            }
            volumeModeNote(dac.volumeMode)?.takeIf { dac.connected }?.let {
                Text(UiText.volumeNote(it).asString(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            availabilityText(dac)?.let {
                Text(stringResource(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Slider en pasos de 1 dB; no deja pasar del tope. La etiqueta sigue mostrando el valor confirmado. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VolumeSlider(deps: HostUiDeps, dac: DacState, actions: HostActions) {
    val min = dac.minDb
    val max = dac.maxDb
    val confirmed = dac.volumeDb
    if (min == null || max == null || max <= min || confirmed == null) return
    val cap = (dac.capDb ?: max).coerceIn(min, max)
    var dragging by remember { mutableStateOf<Float?>(null) }
    val steps = ((max - min).roundToInt() - 1).coerceIn(0, 300)
    val enabled = dac.control == DacControlAvailability.AVAILABLE || dac.control == DacControlAvailability.NEEDS_HOST_CONFIRMATION
    Column {
        Slider(
            value = (dragging ?: confirmed).coerceIn(min, max),
            onValueChange = { dragging = it.coerceAtMost(cap) },
            onValueChangeFinished = {
                val target = dragging
                dragging = null
                if (target != null && target != confirmed) actions.dac { deps.dacVolume.setVolumeDb(target) }
            },
            valueRange = min..max,
            steps = steps,
            enabled = enabled,
            track = { state -> CapTrack(state, (cap - min) / (max - min)) },
        )
        dragging?.let {
            Text(stringResource(R.string.dac_requested, TechFormat.db(it)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CapTrack(state: SliderState, capFraction: Float) {
    val active = MaterialTheme.colorScheme.primary
    val inactive = Color.White.copy(alpha = 0.18f)
    val r = state.valueRange
    val f = ((state.value - r.start) / (r.endInclusive - r.start)).coerceIn(0f, 1f)
    Canvas(Modifier.fillMaxWidth().height(6.dp)) {
        val cr = CornerRadius(size.height / 2)
        drawRoundRect(inactive, cornerRadius = cr)
        // Zona por encima del tope.
        val capX = size.width * capFraction
        drawRoundRect(CapColor.copy(alpha = 0.25f), topLeft = Offset(capX, 0f), size = Size(size.width - capX, size.height), cornerRadius = cr)
        drawRoundRect(active, size = Size(size.width * f, size.height), cornerRadius = cr)
        drawLine(CapColor, Offset(capX, -size.height), Offset(capX, size.height * 2), strokeWidth = 2.dp.toPx())
    }
}
