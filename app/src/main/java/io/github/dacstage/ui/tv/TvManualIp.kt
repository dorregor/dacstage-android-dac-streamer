package io.github.dacstage.ui.tv

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Surface
import io.github.dacstage.R
import io.github.dacstage.tv.TvViewModel

/**
 * Respaldo del descubrimiento NSD. El campo de texto NO es enfocable hasta que el usuario pulsa
 * "Escribir IP del host": así el teclado en pantalla nunca aparece solo al abrir la app.
 */
@Composable
internal fun TvManualIp(vm: TvViewModel) {
    var editing by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        if (!editing) {
            val shape = RoundedCornerShape(50)
            Surface(
                onClick = { editing = true },
                shape = ClickableSurfaceDefaults.shape(shape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.10f),
                    contentColor = Color.White,
                    focusedContainerColor = Color.White,
                    focusedContentColor = Color(0xFF111111),
                ),
                scale = TvFocus.scale(1.06f),
                border = TvFocus.border(shape),
            ) {
                androidx.tv.material3.Text(
                    stringResource(R.string.tv_manual_ip_button),
                    style = TvType.body.copy(color = Color.Unspecified),
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                )
            }
            return@Column
        }

        Centered(stringResource(R.string.tv_manual_ip_prompt), TvType.body)
        Spacer(Modifier.height(12.dp))
        val req = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            req.requestSafely()
        }
        val shape = RoundedCornerShape(12.dp)
        Box(
            Modifier
                .width(360.dp)
                .background(Color.White.copy(alpha = if (focused) 0.16f else 0.08f), shape)
                .border(2.dp, if (focused) Color.White else Color.White.copy(alpha = 0.25f), shape)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            BasicTextField(
                value = text,
                onValueChange = { v ->
                    text = v.filter { it.isDigit() || it == '.' || it == ':' }.take(21)
                    invalid = false
                },
                singleLine = true,
                textStyle = TvType.bodyPrimary.copy(color = Color.White),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    invalid = !vm.connectManual(text)
                    if (!invalid) editing = false
                }),
                decorationBox = { inner ->
                    if (text.isEmpty()) {
                        androidx.tv.material3.Text(
                            "192.168.1.x",
                            style = TvType.bodyPrimary.copy(color = Color.White.copy(alpha = 0.4f)),
                        )
                    }
                    inner()
                },
                modifier = Modifier.focusRequester(req).onFocusChanged { focused = it.isFocused },
            )
        }
        if (invalid) {
            Spacer(Modifier.height(8.dp))
            Centered(stringResource(R.string.tv_manual_ip_invalid), TvType.body)
        }
    }
}

/**
 * Otros hosts DAC Stage visibles en la red: elegir uno conecta a ese y queda como preferido (p. ej. si la
 * TV encontró antes otro dispositivo y se quedó pidiendo código). No aparece si no hay otros.
 */
@Composable
internal fun TvHostChooser(vm: TvViewModel, current: String?) {
    val hosts by vm.hosts.collectAsStateWithLifecycle()
    val others = hosts.filter { it.serviceName != current }.distinctBy { it.hostId ?: it.serviceName }
    if (others.isEmpty()) return
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Centered(stringResource(R.string.tv_other_hosts), TvType.body)
        Spacer(Modifier.height(12.dp))
        androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            others.take(4).forEach { h ->
                val shape = RoundedCornerShape(50)
                Surface(
                    onClick = { vm.connectTo(h) },
                    shape = ClickableSurfaceDefaults.shape(shape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Color.White.copy(alpha = 0.10f),
                        contentColor = Color.White,
                        focusedContainerColor = Color.White,
                        focusedContentColor = Color(0xFF111111),
                    ),
                    scale = TvFocus.scale(1.06f),
                    border = TvFocus.border(shape),
                ) {
                    androidx.tv.material3.Text(
                        h.serviceName,
                        style = TvType.body.copy(color = Color.Unspecified),
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}
