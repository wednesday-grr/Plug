# Plug

**Plug** is an offline Android music player forked from
[Gramophone](https://github.com/FoedusProgramme/Gramophone), enhanced with a
real-time DJ Automix engine, beatmatching and beat-phase alignment, offline BPM
and musical-key DSP analysis, and harmonic smart queue sorting.

[日本語](./readme_ja.md)

## Features

- Offline playback of music stored on your device
- Dual-deck DJ Automix with tempo matching, beat-phase alignment, and independent
  audio rendering for each deck
- Configurable transitions: Smooth Crossfade, Club Bass-Swap, Filter Sweep,
  BPM-Synced Echo Out, Vinyl Brake, Vocal-Swap, and Smart Auto
- Offline BPM, beat-grid, and musical-key analysis, cached locally in SQLite
- Harmonic queue sorting using tempo and Camelot musical-key compatibility
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

Download the latest Plug build from
[GitHub Releases](https://github.com/FoedusProgramme/Gramophone/releases/latest).
The F-Droid and IzzyOnDroid listings for `org.akanework.gramophone` are for
upstream Gramophone, not Plug.

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
can be discussed. Plug is an offline player and does not request the Internet
permission.
