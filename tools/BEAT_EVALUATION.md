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

`AudioAnalysisScheduler` exposes `requestBeatAnalysis(item, ownerId)` and
`cancelBeatAnalysis(ownerId)`, but no production UI/client currently invokes this API, so users
cannot yet initiate beat analysis. Scheduler tests cover its offline queue behavior. Beat work
shares the existing analysis queue, remains below queued-track analysis and above idle library
scanning, and does not feed playback or AutoMix. `TrackAnalysisCache` stores the result in schema
v6, separate from legacy BPM/Key and periodic-pulse rows. Its beat-grid read distinguishes
missing, invalidated, unavailable, empty, and detected results.

Provide WAV files as mono signed 16-bit little-endian PCM at 11,025 Hz, each at most 60 seconds.
The waveform must be aligned with the supplied reference timestamps; do not silently apply
encoder-delay, fragment-offset, or playback-timeline corrections. Reference files contain one
beat timestamp in seconds per line, relative to the WAV start, sorted and unique. Lines starting
with `#` are comments. BPM is optional and must be independently annotated, not inferred from
the detector. Use separate development and final-evaluation fixtures.

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

The current `spectral-onset-tracker-v2` widens the log-interval tolerance used to connect observed
onsets from 0.28 to 0.36 around each global tempo hypothesis. This can retain moderate timing
variation, but it does not estimate a local tempo curve and has not been validated against
annotated real music. Results from synthetic fixtures must not be presented as real-music accuracy.

`TrackPcmDecoder` uses Android `MediaExtractor` and `MediaCodec`, so it is container-agnostic and
can decode any local audio stream for which the current device exposes a decoder. This is not a
promise that every extension or codec works on every Android device: successful decoding depends
on the actual audio MIME type, device codec implementation, and source constraints. The
on-device instrumentation harness in `app/src/androidTest` reports advertised audio decoder MIME
types, probes each supplied file's actual audio MIME type, and attempts the production decoder,
analyzer, cache, and scheduler cancellation path. Unsupported or failed fixtures are reported
individually rather than aborting the remaining format checks.

Put any local audio fixtures in the app-private `files/beat-benchmark` directory; extensions are
only hints and every regular file there is probed. The generated `benchmark-results.json` uses
anonymous fixture IDs and records MIME type, format hint, size, duration, and analysis/cache
timings, not file names or paths. It reports source duration separately from decoded analysis
window because production decoding is windowed (currently at most 60 seconds); the whole-track
ratio must not be read as if the whole track was analyzed. Synthetic tone/click fixtures exercise
format handling and runtime only, not musical accuracy.

Build with `.\gradlew.bat :app:assembleDebugAndroidTest`, install the debug app and test APK, copy
fixtures into the app-private `files/beat-benchmark` directory, then run
`adb shell am instrument -w -e class org.akanework.gramophone.BeatAnalysisDeviceBenchmarkTest org.plug.player.debug.test/androidx.test.runner.AndroidJUnitRunner`.
The generated `benchmark-results.json` is local device output and is not included in the repository.

Tempo interpretation accuracy compares the selected interpretation against the independently
provided reference BPM: the closest of prior, half prior and double prior must be within 4% of
reference BPM to classify the reference. Otherwise the track is excluded from that metric.
Unavailable or ambiguous results count as incorrect when a reference interpretation is
classifiable. Per-interpretation denominators are reported; null means there was no eligible
track. Failure rate is the fraction with an unavailable grid, including ambiguity; ambiguous rate
is reported separately. An empty grid is not unavailable. These scores are not calibrated
probabilities. The input is a PCM timeline, not evidence that decoder and Media3 timelines match.
