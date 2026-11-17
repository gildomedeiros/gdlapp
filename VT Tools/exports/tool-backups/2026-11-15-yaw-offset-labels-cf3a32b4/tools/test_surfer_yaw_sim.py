"""Evidence, causality, selection isolation and frame integration regressions."""
import json
import math
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch
from surfer_yaw_sim import DEFAULTS, parameters, replay_samples, card_lines, run_for_points, load_observations, csv_bytes


def sample(bearing,time,**extra):
    angle=math.radians(bearing);radius=30/6371000*180/math.pi
    return dict(sourceFile='test',sourceLine=int(time*1000)+1,timeMs=time*1000,timestamp=str(time),
                sessionKey='1',session=1,accepted=True,active=True,sequence=int(time*1000),duplicate=False,
                latitude=radius*math.cos(angle),longitude=radius*math.sin(angle),
                droneLatitude=0.,droneLongitude=0.,droneAgeMs=0.,droneHeading=0.,headingAgeMs=0.,
                loggedBaseline=None,riding=False,rotationProfile='normal',**extra)


class EvidenceTests(unittest.TestCase):
    def run_bearings(self,bearings,**params):
        return replay_samples([sample(b,i*.5) for i,b in enumerate(bearings)],params)

    def test_north_wrap_and_signed_targets(self):
        rows=self.run_bearings([357,359,1,3,5,7,9,11,13])
        self.assertAlmostEqual(rows[3]['netChange'],6,places=5)
        self.assertEqual(rows[-1]['appliedOffset'],5)
        self.assertAlmostEqual(rows[-1]['simTarget'],18,places=5)
        self.assertIn('[S00]',card_lines(rows[-1])[0])
        left=self.run_bearings([3,1,359,357,355,353,351,349,347])
        self.assertEqual(left[-1]['appliedOffset'],-5)
        self.assertIn('[00S]',card_lines(left[-1])[0])

    def test_jitter_and_single_jump_never_establish_direction(self):
        for data in ([0,1,-1,1,-1,1,-1]*3,[0,0,20,20,20,20,20,20]):
            self.assertTrue(all(r['desiredOffset']==0 for r in self.run_bearings(data)))

    def test_full_window_and_two_supporting_changes_required(self):
        rows=replay_samples([sample(b,t) for b,t in [(0,0),(5,.2),(10,.4),(15,1.5)]])
        self.assertEqual(rows[2]['desiredOffset'],0)
        self.assertEqual(rows[3]['desiredOffset'],5)
        irregular=replay_samples([sample(b,t) for b,t in [(0,0),(2,.6),(4,1.2),(6,1.8)]])
        self.assertAlmostEqual(irregular[-1]['windowDuration'],1.8)

    def test_pause_quiet_and_smooth_reversal(self):
        rise=list(range(0,20,2))
        paused=self.run_bearings(rise+[18]*4)
        self.assertEqual(paused[-1]['desiredOffset'],5)
        quiet=self.run_bearings(rise+[18]*18)
        self.assertEqual(quiet[-1]['appliedOffset'],0)
        self.assertEqual(sum(r['quietReset'] for r in quiet),1)
        reversal=self.run_bearings(rise+list(range(16,-14,-2)))
        self.assertEqual(sum(r['directionSwitch'] for r in reversal),1)
        self.assertEqual(reversal[-1]['appliedOffset'],-5)
        self.assertTrue(all(abs(b['appliedOffset']-a['appliedOffset'])<=1.00001 for a,b in zip(reversal,reversal[1:])))
        self.assertTrue(any(r['desiredOffset']==-5 and r['appliedOffset']>0 for r in reversal))

    def test_gap_invalid_and_session_reset(self):
        samples=[sample(b,i*.5) for i,b in enumerate(range(0,20,2))]
        missing=sample(20,5);missing['droneAgeMs']=10000;samples.append(missing)
        samples.append(sample(22,10));samples.append(sample(24,10.5))
        reset=sample(30,11);reset['sessionKey']='2';samples.append(reset)
        rows=replay_samples(samples)
        self.assertFalse(rows[-4]['valid'])
        self.assertEqual(rows[-3]['appliedOffset'],rows[-5]['appliedOffset'])
        self.assertEqual(rows[-3]['observationCount'],1)
        self.assertEqual(rows[-1]['appliedOffset'],0)
        gap=replay_samples(samples[:10]+[sample(30,10)])
        self.assertEqual(gap[-1]['observationCount'],1)
        self.assertIn('GPS gap',gap[-1]['reason'])

    def test_stationary_surfer_moving_drone(self):
        samples=[]
        for i,b in enumerate(range(0,20,2)):
            row=sample(b,i*.5)
            row['droneLatitude']=-row['latitude'];row['droneLongitude']=-row['longitude']
            row['latitude']=row['longitude']=0.;samples.append(row)
        self.assertEqual(replay_samples(samples)[-1]['desiredOffset'],5)

    def test_duplicate_does_not_add_observations_and_heading_does_not_drive_trend(self):
        samples=[sample(10,i*.5) for i in range(10)]
        for i,row in enumerate(samples):row['droneHeading']=i*30
        samples[3]['duplicate']=True
        rows=replay_samples(samples)
        self.assertFalse(rows[3]['valid'])
        self.assertTrue(all(r['desiredOffset']==0 for r in rows))
        self.assertIn('[0S0]',rows[-1]['cardLines'][0])
        samples[-1]['headingAgeMs']=10000
        last=replay_samples(samples)[-1]
        self.assertTrue(last['valid']);self.assertIsNone(last['simError'])

    def test_bad_parameters(self):
        for params in ({'magnitude':float('nan')},{'minObservations':3.5},{'x':1},{'window':0},{'slewRate':True}):
            with self.assertRaises(ValueError):parameters(params)


class LogReplayTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.folder=Path(self.temp.name);self.source=self.folder/'flight.jsonl'
        records=[dict(event='session_start',epochMs=0),
                 dict(event='vt40_configuration',epochMs=1,nextSession=1,effectiveSettingsJson=json.dumps(dict(yawTargetOffsetDegrees=-7,positioningAngleDegrees=45)))]
        self.points={}
        for i,b in enumerate(range(0,20,2)):
            t=1000+i*500;s=sample(b,t/1000)
            records.extend([dict(event='aiming_cycle',epochMs=t-10,session=1,aircraftLatitude=0,aircraftLongitude=0,aircraftAgeMs=0,rotationCurve='riding'),
                            dict(event='orientation_cycle',epochMs=t-9,session=1,aircraftYawDeg=2,aircraftAttitudeAgeMs=0,aircraftAttitudeFresh=True),
                            dict(event='movement_cycle',epochMs=t-8,session=1,riding=True),
                            dict(event='lora_packet',epochMs=t,sequence=i,accepted=True,latitude=s['latitude'],longitude=s['longitude'])])
            self.points[f'S{i}']=dict(point=f'S{i}',sourceFile=str(self.source),sourceLine=len(records),sequence=i,latitude=s['latitude'],longitude=s['longitude'],receivedTimestamp='1970-01-01T00:00:01Z',videoFrameTimestamp='1970-01-01T00:00:01Z')
        # Future orientation must not influence the preceding receipt.
        records.append(dict(event='orientation_cycle',epochMs=5501,session=1,aircraftYawDeg=250,aircraftAttitudeAgeMs=0,aircraftAttitudeFresh=True))
        self.source.write_text('\n'.join(json.dumps(r) for r in records),encoding='utf-8')

    def test_full_log_selection_invariance_config_and_causality(self):
        all_result=run_for_points(self.points,{},'+00:00')
        one=run_for_points({'S9':self.points['S9']},{},'+00:00')
        self.assertEqual(all_result['points']['S9'],one['points']['S9'])
        self.assertEqual(len(one['rows']),10)
        row=one['points']['S9']
        self.assertEqual(row['appliedOffset'],5)
        self.assertEqual(row['baselineOffset'],-7)
        self.assertEqual(row['droneHeading'],2)
        self.assertAlmostEqual(row['simError'],21,places=5)
        self.assertTrue(row['riding']);self.assertEqual(row['rotationProfile'],'riding')
        self.assertIn(b'parameter_magnitude',csv_bytes(one))
        self.assertIn(b'S9',csv_bytes(one))

    def test_snapshot_capture_uses_server_result_and_preserves_original(self):
        from surfer_map_app import MapApp
        app=MapApp(app_dir=self.folder/'app')
        snapshot=dict(id='a'*32,points=self.points,lock=threading.Lock(),output=self.folder,images={},
                      settings=dict(utc_offset='+00:00',video_folder='',ffmpeg='',gap_red_ms=1000))
        result=app.yaw_simulation(snapshot,dict(parameters={}))
        self.assertNotIn('yawSimulation',self.points['S9'])
        self.assertTrue((self.folder/'yaw-simulation'/(result['token']+'.csv')).is_file())
        with patch('surfer_map_capture.capture_variant',return_value=self.folder/'image.png') as capture,patch('telemetry_selected_frames.ffmpeg_path',return_value='ffmpeg'):
            response=app.capture(snapshot,dict(point='S9',yawSimulation=result['token']))
            copied=capture.call_args.args[0]
            self.assertEqual(copied['yawSimulation']['appliedOffset'],5)
            self.assertIn(result['token'],response['url'])
            from surfer_gps_map import position_detail_lines
            self.assertIn('offsetMagnitude[SIM]: +5.00° [S00]',position_detail_lines(copied))
            app.capture(snapshot,dict(point='S9',variant='clean',yawSimulation=result['token']))
            self.assertNotIn('yawSimulation',capture.call_args.args[0])
        with self.assertRaises(ValueError):app.capture(snapshot,dict(point='S9',yawSimulation='bogus'))
        self.assertNotIn('yawSimulation',self.points['S9'])


if __name__=='__main__':unittest.main()
