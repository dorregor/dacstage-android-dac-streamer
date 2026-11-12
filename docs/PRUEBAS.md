# Pruebas en dispositivo (adb)

Estado: **nada de esto se ha ejecutado todavía** (por decisión del usuario, la app no se instaló durante el desarrollo). Las pruebas unitarias (`.\gradlew.bat :app:testDebugUnitTest`, 143 tests) sí pasan.

```powershell
$adb  = "<ruta del SDK>\platform-tools\adb.exe"
$tv   = "<ip-tv>:5555"
$tab  = "<ip-tablet>:<puerto actual de Depuración inalámbrica>"
$apk  = "app\build\outputs\apk\release\app-release.apk"
```

Precauciones: las pruebas cambian lo que suena en la tablet en tiempo real. Nunca `playFromSearch`/`playFromMediaId` en UAPP. Las pruebas que fuerzan el claim del DAC, primero con la música **en pausa**.

## 0. Instalación y arranque
1. `& $adb -s $tv install -r $apk` y `& $adb -s $tab install -r $apk`.
2. Tablet: abrir DAC Stage → aceptar "Solicitud de conexión USB", dar acceso a notificaciones, aceptar notificaciones. Debe aparecer la notificación "Host activo".
3. TV: abrir DAC Stage → "Buscando el host…" → código de 6 dígitos → escribirlo en el host (Ajustes → Emparejar TV).
4. Arranque en frío TV (release): `& $adb -s $tv shell am force-stop io.github.dacstage; & $adb -s $tv shell am start -W -n io.github.dacstage/.MainActivity` → `TotalTime` < 1500 ms (meta).

## 1. Riesgo principal: claim del DAC (música en pausa)
1. Pausar HiBy. En el host: botones ± del panel DAC. Esperado: el volumen cambia y el valor mostrado es el releído.
2. `& $adb -s $tab logcat -s UsbAudioDac DacStageHost` durante la prueba. Anotar si `claimInterface(false)` falla (NeedsForce) y si con `force` funciona.
3. Reanudar HiBy. ¿Suena? ¿Hubo corte? Repetir con UAPP.
4. Con música sonando y sin consentimiento: subir volumen desde la TV → la TV muestra "Volumen no disponible ahora" y el host el diálogo (Pausar y aplicar / Permitir siempre / Cancelar).
5. Solo si en 1–3 no hubo corte: "Permitir siempre con HiBy" y repetir con música → debe aplicarse sin diálogo. Revocable en Ajustes.

## 2. Volumen y tope (desde host, TV y PC)
- TV: ←/→ sobre el control de volumen y teclas VOLUME_UP/DOWN/MUTE del mando (`& $adb -s $tv shell input keyevent KEYCODE_VOLUME_DOWN`). El volumen de la TV no debe cambiar mientras está conectada.
- Mantener pulsado: aceleración gradual con tope (~12 pasos/s).
- Fuera de rango desde el PC (actúa como una TV; emparejar con su código la primera vez):
  ```powershell
  .\tools\hostctl.ps1 -Host <ip-tablet> state
  .\tools\hostctl.ps1 -Host <ip-tablet> vol-up 20      # debe parar en el tope (−10 dB) con VOLUME_ABOVE_CAP
  .\tools\hostctl.ps1 -Host <ip-tablet> raw '{"type":"cmd","id":"x1","command":{"type":"vol_step","deltaDb":5}}'   # INVALID_ARGUMENT
  .\tools\hostctl.ps1 -Host <ip-tablet> raw-noauth '{"type":"cmd","id":"x2","command":{"type":"next"}}'           # auth_fail y cierre
  ```
- Cambiar el tope en el host por debajo del volumen actual → el DAC baja al tope.
- Volumen cambiado en el host se ve en la TV y viceversa.

## 3. Transporte
- TV: play/pausa, siguiente, anterior (botones y teclas multimedia del mando). Con HiBy y con UAPP.
- `.\tools\hostctl.ps1 -Host <ip-tablet> play-pause` / `next` / `prev`.
- Con HiBy y UAPP a la vez en media_session: los comandos van al reproductor activo (`dumpsys media_session`), no a la sesión "más reciente".

## 4. Cola
- **HiBy**: ↓ abre la cola en la TV, ←/→ recorre, OK salta. Verificar en `dumpsys media_session` que suena la pista elegida y en el host la frecuencia (kHz) del DAC.
- **UAPP**: activar en el host Ajustes → Leer la cola de UAPP → emparejar con el código de Depuración inalámbrica. Comprobar que la cola coincide con `PlayQueue.xml` (`& $adb -s $tab shell cat /sdcard/Android/data/com.extreamsd.usbaudioplayerpro/files/UAPP/PlayQueue.xml`). OK en una pista 3 posiciones adelante → "Avanzando… 1/3…" y termina en la pista correcta. Back durante el avance lo cancela. Probar también hacia atrás.
- Sin acceso a archivos (interruptor apagado) → la cola de UAPP se oculta con su mensaje.
- Nota: al 2026-11-11 el `PlayQueue.xml` de la tablet estaba escrito por `shell` con la cola vacía; hasta que UAPP lo reescriba (cambiar su cola), se verá "fuera de sincronía".

## 5. Carátula
- `& $adb -s $tab logcat -s DacStageArt` → por pista: `art <pista>: <ancho>x<alto> <fuente>`; WARN si < 1000×1000.
- UAPP: debe elegir 1280×1280 del CDN. HiBy: lo que publique su sesión (anotar el tamaño real).
- TV: sin reescalado artificial; degradado coherente con la carátula; texto legible.

## 6. Red
- Apagar/encender el Wi-Fi de la TV o reiniciar el host: la TV vuelve a conectar sola (backoff) sin re-emparejar.
- Bloquear NSD (o esperar > 5 s sin descubrimiento): escribir la IP del host (Ajustes del host la muestra) en la TV.

## 7. Rendimiento (TV)
- `dumpsys gfxinfo io.github.dacstage reset` → navegar con D-pad (`input keyevent DPAD_DOWN/RIGHT/LEFT/BACK`) → `dumpsys gfxinfo io.github.dacstage` → Janky frames ≈ 0.
- `dumpsys meminfo io.github.dacstage` → TOTAL PSS < 150 MB.
- Baseline Profile: `.\gradlew.bat :app:generateBaselineProfile` con solo la TV conectada, recompilar release y volver a medir el arranque.
- Host con pantalla apagada 30 min: `dumpsys batterystats io.github.dacstage` sin wake locks propios.
