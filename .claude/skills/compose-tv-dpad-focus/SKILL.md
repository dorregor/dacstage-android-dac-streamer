---
name: compose-tv-dpad-focus
description: Foco y D-pad en Compose for TV (tv-material 1.1) para DAC Stage. Úsalo al añadir o cambiar controles enfocables, la banda de cola, teclas multimedia/volumen o Back en la pantalla de TV.
---

# Foco D-pad en la TV (DAC Stage)

Archivos: `ui/tv/TvNowPlaying.kt`, `ui/tv/TvQueue.kt`, `ui/tv/TvTheme.kt`, `tv/TvViewModel.kt`, `MainActivity.kt`.

## Reglas
- Todo control enfocable es `androidx.tv.material3.Surface(onClick=…)` con `TvFocus.scale(1.05–1.08)` y `TvFocus.border(shape)`: la escala y el borde van en capa gráfica, sin saltos de layout.
- Cada control lleva `focusRequester(...)` + `focusProperties { canFocus = enabled }` + `onFocusChanged { if (it.isFocused) focus.last = requester }`. `TransportFocus.restore()` reintenta durante 3 frames y cae a play/pausa.
- `FocusRequester.requestSafely()` (try/catch) siempre: el nodo puede no estar adjunto todavía; pide foco tras `withFrameNanos {}`.
- Foco inicial: `LaunchedEffect(queueOpen)` → play/pausa. Al cerrar la cola se restaura `focus.last`.
- Red de seguridad: `Modifier.onFocusChanged { hasFocus = it.hasFocus }.focusGroup()` en la raíz; si se pierde el foco 250 ms, `restore()`. La banda de cola hace lo mismo con la pista actual.
- Sin trampas: el control de volumen captura ←/→ (±1 dB con `nativeKeyEvent.repeatCount`), pero ↑ y Back devuelven el foco a play/pausa; ↓ abre la cola.
- Mientras la cola está abierta, los controles de transporte tienen `canFocus = false` para que ←/→ en los extremos de la `LazyRow` no salten arriba.

## Cola
- `LazyRow` con `key = uniqueQueueKeys(items)` (nunca claves duplicadas) y `contentPadding = (ancho − tarjeta)/2` para poder centrar la primera y la última.
- `LocalBringIntoViewSpec provides CenteredBringIntoView` (offset + size/2 − container/2) mantiene la tarjeta enfocada centrada.
- Al abrir: `scrollToItem(currentIndex)` → frame → `currentReq.requestSafely()`.
- ↑ (en `onPreviewKeyEvent` de la banda) y Back cierran; OK → `Command.JumpToQueueItem(key)`.

## Teclas globales
- `MainActivity.dispatchKeyEvent` → `TvViewModel.onKeyEvent(event)` ANTES de Compose. Multimedia → comandos (solo repeatCount 0). Volumen → `VolumeStep(±1, repeatCount)` y se consumen solo si `LinkStatus.Connected` (si no, la TV usa su volumen).
- En reposo, la primera tecla de navegación solo despierta (se traga DOWN y UP).
- Back: un único `BackHandler` con prioridad: cancelar avance (`CancelJump`) → cerrar cola → volver a play.

## Comprobar
`TvKeyMapTest` cubre el mapeo. En dispositivo: `adb shell input keyevent 20/21/22/23/4` y `screencap` (solo con permiso del usuario).
