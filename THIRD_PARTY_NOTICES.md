# Avisos de terceros

El código de DAC Stage se distribuye bajo la licencia MIT (ver [LICENSE](LICENSE)). La APK incluye las
bibliotecas siguientes, cada una bajo su propia licencia. Versiones exactas: `gradle/libs.versions.toml`.

## Incluidas en la APK

| Biblioteca | Licencia |
|---|---|
| AndroidX: core, activity, lifecycle, media, palette, profileinstaller | Apache-2.0 |
| Jetpack Compose: ui, foundation, animation, material3, material-icons-core, adaptive | Apache-2.0 |
| AndroidX TV Material (`androidx.tv:tv-material`) | Apache-2.0 |
| Coil 3 (`io.coil-kt.coil3`) | Apache-2.0 |
| OkHttp y Okio (vía `coil-network-okhttp`) | Apache-2.0 |
| kotlinx.serialization, kotlinx.coroutines, Kotlin stdlib | Apache-2.0 |
| Conscrypt (`org.conscrypt:conscrypt-android`) | Apache-2.0 |
| Bouncy Castle (`bcpkix-jdk15to18`, `bcprov-jdk15to18`) | [Bouncy Castle Licence](https://www.bouncycastle.org/licence.html) (tipo MIT) |
| libadb-android (`com.github.MuntashirAkon:libadb-android`) | `GPL-3.0-or-later OR Apache-2.0`: **DAC Stage la usa bajo Apache-2.0** |
| spake2-android (`com.github.MuntashirAkon.spake2-java:spake2-android`, dependencia de libadb-android) | **LGPL-3.0** |

Texto de Apache-2.0: <https://www.apache.org/licenses/LICENSE-2.0>.

### Sobre spake2-android (LGPL-3.0)

La usa el cliente ADB local que lee la cola de UAPP (emparejado de la depuración inalámbrica). Se enlaza como
biblioteca sin modificar. Su código fuente está en <https://github.com/MuntashirAkon/spake2-java> y el texto de la
licencia en <https://www.gnu.org/licenses/lgpl-3.0.html>. Como el código completo de DAC Stage es abierto y se
compila con Gradle (ver README), cualquiera puede reconstruir la APK con otra versión de esa biblioteca, como
exige la LGPL.

### Íconos

Varios íconos de `ui/common/AppIcons.kt` son trazos de Material Icons (Google, Apache-2.0).

## Solo para compilar o probar (no van en la APK)

JUnit 4 (EPL-1.0), AndroidX Test, UI Automator y Benchmark/Baseline Profile (Apache-2.0), Android Gradle
Plugin y Kotlin Gradle Plugin (Apache-2.0).

## Servicios en línea (opcionales, en tiempo de ejecución)

DAC Stage no incluye datos ni imágenes de estos servicios: los consulta cuando el usuario lo activa.

- **LRCLIB** (<https://lrclib.net>): letras. Se envían título, artista, álbum y duración de la pista.
- **MusicBrainz** y **Cover Art Archive** (<https://musicbrainz.org>): año, sello y portada.
- **Google Earth View**: fotos del salvapantallas de la TV, desde endpoints públicos no documentados de
  Google. Las fotos tienen copyright de Google y de sus proveedores (se muestra la atribución de cada una); no
  se redistribuyen y el servicio puede cambiar o dejar de funcionar sin aviso.

## Marcas

UAPP (USB Audio Player PRO), HiBy, TIDAL, SMSL, Android y Google TV son marcas de sus respectivos dueños. DAC
Stage es un proyecto independiente, sin relación ni respaldo de ellos. Interactúa con esas apps solo mediante
interfaces de Android (sesiones multimedia y archivos o registros de la propia app en el dispositivo del
usuario) y no incluye código ni binarios suyos.
