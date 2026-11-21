import unittest
from pathlib import Path
from tempfile import TemporaryDirectory

from beat_evaluation import match_beats, metrics, read_reference, reference_interpretation


class BeatEvaluationTest(unittest.TestCase):
    def test_one_to_one_matching_includes_boundary_and_counts_duplicates(self):
        errors = match_beats([0, 100000, 100000, 570000], [70000, 100000, 500000])
        self.assertEqual([70000, 0, 70000], errors)
        result = metrics(4, 3, errors)
        self.assertEqual(0.75, result["precision"])
        self.assertEqual(1.0, result["recall"])
        self.assertEqual(70000, result["median_absolute_timing_error_us"])

    def test_empty_inputs_have_explicit_undefined_metrics(self):
        self.assertIsNone(metrics(0, 0, [])["f_measure"])
        self.assertEqual(0, metrics(0, 2, [])["recall"])
        self.assertIsNone(metrics(0, 2, [])["precision"])

    def test_interpretations_and_out_of_tolerance(self):
        self.assertEqual("PRIMARY", reference_interpretation(120, 120))
        self.assertEqual("HALF_TIME", reference_interpretation(120, 60))
        self.assertEqual("DOUBLE_TIME", reference_interpretation(120, 240))
        self.assertIsNone(reference_interpretation(120, 150))
        self.assertIsNone(reference_interpretation(None, 120))

    def test_rwc_r_csv_uses_seconds_and_crops_to_audio_window(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "reference.csv"
            path.write_text("t;beat\n0.150;1\n0.650;2\n60.150;1\n", encoding="utf-8")
            track = {"id": "RWC_R001", "beats": str(path), "reference_format": "rwc-r"}

            self.assertEqual([150_000, 650_000], read_reference(track, 60_000_000))


if __name__ == "__main__":
    unittest.main()
