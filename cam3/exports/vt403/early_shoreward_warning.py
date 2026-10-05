"""Inspect GPS movement around a timestamp. No third-party packages required."""
import argparse
from datetime import datetime, timezone, timedelta
import hashlib
import json
import math
from pathlib import Path
import zipfile
import struct


def sources(folder):
    seen = set()
    for path in sorted(Path(folder).rglob('*')):
        if path.suffix.lower() == '.jsonl':
            with path.open('rb') as f:
                digest = hashlib.sha256(f.read()).digest()
            if digest in seen:
                continue
            seen.add(digest)
            with path.open('rb') as f:
                yield str(path), f
        elif path.suffix.lower() == '.zip':
            with zipfile.ZipFile(path) as z:
                for name in sorted(z.namelist()):
                    if not name.lower().endswith('.jsonl'):
                        continue
                    with z.open(name) as f:
                        h = hashlib.sha256()
                        for chunk in iter(lambda: f.read(1024*1024), b''):
                            h.update(chunk)
                    if h.digest() in seen:
                        continue
                    seen.add(h.digest())
                    with z.open(name) as f:
                        yield str(path)+'::'+name, f


def motion(a, b, sea_bearing):
    lat1, lon1, lat2, lon2 = map(math.radians, (a['lat'], a['lon'], b['lat'], b['lon']))
    h = math.sin((lat2-lat1)/2)**2 + math.cos(lat1)*math.cos(lat2)*math.sin((lon2-lon1)/2)**2
    distance = 6371000*2*math.asin(math.sqrt(max(0, min(1, h))))
    bearing = math.degrees(math.atan2(math.sin(lon2-lon1)*math.cos(lat2),
                                     math.cos(lat1)*math.sin(lat2)-math.sin(lat1)*math.cos(lat2)*math.cos(lon2-lon1))) % 360
    dt = (b['sender']-a['sender'])/1000
    if dt <= 0:
        return ['clock reset'] * 5
    speed = distance/dt*3.6
    shore = -speed*math.cos(math.radians(bearing-sea_bearing))
    direction = 'stationary' if distance == 0 else 'shoreward' if shore > 0 else 'seaward' if shore < 0 else 'alongshore'
    return [f'{dt:.3f}', f'{distance:.2f}', f'{bearing:.1f}', f'{speed:.2f}', f'{shore:+.2f} ({direction})']


def video_timing(path):
    """Read recording start and duration from the MP4 movie header."""
    with path.open('rb') as stream:
        def boxes(end):
            while stream.tell() + 8 <= end:
                start = stream.tell()
                size, kind = struct.unpack('>I4s', stream.read(8))
                header = 8
                if size == 1:
                    size = struct.unpack('>Q', stream.read(8))[0]
                    header = 16
                elif size == 0:
                    size = end - start
                if size < header or start + size > end:
                    raise ValueError('Invalid MP4 box size')
                if kind == b'moov':
                    result = boxes(start + size)
                    if result:
                        return result
                elif kind == b'mvhd':
                    version = stream.read(4)[0]
                    if version == 0:
                        created, _, scale, duration = struct.unpack('>IIII', stream.read(16))
                    elif version == 1:
                        created, _, scale, duration = struct.unpack('>QQIQ', stream.read(28))
                    else:
                        raise ValueError('Unsupported movie header version')
                    if not created or not scale:
                        raise ValueError('Missing recording time or timescale')
                    epoch = datetime(1904, 1, 1, tzinfo=timezone.utc)
                    return (epoch + timedelta(seconds=created)).timestamp(), duration / scale
                stream.seek(start + size)
        result = boxes(path.stat().st_size)
        if not result:
            raise ValueError('No MP4 movie header')
        return result



# Editable candidate parameters. These are not validated VT settings.
PARAMETERS = {
    'successive_speed_updates': 3,   # positive, strictly increasing 1–2 s speeds
    'minimum_shoreward_kmh': 0.0,
    'minimum_acceleration_mps2': 0.0,
    'minimum_closing_mps': 0.0,
    'reference_min_ms': 1000,
    'reference_max_ms': 2000,
    'packet_gap_reset_ms': 3000,
    'fresh_packet_max_age_ms': 3000,
    'jump_limit_kmh': 40.0,
}


def velocity(a, b, sea):
    dt = (b['sender'] - a['sender']) / 1000
    if dt <= 0:
        return None
    lat = math.radians((a['lat'] + b['lat']) / 2)
    north = math.radians(b['lat'] - a['lat']) * 6371000
    east = math.radians(b['lon'] - a['lon']) * 6371000 * math.cos(lat)
    total = math.hypot(north, east) / dt * 3.6
    angle = math.radians(sea)
    shore = -(east * math.sin(angle) + north * math.cos(angle)) / dt * 3.6
    return total, shore


def main():
    parser = argparse.ArgumentParser(description='Candidate early warning: accelerating shoreward GPS motion plus closing separation. No dependencies; no VT changes.')
    parser.add_argument('--logs', default=r'C:\Users\gildo\temp\vt 4.03\logs')
    parser.add_argument('--videos', default=r'E:\DCIM\100MEDIA')
    parser.add_argument('--csv', help='Optional output CSV path')
    parser.add_argument('--retreat-window', type=float, default=20, help='Match nearest actual retreat start within this many seconds, same log/session (default 20)')
    args = parser.parse_args()
    cfg = PARAMETERS
    if cfg['successive_speed_updates'] < 2:
        parser.error('successive_speed_updates must be at least 2')
    videos = []
    for path in sorted(Path(args.videos).rglob('*.MP4')):
        try:
            start, duration = video_timing(path)
            videos.append((path.name, start, duration))
        except (OSError, ValueError, struct.error, IndexError):
            pass
    def media(epoch_ms):
        hits = [(name, epoch_ms/1000-start) for name, start, duration in videos if start <= epoch_ms/1000 < start+duration]
        if len(hits) != 1:
            return ('-' if not hits else 'AMBIGUOUS', '-')
        name, offset = hits[0]
        return name, f'{int(offset//60):02d}:{offset%60:06.3f}'
    output = []
    retreats = {}
    for source, stream in sources(args.logs):
        aim = None
        session = None
        history = []
        previous = None
        speeds = []
        warned = False
        sea_previous = None
        for line in stream:
            r = json.loads(line)
            if r.get('event') == 'retreat_started':
                retreats.setdefault((source, r.get('session')), []).append(r)
            if r.get('event') == 'aiming_cycle':
                aim = r
                continue
            if r.get('event') != 'movement_cycle' or not aim or r.get('cycleId') != aim.get('cycleId') or r.get('session') != aim.get('session'):
                continue
            sea = r.get('seawardBearingDeg')
            if session != r.get('session') or sea != sea_previous:
                history, speeds, previous, warned = [], [], None, False
                session, sea_previous = r.get('session'), sea
            if sea is None or r.get('phase') == 'OFF':
                history, speeds, previous, warned = [], [], None, False
                continue
            fields = [aim.get(k) for k in ('targetLatitude', 'targetLongitude', 'targetFixMs', 'targetSenderMs', 'distanceM', 'targetAgeMs')]
            if any(v is None for v in fields) or fields[-1] > cfg['fresh_packet_max_age_ms']:
                history, speeds, previous, warned = [], [], None, False
                continue
            fix = dict(lat=fields[0], lon=fields[1], receipt=fields[2], sender=fields[3], distance=fields[4], epoch=r['epochMs'])
            if previous and fix['receipt'] == previous['receipt']:
                continue
            if previous and (fix['receipt'] <= previous['receipt'] or fix['sender'] <= previous['sender'] or fix['receipt']-previous['receipt'] > cfg['packet_gap_reset_ms'] or fix['sender']-previous['sender'] > cfg['packet_gap_reset_ms']):
                history, speeds, previous, warned = [], [], None, False
            if previous:
                step = velocity(previous, fix, sea)
                if step and step[0] > cfg['jump_limit_kmh']:
                    history, speeds, previous, warned = [], [], None, False
                    continue  # rejected fix must not seed a warning
            references = [p for p in history if cfg['reference_min_ms'] <= fix['sender']-p['sender'] <= cfg['reference_max_ms']]
            reference = references[-1] if references else None
            if reference:
                total, shore = velocity(reference, fix, sea)
                closing_dt = (fix['epoch']-reference['epoch'])/1000
                closing = (reference['distance']-fix['distance'])/closing_dt if closing_dt > 0 else None
                sample = dict(shore=shore, closing=closing, sender=fix['sender'])
                if total > cfg['jump_limit_kmh']:
                    history, speeds, previous, warned = [], [], None, False
                    continue
                if shore <= cfg['minimum_shoreward_kmh']:
                    speeds, warned = [], False
                else:
                    speeds.append(sample)
                    speeds = speeds[-cfg['successive_speed_updates']:]
                    if len(speeds) == cfg['successive_speed_updates']:
                        accelerations = [(b['shore']-a['shore'])/3.6/((b['sender']-a['sender'])/1000) for a,b in zip(speeds,speeds[1:])]
                        increasing = all(x > cfg['minimum_acceleration_mps2'] for x in accelerations)
                        closing_all = all(x['closing'] is not None and x['closing'] > cfg['minimum_closing_mps'] for x in speeds)
                        if increasing and closing_all and not warned:
                            warned = True
                            mp4, offset = media(r['epochMs'])
                            output.append(dict(timestamp=r['timestamp'],epoch_ms=r['epochMs'],session=r.get('session'),mode=r.get('positioningMode','-'),mp4=mp4,offset=offset,shore_kmh=round(shore,2),accel_mps2=round(accelerations[-1],2),closing_mps=round(closing,2),direct_m=round(fix['distance'],2),projected_m=r.get('projectedSeparationM'),gps_age_ms=fields[-1],retreat_active=r.get('retreatActive'),phase=r.get('phase'),source=source))
            else:
                speeds = []
            history.append(fix)
            history = [p for p in history if fix['sender']-p['sender'] <= cfg['reference_max_ms']]
            previous = fix
    output.sort(key=lambda x:x['timestamp'])
    for warning in output:
        candidates = [r for r in retreats.get((warning['source'], warning['session']), []) if abs(r['epochMs']-warning['epoch_ms']) <= args.retreat_window*1000]
        match = min(candidates, key=lambda r:abs(r['epochMs']-warning['epoch_ms'])) if candidates else None
        warning['retreat_timestamp'] = match['timestamp'] if match else None
        warning['retreat_delta_s'] = (match['epochMs']-warning['epoch_ms'])/1000 if match else None
    headers = ['#','Warning time','Mode','MP4','Offset','Shore km/h','Accel m/s2','Closing m/s','Direct m','GPS ms','Retreat active','Retreat time','Delta s']
    rows = [[str(i),r['timestamp'][11:23],str(r['mode']),r['mp4'],r['offset'],str(r['shore_kmh']),str(r['accel_mps2']),str(r['closing_mps']),str(r['direct_m']),str(r['gps_age_ms']),str(r['retreat_active']),r['retreat_timestamp'][11:23] if r['retreat_timestamp'] else '-',f"{r['retreat_delta_s']:+.3f}" if r['retreat_delta_s'] is not None else '-'] for i,r in enumerate(output,1)]
    widths = [max([len(h)]+[len(row[i]) for row in rows]) for i,h in enumerate(headers)]
    print(' | '.join(h.ljust(w) for h,w in zip(headers,widths)))
    print('-+-'.join('-'*w for w in widths))
    for row in rows:
        print(' | '.join(v.ljust(w) for v,w in zip(row,widths)))
    print(f'\nWarnings: {len(output)}. One warning per shoreward burst; rearms on nonpositive shoreward speed or reset.')
    print('Rule: three positive, increasing GPS window speeds, with closing separation in all three updates (editable PARAMETERS).')
    print('Candidate warnings, not confirmed rides or simulated retreat starts. GPS noise can trigger; wave pushback is acceptable.')
    print(f'Delta s = actual retreat start minus warning: + after warning, - before warning. Nearest start within +/-{args.retreat_window:g}s, same log/session; no match = -. Temporal match does not establish causation.')
    print('MP4 offsets use movie creation time; alignment is provisional. CSV retains date, source and controller phase.')
    if args.csv and output:
        import csv
        with Path(args.csv).open('w',newline='',encoding='utf-8') as f:
            w=csv.DictWriter(f,fieldnames=list(output[0]));w.writeheader();w.writerows(output)


if __name__ == '__main__':
    main()
