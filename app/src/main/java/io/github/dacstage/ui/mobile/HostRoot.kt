package io.github.dacstage.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dacstage.R
import io.github.dacstage.dac.PendingDacConfirmation
import io.github.dacstage.ui.common.LocalReduceMotion
import io.github.dacstage.ui.common.Motion
import io.github.dacstage.ui.common.UiText
import io.github.dacstage.ui.common.rememberReduceMotion
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal val HostColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFA8C7FF),
    onPrimary = Color(0xFF0A2350),
    secondary = Color(0xFFC2C7D6),
    background = Color(0xFF0E1015),
    onBackground = Color(0xFFE6E8EE),
    surface = Color(0xFF14171D),
    onSurface = Color(0xFFE6E8EE),
    onSurfaceVariant = Color(0xFFB9BEC9),
    surfaceContainer = Color(0xFF1A1E25),
    surfaceContainerHigh = Color(0xFF20242C),
    error = Color(0xFFFFB4AB),
)

private enum class HostScreen { PLAYER, SETTINGS }

/** Punto de montaje del host (tablet/teléfono). */
@Composable
fun HostRoot(deps: HostUiDeps, modifier: Modifier = Modifier, onSwitchToRemote: (() -> Unit)? = null) {
    val reduce = rememberReduceMotion()
    CompositionLocalProvider(LocalReduceMotion provides reduce) {
        MaterialTheme(colorScheme = HostColors) {
          // Color de contenido base claro: sin esto, el texto e iconos sin color explícito salían negros.
          CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            var screen by rememberSaveable { mutableStateOf(HostScreen.PLAYER) }
            val snackbar = remember { SnackbarHostState() }
            BackHandler(enabled = screen == HostScreen.SETTINGS) { screen = HostScreen.PLAYER }
            AnimatedContent(
                targetState = screen,
                transitionSpec = {
                    if (reduce) EnterTransition.None togetherWith ExitTransition.None
                    else fadeIn(Motion.fade(false, 260)) togetherWith fadeOut(Motion.fade(false, 180))
                },
                modifier = modifier,
                label = "hostScreen",
            ) { s ->
                when (s) {
                    HostScreen.PLAYER -> HostPlayerScreen(deps, snackbar, onOpenSettings = { screen = HostScreen.SETTINGS })
                    HostScreen.SETTINGS -> HostSettingsScreen(deps, onBack = { screen = HostScreen.PLAYER }, onSwitchToRemote = onSwitchToRemote)
                }
            }
            val pending by deps.dacVolume.pendingConfirmation.collectAsStateWithLifecycle()
            pending?.let { PendingConfirmationDialog(it, deps, snackbar) }
          }
        }
    }
}

/** Diálogo cuando cambiar el volumen exige tomar la interfaz del DAC con música sonando. */
@Composable
private fun PendingConfirmationDialog(p: PendingDacConfirmation, deps: HostUiDeps, snackbar: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val res = LocalResources.current
    AlertDialog(
        onDismissRequest = { deps.dacVolume.dismissPending() },
        title = { Text(stringResource(R.string.dac_confirm_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(p.description)
                Text(
                    (p.playerName ?: p.player?.displayName)?.let { stringResource(R.string.dac_confirm_warning_by, it) }
                        ?: stringResource(R.string.dac_confirm_warning),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = {
                        scope.launch {
                            val r = deps.dacVolume.pauseAndApplyPending()
                            if (!r.ok) UiText.commandError(r.error)?.let { snackbar.showSnackbar(it.resolve(res)) }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.dac_confirm_pause_apply)) }
                p.player?.let { player ->
                    OutlinedButton(
                        onClick = { deps.dacVolume.grantForceConsent(player) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.dac_confirm_always_allow, p.playerName ?: player.displayName)) }
                }
                TextButton(onClick = { deps.dacVolume.dismissPending() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}

/** Recoge solo una parte de un StateFlow (evita recomponer por cambios ajenos, p. ej. el progreso). */
@Composable
internal fun <T, R> StateFlow<T>.collectSlice(selector: (T) -> R): State<R> {
    val flow = remember(this) { map(selector).distinctUntilChanged() }
    return flow.collectAsStateWithLifecycle(initialValue = remember(this) { selector(value) })
}
