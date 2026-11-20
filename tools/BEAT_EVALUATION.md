# Offline beat evaluation

This tool evaluates the existing Kotlin analyzer, not a separate implementation. It runs only
when explicitly invoked, never in playback. Supply audio and annotations you have rights to use;
no audio or annotations are included in this repository. A useful candidate is the
[Salsa Dataset](https://doi.org/10.5281/zenodo.13120822), published by Gómez-Marín et al.
under CC BY 4.0, with 124 salsa tracks, expert annotations, and audio fragments. Its authors
request citation of [the dataset paper](https://doi.org/10.5334/tismir.183); full audio is
available separately on request. Verify permissions for the particular recordings and check
fragment offsets against annotations before evaluation; the paper describes its annotations in
milliseconds, so they must also be converted to seconds for this tool. No fragment-to-annotation
offset has been established here. This dataset covers only salsa and cannot establish broad
genre accuracy.

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

Tempo interpretation accuracy compares the selected interpretation against the independently
provided reference BPM: the closest of prior, half prior and double prior must be within 4% of
reference BPM to classify the reference. Otherwise the track is excluded from that metric.
Unavailable or ambiguous results count as incorrect when a reference interpretation is
classifiable. Per-interpretation denominators are reported; null means there was no eligible
track. Failure rate is the fraction with an unavailable grid, including ambiguity; ambiguous rate
is reported separately. An empty grid is not unavailable. These scores are not calibrated
probabilities. The input is a PCM timeline, not evidence that decoder and Media3 timelines match.
