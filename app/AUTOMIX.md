# Android AutoMix

## Playback and effects

AutoMix uses two Media3 ExoPlayer decks and the existing MediaSession. Each deck
decodes independently and passes stereo PCM through the existing ReplayGain and
DJ processors, followed by Media3 Sonic for playback-speed changes. Android's
audio output mixes the two decks; this application does not currently have a
shared master PCM bus or master limiter.

The transition style remains the primary preset. Additional simultaneous effects
can be selected in audio settings; the default is none. Their selection persists
and does not replace the primary preset. The transition plan assigns each
effect an independent normalized start/end, curve and intensity; built-in plans
use fixed parameters rather than exposing a parameter editor. The player
updates the plan approximately every 25 ms while both decks are playing.
An effect-intensity slider scales all scheduled effects together, independently
of crossfade, beatmatching, Vinyl Brake and Tremolo Fade. It does not configure
individual effects.
Pausing freezes transition progress; cancellation and handoff bypass the deck
processors. The quality-info display reports the active or most recent effects.

| Effect | Bus | Built-in automation |
| --- | --- | --- |
| Bass Swap | Both | Smooth swap in the second half |
| Vocal Swap | Both | Smooth vocal-band attenuation swap in the second half |
| Three-Band EQ Transition | Both | Low, mid and high cuts exchange during the fade |
| Filter Sweep | Outgoing high-pass and incoming low-pass | Exponential cutoff sweep |
| High-Pass Intro | Incoming | High-pass cutoff falls from 3500 Hz to 20 Hz |
| Rise | Incoming | Low-pass cutoff rises from 300 Hz to 20 kHz |
| Echo Out | Outgoing | Wet level rises, dry level falls near the end |
| Reverb Tail | Outgoing | Wet level rises during the transition |

Bass Swap, Vocal Swap, one filter preset, Echo Out and Reverb Tail can run at
the same time as Three-Band EQ, crossfade and, when reliable beat grids are available,
beatmatching. Each deck currently has **one** filter slot: Filter Sweep,
High-Pass Intro and Rise cannot share the incoming slot. The primary preset wins;
otherwise their listed order above determines priority. Rejected effects are
logged and are not listed as active. Bass and vocal processing have separate
slots. Bass Swap takes ownership of the low EQ band and Vocal Swap takes
ownership of the mid band when combined with EQ, so their cuts do not stack.
The EQ uses fixed low shelf (200 Hz), mid cut (1 kHz) and high shelf (6 kHz)
with roughly 12 dB maximum attenuation per band and 10 ms parameter smoothing;
its bands cannot be individually configured. Crossfade controls deck volume once; overlap headroom and per-deck PCM
clamping reduce risk but cannot guarantee the combined system mix will not clip.

Echo and reverb are processed in series on the outgoing deck. Their tails end
when the outgoing deck is stopped; they are not rendered past handoff. Filter
and parameter updates are control-rate, not sample-accurate. There is no
configurable band frequencies, simultaneous high-pass and low-pass on one deck,
bar/downbeat alignment, beat loop, room/hall/plate selector, or independent
time-based tail bus. No full-device performance or listening measurements have
been made. Mono and unsupported PCM formats cannot use this stereo processor.

## Analysis and Essentia

The current analyzer is Kotlin `TrackDspAnalyzer`, run off the main thread by
`AudioAnalysisScheduler` using PCM from Android `MediaCodec`. It analyzes a
middle window for tempo/key and additional edge windows for transition points.
Cached analyses remain in the existing SQLite database. Confidence describes
the existing estimator and is not interchangeable with another analyzer's.

This repository contains no Python AutoMix renderer or Librosa/CUE-DETR analysis.
Audio settings provide an experimental **BPM analysis engine** choice. Existing
is the default; Essentia requires the opt-in build below. Changing the setting
does not rebuild the player or reset its queue. Cached results are keyed by
analyzer; existing version 3 cache rows migrate as `existing`. An uncached track
is analyzed when it is queued or when idle library analysis reaches it.
Switching back reuses that engine's cached result. Files changed on disk are
reanalyzed according to their modification identity.

Essentia's RhythmExtractor2013 multifeature receives up to 60 seconds from the
middle of a track, decoded to mono and resampled to 44,100 Hz with a
windowed-sinc low-pass filter. The limit bounds memory and processing cost but
does not capture all tempo changes, long intros or outros. At least five seconds
and a known track duration are required; silent audio, discontinuous timestamps
and invalid results are reported as failures. No
automatic analyzer fallback occurs. Failures are logged and shown in quality
info for the current track. Audio tags still take precedence over analyzed BPM
and key; the quality-info label identifies the *setting*, not necessarily the
source of a tagged BPM.

Essentia currently supplies the middle-window BPM only to AutoMix. Its raw
confidence is not comparable to the existing analyzer's confidence, so it is
not converted into a trusted beat-grid confidence. Key and vocal energy are
not supplied by this backend. The existing edge/silence analyzer still runs on
edge windows for transition points and may provide its own independently
validated edge grids. Native computation itself is not interruptible; on
cancellation the result is discarded after the native call returns. Full-track
tempo variability, analyzer comparisons and on-device performance measurements
remain unverified.

The **Build Essentia for Android** GitHub Actions workflow can be started via
Actions > Build Essentia for Android > Run workflow after it is pushed. It
cross-compiles a pinned Essentia revision for arm64-v8a, armeabi-v7a, x86 and
x86_64 using NDK 27.2 and API 23. Each job publishes an artifact containing the
static library, headers, dependency/license information and build metadata.
The lightweight build uses KissFFT and excludes FFmpeg and libsamplerate, so
audio decoding and 44.1 kHz resampling must be supplied separately. The workflow
checks object architectures, algorithm registration and linking, then downloads
all four artifacts and builds an opt-in debug APK containing the JNI library.
The workflow does not run the algorithm on an Android device.

### Local Essentia build

The default app build requires no Essentia files and keeps the existing analyzer.
To enable Essentia, download the four `essentia-android-<ABI>`
artifacts from the same workflow run and extract each archive into
`app/essentia-artifacts/<ABI>/`, such that `lib/libessentia.a`,
`include/`, `licenses/`, and `build-info.txt` are directly inside the ABI
directory. This directory is ignored by Git; do not commit the binaries. The
build checks the pinned commit, ABI, NDK, API and Eigen metadata. Use NDK
27.2.12479018 and CMake 3.22.1. To enable it for Android Studio's Run/Debug
configurations, add `useEssentia=true` to the root `local.properties` file and
sync Gradle. This file is local and ignored by Git. Run or reinstall the
resulting debug app; an already installed APK does not gain the native library.
Alternatively, enable it for a single command:

```shell
./gradlew :app:assembleDebug -PuseEssentia=true
```

On Windows use `gradlew.bat` instead. The command-line Gradle property takes
precedence over `local.properties`: use `-PuseEssentia=false` to explicitly
build without Essentia. The choice controls packaging for all build types,
including release. Native dependency license files are
packaged in the opt-in APK assets. Essentia is AGPL-licensed; packaging notices
alone does not establish compliance. Review Essentia's AGPL, its dependencies'
licenses, and corresponding-source obligations before distributing the APK.
The pinned source and build scripts are identified in the workflow.

No automatic fallback, comparison benchmark or accuracy claim is provided.
The existing analyzer remains available and is the safer choice for its
established key and beat-grid behavior; Essentia is useful for controlled
experimentation with an alternative rhythm estimator, not guaranteed better
accuracy. On builds without the native library, choosing Essentia reports a
failure instead of silently switching engines.
