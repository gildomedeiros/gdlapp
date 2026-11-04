"""On-demand breaking-crest estimates; never treat image angle as map bearing.

Projection: https://docs.opencv.org/4.x/d9/d0c/group__calib3d.html
Edges: https://docs.opencv.org/4.x/d9/db0/tutorial_hough_lines.html
Map bearings are undirected axes, modulo 180 degrees, not travel directions.
"""
from datetime import datetime
from functools import lru_cache
import hashlib
import json
import math
from pathlib import Path
import threading

VERSION = 3

# Local evidence only: a strong foam edge elsewhere in the frame must never
# become the surfer's crest just because nothing suitable is nearby.
CREST_SEARCH_RADIUS_FRACTION = .25
PERSON_LOCATOR_LOCK = threading.Lock()


@lru_cache(maxsize=1)
def person_locator():
    from surfer_fov import FovEstimator
    model = Path(__file__).with_name('models')/'yolo11n-seg.pt'
    return FovEstimator(model) if model.is_file() else None


def surfer_reference(image, projected, width, height):
    """Use a single visible surfer's water contact; otherwise retain GPS projection."""
    import numpy as np
    locator = person_locator()
    if locator is None: return projected, 'GPS projection'
    try:
        with PERSON_LOCATOR_LOCK:
            result = locator.predict(image)
    except (ImportError, RuntimeError, ValueError, OSError):
        return projected, 'GPS projection'
    boxes = sorted((b for b in result.boxes if result.names[int(b.cls.item())]=='person'),
                   key=lambda b:float(b.conf.item()), reverse=True)
    unique = []
    for box in boxes:
        coords = np.array(box.xyxy[0].tolist(), dtype=float)
        duplicate = False
        for other in unique:
            a = np.maximum(coords[:2],other[:2]); b = np.minimum(coords[2:],other[2:])
            intersection = float(np.maximum(b-a,0).prod())
            union = float((coords[2:]-coords[:2]).prod()+(other[2:]-other[:2]).prod())-intersection
            if union>0 and intersection/union>.5: duplicate=True
        if not duplicate: unique.append(coords)
    if len(unique)!=1: return projected, 'GPS projection'
    x1,y1,x2,y2 = unique[0]
    original_h, original_w = result.orig_shape
    return np.array([(x1+x2)/2*width/original_w,y2*height/original_h]), 'detected surfer water contact'


def finite(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def camera_pose(point):
    from telemetry_around import record_milliseconds
    frame = datetime.fromisoformat(point['videoFrameTimestamp'])
    target = frame.timestamp() * 1000
    best = None
    with Path(point['sourceFile']).open(encoding='utf-8-sig') as stream:
        for number, line in enumerate(stream, 1):
            if not line.strip(): continue
            row = json.loads(line)
            if not isinstance(row, dict) or row.get('event') != 'orientation_cycle': continue
            t = record_milliseconds(row, frame.tzinfo)
            if t > target or (best and (t, number) < best[:2]): continue
            if point.get('session') is not None and row.get('session') != point['session']: continue
            best = (t, number, row)
    if best is None: raise ValueError('No camera orientation logged before this frame.')
    t, number, row = best
    for name in ('aircraftAttitude', 'gimbalAttitude', 'gimbalRelativeYaw', 'height'):
        age = row.get(name+'AgeMs')
        if row.get(name+'Fresh') is not True or not finite(age) or not 0 <= age + target-t <= 1500:
            raise ValueError('Camera orientation/height is missing or stale for this frame; no map direction plotted.')
    keys = ('aircraftYawDeg', 'gimbalYawRelativeToAircraftDeg', 'gimbalPitchDeg', 'gimbalRollDeg', 'heightAboveTakeoffM')
    if not all(finite(row.get(k)) for k in keys): raise ValueError('Incomplete camera orientation.')
    if not -90 <= row['gimbalPitchDeg'] <= -5 or row['heightAboveTakeoffM'] <= 0:
        raise ValueError('A downward-facing camera and positive logged height are needed.')
    return dict(yaw=(row['aircraftYawDeg']+row['gimbalYawRelativeToAircraftDeg']) % 360,
                pitch=row['gimbalPitchDeg'], roll=row['gimbalRollDeg'], height=row['heightAboveTakeoffM'],
                timestamp=datetime.fromtimestamp(t/1000, frame.tzinfo).isoformat(timespec='milliseconds'), sourceLine=number,
                headingSource='aircraft heading + gimbal yaw relative to aircraft')


def camera_axes(pose):
    import numpy as np
    yaw, down, roll = map(math.radians, (pose['yaw'], -pose['pitch'], pose['roll']))
    forward = np.array([math.sin(yaw)*math.cos(down), math.cos(yaw)*math.cos(down), -math.sin(down)])
    right = np.array([math.cos(yaw), -math.sin(yaw), 0.])
    lower = np.cross(forward, right)
    return forward, right*math.cos(roll)+lower*math.sin(roll), lower*math.cos(roll)-right*math.sin(roll)


def ground_points(pixels, width, height, hfov, pose):
    import numpy as np
    forward, right, lower = camera_axes(pose)
    focal = width/(2*math.tan(math.radians(hfov)/2))
    pixels = np.asarray(pixels, dtype=float)
    rays = forward+(pixels[:, 0:1]-width/2)/focal*right+(pixels[:, 1:2]-height/2)/focal*lower
    if np.any(rays[:, 2] >= -.05): raise ValueError('Detected crest is too close to or above the horizon.')
    return rays[:, :2]/-rays[:, 2:3]  # Unit camera height; orientation is independent of scale.


def expected_surfer(point, width, height, hfov, pose):
    import numpy as np
    values = [point.get(k) for k in ('latitude', 'longitude', 'droneLatitude', 'droneLongitude')]
    if not all(finite(v) for v in values): raise ValueError('Surfer and drone GPS are required to select the nearby crest.')
    lat, lon, dlat, dlon = values
    east = 6371000*math.radians(lon-dlon)*math.cos(math.radians(dlat))
    north = 6371000*math.radians(lat-dlat)
    vector = np.array([east, north, -pose['height']])
    forward, right, lower = camera_axes(pose)
    z = vector @ forward
    if z <= 0: raise ValueError('Logged surfer position is behind the camera.')
    focal = width/(2*math.tan(math.radians(hfov)/2))
    target = np.array([width/2+focal*(vector @ right)/z, height/2+focal*(vector @ lower)/z])
    if not -.2*width < target[0] < 1.2*width or not -.2*height < target[1] < 1.2*height:
        raise ValueError('GPS/camera geometry places the surfer outside the frame; check horizontal FOV.')
    return target


def detect_crest(image, point, pose, hfov):
    import cv2
    import numpy as np
    original = cv2.imread(str(image))
    if original is None: raise ValueError('Could not read the clean video frame.')
    original_h, original_w = original.shape[:2]
    scale = min(1., 960/original_w)
    frame = cv2.resize(original, (round(original_w*scale), round(original_h*scale)))
    h, w = frame.shape[:2]
    projected = expected_surfer(point, w, h, hfov, pose)
    target, reference = surfer_reference(image, projected, w, h)
    search_radius = w*CREST_SEARCH_RADIUS_FRACTION
    hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
    mask = ((hsv[:, :, 1] < 85) & (hsv[:, :, 2] > 175)).astype('uint8')*255
    mask = cv2.morphologyEx(mask, cv2.MORPH_CLOSE, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (5, 5)))
    mask = cv2.morphologyEx(mask, cv2.MORPH_OPEN, np.ones((3, 3), 'uint8'))
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
    contours = [c for c in contours if cv2.contourArea(c) >= w*h*.0006]
    edge = np.zeros_like(mask)
    cv2.drawContours(edge, contours, -1, 255, 1)
    edge[:8] = 0; edge[-8:] = 0; edge[:, :8] = 0; edge[:, -8:] = 0
    lines = cv2.HoughLinesP(edge, 1, np.pi/360, 25, minLineLength=max(30, w*.055), maxLineGap=max(10, w*.02))
    candidates = []
    for raw in [] if lines is None else lines.reshape(-1, 4):
        a, b = raw[:2].astype(float), raw[2:].astype(float)
        delta = b-a; length = float(np.linalg.norm(delta))
        normal = np.array([-delta[1], delta[0]])/length
        samples = a+np.linspace(.1, .9, 15)[:, None]*delta
        coverage = []
        for sign in (-1, 1):
            probe = np.rint(samples+normal*sign*8).astype(int)
            probe[:, 0] = probe[:, 0].clip(0, w-1); probe[:, 1] = probe[:, 1].clip(0, h-1)
            coverage.append(float(np.mean(mask[probe[:, 1], probe[:, 0]] > 0)))
        contrast = abs(coverage[0]-coverage[1])
        if contrast < .45 or min(coverage) > .25: continue
        fraction = np.clip(np.dot(target-a, delta)/(length*length), 0, 1)
        separation = float(np.linalg.norm(target-(a+fraction*delta)))
        if separation > search_radius: continue
        try: ground = ground_points([a, b], w, h, hfov, pose)
        except ValueError: continue
        direction = ground[1]-ground[0]
        bearing = math.degrees(math.atan2(direction[0], direction[1])) % 180
        weight = length*contrast*math.exp(-(separation/(w*.12))**2)
        candidates.append(dict(a=a, b=b, length=length, separation=separation, bearing=bearing, weight=weight, contrast=contrast))
    if not candidates:
        raise ValueError('No clear breaking-crest edge close to the surfer. The whitewater detector cannot identify an unbroken crest; no line added.')
    angles = np.array([c['bearing'] for c in candidates])
    weights = np.array([c['weight'] for c in candidates])
    differences = np.abs((angles[:, None]-angles+90) % 180-90)
    support = differences < 15
    totals = support @ weights
    winner = int(np.argmax(totals))
    chosen = [c for c, yes in zip(candidates, support[winner]) if yes]
    competing = totals[differences[winner] > 35]
    # A single clear breaking lip beside the surfer is useful evidence; don't
    # require unrelated, parallel edges farther away to make up a vote count.
    close_edge = any(c['separation']<=w*.08 and c['length']>=w*.06 and c['contrast']>=.65 for c in chosen)
    if (not close_edge and (len(chosen)<2 or max(c['length'] for c in chosen)<w*.08)) or (len(competing) and max(competing)>totals[winner]*.85):
        raise ValueError('Wave edges are short or ambiguous; no reliable crest orientation added.')
    vector = sum(c['weight']*np.exp(2j*math.radians(c['bearing'])) for c in chosen)
    bearing = math.degrees(np.angle(vector)/2) % 180
    representative = max(chosen, key=lambda c:c['weight'])
    return dict(bearingDegrees=round(bearing, 1), imageWidth=original_w, imageHeight=original_h,
                imageLine=(np.array([representative['a'], representative['b']])/scale).round(2).tolist(),
                supportSegments=len(chosen), angularSpreadDegrees=round(float(np.average(
                    [abs((c['bearing']-bearing+90) % 180-90) for c in chosen], weights=[c['weight'] for c in chosen])), 1),
                projectedSurferImage=(projected/scale).round(2).tolist(),
                surferReferenceImage=(target/scale).round(2).tolist(),
                surferReferenceMethod=reference,
                searchRadiusPixels=round(search_radius/scale, 2),
                edgeDistancePixels=round(representative['separation']/scale, 2),
                method='local whitewater outer-edge segments near surfer; perspective-corrected crest axis')


def wave_key(point):
    identity = [point.get('sourceFile'), point.get('receivedTimestamp'), point.get('videoFrameTimestamp')]
    return hashlib.sha256(json.dumps(identity).encode()).hexdigest()[:24]


def locate_crest(result, point, pose, hfov):
    """Project the actual detected endpoints onto water, without a surfer offset."""
    import numpy as np
    lat, lon = point.get('droneLatitude'), point.get('droneLongitude')
    if not all(finite(v) for v in (lat, lon)) or not -90 <= lat <= 90 or not -180 <= lon <= 180:
        raise ValueError('Drone coordinates are needed to locate the crest.')
    ground = ground_points(result['imageLine'], result['imageWidth'], result['imageHeight'], hfov, pose)*pose['height']
    if result.get('surferReferenceMethod') == 'detected surfer water contact':
        anchor = ground_points([result['surferReferenceImage']], result['imageWidth'], result['imageHeight'], hfov, pose)[0]*pose['height']
        ground -= anchor
        lat,lon = point['latitude'], point['longitude']
        result['placementReference'] = 'relative to detected surfer, registered to surfer GPS'
    else:
        result['placementReference'] = 'drone GPS and logged camera pose'
    cos_lat = math.cos(math.radians(lat))
    if abs(cos_lat) < 1e-6: raise ValueError('Map projection is unavailable near the poles.')
    ends = [dict(latitude=lat+math.degrees(north/6371000),
                 longitude=lon+math.degrees(east/(6371000*cos_lat))) for east,north in ground]
    delta = ground[1]-ground[0]
    result.update(crestEndpoints=ends, crestLengthMetres=round(float(np.linalg.norm(delta)), 2),
                  consensusBearingDegrees=result['bearingDegrees'],
                  bearingDegrees=round(math.degrees(math.atan2(delta[0], delta[1])) % 180, 1))
    return result


def ignore_wave(point, output):
    """Persist rejection of exactly this log receipt/frame, not its map-local ID."""
    key = wave_key(point)
    path = Path(output)/'wave-orientations'/(key+'.json')
    try:
        row = json.loads(path.read_text(encoding='utf-8'))
    except FileNotFoundError:
        return dict(key=key, ignored=False)
    row['ignored'] = True
    temp = path.with_suffix('.tmp')
    temp.write_text(json.dumps(row, indent=2), encoding='utf-8')
    temp.replace(path)
    return dict(key=key, ignored=True)


def wave_results(points, output):
    sources = {p.get('sourceFile') for p in points}
    current = {(p.get('sourceFile'), p.get('receivedTimestamp')): p['point'] for p in points}
    results = []
    for path in (Path(output)/'wave-orientations').glob('*.json'):
        try:
            row = json.loads(path.read_text(encoding='utf-8'))
            if row.get('version') != VERSION or row.get('ignored') or row.get('sourceFile') not in sources: continue
            row['point'] = current.get((row['sourceFile'], row['gpsTimestamp']))
            results.append(row)
        except (ValueError, OSError, KeyError, TypeError): pass
    return results


def analyze_wave(point, request, video_folder, output, cache_output, ffmpeg):
    from surfer_map_capture import capture_frame, resolve_video
    hfov = request.get('horizontalFov', 74)
    if not finite(hfov) or not 20 <= hfov <= 120: raise ValueError('Horizontal FOV estimate must be 20–120 degrees.')
    if not point.get('videoFrameTimestamp'): raise ValueError('This surfer position has no mapped video frame.')
    if abs(point.get('videoFrameOffsetMs') or 0) > 100: raise ValueError('Frame is more than 100 ms from this GPS receipt.')
    key = wave_key(point)
    folder = Path(cache_output)/'wave-orientations'; folder.mkdir(parents=True, exist_ok=True)
    dest = folder/(key+'.json')
    video = resolve_video(point, video_folder)
    stamp = [str(video.resolve()), video.stat().st_size, video.stat().st_mtime_ns, Path(point['sourceFile']).stat().st_mtime_ns]
    try:
        cached = json.loads(dest.read_text(encoding='utf-8'))
        if not cached.get('ignored') and cached.get('version') == VERSION and cached.get('horizontalFov') == hfov and cached.get('sourceStamp') == stamp:
            return dict(cached, point=point['point'])
    except (OSError, ValueError): pass
    pose = camera_pose(point)
    clean = capture_frame(point, video_folder, output, ffmpeg, annotate=False)
    result = detect_crest(clean, point, pose, hfov)
    locate_crest(result, point, pose, hfov)
    result.update(version=VERSION, key=key, sourceFile=point['sourceFile'], sourceStamp=stamp,
                  gpsTimestamp=point['receivedTimestamp'], frameTimestamp=point['videoFrameTimestamp'], point=point['point'],
                  latitude=point['latitude'], longitude=point['longitude'], horizontalFov=hfov, pose=pose,
                  status='estimated crest axis (0–180°); not wave travel direction',
                  inputFrame='clean MP4 frame without telemetry',
                  assumptions='Estimated water position from image endpoints, logged camera pose and drone GPS. Horizontal FOV, square pixels, water at takeoff datum and no lens distortion are assumed.')
    temp = dest.with_suffix('.tmp'); temp.write_text(json.dumps(result, indent=2), encoding='utf-8'); temp.replace(dest)
    return result
