"""Lazy, local-only U-Net inference. No package installation or downloads on W."""
from functools import lru_cache
import hashlib
from pathlib import Path
import threading

MODEL_PATH = Path(__file__).with_name('models')/'wave_crest_unet.onnx'
MODEL_SHA256 = '297b7ba6058fb10b284b8b60752d98e7247f09d94093b2acfb0d71a5e89df087'
MODEL_ID = 'wave-by-wave-unet-'+MODEL_SHA256[:12]
MODEL_LOCK = threading.Lock()


class WaveModelUnavailable(RuntimeError):
    pass


@lru_cache(maxsize=2)
def load_model(path, size, modified):
    import cv2
    if hashlib.sha256(Path(path).read_bytes()).hexdigest() != MODEL_SHA256:
        raise WaveModelUnavailable('Bundled wave model failed its integrity check')
    net = cv2.dnn.readNetFromONNX(path)
    net.setPreferableBackend(cv2.dnn.DNN_BACKEND_OPENCV)
    return net


def model_signature():
    """Invalidate fallback cache if the model is later installed/replaced."""
    try:
        stat = MODEL_PATH.stat()
        return [MODEL_ID, stat.st_size, stat.st_mtime_ns]
    except OSError:
        return [MODEL_ID, 'missing']


def breaking_probabilities(frame):
    """Return probabilities at original size, preserving aspect and pixel layout."""
    import cv2
    import numpy as np
    h, w = frame.shape[:2]
    factor = 512/max(w, h)
    width, height = max(1, round(w*factor)), max(1, round(h*factor))
    gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
    gray = cv2.resize(gray, (width, height), interpolation=cv2.INTER_AREA if factor<1 else cv2.INTER_LINEAR)
    top, left = (512-height)//2, (512-width)//2
    padded = cv2.copyMakeBorder(gray, top, 512-height-top, left, 512-width-left, cv2.BORDER_REFLECT_101)
    blob = padded.astype(np.float32)[None, None]/255.
    try:
        stat = MODEL_PATH.stat()
        # OpenCV's setInput/forward are stateful. Serialize the shared instance.
        with MODEL_LOCK:
            net = load_model(str(MODEL_PATH), stat.st_size, stat.st_mtime_ns)
            net.setInput(blob)
            output = net.forward()
    except (OSError, cv2.error, RuntimeError) as error:
        raise WaveModelUnavailable('Wave model could not run: '+str(error).splitlines()[0]) from error
    if output.shape != (1, 1, 512, 512) or not np.all(np.isfinite(output)):
        raise WaveModelUnavailable('Wave model returned invalid probabilities')
    restored = output[0, 0, top:top+height, left:left+width]
    return cv2.resize(restored, (w, h), interpolation=cv2.INTER_LINEAR).clip(0., 1.)
