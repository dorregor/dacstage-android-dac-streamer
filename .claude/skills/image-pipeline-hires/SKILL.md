---
name: image-pipeline-hires
description: Cadena de carátulas de DAC Stage en alta resolución (host → TV). Úsala al tocar artwork/, ArtRef, ArtCandidate, el servidor HTTP de carátulas o la carga de imágenes en la TV.
---

# Carátulas en alta resolución (DAC Stage)

Objetivo: la TV muestra la carátula con la MAYOR resolución real disponible (mínimo deseable
1000×1000), sin inventar calidad y sin reventar la memoria de una TV de 2 GB.

## 1. Fuentes (host, `players/` → `ArtCandidate`)
Orden de candidatas (mejor primero), las publica cada adaptador de reproductor:
1. `ArtCandidate.Url(PLAYER_QUEUE_URL)`: URL de CDN del reproductor (UAPP: `ArtURL` de PlayQueue.xml)
   reescrita al mayor tamaño plausible en `url`, y tamaños menores en `fallbackUrls` (descendente).
2. `ArtCandidate.Url(SESSION_ART_URI)`: `METADATA_KEY_ART_URI` / `ALBUM_ART_URI` de la MediaSession
   (http/https, `content://`, `android.resource://`).
3. `ArtCandidate.Embedded(SESSION_BITMAP)`: bitmap de la sesión (suele venir reducido).

## 2. Resolución (`artwork/HostArtworkResolver`)
- Por candidata URL: probar `url` y luego `fallbackUrls`; quedarse con la PRIMERA que responda con una
  imagen decodificable (HTML de error o 404 → siguiente).
- `content://` vía ContentResolver; `SecurityException` = candidata no disponible (no es un fallo).
- Medir SIEMPRE el tamaño real: `BitmapFactory.Options.inJustDecodeBounds = true` sobre los bytes.
  Nunca fiarse del tamaño que dice la URL (`1280x1280.jpg` puede servir 640 px).
- Elegir la de MAYOR área real entre todas (empate → la anterior). No quedarse con la primera.
- Nunca reescalar para aparentar más resolución. JPEG/WebP/PNG descargados se guardan byte a byte;
  un bitmap embebido se codifica a JPEG calidad 92 a su tamaño real (y solo si puede ganar).
- `id` = SHA-256 hex de los bytes elegidos. `hostPath = "/art/<id>"` siempre; `url` solo si la ganadora
  es http(s) pública (no loopback, LAN, `.local`, ULA, CGNAT).
- Log `DacStageArt`: `art <trackKey>: <w>x<h> <source> <url|embedded>` y WARN si no llega a 1000×1000.
- HTTP: `HttpURLConnection`, timeouts 5 s, User-Agent genérico, ≤ 4 redirecciones, ≤ 20 MB.
- Caché: por (trackKey + huella de candidatas) para no re-resolver la misma pista; si llegan
  candidatas nuevas para la misma pista, se resuelve otra vez. Los fallos no se cachean.

## 3. Transferencia host → TV
- Por el socket de estado solo viaja `ArtRef` (id, tamaño real, fuente, url?, hostPath). NUNCA Base64
  ni bytes de imagen en el JSON de estado.
- `url` pública → la TV la descarga directamente de la CDN.
- Si no → `GET http://<host>:<artPort>/t/<artToken>/art/<id>` (= `artBaseUrl + hostPath`;
  también vale `/art/<id>?t=<artToken>`). Respuesta inmutable: `Cache-Control: max-age=31536000,
  immutable`, `ETag: "<id>"`. 403 sin token válido, 404 si no existe.
- Clave de caché en la TV = `ArtRef.id` (no la URL: el token o la IP pueden cambiar).
- HTTP en claro: la app necesita permitir cleartext (network security config) para la IP del host.

## 4. Carga en la TV (progresiva)
1. Mostrar al instante lo que haya: miniatura de la cola (`QueueItem.artUrl`) o la carátula anterior
   con fundido; nunca un hueco negro si hay algo mejor que nada.
2. Pedir la alta (`ArtRef`) con tamaño de decodificación acotado al área de pantalla que ocupa
   (p. ej. ≤ 1080 px de lado en una TV 1080p; `size()` en Coil). Decodificar 3000×3000 ARGB_8888 son
   36 MB: en armeabi-v7a con 2 GB eso mata la app.
3. Sustituir con crossfade cuando la alta esté lista. Si falla, quedarse con la miniatura.

## 5. Memoria acotada
- Host: LRU en memoria ≤ 24 MB de bytes codificados + disco `cacheDir/art/` ≤ 100 MB (LRU por mtime).
- TV: caché de memoria de Coil acotada (p. ej. 15–20 % del heap) y caché de disco por id; como mucho
  la carátula actual y la siguiente decodificadas; `RGB_565` o `allowHardware` si se necesita aire.
- No mantener `Bitmap` de candidatas descartadas; solo los bytes de la ganadora.

## Verificación
- Tests: `app/src/test/java/io/github/dacstage/artwork/HostArtworkResolverTest.kt` (selección por tamaño
  real, fallbacks, caché, URL pública/privada) y `network/ArtHttpServerTest.kt` (403/404/200/304).
- En dispositivo: `adb logcat -s DacStageArt` y comprobar `art …: WxH` ≥ 1000 para TIDAL.
