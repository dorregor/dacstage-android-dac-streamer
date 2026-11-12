---
name: perf-budget-android
description: Presupuesto de rendimiento de DAC Stage (TV TCL 32 bits, 2 GB) — Baseline Profile, R8, macrobenchmarks, auditoría de recomposición y de red. Úsalo al medir o optimizar arranque, memoria, jank o tráfico host↔TV.
---

# Presupuesto de rendimiento (medir SIEMPRE en la TV real, no en la tablet)

Metas: arranque en frío < 1,5 s en TV (release), < 150 MB PSS en TV, 0 frames perdidos navegando con D-pad,
batería del host despreciable con pantalla apagada.

## Medir (TV = `-s <ip-tv>:5555`)
- Arranque: `adb shell am force-stop io.github.dacstage; adb shell am start -W -n io.github.dacstage/.MainActivity` → `TotalTime`. Solo cuenta la build **release** (debug no tiene R8 ni perfil: 5–6 s es normal ahí).
- Memoria: `adb shell dumpsys meminfo io.github.dacstage` → TOTAL PSS y "Graphics".
- Jank: `adb shell dumpsys gfxinfo io.github.dacstage reset`, navegar con `input keyevent` (DPAD_*, ENTER), luego `dumpsys gfxinfo io.github.dacstage` → "Janky frames" y percentiles.
- Host en reposo: `adb shell dumpsys batterystats io.github.dacstage`, `dumpsys power | grep -i wake` (no debe haber wake locks propios).

## Reglas
- R8 + shrinkResources en release; reglas keep mínimas (kotlinx.serialization, libadb/Conscrypt/BouncyCastle).
- Baseline Profile: módulo `:baselineprofile` (com.android.test + androidx.baselineprofile) que recorre arranque TV → abrir cola → mover foco → cerrar. Se genera sobre la TV real.
- Compose: estado en StateFlow + `collectAsStateWithLifecycle`; leer progreso/animaciones en fase de dibujo (`Modifier.drawBehind`, `graphicsLayer { }` con lambda); claves estables en Lazy*; nada de objetos nuevos en cada recomposición.
- Imágenes: una sola librería (Coil), memoria ≤ 15 % del heap, `allowHardware`, decodificar al tamaño en pantalla; paleta sobre copia de 64 px fuera del hilo principal.
- Red: un socket, parches por clave de primer nivel, coalescer ~50 ms, cola solo cuando cambia su versión, progreso solo en discontinuidades (la TV extrapola). Carátulas por URL o HTTP con hash, nunca por el socket de estado.
