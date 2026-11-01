"""Pitch/distance filtering must preserve actual GPS context and ride windows."""
import argparse
from contextlib import redirect_stderr, redirect_stdout
from datetime import datetime, timezone
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from surfer_gps_map import drone_next_bearing, main, write_outputs
from surfer_pitch_samples import run_pitch_samples, select_pitch_samples


def point(t, pitch=-35, distance=25, source='flight', rides=None):
    return dict(time_ms=t, gimbalTiltDeg=pitch, droneSurferDistanceMetres=distance,
                sourceFile=source, sourceLine=t, point=f'S{t}',
                waveRideStartTimesMs=[10000] if rides is None else rides)


class PitchSamplesTests(unittest.TestCase):
    def test_and_filter_inclusive_edges_and_chronological_limit(self):
        points = [point(12000, -34, 30), point(9000, -36, 20), point(10000),
                  point(9100, -33, 25), point(9200, -35, 31),
                  point(9300, -30, 10), point(9400, None),
                  point(9500, float('nan')), point(9600, distance=float('nan'))]
        # Reverse range arguments are accepted; either mismatch must exclude.
        selected, eligible, candidates = select_pitch_samples(points, [-34, -36], 1, 2, 0, 2, [30, 20])
        self.assertEqual([p['time_ms'] for p in selected], [9000, 10000])
        self.assertEqual((eligible, candidates), (3, 3))

    def test_windows_stay_in_source_log_and_overlap_does_not_duplicate(self):
        points = [point(8999), point(9000), point(12000), point(12001),
                  point(10000, source='no-ride', rides=[]),
                  point(11000, source='second-flight', rides=[10000, 11000])]
        selected, _, _ = select_pitch_samples(points, [-36, -34], 1, 2, 0, 50)
        self.assertEqual([p['time_ms'] for p in selected], [9000, 11000, 12000])

    def test_spacing_keeps_significant_tilt_changes_and_each_source(self):
        points = [point(9000, -36), point(9500, -35), point(9600, -33),
                  point(9700, -34, source='second-flight')]
        selected, _, _ = select_pitch_samples(points, [-36, -33], 1, 2, 5, 50, [20, 30])
        self.assertEqual([p['time_ms'] for p in selected], [9000, 9600, 9700])

    def test_sample_context_and_interactive_output(self):
        packets, drones = [], {'flight': []}
        for i in range(7):
            t = 1000 + i*500
            packets.append(dict(time_ms=t, receivedTimestamp=datetime.fromtimestamp(t/1000,timezone.utc).isoformat(timespec='milliseconds'),
                sequence=i, latitude=-27.95+i*.00001, longitude=153.43, accepted=True,
                sourceFile='flight', sourceLine=i, waveRideStartTimesMs=[2500],
                gimbalTiltDeg=-35, gimbalReportedTimestamp='1970-01-01T00:00:01+00:00'))
            # Drone changes direction on each receipt. Filtered-neighbour
            # headings must not replace the real next-receipt heading.
            drones['flight'].append(dict(time_ms=t-20, latitude=-27.9501,
                longitude=153.43+(i%2)*.00001, timestamp='1970-01-01T00:00:01+00:00',
                sourceLine=i, age=0, fix=t-20))
        root=Path(__file__).resolve().parent.parent/'exports'
        root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=root) as folder:
            args=argparse.Namespace(pitch_range=[-36,-34], distance_range=[0,100],
                sample_before=1, sample_after=1, sample_interval=1, max_samples=2,
                output=folder, video_folder='unused')
            with patch('surfer_gps_map.add_video_playback') as video, redirect_stdout(io.StringIO()):
                selected,out,description=run_pitch_samples(packets,drones,args,timezone.utc)
            self.assertEqual([p['point'] for p in selected], ['S1','S3'])
            self.assertEqual(video.call_args.args[0], selected)
            self.assertEqual(selected[1]['sincePreviousReceivedMs'], 500)
            self.assertEqual(selected[1]['previousDronePoint'], 'D2')
            self.assertEqual(selected[1]['surferNextBearingDeg'], 0)
            self.assertAlmostEqual(drone_next_bearing(selected, 1), 270, places=3)
            write_outputs(selected,selected[0]['time_ms'],out,timezone.utc,selection_info=description)
            page=(out/'surfer-gps.html').read_text(encoding='utf-8')
            self.assertIn('AND horizontal GPS distance 0 to 100 m', page)
            self.assertIn('Ctrl + left-click', page)
            self.assertIn('id="pair-options"', page)
            self.assertIn('id="ride-filter"', page)
            self.assertIn('id="position-filter"', page)
            self.assertNotIn('__MAP_FILTER_SCRIPT__', page)
            self.assertNotIn('MAP_FILTER_DATA', page)
            self.assertIn('"offsets": {"R1": -1.0}', page)
            self.assertNotIn('S0 = last received packet', page)
            summary=json.loads((out/'pitch-sample-summary.json').read_text())
            self.assertEqual(summary['selectedCount'], 2)
            self.assertEqual(summary['distanceRangeMetres'], [0,100])

    def test_no_matching_samples(self):
        self.assertEqual(select_pitch_samples([point(10000)], [-31,-29], 1, 2, 0, 10, [20,30]), ([],0,0))

    def test_cli_seeds_app_with_pitch_and_distance_without_at(self):
        argv=['surfer_gps_map.py','--folder','logs','--pitch-range','-36','-34',
              '--distance-range','20','30','--max-samples','7','--granularity','1','--serve']
        with patch('sys.argv',argv), patch('surfer_gps_map.load_packets') as load, \
             patch('surfer_map_app.serve_app') as serve:
            main()
        args=serve.call_args.args[0]
        self.assertEqual((args.distance_range,args.max_samples,args.sample_interval),([20,30],7,1))
        load.assert_not_called()

    def test_cli_rejects_invalid_limits_before_reading_logs(self):
        cases=[['--max-samples','0'],['--distance-range','-1','20'],
               ['--distance-range','0','inf'],['--get-fov-samples']]
        for extra in cases:
            argv=['surfer_gps_map.py','--folder','logs','--pitch-range','-36','-34',*extra]
            with self.subTest(extra=extra), patch('sys.argv',argv), redirect_stderr(io.StringIO()), \
                 patch('surfer_gps_map.load_packets') as load, self.assertRaises(SystemExit) as error:
                main()
            self.assertEqual(error.exception.code,2)
            load.assert_not_called()


if __name__=='__main__':
    unittest.main()
