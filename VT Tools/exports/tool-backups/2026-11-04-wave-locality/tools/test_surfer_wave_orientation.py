import json
import math
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import numpy as np

from surfer_wave_orientation import camera_axes, camera_pose, ground_points, detect_crest, analyze_wave, wave_results, VERSION


class WaveTests(unittest.TestCase):
    def setUp(self):
        root=Path(__file__).resolve().parent.parent/'exports';root.mkdir(exist_ok=True)
        self.temp=tempfile.TemporaryDirectory(dir=root);self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name)
        self.source=self.root/'log.jsonl'
        self.point=dict(point='S0',sourceFile=str(self.source),session=1,sequence=1,
                        receivedTimestamp='2026-11-04T16:14:03.829+10:00',videoFrameTimestamp='2026-11-04T16:14:03.837+10:00',
                        videoFrameOffsetMs=8,videoPlaybackSeconds=202.3,videoId='643',latitude=0.,longitude=0.,droneLatitude=-.00012,droneLongitude=0.)
        self.row=dict(event='orientation_cycle',timestamp='2026-11-04T16:14:03.800+10:00',session=1,
                      aircraftYawDeg=350,gimbalYawRelativeToAircraftDeg=20,gimbalYawDeg=123,
                      gimbalPitchDeg=-30,gimbalRollDeg=0,heightAboveTakeoffM=9)
        for key in ('aircraftAttitude','gimbalAttitude','gimbalRelativeYaw','height'):
            self.row[key+'Fresh']=True;self.row[key+'AgeMs']=100
        self.write([self.row])

    def write(self,rows):self.source.write_text('\n'.join(json.dumps(r) for r in rows),encoding='utf-8')

    def test_pose_uses_relative_yaw_and_no_future_or_other_session(self):
        future=dict(self.row,timestamp='2026-11-04T16:14:03.900+10:00',aircraftYawDeg=100)
        wrong=dict(self.row,session=2,timestamp='2026-11-04T16:14:03.830+10:00',aircraftYawDeg=80)
        self.write([self.row,future,wrong])
        self.assertEqual(camera_pose(self.point)['yaw'],10)
        self.write([dict(self.row,gimbalRelativeYawFresh=False)])
        with self.assertRaisesRegex(ValueError,'stale'):camera_pose(self.point)
        self.write([dict(self.row,timestamp='2026-11-04T16:14:01.800+10:00')])
        with self.assertRaisesRegex(ValueError,'stale'):camera_pose(self.point)

    def test_perspective_and_roll_recover_known_ground_direction(self):
        # Project a known world segment into a tilted/rolled camera and back.
        for yaw in (0,90,127,350):
            pose=dict(yaw=yaw,pitch=-40,roll=7,height=9)
            forward,right,lower=camera_axes(pose)
            for angle in (0,30,90,140):
                centre=forward*20;centre[2]=-9
                d=np.array([math.sin(math.radians(angle)),math.cos(math.radians(angle)),0.])*3
                vectors=np.array([centre-d,centre+d]);f=960/(2*math.tan(math.radians(74)/2))
                pixels=np.column_stack((480+f*(vectors@right)/(vectors@forward),270+f*(vectors@lower)/(vectors@forward)))
                projected=ground_points(pixels,960,540,74,pose)
                a,b=projected;actual=math.degrees(math.atan2(*(b-a)))%180
                self.assertLess(abs((actual-angle+90)%180-90),1e-7)

    def test_blank_water_is_rejected_and_clear_foam_boundary_is_detected(self):
        import cv2
        frame=np.full((540,960,3),(130,115,40),dtype=np.uint8)
        path=self.root/'frame.png';cv2.imwrite(str(path),frame)
        pose=dict(yaw=0,pitch=-40,roll=0,height=9)
        with self.assertRaisesRegex(ValueError,'No clear'):detect_crest(path,self.point,pose,74)
        cv2.fillPoly(frame,[np.array([[200,420],[760,150],[800,220],[250,490]])],(245,245,245))
        cv2.imwrite(str(path),frame)
        result=detect_crest(path,self.point,pose,74)
        self.assertTrue(0<=result['bearingDegrees']<180)
        self.assertGreaterEqual(result['supportSegments'],2)
        self.assertEqual(result['imageWidth'],960)

    def test_cache_tracks_source_frame_and_calibration_and_restores_across_ids(self):
        video=self.root/'DJI_0643.MP4';video.write_bytes(b'video')
        result=dict(bearingDegrees=35.,imageWidth=960,imageHeight=540,imageLine=[[10,20],[50,30]])
        with patch('surfer_map_capture.resolve_video',return_value=video),patch('surfer_map_capture.capture_frame',return_value=self.root/'frame.png') as capture,patch('surfer_wave_orientation.detect_crest',return_value=result):
            first=analyze_wave(self.point,{},self.root,self.root,self.root,'ffmpeg')
            second=analyze_wave(dict(self.point,point='S99'),{},self.root,self.root,self.root,'ffmpeg')
            self.assertEqual(capture.call_count,1)
            self.assertEqual(first['key'],second['key']);self.assertEqual(second['point'],'S99')
            analyze_wave(self.point,dict(horizontalFov=80),self.root,self.root,self.root,'ffmpeg')
            self.assertEqual(capture.call_count,2)
            video.write_bytes(b'updated video')
            analyze_wave(self.point,dict(horizontalFov=80),self.root,self.root,self.root,'ffmpeg')
            self.assertEqual(capture.call_count,3)
        restored=wave_results([dict(self.point,point='S123')],self.root)
        self.assertEqual(len(restored),1);self.assertEqual(restored[0]['point'],'S123')
        self.assertEqual(restored[0]['version'],VERSION)
        self.assertEqual(wave_results([dict(self.point,sourceFile='other flight')],self.root),[])
        outside=wave_results([dict(self.point,receivedTimestamp='later')],self.root)
        self.assertEqual(len(outside),1);self.assertIsNone(outside[0]['point'])

    def test_invalid_input_does_not_decode_video(self):
        with patch('surfer_map_capture.capture_frame') as capture:
            for request in (dict(horizontalFov=True),dict(horizontalFov=0),dict(horizontalFov=float('nan'))):
                with self.assertRaises(ValueError):analyze_wave(self.point,request,self.root,self.root,self.root,'ffmpeg')
            with self.assertRaisesRegex(ValueError,'no mapped'):analyze_wave(dict(self.point,videoFrameTimestamp=None),{},self.root,self.root,self.root,'ffmpeg')
            capture.assert_not_called()


if __name__=='__main__':unittest.main()
