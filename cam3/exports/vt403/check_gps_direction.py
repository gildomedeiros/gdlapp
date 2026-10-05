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



def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('timestamp', help='HH:MM:SS.mmm or full ISO timestamp')
    p.add_argument('--date', default='2026-10-04')
    p.add_argument('--logs', default=r'C:\Users\gildo\temp\vt 4.03\logs')
    p.add_argument('--videos', default=r'E:\DCIM\100MEDIA')
    p.add_argument('--latitude', action='store_true', help='Show latitude column')
    p.add_argument('--longitude', action='store_true', help='Show longitude column')
    p.add_argument('--sender-ms', action='store_true', help='Show GPS sender timestamp column')
    p.add_argument('--sea-bearing', action='store_true', help='Show configured sea bearing column')
    p.add_argument('--details', action='store_true', help='Show all four optional columns')
    p.add_argument('--before', type=float, default=10, help='seconds before timestamp')
    p.add_argument('--after', type=float, default=5, help='seconds after timestamp')
    args = p.parse_args()
    text = args.timestamp if 'T' in args.timestamp else args.date+'T'+args.timestamp
    instant = datetime.fromisoformat(text.replace('Z', '+00:00'))
    if instant.tzinfo is None:
        instant = instant.replace(tzinfo=timezone(timedelta(hours=10)))
    epoch = instant.timestamp()*1000
    videos = []
    for path in sorted(Path(args.videos).rglob('*')):
        if path.suffix.lower() != '.mp4':
            continue
        try:
            start, duration = video_timing(path)
            videos.append((path.name, start, duration))
        except (OSError, ValueError, struct.error, IndexError) as error:
            print(f'Skipping {path.name}: {error}')
    def media(stamp):
        moment = datetime.fromisoformat(stamp.replace('Z', '+00:00')).timestamp()
        matches = [(name, moment-start) for name,start,duration in videos if start <= moment < start+duration]
        if len(matches) != 1:
            return ['UNMATCHED' if not matches else 'AMBIGUOUS', '-']
        name, offset = matches[0]
        return [name, f'{int(offset//60):02d}:{offset%60:06.3f}']
    low, high = epoch-args.before*1000, epoch+args.after*1000
    found = 0
    for name, stream in sources(args.logs):
        orientations, pending = {}, []
        aim, previous, history, rows = None, None, [], []
        session = None
        for line in stream:
            r = json.loads(line)
            if r.get('event') == 'orientation_cycle':
                orientations[(r.get('session'), r.get('cycleId'))] = r
                continue
            if r.get('event') == 'aiming_cycle':
                aim = r
            if r.get('event') != 'movement_cycle' or aim is None or r.get('cycleId') != aim.get('cycleId'):
                continue
            if r.get('session') != session:
                previous, history = None, []
                session = r.get('session')
            if None in (aim.get('targetLatitude'), aim.get('targetLongitude'), aim.get('targetFixMs'), aim.get('targetSenderMs')):
                continue
            fix = dict(lat=aim['targetLatitude'], lon=aim['targetLongitude'], receipt=aim['targetFixMs'],
                       sender=aim['targetSenderMs'], time=r['timestamp'])
            if previous and fix['receipt'] == previous['receipt']:
                continue
            if previous and (fix['receipt'] < previous['receipt'] or fix['receipt']-previous['receipt'] > 3000 or fix['sender'] <= previous['sender']):
                history = []
            history = [x for x in history if fix['sender']-x['sender'] <= 7000]
            references = [x for x in history if 1000 <= fix['sender']-x['sender'] <= 2000]
            reference = references[-1] if references else None
            sea = r.get('seawardBearingDeg')
            if low <= r['epochMs'] <= high:
                step = motion(previous, fix, sea) if previous and sea is not None else ['-']*5
                window = motion(reference, fix, sea)[-1] if reference and sea is not None else '-'
                rows.append([fix['time'][11:23], f'{fix["lat"]:.5f}', f'{fix["lon"]:.5f}',
                             str(fix['sender']), str(sea), *step, window, *media(fix['time'])])
                distance = aim.get('distanceM')
                rows[-1].append(f'{distance:.2f}' if distance is not None else '-')
                pending.append((len(rows)-1, (r.get('session'), r.get('cycleId'))))
            history.append(fix)
            previous = fix
        for index, key in pending:
            orientation = orientations.get(key, {})
            pitch = orientation.get('gimbalPitchDeg') if orientation.get('gimbalAttitudeFresh') else None
            age = orientation.get('gimbalAttitudeAgeMs')
            rows[index].extend([f'{pitch:.2f}' if pitch is not None else '-', str(age) if age is not None else '-'])
        if rows:
            found += len(rows)
            print('\n'+name)
            headers = ['Consumed at', 'Latitude', 'Longitude', 'Sender ms', 'Sea bearing',
                       'Step dt s', 'Step m', 'Travel deg', 'Total km/h', 'Step shore km/h', '1-2s shore km/h', 'MP4', 'Offset', 'Direct m', 'Actual pitch deg', 'Gimbal age ms']
            optional = {1: args.latitude, 2: args.longitude, 3: args.sender_ms, 4: args.sea_bearing}
            columns = [i for i in range(len(headers)) if i not in optional or args.details or optional[i]]
            headers = [headers[i] for i in columns]
            rows = [[row[i] for i in columns] for row in rows]
            widths = [max(len(headers[i]), max(len(row[i]) for row in rows)) for i in range(len(headers))]
            print(' | '.join(x.ljust(w) for x, w in zip(headers, widths)))
            print('-+-'.join('-'*w for w in widths))
            for row in rows:
                print(' | '.join(x.ljust(w) for x, w in zip(row, widths)))
    print('\nPositive shore speed = toward configured shoreline; negative = seaward.')
    print('Consumed at is controller time, not GPS measurement time. Sender ms determines speed.')
    print('Direct m is GPS-derived horizontal drone-surfer separation at Consumed at. Actual pitch is measured in that same session/cycle, not commanded pitch; missing/stale pitch = -.')
    print('This diagnoses GPS evidence, not video ground truth. 1-2s column shows reference speed before jump filtering.')
    if not found:
        print('No GPS fixes found in that time window.')


if __name__ == '__main__':
    main()
