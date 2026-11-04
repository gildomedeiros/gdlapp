"""On-demand breaking-crest estimates; never treat image angle as map bearing.

Projection: https://docs.opencv.org/4.x/d9/d0c/group__calib3d.html
Edges: https://docs.opencv.org/4.x/d9/db0/tutorial_hough_lines.html
Map bearings are undirected axes, modulo 180 degrees, not travel directions.
"""
from datetime import datetime
import hashlib
import json
import math
from pathlib import Path

VERSION = 2

# Local evidence only: a strong foam edge elsewhere in the frame must never
# become the surfer's crest just because nothing suitable is nearby.
CREST_SEARCH_RADIUS_FRACTION = .18


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
    target = expected_surfer(point, w, h, hfov, pose)
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
        weight = length*contrast*math.exp(-(separation/search_radius)**2)
        candidates.append(dict(a=a, b=b, length=length, separation=separation, bearing=bearing, weight=weight))
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
    if len(chosen) < 2 or max(c['length'] for c in chosen) < w*.08 or (len(competing) and max(competing) > totals[winner]*.85):
        raise ValueError('Wave edges are short or ambiguous; no reliable crest orientation added.')
    vector = sum(c['weight']*np.exp(2j*math.radians(c['bearing'])) for c in chosen)
    bearing = math.degrees(np.angle(vector)/2) % 180
    representative = max(chosen, key=lambda c:c['weight'])
    return dict(bearingDegrees=round(bearing, 1), imageWidth=original_w, imageHeight=original_h,
                imageLine=(np.array([representative['a'], representative['b']])/scale).round(2).tolist(),
                supportSegments=len(chosen), angularSpreadDegrees=round(float(np.average(
                    [abs((c['bearing']-bearing+90) % 180-90) for c in chosen], weights=[c['weight'] for c in chosen])), 1),
                projectedSurferImage=(target/scale).round(2).tolist(),
                searchRadiusPixels=round(search_radius/scale, 2),
                edgeDistancePixels=round(representative['separation']/scale, 2),
                method='local whitewater outer-edge segments within 18% of frame width of projected surfer; perspective-corrected crest axis')


def wave_key(point):
    identity = [point.get('sourceFile'), point.get('receivedTimestamp'), point.get('videoFrameTimestamp')]
    return hashlib.sha256(json.dumps(identity).encode()).hexdigest()[:24]


def wave_results(points, output):
    sources = {p.get('sourceFile') for p in points}
    current = {(p.get('sourceFile'), p.get('receivedTimestamp')): p['point'] for p in points}
    results = []
    for path in (Path(output)/'wave-orientations').glob('*.json'):
        try:
            row = json.loads(path.read_text(encoding='utf-8'))
            if row.get('version') != VERSION or row.get('sourceFile') not in sources: continue
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
        if cached.get('version') == VERSION and cached.get('horizontalFov') == hfov and cached.get('sourceStamp') == stamp:
            return dict(cached, point=point['point'])
    except (OSError, ValueError): pass
    pose = camera_pose(point)
    clean = capture_frame(point, video_folder, output, ffmpeg, annotate=False)
    result = detect_crest(clean, point, pose, hfov)
    result.update(version=VERSION, key=key, sourceFile=point['sourceFile'], sourceStamp=stamp,
                  gpsTimestamp=point['receivedTimestamp'], frameTimestamp=point['videoFrameTimestamp'], point=point['point'],
                  latitude=point['latitude'], longitude=point['longitude'], horizontalFov=hfov, pose=pose,
                  status='estimated crest axis (0–180°); not wave travel direction',
                  assumptions='Horizontal FOV is an estimate; square pixels, level water at takeoff datum, no lens distortion. Marker is anchored at surfer GPS, not a mapped crest location.')
    temp = dest.with_suffix('.tmp'); temp.write_text(json.dumps(result, indent=2), encoding='utf-8'); temp.replace(dest)
    return result
