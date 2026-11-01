"""All-rides map sampling, interval boundaries, and CLI routing."""
import argparse
from contextlib import redirect_stderr, redirect_stdout
from datetime import datetime, timezone
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from surfer_gps_map import main
from surfer_map_filters import map_filter_data
from surfer_pitch_samples import annotate_ride_windows, run_ride_samples, select_ride_samples


def point(t, source='flight', rides=None, **extra):
    return dict(time_ms=t, sourceFile=source, sourceLine=t,
                waveRideStartTimesMs=[10000] if rides is None else rides, **extra)


class RideSamplesTests(unittest.TestCase):
    def test_map_membership_keeps_overlap_endpoints_and_original_ids(self):
        points=[point(t,point=label,receivedTimestamp='1970-01-01T00:00:10+00:00')
                for t,label in [(8999,'S40'),(9000,'S41'),(10000,'S42'),(11000,'S43'),(12001,'S44')]]
        points.append(point(10000,source='another-flight',point='S45',
                            receivedTimestamp='1970-01-01T00:00:10+00:00'))
        # Windows overlap, but the same clock time in another log is not a match.
        annotate_ride_windows(points,[(10000,'flight'),(11000,'flight')],1,1,timezone.utc)
        self.assertEqual([[w['id'] for w in p['mapRideWindows']] for p in points],
                         [[],['R1'],['R1','R2'],['R1','R2'],[],[]])
        data=map_filter_data(list(reversed(points)))
        self.assertEqual(data['rides'][0]['points'],['S41','S42','S43'])
        self.assertEqual(data['rides'][1]['points'],['S42','S43'])
        self.assertEqual(next(p for p in data['points'] if p['id']=='S42')['offsets'],
                         {'R1':0,'R2':-1})
        self.assertEqual(data['rides'][0]['timestamp'],'1970-01-01T00:00:10.000+00:00')
        self.assertEqual([p['point'] for p in points],['S40','S41','S42','S43','S44','S45'])

    def test_map_without_ride_metadata_keeps_all_original_positions(self):
        data=map_filter_data([point(1000,point='S0',receivedTimestamp='1970-01-01T00:00:01+00:00')])
        self.assertEqual(data['rides'],[])
        self.assertEqual(data['points'][0]['id'],'S0')
        self.assertEqual(data['points'][0]['offsets'],{})
        self.assertIsNone(data['points'][0]['distance'])

    def test_timestamp_map_retains_real_drone_direction_without_cached_field(self):
        points=[point(t,point=f'S{i}',receivedTimestamp='1970-01-01T00:00:01+00:00',
                      droneLatitude=-27.95+i*.00001,droneLongitude=153.43) for i,t in enumerate([1000,2000])]
        data=map_filter_data(points)
        self.assertAlmostEqual(data['points'][0]['droneBearing'],0)
        self.assertIsNone(data['points'][1]['droneBearing'])
        points[0]['droneNextBearingDeg']=270
        self.assertEqual(map_filter_data(points)['points'][0]['droneBearing'],270)

    def test_last_packet_per_window_interval_and_same_source(self):
        points=[point(t) for t in [8999,9000,9400,9999,10000,10999,11000,11001]]
        points.append(point(10000,source='other-log',rides=[]))
        selected, eligible, candidates=select_ride_samples(list(reversed(points)),1,1,1)
        self.assertEqual([p['time_ms'] for p in selected],[9999,10999,11000])
        self.assertEqual((eligible,candidates),(6,3))

    def test_no_cap_no_required_pitch_or_drone_and_zero_keeps_every_packet(self):
        points=[point(10000+i*500) for i in range(120)]
        selected, _, _=select_ride_samples(points,0,60,0)
        self.assertEqual(len(selected),120)
        selected, _, _=select_ride_samples(points,0,60,1)
        self.assertEqual(len(selected),60)

    def test_overlap_deduplicates_but_keeps_separate_log_packets(self):
        points=[point(10000,rides=[10000,10500]),
                point(10000,source='second-flight',rides=[10000,10500])]
        selected, _, candidates=select_ride_samples(points,1,1,1)
        self.assertEqual(len(selected),2)
        self.assertEqual(candidates,2)

    def test_optional_and_filters_apply_before_sampling_and_cap(self):
        points=[point(9100,gimbalTiltDeg=-35,droneSurferDistanceMetres=25),
                point(9200,gimbalTiltDeg=-35,droneSurferDistanceMetres=35),
                point(9300,gimbalTiltDeg=-30,droneSurferDistanceMetres=25),
                point(10100,gimbalTiltDeg=-34,droneSurferDistanceMetres=30)]
        selected, eligible, candidates=select_ride_samples(points,1,1,1,1,[-36,-34],[20,30])
        self.assertEqual([p['time_ms'] for p in selected],[9100])
        self.assertEqual((eligible,candidates),(2,2))

    def test_cli_seeds_app_with_report_style_ride_options(self):
        argv=['surfer_gps_map.py','--folder','logs','--all-rides',
              '--before','5','--after','20','--granularity','1','--serve']
        with patch('sys.argv',argv), patch('surfer_gps_map.load_packets') as load, \
             patch('surfer_map_app.serve_app') as serve:
            main()
        args=serve.call_args.args[0]
        self.assertEqual((args.sample_before,args.sample_after,args.sample_interval),(5,20,1))
        self.assertIsNone(args.max_samples)
        self.assertIsNone(args.pitch_range)
        load.assert_not_called()

    def test_ride_limit_is_global_chronological_and_preserves_real_packet_context(self):
        packets=[]
        # File order differs from ride order, with a repeated start and overlapping
        # selected/unselected windows. No packet in the first ride matches pitch.
        for source, starts in [('flight-a',[20000,20000,30000]),('flight-b',[10000,20500])]:
            for t in [9000,10000,19000,20000,20500,21000,21500,30000]:
                if source=='flight-b' and t==9000:
                    t=8999
                packets.append(dict(time_ms=t, sourceFile=source, sourceLine=t,
                    waveRideStartTimesMs=starts, latitude=-27.95+t*.000000001, longitude=153.43,
                    receivedTimestamp=datetime.fromtimestamp(t/1000,timezone.utc).isoformat(),
                    sequence=t, accepted=True, gimbalTiltDeg=-35 if t>=19000 else -20))
        packets.sort(key=lambda p:(p['time_ms'],p['sourceFile']))
        root=Path(__file__).resolve().parent.parent/'exports'
        root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=root) as folder:
            args=argparse.Namespace(number_of_rides=2, pitch_range=[-36,-34], distance_range=None,
                sample_before=1, sample_after=1, sample_interval=0, max_samples=None,
                output=folder, video_folder='unused')
            with patch('surfer_gps_map.add_video_playback'), redirect_stdout(io.StringIO()):
                selected,out,description=run_ride_samples(packets,{},args,timezone.utc)
            self.assertEqual([(p['sourceFile'],p['time_ms']) for p in selected],
                             [('flight-a',t) for t in [19000,20000,20500,21000]])
            self.assertEqual([p['point'] for p in selected],['S4','S6','S8','S10'])
            self.assertEqual(selected[0]['sincePreviousReceivedMs'],9000)
            self.assertTrue(all(p['waveRideStartTimesMs']==[20000] for p in selected))
            self.assertTrue(all([w['id'] for w in p['mapRideWindows']]==['R2'] for p in selected))
            self.assertEqual(map_filter_data(selected)['rides'][0]['number'],2)
            original=next(p for p in packets if p['sourceFile']=='flight-a')
            self.assertEqual(original['waveRideStartTimesMs'],[20000,20000,30000])
            summary=json.loads((out/'ride-sample-summary.json').read_text())
            self.assertEqual(summary['numberOfRides'],2)
            self.assertEqual(summary['totalRideCount'],4)
            self.assertEqual(summary['selectedRideCount'],2)
            self.assertEqual(summary['rideStartsBySource'],{'flight-a':[20000],'flight-b':[10000]})
            self.assertIn('First 2 of 4 logged wave rides',description)
            # Asking for more than exist uses all available ride windows.
            args.number_of_rides=10
            with patch('surfer_gps_map.add_video_playback'), redirect_stdout(io.StringIO()):
                selected,out,description=run_ride_samples(packets,{},args,timezone.utc)
            self.assertIn('First 4 of 4 logged wave rides (requested 10)',description)
            self.assertIn(('flight-b',21500),[(p['sourceFile'],p['time_ms']) for p in selected])
            self.assertIn(('flight-a',30000),[(p['sourceFile'],p['time_ms']) for p in selected])

    def test_cli_number_of_rides_seeds_app_and_accepts_both_spellings(self):
        for option in ['--number_of_rides','--number-of-rides']:
            argv=['surfer_gps_map.py','--folder','logs',option,'3',
                  '--distance-range','20','30','--serve']
            with self.subTest(option=option), patch('sys.argv',argv), \
                 patch('surfer_gps_map.load_packets') as load, patch('surfer_map_app.serve_app') as serve:
                main()
            args=serve.call_args.args[0]
            self.assertEqual((args.number_of_rides,args.sample_interval),(3,1))
            self.assertIsNone(args.max_samples)
            load.assert_not_called()

    def test_cli_rejects_invalid_ride_count_and_conflicting_modes(self):
        for extra in [['0'],['-1'],['1.5'],['3','--all-rides'],['3','--get-fov-samples']]:
            argv=['surfer_gps_map.py','--folder','logs','--number_of_rides',*extra]
            with self.subTest(extra=extra), patch('sys.argv',argv), redirect_stderr(io.StringIO()), \
                 patch('surfer_gps_map.load_packets') as load, self.assertRaises(SystemExit) as error:
                main()
            self.assertEqual(error.exception.code,2)
            load.assert_not_called()


if __name__=='__main__':
    unittest.main()
