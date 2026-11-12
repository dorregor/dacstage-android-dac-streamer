---
name: dac-safety-ux
description: Patrones de volumen seguro para el DAC USB de DAC Stage (tope, pasos de 1 dB, aceleración limitada, mute, claim forzado con consentimiento) y cómo mostrar los errores del hardware en host y TV. Úsala al tocar dac/**, la UI de volumen del host o de la TV, o los comandos VolumeStep/ToggleMute.
---

# Volumen seguro del DAC (dac-safety)

Código: `app/src/main/java/io/github/dacstage/dac/` — `DacVolumeManager` (política) sobre
`UsbAudioDacController` (USB). La política se aplica en el host y vale igual para TV y UI del host.

## Reglas que no se rompen
- **Nunca escribir por encima del tope.** Tope = `HostSettings.capDb` o `maxDb − 10 dB`. Un paso que lo
  superaría → `VOLUME_ABOVE_CAP` sin escribir. Bajar siempre está permitido.
- **Perilla por encima del tope**: se muestra tal cual y se bloquean subidas; no se baja solo. Solo al
  *bajar el tope* explícitamente se baja el volumen al nuevo tope.
- **Solo ±1 dB por paso** (otro delta → `INVALID_ARGUMENT`). No hay volumen absoluto desde la TV.
- **Aceleración con tope**: pulsación nueva ≤12/s; mantenida empieza ~4/s y sube 0,5/s por repetición hasta
  12/s. Exceso → `RATE_LIMITED` (no escribe; la UI lo ignora en silencio).
- **Mute siempre disponible**: sin tope ni límite de velocidad (sí respeta la política de claim).
- **El estado es lo leído**: tras escribir se relee (GET_CUR). Si no se pudo leer → `null`, nunca el valor
  pedido ni uno supuesto. 0x8000 (−∞) se publica como `null`.
- **Claim forzado** (`claimInterface(true)`, puede cortar el audio): solo si el reproductor activo no suena
  (o no hay ninguno), o hay consentimiento guardado para el par DAC (`"VID:PID"`) + reproductor. Si no:
  `control = NEEDS_HOST_CONFIRMATION`, `pendingConfirmation` y `VOLUME_UNAVAILABLE`.
- No hay selector de fuente ni avisos de perilla (sin endpoint de interrupción): llamar `refresh()` al abrir
  la pantalla del host, al cambiar de pista y tras cada cambio.

## Mensajes (español, cortos)
| Situación | TV | Host |
|---|---|---|
| `VOLUME_ABOVE_CAP` | "Tope de volumen (X dB)" breve | Igual; enlace a ajustar tope |
| `VOLUME_UNAVAILABLE` + `NEEDS_HOST_CONFIRMATION` | "Volumen no disponible ahora" | Diálogo: "Pausar y aplicar" / "Permitir siempre con <reproductor>" / "Cancelar" |
| `NO_PERMISSION` | "Volumen no disponible ahora" | "Concede el permiso USB" (refresh() vuelve a pedirlo) |
| `BUSY` | "Volumen no disponible ahora" | "El DAC está ocupado por otra app" |
| `DAC_DISCONNECTED` | "DAC desconectado" | Igual |
| `RATE_LIMITED` | nada | nada |
| `NOT_SUPPORTED` (mute) | ocultar el botón | ocultar el botón |

## UI
- Mostrar siempre volumen leído + tope; si `volumeDb > capDb`, marcarlo ("por encima del tope").
- `volumeDb == null` → "—", nunca un número por defecto.
- "Pausar y aplicar": `pauseAndApplyPending()` pausa (espera PAUSED ≤ 2 s), aplica forzado y reanuda.
- Consentimiento revocable desde Ajustes (`revokeForceConsent`).

## Pruebas
- Unitarias: `app/src/test/java/io/github/dacstage/dac/` con `FakeDacController` y tiempo virtual.
- En dispositivo: el usuario escucha música en vivo. Prohibido instalar, enviar teclas o tocar USB sin pedirlo;
  solo lectura (`adb shell dumpsys usb`).
