from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import cv2
import numpy as np
import surfer_wave_model as model


class WaveModelTests(unittest.TestCase):
    def test_letterbox_round_trip_preserves_aspect_and_original_coordinates(self):
        class Echo:
            def setInput(self, blob): self.blob=blob
            def forward(self): return self.blob.copy()
        net=Echo()
        for shape in ((540,960),(960,540)):
            frame=np.zeros((*shape,3),dtype=np.uint8)
            frame[shape[0]//3:shape[0]//2,shape[1]//4:shape[1]//2]=255
            with patch.object(model,'MODEL_PATH',Path(__file__)),patch.object(model,'load_model',return_value=net): probability=model.breaking_probabilities(frame)
            self.assertEqual(net.blob.shape,(1,1,512,512))
            self.assertEqual(net.blob.dtype,np.float32)
            self.assertEqual(probability.shape,shape)
            expected=frame[:,:,0]>0;actual=probability>.5
            self.assertGreater((actual&expected).sum()/(actual|expected).sum(),.97)

    def test_missing_corrupt_or_invalid_model_fails_cleanly(self):
        root=Path(__file__).resolve().parent.parent/'exports';root.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=root) as folder:
            missing=Path(folder)/'missing.onnx'
            with patch.object(model,'MODEL_PATH',missing):
                self.assertEqual(model.model_signature()[-1],'missing')
                with self.assertRaises(model.WaveModelUnavailable):model.breaking_probabilities(np.zeros((80,100,3),dtype=np.uint8))
            missing.write_bytes(b'not a model')
            with self.assertRaisesRegex(model.WaveModelUnavailable,'integrity'):
                model.load_model(str(missing),missing.stat().st_size,missing.stat().st_mtime_ns)
        class Bad:
            def setInput(self, blob):pass
            def forward(self):return np.full((1,1,512,512),np.nan,dtype=np.float32)
        with patch.object(model,'MODEL_PATH',Path(__file__)),patch.object(model,'load_model',return_value=Bad()):
            with self.assertRaisesRegex(model.WaveModelUnavailable,'invalid probabilities'):
                model.breaking_probabilities(np.zeros((80,100,3),dtype=np.uint8))

    def test_opencv_failure_is_reported_as_optional_model_unavailable(self):
        with patch.object(model,'MODEL_PATH',Path(__file__)),patch.object(model,'load_model',side_effect=cv2.error('Failed to load')):
            with self.assertRaises(model.WaveModelUnavailable):model.breaking_probabilities(np.zeros((80,100,3),dtype=np.uint8))

    @unittest.skipUnless(model.MODEL_PATH.is_file(),'Bundled model not installed')
    def test_bundled_model_runs_on_cpu_and_returns_bounded_probabilities(self):
        frame=np.full((288,512,3),(130,115,40),dtype=np.uint8)
        output=model.breaking_probabilities(frame)
        self.assertEqual(output.shape,(288,512))
        self.assertTrue(np.all(np.isfinite(output)))
        self.assertGreaterEqual(float(output.min()),0.)
        self.assertLessEqual(float(output.max()),1.)


if __name__=='__main__':unittest.main()
