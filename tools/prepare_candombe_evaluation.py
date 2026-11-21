#!/usr/bin/env python3
"""Prepare local first-minute Candombe PCM fixtures with a session-separated split."""

import argparse
import hashlib
import json
import math
import statistics
import zipfile
from pathlib import Path

import soundfile


def verify_md5(path, expected):
    digest = hashlib.md5()  # Dataset publisher's archive-integrity checksum, not a security primitive.
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    if digest.hexdigest() != expected:
        raise ValueError(f"Dataset archive checksum does not match: {path.name}")


def prepare(audio_zip, annotation_zip, output, split):
    verify_md5(audio_zip, "ccd7f437024807b1a52c0818aa0b7f06")
    verify_md5(annotation_zip, "f78aff60aa413cb4960c0c77cc31c243")
    output.mkdir(parents=True, exist_ok=True)
    if (output / f"candombe-{split}.json").exists():
        raise FileExistsError(f"Manifest already exists in {output}")
    tracks = []
    with zipfile.ZipFile(audio_zip) as audio, zipfile.ZipFile(annotation_zip) as annotations:
        for name in sorted(n for n in audio.namelist() if n.endswith(".flac")):
            track_id = Path(name).stem
            is_holdout = track_id.startswith("zavala.muniz.2014_")
            if not (is_holdout or track_id.startswith(("csic.1995_", "proyecto.1992_"))):
                raise ValueError(f"Unknown recording session: {track_id}")
            if is_holdout != (split == "holdout"):
                continue
            if (output / f"{track_id}.wav").exists() or (output / f"{track_id}.txt").exists():
                raise FileExistsError(f"Output already exists for {track_id}")
            annotation = f"candombe_annotations/without_bar_number/{track_id}.beats"
            rows = annotations.read(annotation).decode("utf-8").splitlines()
            times = [float(row.split()[0]) for row in rows if row.strip()]
            if (not times or any(not math.isfinite(time) or time < 0 for time in times)
                    or not all(a < b for a, b in zip(times, times[1:]))):
                raise ValueError(f"Invalid reference timestamps: {track_id}")
            with audio.open(name) as stream, soundfile.SoundFile(stream) as source:
                if (source.samplerate, source.channels, source.subtype) != (44100, 2, "PCM_16"):
                    raise ValueError(f"Unexpected audio format: {track_id}")
                frame_count = min(len(source), 60 * source.samplerate)
                samples = source.read(frames=frame_count, dtype="int16")
                soundfile.write(output / f"{track_id}.wav", samples, 44100, subtype="PCM_16")
            (output / f"{track_id}.txt").write_text(
                "".join(f"{time:.9f}\n" for time in times), encoding="utf-8")
            window_end = frame_count / 44100
            within = [time for time in times if time <= window_end]
            if len(within) < 2:
                raise ValueError(f"Insufficient reference beats in window: {track_id}")
            bpm = 60 / statistics.median(b - a for a, b in zip(within, within[1:]))
            tracks.append({"id": track_id, "audio": f"{track_id}.wav",
                           "beats": f"{track_id}.txt", "reference_bpm": bpm})
    expected = 21 if split == "training" else 14
    if len(tracks) != expected:
        raise ValueError(f"Expected {expected} tracks, found {len(tracks)}")
    manifest = {"dataset": f"Candombe {split}: first 60 seconds, session-separated",
                "license": "CC BY 4.0, Nunes et al. 2015",
                "tracks": tracks}
    path = output / f"candombe-{split}.json"
    path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(f"Prepared {len(tracks)} {split} recordings in {path}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("audio_zip", type=Path)
    parser.add_argument("annotation_zip", type=Path)
    parser.add_argument("output_directory", type=Path)
    parser.add_argument("--split", choices=("training", "holdout"), required=True)
    args = parser.parse_args()
    prepare(args.audio_zip, args.annotation_zip, args.output_directory, args.split)
