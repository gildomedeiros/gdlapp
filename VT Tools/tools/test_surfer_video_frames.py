"""Video stepping uses actual presentation timestamps, including irregular spacing."""
from fractions import Fraction
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

from PIL import Image
from surfer_map_app import MapApp
from surfer_video_frames import capture_video_frame, presentation_times, run_ffmpeg
from telemetry_selected_frames import ffmpeg_path


class VideoFrameTests(unittest.TestCase):
    def setUp(self):
        root=Path(__file__).resolve().parent.parent/'exports'
        root.mkdir(exist_ok=True)
        temp=tempfile.TemporaryDirectory(dir=root)
        self.addCleanup(temp.cleanup)
        self.folder=Path(temp.name)

    def test_showinfo_uses_integer_pts_and_timebase(self):
        text='config in time_base: 1/30000, frame_rate: 30000/1001\n'
        text+='n: 0 pts:9547538 pts_time:318.251267\nn: 1 pts:9548539 pts_time:318.284633\n'
        self.assertEqual(presentation_times(text),(Fraction(9547538,30000),Fraction(9548539,30000)))
        self.assertEqual(presentation_times('no video frames'),())

    def test_invalid_requests_do_not_decode(self):
        cases=[dict(step=2),dict(step=True),dict(step=1.0),dict(step=0,videoTime=-1),
               dict(step=1,videoTime=float('nan')),dict(step=1,videoTime=True),
               dict(step=1,variant='telemetry'),dict(step=1,variant='../file')]
        with patch('surfer_video_frames.resolve_video') as resolve:
            for request in cases:
                with self.subTest(request=request),self.assertRaises(ValueError):
                    capture_video_frame(dict(videoPlaybackSeconds=1),request,'video',self.folder,'ffmpeg')
            resolve.assert_not_called()

    def test_api_routes_raw_frames_without_mutating_gps_or_telemetry_image(self):
        app=MapApp(app_dir=self.folder/'app')
        raw=self.folder/'actual-frame-clean.png';raw.write_bytes(b'raw')
        point=dict(point='S1',videoId='99',videoPlaybackSeconds=.04,videoFrameTimestamp='1970-01-01T00:00:00.040+00:00')
        before=dict(point)
        snapshot=dict(id='b'*32,points={'S1':point},settings=dict(video_folder='video',ffmpeg=''),
                      output=self.folder,images={'old-telemetry':self.folder/'old.png'},lock=threading.Lock())
        meta=dict(kind='video',variant='clean',videoTime=.16,filename=raw.name)
        request=dict(point='S1',step=1)
        with patch('surfer_video_frames.capture_video_frame',return_value=(raw,meta)) as capture,patch('telemetry_selected_frames.ffmpeg_path',return_value='ffmpeg'):
            result=app.video_frame(snapshot,request)
        self.assertEqual(result['videoTime'],.16)
        self.assertIn('/maps/'+'b'*32+'/frames/',result['url'])
        self.assertEqual(snapshot['images'][result['url']],raw)
        self.assertIn('old-telemetry',snapshot['images'])
        self.assertEqual(point,before)
        self.assertEqual(capture.call_args.args[1],request)

    def test_real_variable_frame_rate_video_steps_exactly_and_stops_at_ends(self):
        try:executable=ffmpeg_path(os.environ.get('VT_TEST_FFMPEG'))
        except ValueError:self.skipTest('FFmpeg unavailable; set VT_TEST_FFMPEG to run real decoding check.')
        colors=[(255,0,0),(0,255,0),(0,0,255),(255,255,0),(255,0,255),(0,255,255)]
        durations=[.04,.12,.04,.20,.08]
        lines=[]
        for i,color in enumerate(colors):
            name=f'frame-{i}.png';Image.new('RGB',(80,48),color).save(self.folder/name)
            lines.append(f"file '{name}'")
            if i<len(durations):lines.append('duration '+str(durations[i]))
        concat=self.folder/'frames.txt';concat.write_text('\n'.join(lines),encoding='utf-8')
        video=self.folder/'DJI_0099.mp4'
        run_ffmpeg(executable,['-f','concat','-safe','0','-i',str(concat),'-fps_mode','vfr','-c:v','libx264','-bf','0','-pix_fmt','yuv420p','-y',str(video)])
        point=dict(point='S1',videoId='99',videoPlaybackSeconds=.04,videoFrameTimestamp='1970-01-01T00:00:00.040+00:00')
        output=self.folder/'output'
        forward,first=capture_video_frame(point,dict(step=1),self.folder,output,executable)
        self.assertAlmostEqual(first['videoTime'],.16)
        self.assertEqual(first['timestamp'],'1970-01-01T00:00:00.160+00:00')
        with Image.open(forward) as image:
            red,green,blue=image.getpixel((40,24))
            self.assertGreater(blue,220);self.assertLess(red+green,30)
            self.assertEqual(image.size,(80,48))
        _,second=capture_video_frame(point,dict(step=1,videoTime=first['videoTime']),self.folder,output,executable)
        self.assertAlmostEqual(second['videoTime'],.20)
        back,third=capture_video_frame(point,dict(step=-1,videoTime=second['videoTime']),self.folder,output,executable)
        self.assertEqual(back,forward)
        self.assertEqual(third['videoTime'],first['videoTime'])
        with self.assertRaisesRegex(ValueError,'First frame'):
            capture_video_frame(point,dict(step=-1,videoTime=0),self.folder,output,executable)
        with self.assertRaisesRegex(ValueError,'Last frame'):
            capture_video_frame(point,dict(step=1,videoTime=.48),self.folder,output,executable)
        enhanced,export=capture_video_frame(point,dict(step=0,videoTime=.16,variant='enhanced'),self.folder,output,executable)
        self.assertEqual(export['videoTime'],.16)
        self.assertNotEqual(enhanced,forward)
        self.assertEqual(point['videoPlaybackSeconds'],.04)


if __name__=='__main__':unittest.main()
