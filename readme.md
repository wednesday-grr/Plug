# Plug

Plug is a fork of [Gramophone](https://github.com/FoedusProgramme/Gramophone), an
offline Android music player, enhanced with DJ Automix, beatmatching and beat-phase
alignment, offline BPM/key DSP analysis, and harmonic smart queue sorting. Plug
retains Gramophone's GPLv3-or-later license and credits its original authors and
contributors.

[日本語](./readme_ja.md)

## Features

- Offline playback of music stored on your device
- DJ Automix with tempo matching, beat-phase alignment, and configurable transitions
- Offline BPM and musical-key analysis, cached locally
- Harmonic queue sorting using tempo and musical key
- Material 3 design, dynamic colors, and Monet-themed icon on Android 12+
- Search, playlists, folder browsing, and natural sorting
- Synchronized lyrics (LRC, TTML, SRT), including word/syllable karaoke sync
- ReplayGain 2.0 and support for system or third-party equalizers

Automix analysis is an estimate, not a guaranteed beat grid. Embedded tags take
precedence over cached analysis. Tracks without a stable modification date or a
supported system decoder may be skipped. Queue sorting reorders tracks after the
current song and is unavailable during a crossfade.

## Installation

Plug's application ID is `org.plug.player`. It installs separately from
Gramophone, so Gramophone's app-private settings, queues, analysis cache, and
permissions are not transferred automatically.

Use the [latest GitHub release](https://github.com/FoedusProgramme/Gramophone/releases/latest)
for Plug builds. The F-Droid and IzzyOnDroid listings for
`org.akanework.gramophone` are for upstream Gramophone, not Plug.

## Building

Install [Android Studio](https://developer.android.com/studio), clone the
repository with its submodules, and build the debug variant:

```sh
git clone --recurse-submodules https://github.com/FoedusProgramme/Gramophone.git
cd Gramophone
git submodule update --init --recursive
```

Create `package.properties` in the repository root with:

```properties
releaseType=SelfBuilt
```

Then run `./gradlew assembleDebug` (or `.\gradlew.bat assembleDebug` on Windows).
The default application ID is `org.plug.player`.

## License and upstream credit

Plug is distributed under the GNU General Public License, version 3 or (at your
option) any later version. The original Gramophone copyright notices and
attribution are retained; see [LICENSE](./LICENSE) and
[FoedusProgramme/Gramophone](https://github.com/FoedusProgramme/Gramophone).

## Contributing

Please open an issue before starting substantial work so scope and implementation
can be discussed. Plug is an offline player and does not request the Internet
permission.
