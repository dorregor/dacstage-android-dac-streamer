# Third-party notices

DAC Stage's code is released under the MIT License (see [LICENSE](LICENSE)). The APK bundles the following
libraries, each under its own license. Exact versions: `gradle/libs.versions.toml`.

## Bundled in the APK

| Library | License |
|---|---|
| AndroidX: core, activity, lifecycle, media, palette, profileinstaller | Apache-2.0 |
| Jetpack Compose: ui, foundation, animation, material3, material-icons-core, adaptive | Apache-2.0 |
| AndroidX TV Material (`androidx.tv:tv-material`) | Apache-2.0 |
| Coil 3 (`io.coil-kt.coil3`) | Apache-2.0 |
| OkHttp and Okio (via `coil-network-okhttp`) | Apache-2.0 |
| kotlinx.serialization, kotlinx.coroutines, Kotlin stdlib | Apache-2.0 |
| Conscrypt (`org.conscrypt:conscrypt-android`) | Apache-2.0 |
| Bouncy Castle (`bcpkix-jdk15to18`, `bcprov-jdk15to18`) | [Bouncy Castle Licence](https://www.bouncycastle.org/licence.html) (MIT-style) |
| libadb-android (`com.github.MuntashirAkon:libadb-android`) | `GPL-3.0-or-later OR Apache-2.0`: **DAC Stage uses it under Apache-2.0** |
| spake2-android (`com.github.MuntashirAkon.spake2-java:spake2-android`, a dependency of libadb-android) | **LGPL-3.0** |

Apache-2.0 text: <https://www.apache.org/licenses/LICENSE-2.0>.

### About spake2-android (LGPL-3.0)

It is used by the local ADB client that reads the UAPP queue (wireless debugging pairing). It is linked as an
unmodified library. Its source code is at <https://github.com/MuntashirAkon/spake2-java> and the license text
at <https://www.gnu.org/licenses/lgpl-3.0.html>. Since DAC Stage's full source is open and builds with Gradle
(see the README), anyone can rebuild the APK with a different version of that library, as the LGPL requires.

### Icons

Several icons in `ui/common/AppIcons.kt` are paths from Material Icons (Google, Apache-2.0).

## Build and test only (not in the APK)

JUnit 4 (EPL-1.0), AndroidX Test, UI Automator and Benchmark/Baseline Profile (Apache-2.0), Android Gradle
Plugin and Kotlin Gradle Plugin (Apache-2.0).

## Online services (optional, at runtime)

DAC Stage doesn't include data or images from these services: it queries them when the user enables them.

- **LRCLIB** (<https://lrclib.net>): lyrics. The track's title, artist, album and duration are sent.
- **MusicBrainz** and **Cover Art Archive** (<https://musicbrainz.org>): year, label and cover.
- **Google Earth View**: TV screensaver photos, from public but undocumented Google endpoints. The photos are
  copyrighted by Google and its providers (each photo's attribution is shown); they are not redistributed and
  the service may change or stop working without notice.

## Screenshots

The images in `docs/screenshots/` show album artwork, song lyrics and Earth View photos that belong to their
respective rights holders. They appear only to illustrate how the app works and are not covered by this
project's MIT License.

## Trademarks

UAPP (USB Audio Player PRO), HiBy, TIDAL, SMSL, Android and Google TV are trademarks of their respective
owners. DAC Stage is an independent project, not affiliated with or endorsed by them. It interacts with those
apps only through Android interfaces (media sessions, and the app's own files or logs on the user's device)
and contains no code or binaries from them.
