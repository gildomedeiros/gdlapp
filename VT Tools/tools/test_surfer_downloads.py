"""Clean and enhanced exports retain original image content and distinct URLs."""
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import patch

from PIL import Image
from surfer_map_capture import capture_variant
from surfer_map_app import MapApp


class FrameDownloadTests(unittest.TestCase):
    def setUp(self):
        root=Path(__file__).resolve().parent.parent/'exports'
        root.mkdir(exist_ok=True)
        temp=tempfile.TemporaryDirectory(dir=root)
        self.addCleanup(temp.cleanup)
        self.folder=Path(temp.name)
        self.raw=self.folder/'raw.png'
        image=Image.new('RGB',(120,80))
        image.putdata([(x*2,y*3,(x+y)%256) for y in range(80) for x in range(120)])
        image.save(self.raw)
        self.original=self.raw.read_bytes()

    def test_clean_is_exact_original_and_enhanced_preserves_resolution_and_source(self):
        with patch('surfer_map_capture.capture_frame',return_value=self.raw) as extract:
            clean=capture_variant({},'video',self.folder,'ffmpeg',variant='clean')
            enhanced=capture_variant({},'video',self.folder,'ffmpeg',variant='enhanced')
        self.assertTrue(all(call.kwargs['annotate'] is False for call in extract.call_args_list))
        self.assertEqual(clean.read_bytes(),self.original)
        self.assertEqual(self.raw.read_bytes(),self.original)
        self.assertNotEqual(clean,enhanced)
        with Image.open(enhanced) as image, Image.open(clean) as original:
            self.assertEqual(image.size,original.size)
            self.assertEqual(image.mode,'RGB')
            self.assertNotEqual(image.tobytes(),original.tobytes())

    def test_unknown_variant_cannot_extract_or_write_a_frame(self):
        with patch('surfer_map_capture.capture_frame') as extract:
            for variant in ('../file',None,True,['clean']):
                with self.subTest(variant=variant),self.assertRaises(ValueError):
                    capture_variant({},'video',self.folder,'ffmpeg',variant=variant)
            extract.assert_not_called()

    def test_variant_routes_keep_telemetry_image_and_snapshot_separate(self):
        app=MapApp(app_dir=self.folder/'app')
        telemetry=self.folder/'telemetry.png'
        Image.new('RGB',(120,80),'white').save(telemetry)
        original_telemetry=telemetry.read_bytes()
        snapshot=dict(id='a'*32,points={'S1':dict(point='S1',videoFrameTimestamp='frame-time')},
                      settings=dict(video_folder='video',ffmpeg='',gap_red_ms=1000),
                      output=self.folder,images={},lock=threading.Lock())
        def extract(*args,**kwargs):
            return self.raw if kwargs.get('annotate') is False else telemetry
        with patch('surfer_map_capture.capture_frame',side_effect=extract),patch('telemetry_selected_frames.ffmpeg_path',return_value='ffmpeg'):
            results=[app.capture(snapshot,dict(point='S1',variant=variant)) for variant in ('telemetry','clean','enhanced')]
        self.assertEqual(len(set(r['url'] for r in results)),3)
        self.assertEqual([r['variant'] for r in results],['telemetry','clean','enhanced'])
        self.assertTrue(results[0]['url'].endswith('/S1.png'))
        self.assertTrue(results[1]['url'].endswith('/S1-clean.png'))
        self.assertTrue(results[2]['url'].endswith('/S1-enhanced.png'))
        self.assertTrue(all('/maps/'+'a'*32+'/' in r['url'] for r in results))
        self.assertEqual(telemetry.read_bytes(),original_telemetry)
        self.assertEqual(snapshot['images'][results[1]['url']].read_bytes(),self.original)


if __name__=='__main__':
    unittest.main()
