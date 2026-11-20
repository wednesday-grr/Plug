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

This repository contains no Python AutoMix renderer, Librosa/CUE-DETR analysis,
or Essentia integration. Essentia has an Android-capable C++ library, but adding
it to playback analysis requires a separately verified NDK build and JNI
adapter, ABI packaging, dependency and AGPL licensing review, cache provenance,
and device tests. An external Python tool would not provide on-device analysis.
The official [Essentia Android build instructions](https://essentia.upf.edu/installing.html#building-essentia-on-android)
specify Linux or macOS cross-compilation; the current Windows environment has no
installed WSL distribution. RhythmExtractor2013 requires 44.1 kHz input, whereas
the current decoder produces 11,025 Hz analysis PCM, so an adapter must resample
or decode separately, retain timestamp offsets, and distinguish analyzer results.

The **Build Essentia for Android** GitHub Actions workflow can be started via
Actions > Build Essentia for Android > Run workflow after it is pushed. It
cross-compiles a pinned Essentia revision for arm64-v8a, armeabi-v7a, x86 and
x86_64 using NDK 27.2 and API 23. Each job publishes an artifact containing the
static library, headers, dependency/license information and build metadata.
The lightweight build uses KissFFT and excludes FFmpeg and libsamplerate, so
audio decoding and 44.1 kHz resampling must be supplied separately. The workflow
checks object architectures, algorithm registration and linking; it does not
run the algorithm on an Android device. The result is not yet a JNI library and
is not packaged in the app. Review Essentia's AGPL and other dependency licenses
before distribution.

No Essentia option, automatic fallback, comparison benchmark, or accuracy claim
is provided until an actual backend and representative ground-truth dataset
are available. The existing analyzer remains unchanged.
