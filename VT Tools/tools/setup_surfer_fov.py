"""Download the official Ultralytics segmentation model used for board detection.

First install the runtime with: python -m pip install ultralytics
Then run: python tools/setup_surfer_fov.py
"""
from pathlib import Path
import urllib.request


def main():
    destination = Path(__file__).parent / 'models' / 'yolo11n-seg.pt'
    destination.parent.mkdir(exist_ok=True)
    if destination.is_file():
        print('Model already available:', destination)
        return
    url = 'https://github.com/ultralytics/assets/releases/download/v8.3.0/yolo11n-seg.pt'
    temporary = destination.with_suffix('.download')
    print('Downloading official surfboard-capable segmentation model...', flush=True)
    try:
        urllib.request.urlretrieve(url, temporary)
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)
    print('Model saved:', destination)


if __name__ == '__main__':
    main()
