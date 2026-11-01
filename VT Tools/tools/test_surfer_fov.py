"""Run with python -m unittest discover -s tools -p test_surfer_fov.py."""
import json
import math
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from surfer_fov import FovEstimator, estimate_vertical_fov


class FovTests(unittest.TestCase):
    def test_recovers_known_projection_above_and_below_centre(self):
        for distance in (10, 30):
            delta = math.atan2(8.5, distance) + math.radians(-30)
            y = (math.tan(delta) / math.tan(math.radians(60 / 2)) + 1) / 2
            self.assertAlmostEqual(estimate_vertical_fov(y, 8.5, distance, -30), 60)

    def test_rejects_indeterminate_or_inconsistent_geometry(self):
        for values in ((.5, 8.5, 20, -30), (.9, 8.5, 30, -30), (.2, None, 30, -30), (.2, 8.5, 0, -30)):
            with self.assertRaises(ValueError):
                estimate_vertical_fov(*values)

    def test_records_single_frame_provenance_and_rejects_stale_tilt(self):
        point = dict(point='S1', sourceFile='flight.jsonl', receivedTimestamp='2026-10-30T14:03:21.000+10:00',
                     videoFrameTimestamp='2026-10-30T14:03:21.020+10:00', videoFrameOffsetMs=20,
                     gimbalReportedTimestamp='2026-10-30T14:03:20.950+10:00', gimbalTiltDeg=-30)
        detection = dict(yFraction=.25, confidence=.8)
        estimator = FovEstimator('unused.pt')
        # Workspace-local temporary files also work inside the Windows sandbox.
        root = Path(__file__).resolve().parent.parent / 'exports'
        root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=root) as temp, patch.object(estimator, 'detect', return_value=detection), patch('surfer_gps_map.pair_distance', return_value=30):
            result = estimator.calculate('raw.png', point, 8.5, temp)
            saved = list((Path(temp) / 'fov-estimates').glob('*.json'))
            self.assertEqual(len(saved), 1)
            self.assertEqual(json.loads(saved[0].read_text())['frameTimestamp'], point['videoFrameTimestamp'])
            self.assertEqual(result['height'], 8.5)
            point['gimbalReportedTimestamp'] = '2026-10-30T14:03:19.000+10:00'
            with self.assertRaises(ValueError):
                estimator.calculate('raw.png', point, 8.5, temp)


if __name__ == '__main__':
    unittest.main()
