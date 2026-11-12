---
name: artwork-palette-gradients
description: Carga de carátulas con Coil 3 y fondo degradado derivado con androidx.palette y contraste AA en DAC Stage. Úsalo al tocar carga de imágenes, cachés, tamaños de decodificación, colores de fondo o contraste de texto.
---

# Carátulas, paleta y degradados

Archivos: `ui/common/ArtImages.kt`, `ArtModel.kt`, `ArtColors.kt`, `ColorMath.kt`, `NowPlayingParts.kt` (`artBackground`, `ArtworkView`), `DacStageApp.kt`.

## Carga (Coil 3)
- ImageLoader único (`DacStageApp : SingletonImageLoader.Factory` → `ArtImages.newImageLoader`): memoria `maxSizePercent(0.15)`, disco `cacheDir/art_cache` 48 MB, `allowHardware(true)`.
- URL en TV: `ArtUrls.forTv(art, artBaseUrl)` = `art.url` o `artBaseUrl + hostPath` (la ruta va antes de la query del token). En host: `HostUiDeps.artUrlFor(art)`.
- Claves: memoria y disco = `ArtRef.id`. Miniaturas: memoria `"$url@128"`, disco `url`.
- Tamaño: decodifica al tamaño en pantalla (TV: 70 % del alto ≈ 756 px); `Precision.INEXACT` = Coil nunca escala hacia arriba.
- Progresivo (`rememberArtwork`): miniatura de la cola → alta; se conserva la anterior hasta que la nueva está decodificada (sin parpadeo).

## Paleta
- Tras cargar la alta: petición de 64 px, `allowHardware(false)`, sin caché de memoria, sale de disco. `Palette.generate()` en `Dispatchers.Default`. LRU de 48 por `ArtRef.id`.
- `ArtColors.derive(ArtSwatches)` (puro): `deep` muy oscuro teñido, `glow` oscurecido hasta AA, `accent` aclarado hasta 3:1.

## Contraste
- `ColorMath` trabaja con ARGB `Int` (testeable en JVM). Texto primario blanco, secundario `#D0D0D0` opaco (rige el contraste). Garantía: `glow` y `deep` ≥ 4.5:1 con ambos; una mezcla sRGB de dos colores no supera la luminancia del más claro, así que el degradado entero cumple.
- Chips sobre fondo translúcido: texto blanco (no gris).
- Test: `ColorContrastTest.derivedPaletteAlwaysAa` con paletas extremas; añade casos si cambias `derive`.

## Dibujo
- `rememberAnimatedArtColors` anima los 3 colores (1100 ms o snap si se reducen animaciones); `Modifier.artBackground` los lee en `drawBehind` → la transición no recompone.
