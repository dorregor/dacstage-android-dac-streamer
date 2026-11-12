---
name: adaptive-layouts
description: Layouts adaptativos del host de DAC Stage (teléfono/tablet) con WindowSizeClass y la pantalla fija de TV a 960×540 dp. Úsalo al cambiar la disposición de la vista reproduciendo, la cola o los ajustes.
---

# Layouts adaptativos

Archivos: `ui/mobile/HostPlayer.kt`, `HostDacPanel.kt`, `HostSettingsScreen.kt`, `ui/tv/TvNowPlaying.kt`, `ui/tv/TvTheme.kt`.

## Host (Material 3)
- `currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(...)`:
  - COMPACT (< 600 dp, teléfono): columna con scroll; carátula a ancho completo; botón "Cola" → `ModalBottomSheet`.
  - MEDIUM (600–840, tablet vertical ≈ 753 dp): fila [carátula + info + transporte + DAC | cola].
  - EXPANDED (≥ 840, tablet horizontal ≈ 1205 dp): fila [carátula cuadrada al alto disponible | info + DAC | cola].
- Edge-to-edge: `Scaffold(contentWindowInsets = WindowInsets(0))` + `windowInsetsPadding(WindowInsets.safeDrawing)` en el contenido.
- Ajustes: `LazyColumn` con secciones de ancho máximo 720 dp centradas.
- Panel DAC: slider con `track` propio (`CapTrack`) que marca el tope; la etiqueta muestra siempre `dac.volumeDb` confirmado.

## TV (fija)
- 960×540 dp (1080p a 320 dpi). Overscan 5 %: `TvDims.overscanH = 48.dp`, `overscanV = 27.dp`.
- Carátula = 70 % del alto (`BoxWithConstraints`), columna de texto con `weight(1f)`.
- Tipografía `TvType`: nada secundario por debajo de 18 sp.

## Previews
`app/src/debug/.../demo/DemoPreviews.kt`: TV (`spec:width=960dp,height=540dp,dpi=320`), teléfono y tablet. Modo demo en debug: `am start -n io.github.dacstage/.MainActivity --es demo <escenario> [--es demo_role host]` (escenarios en `DemoSimulator`).
