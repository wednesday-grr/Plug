# Android AutoMix

## Playback and effects

AutoMix uses two Media3 ExoPlayer decks and the existing MediaSession. Each deck
decodes independently and passes PCM through the deck processor chain
(optional PCM tap, ReplayGain, AGC, Bass Boost, the DJ transition processor,
a seek declick stage), followed by Media3 Sonic for playback-speed changes.
Android's
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
processors. The Now Playing information button opens a transition detail panel
for the active or most recently completed transition in the current queue. It
uses the captured planner inputs and decisions, without rerunning analysis.
The panel distinguishes scheduled effects from skipped filter-slot conflicts
and does not claim per-effect rendering was confirmed: the audio processor does
not currently report that outcome. Analyzer labels show the selected setting;
tags may supply BPM and key instead. Key compatibility, overall energy, tempo
stability, analyzer version and analysis warnings are not captured by the
transition planner and are shown as unavailable. Automatic effect selection
does not analyze bass overlap, echo suitability or reverb suitability.

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
bar/downbeat alignment, room/hall/plate selector, or independent
time-based tail bus. Beat Jump, Beat Loop and Loop Transition exist but use
Media3 `seekTo` on the ~25 ms tick, so boundaries are not sample-accurate. No
full-device performance or listening measurements have
been made. Mono and unsupported PCM formats cannot use this stereo processor.

## Phases A–E additions

Status: implemented and unit-tested (358 unit tests passing, 2 skipped).
Lint, instrumentation tests, device listening checks and benchmarks have not
been run. Phases F–H are described in the next section.

| Feature | Implementation | Setting (default) |
| --- | --- | --- |
| Exponential / S-curve fades | `VolumeCurve` models with independent outgoing/incoming envelopes | Transition style; `automix_fade_curve_intensity` |
| Loudness Matching | `LoudnessMeter` (ITU-R BS.1770 integrated/short-term LUFS and sample peak, 60 s middle window) cached in `loudness` table (DB v7); `LoudnessMatcher` attenuates the louder deck during the overlap, after applied ReplayGain; missing data is "unavailable" | `automix_loudness_matching` (off) |
| AGC | `AutomaticGainController` + `AgcAudioProcessor` in each deck chain; disabled while loudness matching is on | `automix_agc` (off) |
| Bass Boost | `BassBoostFilter` low-shelf biquad (0–12 dB, 40–250 Hz, Q fixed at 0.707) with peak limiter; per-deck mix weighted by the fade so bass does not stack | `automix_bass_boost` (off), `_gain_db` (4), `_frequency_hz` (100) |
| Energy Matching | `EnergyAnalyzer`: unweighted RMS and low (<250 Hz) / mid / high (>4 kHz) band levels in 1 s frames, 0.5 s hop, over the existing 30 s start/end windows (mono, 22,050 Hz, band-limited ~11 kHz), stored in the `energy` table (DB v8). `EnergyMatcher` compares the last 10 s of the outgoing track with the first 10 s of the incoming one and lengthens the overlap up to 1.5x when energy rises by more than 6 dB or drops by more than 9 dB. It never changes gain. Missing, mostly silent or non-comparable data is unavailable | `automix_energy_matching` (off) |
| Beatmatch | Pre-existing: incoming speed matched to outgoing BPM with `BeatPhaseAligner`; half/double-time candidates checked against speed limits; falls back to normal crossfade without a reliable beat grid; bypassed for Vinyl Brake | `automix_beatmatch_enabled` (on) |

RMS values are unweighted and are not perceived loudness. Energy Matching does
not use vocal activity or beat-grid information.

### Arbitration and Audio Quality Info

`AutoMixEffectArbitration.kt` lists every effect with whether it is
runtime-available, the parameters it owns (deck gain, level trim, bass boost,
`TRANSITION_DURATION`, playback speed, filters, loop region and so on) and
pairwise compatibility. Two effects that need the same parameter are not run
together; e.g. Energy Matching owns only `TRANSITION_DURATION`, so it coexists
with the fades, while AGC and Loudness Matching are mutually exclusive. The
Audio Quality Info sheet shows loudness and energy decisions (applied, within
threshold, data unavailable) with the measured values captured when the
transition started. Bass Boost and AGC are not yet reported there.

## Phases F–H: stems

Status: code complete and unit-tested (403 unit tests, lint clean) but **not
verified by listening on a device**: the real Spleeter model has never been
downloaded or run, and stem-player/deck sync is unmeasured. No benchmarks exist.

- **Model.** Spleeter 2-stem fp32 ONNX (upstream MIT; sherpa-onnx conversion,
  Apache-2.0, pinned HF commit `7001ba3…`), run with ONNX Runtime 1.22.0 off the
  audio thread. It is not bundled: Settings → AI Stem Separation downloads ~78.6 MB
  after an explicit tap and verifies size and SHA-256 per file. Removing the
  model and clearing the stem cache are in the same screen; the cache size shown
  is the real size of completed entries on disk.
- **Separation.** `StemSeparationManager` queues the current and next track
  (single worker, LRU cache limit 1 GB, cancellation, integrity checks).
  Stems are ~11 kHz band-limited 44.1 kHz stereo.
- **Playback.** When `automix_stem_transitions` (default **off**) is on and both
  tracks have verified stems, `StemPlayer` renders four sources (A vocal, A
  instrumental, B vocal, B instrumental) to its own `AudioTrack` for
  Instrumental Drop / Instrumental Fade / Vocal-to-Instrumental /
  Instrumental-to-Vocal. The decks stay silent-ready underneath; any unmet
  condition (stems missing or still processing, AGC/Bass Boost/additional
  effects on, non-smooth style, tempo change over 1 %, failure or pause mid
  transition) uses the ordinary crossfade instead.
- **Audio Quality Info** shows measured BS.1770 LUFS, per-track stem status
  (disabled / ready / separating / not cached / failed) and the exact
  skip/fallback reason.
- **Not implemented:** a Now Playing visualizer (none exists in the app), and
  user-facing independent stem gain, Vocal/Instrumental "effects" on their own.

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
reanalyzed according to their modification identity. Essentia cache entries
without a key are reanalyzed.

Essentia's RhythmExtractor2013 multifeature receives up to 60 seconds from the
middle of a track, decoded to mono and resampled to 44,100 Hz with a
windowed-sinc low-pass filter. The limit bounds memory and processing cost but
does not capture all tempo changes, long intros or outros. At least five seconds
and a known track duration are required; silent audio, discontinuous timestamps
and invalid results are reported as failures. After seeking, a short initial
buffer with a timestamp gap bounded by the reported encoder delay is discarded;
analysis starts at the next buffer, without changing playback. No
automatic analyzer fallback occurs. Failures are logged and shown in quality
info for the current track. Audio tags still take precedence over analyzed BPM
and key; the quality-info label identifies the *setting*, not necessarily the
source of a tagged BPM.

Essentia supplies middle-window BPM and a Camelot key to AutoMix. The key is
estimated by Essentia's `KeyExtractor`; its strength is kept separate from the
rhythm confidence and is not currently used by AutoMix. Rhythm confidence is
not comparable to the existing analyzer's confidence, so it is not converted
into a trusted beat-grid confidence. Vocal energy is not supplied by this
backend. The existing edge/silence analyzer still runs on edge windows for
transition points and may provide its own independently validated edge grids.
Native computation itself is not interruptible; on cancellation the result is
discarded after the native call returns. Full-track tempo variability, analyzer
comparisons and on-device performance measurements remain unverified.

The Now Playing **Audio Quality Info** panel reads the active or most recently
completed transition snapshot from `AutomixPlayer`. It reports planner-selected
effects separately from filter-slot conflicts and includes the reason codes and
metrics captured when that plan started. When there is no transition snapshot,
the panel shows current and next-track results from the existing analysis cache
and reports whether analysis is pending, running, available or failed; opening
the panel does not start analysis. The engine shown per track is the selected
analyzer used for the corresponding cached result lookup, while tagged BPM/key
may take precedence. Stale snapshots are suppressed after queue, track or
analyzer changes.

Explanations are limited to decisions the current selector and effect planner
actually make. Effects omitted by the planner are not described as rejected,
and no key compatibility, overall-energy, BPM-confidence, variable-tempo or
per-effect render-success result is inferred. To add a reason, add an
`EffectDecisionReason` value, emit it only from the decision path that observes
its condition, and add matching English and Thai string resources and
deterministic tests. A new analysis engine must be wired through
`AudioAnalysisScheduler` and `TrackAnalysisCache`, and must keep its own cache
identity rather than being represented as an analyzer that did not produce the
result.

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
