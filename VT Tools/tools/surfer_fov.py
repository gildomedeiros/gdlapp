"""Single-frame vertical FOV estimation; CV supplies only board image position."""
import hashlib
import json
import math
from pathlib import Path


def estimate_vertical_fov(y_fraction, height, distance, pitch):
    values = (y_fraction, height, distance, pitch)
    if not all(isinstance(v, (float, int)) and not isinstance(v, bool) and math.isfinite(v) for v in values):
        raise ValueError('Finite image position, height, GPS distance and tilt are required.')
    if not 0 < y_fraction < 1 or height <= 0 or distance <= 0 or not -90 < pitch < 0:
        raise ValueError('Height/distance must be positive and camera tilt must be between -90° and 0°.')
    normalized_y = 2 * y_fraction - 1
    delta = math.atan2(height, distance) + math.radians(pitch)
    if abs(normalized_y) < .05 or abs(delta) < math.radians(.5):
        raise ValueError('Board too close to the optical centre: single-frame FOV is indeterminate.')
    tangent = math.tan(delta) / normalized_y
    if tangent <= 0:
        raise ValueError('Detected board position conflicts with logged height/distance/tilt; no valid FOV.')
    fov = math.degrees(2 * math.atan(tangent))
    if not 5 <= fov <= 150:
        raise ValueError('Implied FOV is implausible; check detection, water height and telemetry.')
    return fov


def frame_zone(y_fraction):
    if not isinstance(y_fraction,(int,float)) or not math.isfinite(y_fraction) or not 0<=y_fraction<=1:
        raise ValueError('Invalid image position')
    return 'TOP' if y_fraction<1/3 else 'MIDDLE' if y_fraction<2/3 else 'BOTTOM'


class FovEstimator:
    def __init__(self, model_path):
        self.model_path = Path(model_path)
        self.model = None

    def predict(self, image):
        if not self.model_path.is_file():
            raise ValueError(f'FOV detector model missing: {self.model_path}. Run tools/setup_surfer_fov.py first.')
        if self.model is None:
            try:
                from ultralytics import YOLO
            except ImportError as error:
                raise ValueError('FOV detection needs ultralytics. Run: python -m pip install ultralytics') from error
            self.model = YOLO(str(self.model_path))
        result = self.model.predict(str(image), imgsz=1280, conf=.35, device='cpu', verbose=False)[0]
        return result

    def detect(self, image):
        result=self.predict(image)
        boards = []
        for i, box in enumerate(result.boxes):
            if result.names[int(box.cls.item())] != 'surfboard':
                continue
            if result.masks is None:
                continue
            polygon = result.masks.xy[i]
            # Polygon centroid, preserving full-frame coordinates.
            x, y = polygon[:, 0], polygon[:, 1]
            import numpy as np
            cross = x * np.roll(y, -1) - np.roll(x, -1) * y
            area = cross.sum()
            if abs(area) < 1:
                continue
            cx = float(((x + np.roll(x, -1)) * cross).sum() / (3 * area))
            cy = float(((y + np.roll(y, -1)) * cross).sum() / (3 * area))
            boards.append(dict(x=cx, y=cy, confidence=float(box.conf.item())))
        if not boards:
            raise ValueError('No surfboard detected confidently. FOV remains unavailable.')
        if len(boards) != 1:
            raise ValueError('Multiple surfboards detected; cannot identify the GPS surfer automatically.')
        h, w = result.orig_shape
        board = boards[0]
        # Side-view geometry assumes negligible lateral displacement in camera view.
        if abs(board['x'] / w - .5) > .15:
            raise ValueError('Board too far off-centre horizontally for the side-view geometry assumption.')
        return dict(board, imageWidth=w, imageHeight=h, yFraction=board['y'] / h,
                    method='surfboard segmentation polygon centroid')

    def classify_person(self, image, point, output):
        result=self.predict(image)
        people=[box for box in result.boxes if result.names[int(box.cls.item())]=='person']
        if not people:
            raise ValueError('No person detected confidently in this frame.')
        if len(people)>1:
            raise ValueError('Multiple people detected; cannot automatically identify the GPS surfer.')
        box=people[0]
        x1,y1,x2,y2=map(float,box.xyxy[0].tolist())
        h,w=result.orig_shape
        y_fraction=(y1+y2)/(2*h)
        zone=frame_zone(y_fraction)
        record=dict(point=point['point'],zone=zone,yFraction=y_fraction,
                    detection=dict(confidence=float(box.conf.item()),box=[x1,y1,x2,y2],imageWidth=w,imageHeight=h),
                    frameTimestamp=point.get('videoFrameTimestamp'),gpsTimestamp=point['receivedTimestamp'],
                    sourceFile=point.get('sourceFile'),method='person bounding-box centre; equal vertical thirds')
        key=hashlib.sha256((str(record['sourceFile'])+str(record['frameTimestamp'])).encode()).hexdigest()[:20]
        folder=Path(output)/'person-positions';folder.mkdir(parents=True,exist_ok=True)
        (folder/(key+'.json')).write_text(json.dumps(record,indent=2),encoding='utf-8')
        return record

    def calculate(self, image, point, height, output):
        from surfer_gps_map import pair_distance
        from datetime import datetime
        frame_time = point.get('videoFrameTimestamp')
        orientation_time = point.get('gimbalReportedTimestamp')
        if not frame_time or not orientation_time:
            raise ValueError('Missing frame or gimbal timestamp.')
        lag = (datetime.fromisoformat(frame_time) - datetime.fromisoformat(orientation_time)).total_seconds()
        if not 0 <= lag <= .5:
            raise ValueError('Gimbal sample is not within 500 ms before the original frame timestamp.')
        if abs(point.get('videoFrameOffsetMs', float('inf'))) > 100:
            raise ValueError('Nearest video frame is more than 100 ms from the GPS packet.')
        detection = self.detect(image)
        distance, pitch = pair_distance(point), point.get('gimbalTiltDeg')
        fov = estimate_vertical_fov(detection['yFraction'], height, distance, pitch)
        result = dict(fov=fov, point=point['point'], detection=detection, height=height,
                      distance=distance, pitch=pitch, frameTimestamp=frame_time,
                      gpsTimestamp=point['receivedTimestamp'], gimbalTimestamp=orientation_time,
                      sourceFile=point.get('sourceFile'),
                      model=self.model_path.name,
                      assumptions='Water at supplied height datum; board at water level; camera faces surfer; negligible roll; centred rectilinear projection.',
                      status='single-frame estimate; detection not manually verified')
        key = hashlib.sha256((str(point.get('sourceFile')) + frame_time + str(height)).encode()).hexdigest()[:20]
        folder = Path(output) / 'fov-estimates'
        folder.mkdir(parents=True, exist_ok=True)
        (folder / (key + '.json')).write_text(json.dumps(result, indent=2), encoding='utf-8')
        return result
