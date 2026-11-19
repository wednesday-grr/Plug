# Plug

**Plug** is an offline-first Android music player forked from
[Gramophone](https://github.com/FoedusProgramme/Gramophone). It plays music
stored on your device and adds a real-time DJ Automix engine, beatmatching,
offline BPM and musical-key analysis, and harmonic smart queue sorting.

[日本語](./readme_ja.md)

## Features

- Local music library with search, playlists, folder browsing, and natural sorting
- Media3 playback with playlists, media-session controls, audio previews, and
  support for Android's available decoders plus bundled ALAC and MIDI decoders
- Dual-deck Automix with tempo matching, beat-phase alignment, and independent
  audio rendering for each deck
- Configurable transitions: Smart Auto, Smooth Crossfade, Club Bass-Swap,
  Filter Sweep, BPM-Synced Echo Out, and Vinyl Brake
- Offline BPM, beat-grid, and musical-key analysis, cached in a local SQLite
  database; embedded BPM and key tags take precedence over cached analysis
- Harmonic queue sorting using tempo and Camelot-key compatibility
- Synchronized lyrics (LRC, TTML, and SRT), including word- and syllable-level
  karaoke synchronization, plus a lyric widget
- Optional online lyrics lookup through Spicy Lyrics. This is disabled by
  default, requires your own API key, and sends the track's ISRC or title,
  artist, and duration to the service when a lookup is made
- ReplayGain 2.0, dynamic-range compression, and support for system or
  third-party equalizers
- Material 3 design, dynamic colors, Android 12+ Monet-themed icons, and
  Android Auto-compatible media integration

## Usage

Enable Automix under **Settings > Audio**. Beatmatching is enabled by default;
choose a transition style and crossfade duration there. With Automix enabled,
use **Sort for Automix** in the queue to reorder tracks after the current song.
Online lyrics lookup can be enabled in lyric settings and requires your own
Spicy Lyrics API key.

Plug is an Android app and does not provide a public server API or a separate
application CLI. The Gradle commands below are for building and testing.

Automix analysis is an estimate, not a guaranteed beat grid. Tracks may be
skipped if they lack stable file metadata or cannot be decoded by a supported
decoder. Queue sorting reorders tracks after the current song and is unavailable
while a transition is in progress.

## Installation

Plug's application ID is `org.plug.player` (`org.plug.player.debug` for the
debug build). It installs separately from Gramophone, so Gramophone's
app-private settings, queues, analysis cache, and permissions are not
transferred automatically.

Download the latest Plug build from
[GitHub Releases](https://github.com/FoedusProgramme/Gramophone/releases/latest).
The F-Droid and IzzyOnDroid listings for `org.akanework.gramophone` are for
upstream Gramophone, not Plug.

## Building

Install Android Studio with JDK 21, Android SDK Platform 37, Android NDK, and
CMake 3.22.1. Clone the repository with its submodules:

```sh
git clone --recurse-submodules https://github.com/FoedusProgramme/Gramophone.git
cd Gramophone
git submodule update --init --recursive
```

The tracked `package.properties` already sets the required `releaseType` to
`SelfBuilt`. Build and install the debug APK with:

```sh
./gradlew :app:assembleDebug
```

On Windows, use `.\gradlew.bat :app:assembleDebug`. The debug application ID
has the `.debug` suffix.

Run the JVM unit tests with:

```sh
./gradlew test
```

On Windows, use `.\gradlew.bat test`. The app's tests are in
`app/src/test`; generating the Android baseline profile additionally requires
a configured emulator or connected device:

```sh
./gradlew :app:generateBaselineProfile
```

There is no `.env` file or Python `requirements.txt`; build configuration is
provided through Gradle properties and `package.properties`. The optional
`appIdOverride` and `versionNameSuffixOverride` properties can also be set in
`package.properties`. Release signing values should be kept outside the
repository in Gradle user properties: `AKANE_RELEASE_STORE_FILE`,
`AKANE_RELEASE_STORE_PASSWORD`, `AKANE_RELEASE_KEY_ALIAS`, and
`AKANE_RELEASE_KEY_PASSWORD`. The Google Play release variant uses the
corresponding `AKANE2_RELEASE_*` properties.

Plug is primarily a local music player and does not stream music. It requests
Internet access for the opt-in Spicy Lyrics lookup described above; local
playback and music-library browsing do not require a network connection.

## Project structure

```text
.
├── app/                 Android app, UI, playback, Automix, lyrics, and tests
├── baselineprofile/     Macrobenchmark and baseline-profile generator
├── hificore/            Native C++ audio-output and HiFi integration
├── media3/              Media3 source submodule used by the build
├── misc/
│   ├── alacdecoder/     Bundled ALAC decoder
│   └── audiofx*/        Audio-effect forwarding and SDK stubs
├── fastlane/            Android release automation and store metadata
├── .github/workflows/   CI workflows
├── gradle/              Gradle wrapper configuration
├── build.gradle.kts     Root plugin and build configuration
├── settings.gradle.kts  Gradle modules and local Media3 substitutions
└── package.properties   Local build identity (releaseType)
```

The main app source is under `app/src/main/java`: `org.akanework.gramophone`
contains playback logic, Automix, analysis, and UI code; `uk.akane.libphonograph`
contains media-library readers and item utilities. App resources are in
`app/src/main/res`.

## Tech stack

- Kotlin 2.3.0 and Java 21
- Gradle 9.4.1 wrapper and Android Gradle Plugin 9.2.1
- AndroidX Compose and Material 3 (Compose BOM 2025.05.00), AndroidX Media3
  (app dependency coordinates 1.10.1, substituted with the local source
  submodule), Kotlin coroutines-guava 1.11.0, and Coil 3.4.0
- Native C++ built with CMake 3.22.1
- Android minimum SDK 23; app compile and target SDK 37
- Current app version in Gradle: 1.1.2 (version code 24)

The app's Media3 dependencies are substituted with projects from the checked-out
`media3` submodule. Use the Gradle wrapper and recursively initialized
submodules when building.

## License and attribution

Plug is distributed under the GNU General Public License, version 3 or (at your
option) any later version. The original Gramophone copyright notices and
attribution are retained; see [LICENSE](./LICENSE) and
[FoedusProgramme/Gramophone](https://github.com/FoedusProgramme/Gramophone).

## Maintainer

- [wednesday-grr](https://github.com/wednesday-grr) ·
  [Email](mailto:wednesdayfortiktok@gmail.com)

## Contributing

Please open an issue before starting substantial work so scope and implementation
can be discussed. Preserve Plug's local-first playback behavior; online lyrics
lookup is an opt-in feature and is disabled by default.
