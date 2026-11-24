# Offline beat evaluation

This tool evaluates the existing Kotlin analyzer, not a separate implementation. It runs only
when explicitly invoked, never in playback. Supply audio and annotations you have rights to use;
no audio or annotations are included in this repository. A useful candidate is the
[Salsa Dataset](https://doi.org/10.5281/zenodo.13120822), published by Gómez-Marín et al.
as a dataset-level CC BY 4.0 record. The record lists 124 songs and includes
`audio_fragments.zip`, `beat_annotations.zip`, a song metadata CSV, and mel spectrograms. Cite
the [dataset paper](https://doi.org/10.5334/tismir.183) when using its data. The paper describes
the original songs as 48 kHz, 16-bit WAV, says annotations are in milliseconds, and says
additional complete audio is shared on request for fair nonprofit research. It describes the
annotated events as participants' beat sensations, not downbeats. The CSV contains song ID,
title, artist, year, album, and AcoustID, but no fragment start, duration, sample-rate conversion,
or timeline metadata.

The published `beat_annotations.zip` was inspected and its MD5 matched the Zenodo record.
It contains 124 text files with 49,447 integral, nonnegative, strictly increasing timestamps in
total (observed range: 69–619,021 ms). This confirms the annotation file structure and values,
not the alignment of the files to the audio fragments. The article reports different aggregate
annotation counts in its prose (49,433) and table (50,511), which should be clarified with the
authors before using the set as a formal benchmark.

**Acceptance status: no Salsa recordings are currently accepted for accuracy evaluation.** The
Zenodo record does not provide per-recording rights statements. Although it labels the dataset
CC BY 4.0, songs are identified as commercial recordings and the paper separately limits access
to complete audio to fair nonprofit research; rights for each distributed fragment and local
evaluation have not been independently established. The paper describes full-song annotation
but does not document how each published audio fragment maps onto those timestamps. Do not
copy, redistribute, publish, or benchmark these recordings until the relevant recording rights
and each audio/annotation mapping have been independently confirmed. Convert annotation
milliseconds to seconds only after the mapping is confirmed; do not infer an offset from song
metadata or signal similarity. Thus the current accepted count is zero, and all 124 candidate
recordings are excluded pending track-level rights and alignment evidence. Salsa-only results,
even when available, cannot establish broad genre accuracy.

## RWC-R local development evaluation

The approved local, non-commercial evaluation used all 15 recordings in the RWC-R subset. Audio
came from the [RWC 2.0 RWC-R Zenodo record](https://doi.org/10.5281/zenodo.18656623); the separate
annotation source was [`rwc-music/rwc-annotations` at commit
`0a1a6c31dbe73a7f5d44f7caef8cd0999402a4c2`](https://github.com/rwc-music/rwc-annotations/tree/0a1a6c31dbe73a7f5d44f7caef8cd0999402a4c2).
The audio and annotations are each identified as CC BY-NC 4.0 by their respective release sources.
Attribution is required and this use was limited to local non-commercial evaluation; neither audio
nor annotations are redistributed here. This does not grant rights to the underlying compositions
or authorize commercial use.

All 15 files were matched by RWC-R recording ID to annotation CSVs. The WAVs are 44.1 kHz, stereo,
16-bit PCM. The annotation `t` field is seconds on the full-file timeline; its `beat` field was not
used to classify event type. Each evaluation window is exactly the first 60 seconds from file
time zero, with no offset or silence trimming. Audio was averaged to mono and resampled to 11,025 Hz
with the evaluation runner's centered 33-tap windowed-sinc filter; a known one-second impulse test
verified the conversion to within one output frame. The first-minute reference events are selected
from the source CSV by `t <= 60` and converted directly to integer microseconds. The full source
durations range from 106.214 to 176.666 seconds. No recording was excluded from this local
development run. Several files have annotations extending beyond their metadata `audio_end`;
those tails are outside the evaluated windows and are not evidence of full-track alignment.

The manifest can point `beats` at the source CSV and set `"reference_format": "rwc-r"`. For
example:

```json
{
  "dataset": "RWC 2.0 RWC-R, first-minute development windows",
  "license": "CC BY-NC 4.0; local non-commercial evaluation only; no redistribution",
  "tracks": [
    {
      "id": "RWC_R001",
      "audio": "audio/RWC_R001.wav",
      "beats": "annotations/RWC_R001.csv",
      "reference_format": "rwc-r",
      "reference_bpm": 120.0
    }
  ]
}
```

After constructing a manifest for all 15 locally held recordings and their matching source CSVs,
run:

```powershell
python tools\beat_evaluation.py C:\path\to\rwc-r\manifest.json C:\path\to\rwc-r-results.json
```

This run invoked the production Kotlin `TrackDspAnalyzer` / `MusicalBeatTracker`, algorithm
`spectral-onset-tracker-v3-local-interval`, configuration
`track-dsp-beat-config-v2-local-interval`, and the fixed 70 ms matching tolerance. It is
development-set evidence, not a held-out test or a confidence calibration.

| Track | Source s / window s | Detected / reference | Matched | F1 | Median error ms | Analysis ms | Tempo result |
|---|---:|---:|---:|---:|---:|---:|---|
| RWC_R001 | 125.149 / 60 | 0 / 120 | 0 | 0.000 | — | 497.6 | Ambiguous |
| RWC_R002 | 128.033 / 60 | 103 / 104 | 90 | 0.870 | 17.2 | 298.6 | Primary |
| RWC_R003 | 176.666 / 60 | 0 / 75 | 0 | 0.000 | — | 280.1 | Ambiguous |
| RWC_R004 | 126.467 / 60 | 55 / 70 | 52 | 0.832 | 14.4 | 311.6 | Primary |
| RWC_R005 | 128.354 / 60 | 126 / 130 | 122 | 0.953 | 21.4 | 302.6 | Double |
| RWC_R006 | 118.346 / 60 | 112 / 115 | 39 | 0.344 | 49.2 | 274.0 | Primary |
| RWC_R007 | 122.736 / 60 | 0 / 100 | 0 | 0.000 | — | 1254.0 | Ambiguous |
| RWC_R008 | 124.079 / 60 | 130 / 130 | 130 | 1.000 | 17.4 | 437.0 | Double |
| RWC_R009 | 152.672 / 60 | 96 / 100 | 94 | 0.959 | 10.2 | 475.3 | Primary |
| RWC_R010 | 143.938 / 60 | 58 / 116 | 54 | 0.621 | 16.3 | 531.4 | Half (incorrect) |
| RWC_R011 | 128.524 / 60 | 117 / 120 | 44 | 0.371 | 64.6 | 313.7 | Primary |
| RWC_R012 | 122.901 / 60 | 0 / 200 | 0 | 0.000 | — | 423.1 | Ambiguous |
| RWC_R013 | 116.525 / 60 | 0 / 70 | 0 | 0.000 | — | 480.7 | Ambiguous |
| RWC_R014 | 122.719 / 60 | 175 / 180 | 115 | 0.648 | 56.8 | 336.8 | Primary |
| RWC_R015 | 106.214 / 60 | 0 / 79 | 0 | 0.000 | — | 317.4 | Ambiguous |

Micro aggregate: precision 0.761, recall 0.433, F1 0.552, and median absolute timing error
21.939 ms over 740 matches. Tempo interpretation was correct for 8/14 classifiable tracks
(0.571); RWC_R012 was excluded from this denominator because its independently supplied 200 BPM
cannot be classified within 4% of any of its three tempo-prior interpretations. Six tracks
(0.400) were ambiguous/unavailable and remain included in beat-event aggregate metrics as zero
detections. RWC_R010 is a known wrong half-time selection. Analysis took 274–1,254 ms per
60-second window (median 336.8 ms; mean 435.6 ms); this excludes WAV loading and Gradle startup.
These host-side measurements are not device benchmarks.

These are real-audio development metrics, not genre-wide accuracy and not evidence that confidence
is calibrated. The earlier synthetic click-track result (F1 0.974, median absolute timing error
45.7 ms) is a separate synthetic test and must not be conflated with this evaluation.

## Candombe session-separated evaluation

The [Uruguayan Candombe dataset](https://doi.org/10.5281/zenodo.6533068) is published by Luis
Jure and Martin Rocamora under CC BY 4.0, with 35 studio performances, 44.1 kHz stereo 16-bit
FLAC, and expert beat annotations. Cite Nunes, Rocamora, Jure and Biscainho, *Beat and Downbeat
Tracking Based on Rhythmic Patterns Applied to the Uruguayan Candombe Drumming*, ISMIR 2015.
The source `.beats` files contain beat times in seconds and a beat-position column; only the
first column is used. The publisher describes the annotations as corresponding to the complete
recordings. No trim, guessed offset, or sample-rate conversion is applied when extracting the
first 60 seconds to WAV. The evaluation runner uses its tested 44.1 kHz to 11,025 Hz conversion
and clips reference timestamps to the analyzed window. Reference BPM is the median beat-interval
tempo in that window. These recordings represent percussion ensembles, not general music.

The 1992 and 1995 sessions (21 recordings) were used for development; all 14 2014 session
recordings were held out until the unchanged v3 tracker was selected for evaluation. Session
separation does not guarantee performer independence. The results are **not confidence calibration**:

| Split | Tracks | Detections / references | Matches | Precision | Recall | F1 | Ambiguous / unavailable |
|---|---:|---:|---:|---:|---:|---:|---:|
| 1992 + 1995 development | 21 | 138 / 2,745 | 37 | 0.268 | 0.013 | 0.026 | 20 / 21 |
| 2014 held out | 14 | 0 / 1,644 | 0 | undefined | 0.000 | 0.000 | 14 / 14 |

All holdout recordings produced unavailable, ambiguous grids. No match-probability model or
reference-coverage estimator was fitted or shipped. A bounded-tempo experiment on the development
sets raised RWC-R F1 to 0.596 and Candombe development recall to 0.042, but lowered Candombe
event precision to 0.204 and did not fix RWC_R010; it was discarded. The final unchanged tracker
reproduced the RWC-R F1 of 0.552. This outcome blocks calibrated-confidence and reliable
percussion-grid claims; a wider, representative independent benchmark and improved detector are
needed. The Candombe holdout must not be reused to tune a model and then claimed as independent.

With the publisher's archives stored outside the repository and Python `soundfile` installed,
the session split and evaluation are reproducible:

```powershell
python tools\prepare_candombe_evaluation.py C:\data\candombe_audio.zip C:\data\candombe_annotations.zip C:\data\prepared --split training
python tools\prepare_candombe_evaluation.py C:\data\candombe_audio.zip C:\data\candombe_annotations.zip C:\data\prepared --split holdout
python tools\beat_evaluation.py C:\data\prepared\candombe-training.json C:\data\candombe-training-results.json
python tools\beat_evaluation.py C:\data\prepared\candombe-holdout.json C:\data\candombe-holdout-results.json
```

The preparation script checks the publisher's archive MD5 values and refuses to overwrite
existing generated files. The JSON report includes per-track counts and, for detected events,
the uncalibrated tracker score and one-to-one match label. Audio remains local and is not included
in this repository.

`AudioAnalysisScheduler` exposes `requestBeatAnalysis(item, ownerId)` and
`cancelBeatAnalysis(ownerId)`. The track details screen now offers an explicit **Analyze beats**
action through a MediaSession command; it does not analyze automatically on screen opening.
Scheduler tests cover its offline queue behavior, and the cache stores results in schema v6,
separate from legacy BPM/Key and periodic-pulse rows. Beat-grid reads distinguish missing,
invalidated, unavailable, empty, and detected results. The actual Detail-screen button, cancellation,
and subsequent polled result were exercised by device instrumentation using a synthetic MediaStore
fixture. That test temporarily suspends the debug service's queued offline analysis, restores it
afterward, and does not change the playback queue. Audible playback continuity is not verified.
Beat analysis does not feed playback or AutoMix.

Provide WAV files as mono signed 16-bit little-endian PCM at 11,025 Hz, each at most 60 seconds.
The waveform must be aligned with the supplied reference timestamps; do not silently apply
encoder-delay, fragment-offset, or playback-timeline corrections. Text reference files contain
one beat timestamp in seconds per line, relative to the WAV start, sorted and unique; lines
starting with `#` are comments. Alternatively, `reference_format: "rwc-r"` reads source
semicolon-separated RWC-R CSV `t` values in seconds and clips them to the analyzed window. BPM is
optional and must be independently annotated, not inferred from the detector. Use separate
development and final-evaluation fixtures.

Example manifest (paths relative to the manifest):

```json
{
  "dataset": "example, source and split",
  "license": "license and restrictions",
  "tracks": [
    {"id": "track-1", "audio": "track-1.wav", "beats": "track-1.txt", "reference_bpm": 120.0}
  ]
}
```

With a JDK supported by the project and its Gradle dependencies available:

```powershell
python tools\beat_evaluation.py path\to\manifest.json path\to\results.json
python -m unittest discover -s tools -p test_beat_evaluation.py
```

The test runner executes the same `TrackDspAnalyzer.analyzeWithBeatTracking` code as the
prototype. Timing includes analyzer and tracker computation, not WAV loading or Gradle startup.
The fixed primary tolerance is 70 ms. Sorted events are matched one-to-one using the earliest
compatible reference and detection; each event can match only once. Unmatched and duplicate
events count against precision or recall. Precision is matches/detections, recall is
matches/references, and F1 is 2*matches/(detections+references). Undefined divisions are null.
Timing error is the median absolute error over matched pairs. Aggregate beat metrics are micro
metrics, pooling events and errors across tracks.

`spectral-onset-tracker-v3-local-interval` updates a local period estimate from each selected
observed-onset interval. Its reported BPM is the median local period; output beat events remain
only observed onsets, and the tracker does not fill missing beats or extrapolate through silent
regions. A small support margin determines whether tempo interpretation is ambiguous. Onset,
periodicity, and musical-beat support are not calibrated probabilities. Regions partition the
analysis window into intervals between consecutive observed events and intervals without such
adjacency, including missing-index gaps and outer edges. This is observational metadata, not
proof that a beat is correct or that all beats were found: region-level calibrated event-match
probability and reference coverage are unavailable. It is persisted in the existing schema-v6
JSON metadata; the configuration version `track-dsp-beat-config-v3-observation-regions`
invalidates earlier beat-grid records without changing legacy BPM, key, or phase records.

`TrackPcmDecoder` uses Android `MediaExtractor` and `MediaCodec`, so it is container-agnostic and
can decode any local audio stream for which the current device exposes a decoder. This is not a
promise that every extension or codec works on every Android device: successful decoding depends
on the audio MIME type exposed by `MediaExtractor`, device codec implementation, and source
constraints. The on-device instrumentation harness in `app/src/androidTest` reports advertised
audio decoder MIME types, probes each supplied file's extractor track MIME types (which may be
`audio/raw` for PCM or extractor-decoded sources), and attempts the production decoder, analyzer,
cache, and scheduler cancellation path. This metadata is not necessarily the original compressed
codec. Unsupported or failed fixtures are reported individually rather than aborting the
remaining format checks.

Put any local audio fixtures in the app-private `files/beat-benchmark` directory; extensions are
only hints and every regular file there is probed. The generated `benchmark-results.json` uses
anonymous fixture IDs and records MIME type, format hint, size, duration, and analysis/cache
timings, not file names or paths. It reports source duration separately from decoded analysis
window because production decoding is windowed (currently at most 60 seconds); the whole-track
ratio must not be read as if the whole track was analyzed. Synthetic tone/click fixtures exercise
format handling and runtime only, not musical accuracy.

Build with `.\gradlew.bat :app:assembleDebugAndroidTest`, install the debug app and test APK, copy
fixtures into the app-private `files/beat-benchmark` directory, then run
`adb shell am instrument -w -e class com.siw.plug.BeatAnalysisDeviceBenchmarkTest com.siw.plug.debug.test/androidx.test.runner.AndroidJUnitRunner`.
The generated `benchmark-results.json` is local device output and is not included in the repository.

### RMX3939 synthetic device run

The connected device reported model `RMX3939`, Android 16/API 36, SoC `UMS9230H` / board
`ums9230`, 8,001,176 kB RAM, and `/data` capacity of 105 GB with 23 GB free. The commercial
name was not independently exposed by device properties. This run used synthetic MP3, FLAC, and
WAV fixtures only; results establish format/runtime/cache handling, not musical accuracy.

Elapsed time includes production decode plus analysis of at most the first 60 seconds. CPU is
thread CPU time; PSS is a before/after process snapshot, not peak memory. Cache columns are
measured load-miss, write, and load-hit durations.

| Format | Track s | File bytes | Decode + analysis ms | Elapsed / track | CPU ms | PSS before → after kB | Cache miss / write / hit ms |
|---|---:|---:|---:|---:|---:|---:|---:|
| FLAC | 360.000 | 3,190,868 | 5,241.1 | 0.0146 | 3,573.4 | 118,249 → 121,948 | 3.36 / 60.57 / 29.91 |
| MP3 | 360.071 | 5,761,610 | 6,176.1 | 0.0172 | 3,860.3 | 124,257 → 121,806 | 2.37 / 11.67 / 8.58 |
| FLAC | 120.000 | 1,068,459 | 3,443.3 | 0.0287 | 2,651.7 | 122,186 → 121,154 | 2.28 / 10.41 / 8.48 |
| MP3 | 120.058 | 1,921,401 | 5,879.1 | 0.0490 | 3,688.6 | 121,482 → 121,454 | 2.32 / 11.54 / 8.47 |
| FLAC | 30.000 | 273,236 | 2,408.6 | 0.0803 | 1,691.2 | 121,798 → 122,512 | 2.29 / 26.63 / 4.50 |
| MP3 | 30.066 | 481,532 | 5,144.5 | 0.1711 | 3,008.0 | 121,088 → 117,574 | 2.34 / 19.97 / 4.48 |
| WAV | 30.000 | 661,544 | 959.3 | 0.0320 | 834.3 | 117,829 → 116,315 | 2.69 / 9.93 / 4.53 |

Cancellation response was 127.0 ms on the 360-second MP3 fixture. The MediaSession command was
also exercised through the production service on-device, and both instrumentation tests passed.
No actual playback was observed during analysis; audible continuity remains unverified.

### RMX3939 local M4A smoke check

With the owner's permission, 39 local `.m4a` files in shared `Music` were decoded and analyzed
on RMX3939; all 39 completed and all 39 cache records survived a close/reopen round-trip. This
is a format/stability check only: ground-truth beat annotations were not supplied, no accuracy
or calibration metric can be calculated, and the audio was neither copied from the device nor
committed. The test removes its own cache rows afterwards. Files with other names, extensions,
or locations were not included.

Tempo interpretation accuracy compares the selected interpretation against the independently
provided reference BPM: the closest of prior, half prior and double prior must be within 4% of
reference BPM to classify the reference. Otherwise the track is excluded from that metric.
Unavailable or ambiguous results count as incorrect when a reference interpretation is
classifiable. Per-interpretation denominators are reported; null means there was no eligible
track. Failure rate is the fraction with an unavailable grid, including ambiguity; ambiguous rate
is reported separately. An empty grid is not unavailable. These scores are not calibrated
probabilities. The input is a PCM timeline, not evidence that decoder and Media3 timelines match.
