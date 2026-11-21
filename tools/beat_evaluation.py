#!/usr/bin/env python3
"""Evaluate the production Kotlin tracker on explicitly supplied annotated PCM WAV fixtures."""

import argparse
import csv
import json
import math
import os
from pathlib import Path
import statistics
import subprocess
import tempfile

RATE = 11025
TOLERANCE_US = 70000
TEMPO_RELATIVE_TOLERANCE = 0.04


def match_beats(detected, reference, tolerance=TOLERANCE_US):
    """Earliest compatible one-to-one match; unmatched and duplicate events count as errors."""
    i = j = 0
    errors = []
    while i < len(detected) and j < len(reference):
        delta = detected[i] - reference[j]
        if delta < -tolerance:
            i += 1
        elif delta > tolerance:
            j += 1
        else:
            errors.append(abs(delta))
            i += 1
            j += 1
    return errors


def metrics(detected_count, reference_count, errors):
    matched = len(errors)
    precision = matched / detected_count if detected_count else None
    recall = matched / reference_count if reference_count else None
    f_measure = (2 * matched / (detected_count + reference_count)
                 if detected_count + reference_count else None)
    return {"detected": detected_count, "reference": reference_count, "matched": matched,
            "precision": precision, "recall": recall, "f_measure": f_measure,
            "median_absolute_timing_error_us": statistics.median(errors) if errors else None}


def reference_interpretation(prior, reference_bpm):
    if prior is None or reference_bpm is None:
        return None
    candidates = [(name, abs(prior * factor / reference_bpm - 1))
                  for name, factor in (("PRIMARY", 1), ("HALF_TIME", 0.5), ("DOUBLE_TIME", 2))]
    name, error = min(candidates, key=lambda x: x[1])
    return name if error <= TEMPO_RELATIVE_TOLERANCE else None


def read_reference(track, duration_us):
    reference_path = Path(track["beats"])
    reference_format = track.get("reference_format", "seconds")
    if reference_format == "seconds":
        timestamps = [round(float(line.strip()) * 1_000_000)
                     for line in reference_path.read_text(encoding="utf-8").splitlines()
                     if line.strip() and not line.lstrip().startswith("#")]
    elif reference_format == "rwc-r":
        with reference_path.open(encoding="utf-8", newline="") as source:
            reader = csv.DictReader(source, delimiter=";")
            if not reader.fieldnames or not {"t", "beat"}.issubset(reader.fieldnames):
                raise ValueError(f"{track['id']}: RWC-R CSV must have t and beat columns")
            timestamps = [round(float(row["t"]) * 1_000_000) for row in reader]
    else:
        raise ValueError(f"{track['id']}: unsupported reference format {reference_format!r}")

    if (timestamps != sorted(timestamps) or len(timestamps) != len(set(timestamps)) or
            any(timestamp < 0 for timestamp in timestamps)):
        raise ValueError(f"{track['id']}: reference timestamps must be nonnegative, unique and sorted")
    return [timestamp for timestamp in timestamps if timestamp <= duration_us]


def evaluate(manifest_path, output_path):
    manifest_path = Path(manifest_path).resolve()
    data = json.loads(manifest_path.read_text(encoding="utf-8"))
    tracks = data["tracks"]
    if not tracks:
        raise ValueError("Manifest must contain at least one track")
    root = manifest_path.parent
    ids = set()
    for track in tracks:
        name = track["id"]
        if not isinstance(name, str) or not name or name in ids:
            raise ValueError("Track IDs must be unique nonempty strings")
        ids.add(name)
        for field in ("audio", "beats"):
            track[field] = str((root / track[field]).resolve(strict=True))
            if "\n" in track[field] or "\r" in track[field]:
                raise ValueError("Paths must not contain newlines")
        bpm = track.get("reference_bpm")
        if bpm is not None and (not isinstance(bpm, (int, float)) or
                                not math.isfinite(bpm) or bpm <= 0):
            raise ValueError(f"{name}: invalid reference BPM")
    repository = Path(__file__).resolve().parent.parent
    with tempfile.TemporaryDirectory(prefix="gramophone-beat-eval-") as directory:
        directory = Path(directory)
        input_path = directory / "input.txt"
        result_path = directory / "result.tsv"
        input_path.write_text("\n".join(t["audio"] for t in tracks) + "\n", encoding="utf-8")
        env = os.environ.copy()
        env.update(BEAT_EVAL_INPUT=str(input_path), BEAT_EVAL_OUTPUT=str(result_path))
        wrapper = repository / ("gradlew.bat" if os.name == "nt" else "gradlew")
        subprocess.run([str(wrapper), ":app:testDebugUnitTest", "--tests",
                        "org.akanework.gramophone.BeatEvaluationRunnerTest",
                        "--rerun", "--console", "plain"], cwd=repository, env=env, check=True)
        rows = [line.split("\t") for line in result_path.read_text(encoding="utf-8").splitlines()]
    if len(rows) != len(tracks):
        raise ValueError("Evaluation runner returned an incomplete result")
    results = []
    all_errors = []
    totals = [0, 0]
    for index, (track, row) in enumerate(zip(tracks, rows)):
        if len(row) not in (9, 10, 11) or int(row[0]) != index:
            raise ValueError("Evaluation runner returned a malformed or unordered result")
        duration_us = int(row[1]) * 1000000 // RATE
        reference = read_reference(track, duration_us)
        unavailable = row[7] == "unavailable"
        detected = [] if unavailable or not row[7] else [int(t) for t in row[7].split(",")]
        errors = match_beats(detected, reference)
        all_errors.extend(errors)
        totals[0] += len(detected)
        totals[1] += len(reference)
        prior = float(row[3]) if row[3] else None
        selected = row[4] or None
        expected = reference_interpretation(prior, track.get("reference_bpm"))
        hypotheses = []
        if len(row) >= 10 and row[9]:
            for hypothesis in row[9].split(";"):
                interpretation, bpm, support = hypothesis.split(",")
                hypotheses.append({"interpretation": interpretation,
                                   "bpm": float(bpm), "support": float(support)})
        results.append({"id": track["id"], "duration_us": duration_us,
                        "processing_time_ms": float(row[2]), "bpm_prior": prior,
                        "reference_format": track.get("reference_format", "seconds"),
                        "selected": selected, "selected_bpm": float(row[6]) if row[6] else None,
                        "tempo_hypotheses": hypotheses,
                        "reference_bpm": track.get("reference_bpm"), "expected_interpretation": expected,
                        "interpretation_correct": selected == expected if expected else None,
                        "ambiguous": row[5] == "true", "unavailable": unavailable,
                        "algorithm_version": row[8],
                        "configuration_version": row[10] if len(row) == 11 else None,
                        **metrics(len(detected), len(reference), errors)})
    def accuracy(subset):
        return sum(r["interpretation_correct"] for r in subset) / len(subset) if subset else None
    classified = [r for r in results if r["expected_interpretation"]]
    aggregate = {**metrics(*totals, all_errors),
                 "tempo_interpretation_accuracy": accuracy(classified),
                 "interpretation_accuracy_by_reference": {
                     name: accuracy([r for r in classified if r["expected_interpretation"] == name])
                     for name in ("PRIMARY", "HALF_TIME", "DOUBLE_TIME")},
                 "interpretation_denominators": {
                     name: sum(r["expected_interpretation"] == name for r in results)
                     for name in ("PRIMARY", "HALF_TIME", "DOUBLE_TIME")},
                 "ambiguous_tempo_rate": sum(r["ambiguous"] for r in results) / len(results),
                 "failure_rate": sum(r["unavailable"] for r in results) / len(results)}
    report = {"configuration": {"sample_rate_hz": RATE, "tolerance_us": TOLERANCE_US,
                                 "tempo_relative_tolerance": TEMPO_RELATIVE_TOLERANCE,
                                 "matching": "sorted earliest compatible one-to-one",
                                 "algorithm_version": results[0]["algorithm_version"],
                                 "analysis_configuration_version":
                                     results[0]["configuration_version"],
                                 "dataset": data.get("dataset"), "license": data.get("license")},
              "tracks": results, "aggregate_micro": aggregate}
    Path(output_path).write_text(json.dumps(report, indent=2, allow_nan=False) + "\n", encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", help="JSON fixture manifest")
    parser.add_argument("output", help="JSON result path")
    args = parser.parse_args()
    evaluate(args.manifest, args.output)
