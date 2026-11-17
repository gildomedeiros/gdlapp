"""Persistent selection, log-cache isolation, cancellation and capture routing."""
from contextlib import ExitStack, redirect_stdout, redirect_stderr
from datetime import datetime, timezone
import io
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

from surfer_gps_map import main
from surfer_map_app import MapApp, normalize_request


class MapAppTests(unittest.TestCase):
    def setUp(self):
        self.stack=ExitStack()
        self.addCleanup(self.stack.close)
        root=Path(__file__).resolve().parent.parent/'exports'
        root.mkdir(exist_ok=True)
        self.folder=Path(self.stack.enter_context(tempfile.TemporaryDirectory(dir=root)))
        source=self.folder/'flight.jsonl';source.write_text('',encoding='utf-8')
        self.app=MapApp(app_dir=self.folder/'app')
        self.settings=dict(self.app.config['settings'],folder=str(self.folder),video_folder='',utc_offset='+00:00')
        self.packets=[]
        for i,t in enumerate(range(9000,32000,1000)):
            self.packets.append(dict(time_ms=t,sourceFile=str(source),sourceLine=i+1,
                receivedTimestamp=datetime.fromtimestamp(t/1000,timezone.utc).isoformat(),
                latitude=-27.95,longitude=153.43+i*.000001,sequence=i,accepted=True,
                gimbalTiltDeg=-35 if t<21000 else -30,waveRideStartTimesMs=[10000,20000,30000]))
        drones={str(source):[dict(time_ms=0,sourceLine=0,latitude=-27.9502,longitude=153.43,
                 timestamp='1970-01-01T00:00:00+00:00',age=0,fix=0)]}
        self.load=self.stack.enter_context(patch('surfer_gps_map.load_packets',return_value=(self.packets,drones)))
        self.stack.enter_context(patch('surfer_gps_map.load_boundary',return_value=None))
        self.stack.enter_context(redirect_stdout(io.StringIO()))

    def request(self,mode,**selection):
        return dict(settings=self.settings,mode=mode,selection=dict(self.app.config['presets'][mode],**selection))

    def run_request(self,request):
        job=self.app.start(request)
        self.app.worker.join(5)
        self.assertFalse(self.app.worker.is_alive(),'Selection did not finish')
        return self.app.public_job(job['id'])

    def test_three_modes_share_logs_and_keep_independent_packet_ids(self):
        ride=self.run_request(self.request('rides',scope='first',number_of_rides=1,before=1,after=1,interval=0))
        self.assertEqual(ride['status'],'done',ride)
        first=self.app.snapshot(ride['id'])
        self.assertEqual(list(first['points']),['S0','S1','S2'])
        timestamp=self.run_request(self.request('timestamp',at='00:00:20',next=2))
        self.assertEqual(timestamp['status'],'done',timestamp)
        second=self.app.snapshot(timestamp['id'])
        self.assertEqual(second['points']['S0']['time_ms'],20000)
        self.assertEqual(first['points']['S0']['time_ms'],9000)
        pitch=self.run_request(self.request('pitch',pitch_min=-36,pitch_max=-34,distance_min=20,distance_max=30,
                                           before=1,after=1,interval=0,max_samples=4))
        self.assertEqual(pitch['status'],'done',pitch)
        self.assertEqual(pitch['result']['count'],4)
        self.assertEqual(self.load.call_count,1)
        self.assertNotIn('point',self.packets[0])
        self.assertNotEqual(ride['result']['mapUrl'],timestamp['result']['mapUrl'])
        self.assertTrue((second['output']/'surfer-gps.html').is_file())
        old_ids=[]
        def capture(point,video,output,ffmpeg,*args,**kwargs):
            old_ids.append(point['time_ms']);dest=output/'capture.png';dest.write_bytes(b'png');return dest
        with patch('surfer_map_capture.capture_frame',side_effect=capture),patch('telemetry_selected_frames.ffmpeg_path',return_value='ffmpeg'):
            first['points']['S0']['videoFrameTimestamp']='old-frame'
            second['points']['S0']['videoFrameTimestamp']='new-frame'
            old=self.app.capture(first,{'point':'S0'})
            new=self.app.capture(second,{'point':'S0'})
        self.assertEqual(old_ids,[9000,20000])
        self.assertIn(ride['id'],old['url']);self.assertIn(timestamp['id'],new['url'])

    def test_specific_rides_preserve_numbering_and_empty_selection_keeps_map(self):
        job=self.run_request(self.request('rides',scope='specific',ride_numbers='3, 1',before=0,after=0,interval=0))
        self.assertEqual(job['status'],'done',job)
        points=list(self.app.snapshot(job['id'])['points'].values())
        self.assertEqual([p['time_ms'] for p in points],[10000,30000])
        self.assertEqual([p['mapRideWindows'][0]['number'] for p in points],[1,3])
        empty=self.run_request(self.request('pitch',pitch_min=-90,pitch_max=-80))
        self.assertEqual(empty['status'],'empty')
        self.assertEqual(self.app.current['id'],job['id'])
        invalid=self.run_request(self.request('rides',scope='specific',ride_numbers='4'))
        self.assertEqual(invalid['status'],'error')
        self.assertIn('Only 3 rides',invalid['message'])
        self.assertEqual(self.app.current['id'],job['id'])

    def test_wave_detection_uses_snapshot_point_and_shared_output_cache(self):
        job=self.run_request(self.request('timestamp',at='00:00:10',next=0))
        snapshot=self.app.snapshot(job['id'])
        with patch('surfer_wave_orientation.analyze_wave',return_value={'bearingDegrees':45}) as analyze,patch('telemetry_selected_frames.ffmpeg_path',return_value='ffmpeg'):
            result=self.app.wave(snapshot,dict(point='S0',horizontalFov=74))
            self.assertEqual(result['bearingDegrees'],45)
            args=analyze.call_args.args
            self.assertEqual(args[0]['time_ms'],10000)
            self.assertEqual(args[3],snapshot['output']);self.assertEqual(args[4],snapshot['cache_output'])
            with self.assertRaisesRegex(ValueError,'Unknown surfer'):self.app.wave(snapshot,dict(point='S999'))
            self.assertEqual(analyze.call_count,1)
        with patch('surfer_wave_orientation.ignore_wave',return_value={'key':'frame-key','ignored':True}) as ignore,patch('telemetry_selected_frames.ffmpeg_path') as ffmpeg:
            self.assertTrue(self.app.wave(snapshot,dict(point='S0',action='ignore'))['ignored'])
            self.assertEqual(ignore.call_args.args[0]['time_ms'],10000)
            self.assertEqual(ignore.call_args.args[1],snapshot['cache_output'])
            ffmpeg.assert_not_called()
    def test_cancel_does_not_publish_and_disallows_concurrent_selection(self):
        baseline=self.run_request(self.request('timestamp',at='00:00:10',next=0))
        entered=threading.Event();release=threading.Event()
        from surfer_pitch_samples import run_log_samples
        def delayed(*args,**kwargs):
            entered.set();release.wait(3);return run_log_samples(*args,**kwargs)
        with patch('surfer_pitch_samples.run_log_samples',side_effect=delayed):
            job=self.app.start(self.request('rides'))
            self.assertTrue(entered.wait(2))
            try:
                with self.assertRaisesRegex(ValueError,'still running'):self.app.start(self.request('timestamp'))
                self.app.cancel(job['id'])
            finally:release.set()
            self.app.worker.join(5)
        self.assertEqual(self.app.public_job(job['id'])['status'],'cancelled')
        self.assertEqual(self.app.current['id'],baseline['id'])

    def test_log_file_change_invalidates_cache_and_settings_survive_restart(self):
        self.run_request(self.request('timestamp',at='00:00:10',next=0))
        (self.folder/'flight.jsonl').write_text('changed',encoding='utf-8')
        self.run_request(self.request('timestamp',at='00:00:20',next=1))
        self.assertEqual(self.load.call_count,2)
        reloaded=MapApp(app_dir=self.folder/'app')
        self.assertEqual(reloaded.config['settings']['folder'],str(self.folder))
        self.assertEqual(reloaded.config['mode'],'timestamp')
        self.assertEqual(reloaded.config['presets']['timestamp']['at'],'00:00:20')

    def test_invalid_requests_are_rejected_before_starting_work(self):
        cases=[self.request('timestamp',at=''),self.request('rides',before=-1),
               self.request('rides',scope='specific',ride_numbers='1.5'),
               self.request('pitch',pitch_min=-20,pitch_max=-40),
               self.request('pitch',distance_min=float('nan')),
               self.request('framing',sample_bands='0 20 20'),
               self.request('timestamp',next=True)]
        for request in cases:
            with self.subTest(request=request),self.assertRaises(ValueError):normalize_request(request)
        self.load.assert_not_called()

    def test_start_server_with_no_required_selection_and_offline_requires_folder(self):
        for argv in [['surfer_gps_map.py'],['surfer_gps_map.py','--serve']]:
            with patch('sys.argv',argv),patch('surfer_map_app.serve_app') as serve:
                main()
                serve.assert_called_once()
                self.assertIsNone(serve.call_args.args[0].folder)
        with patch('sys.argv',['surfer_gps_map.py','--at','00:00:10']),redirect_stderr(io.StringIO()),self.assertRaises(SystemExit):main()


if __name__=='__main__':unittest.main()
