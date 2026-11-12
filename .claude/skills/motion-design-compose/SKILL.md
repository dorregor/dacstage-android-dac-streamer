---
name: motion-design-compose
description: Animaciones fluidas y baratas en Compose para DAC Stage (TV de 32 bits y 2 GB). Úsalo al añadir transiciones, progreso, superposiciones o al investigar jank y recomposiciones.
---

# Movimiento en DAC Stage

Archivos: `ui/common/Motion.kt`, `NowPlayingParts.kt`, `ui/tv/TvNowPlaying.kt`, `ui/tv/TvRoot.kt`.

## Especificaciones
- `Motion.spatial(reduce)`: resorte `dampingRatio 0.82`, `StiffnessMediumLow`. `Motion.fade(reduce, ms, delay)`: tween. Ambos devuelven `snap()` si `reduce`.
- `LocalReduceMotion` = `Settings.Global.ANIMATOR_DURATION_SCALE == 0`. Todo `AnimatedContent`/`AnimatedVisibility` usa `EnterTransition.None togetherWith ExitTransition.None` cuando es true. La deriva de reposo pasa a saltos cada 60 s.

## Patrones
- Carátula: `AnimatedContent(contentKey = id)` con fade + `scaleIn(0.94)`; miniatura→alta con `Crossfade` dentro.
- Metadatos: `AnimatedContent(contentKey = trackKey)`.
- Chips: `key(trackKey, kind)` + `Animatable` con retardo `90 ms × i`, alpha/translationY en `graphicsLayer`.
- Cola: `AnimatedVisibility(slideInVertically + fadeIn)`; el contenido de fondo se escala/desplaza con `animateFloatAsState` leído en `graphicsLayer {}` (fase de dibujo).
- Progreso: `rememberPlaybackClock` a ~10 Hz solo mientras suena; `ProgressLine` lo lee en `Canvas` (sin recomponer) y `ElapsedText` usa `derivedStateOf` (recompone 1 vez/s).

## Evitar recomposiciones
- ViewModel con StateFlows granulares (`nowPlaying` sin progreso, `progress`, `queue`, `volume`…) + `collectAsStateWithLifecycle`; en host, `StateFlow.collectSlice { }` (map + distinctUntilChanged).
- Colores animados y alfas: leer `State.value` dentro de `drawBehind`/`graphicsLayer`, nunca en composición.
- `@Immutable` en los modelos de UI; claves estables en `Lazy*`.

## Medir (con permiso del usuario)
`adb shell dumpsys gfxinfo io.github.dacstage reset`, interactuar, `dumpsys gfxinfo io.github.dacstage` → "Janky frames" y percentiles.
