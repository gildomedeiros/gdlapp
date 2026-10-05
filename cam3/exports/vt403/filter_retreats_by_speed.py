"""Show actual retreats after simple GPS speed exceeded a chosen threshold.

Speed is displacement between successive distinct received fixes divided by
GPS sender elapsed time. No VT ride timer, confirmation, or jump rejection.
"""
import argparse
import json
import math
import hashlib
import zipfile
import struct
from pathlib import Path
from datetime import datetime, timedelta, timezone


def sources(folder):
    seen = set()
    for path in sorted(Path(folder).rglob('*')):
        if path.suffix.lower() == '.jsonl':
            streams = [(str(path), path.open('rb'))]
        elif path.suffix.lower() == '.zip':
            with zipfile.ZipFile(path) as archive:
                for name in sorted(archive.namelist()):
                    if name.lower().endswith('.jsonl'):
                        with archive.open(name) as stream:
                            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
                        if digest in seen:
                            continue
                        seen.add(digest)
                        with archive.open(name) as stream:
                            yield str(path) + '::' + name, stream
            continue
        else:
            continue
        for name, stream in streams:
            with stream:
                digest = hashlib.file_digest(stream, 'sha256').hexdigest()
                if digest in seen:
                    continue
                seen.add(digest)
                stream.seek(0)
                yield name, stream





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
    p.add_argument('--speed', type=float, default=10, help='GPS speed threshold in km/h')
    p.add_argument('--within', type=float, default=20, help='maximum seconds from threshold sample to retreat')
    p.add_argument('--logs', default=r'C:\Users\gildo\temp\vt 4.03\logs')
    p.add_argument('--videos', default=r'E:\DCIM\100MEDIA')
    p.add_argument('--first-only', action='store_true', help='consume threshold evidence at first following retreat')
    args = p.parse_args()
    if args.speed < 0 or args.within <= 0:
        p.error('speed must be >=0 and within must be >0')
    # Embedded utility definitions below keep this file standalone.
    videos = []
    for path in sorted(Path(args.videos).rglob('*.MP4')):
        try:
            start, duration = video_timing(path)
            videos.append((path.name, start, duration))
        except (OSError, ValueError, struct.error, IndexError):
            pass
    def media(stamp):
        epoch = datetime.fromisoformat(stamp.replace('Z', '+00:00')).timestamp()
        matches = [(name, epoch-start) for name, start, duration in videos if start <= epoch < start+duration]
        if len(matches) != 1:
            return ('UNMATCHED' if not matches else 'AMBIGUOUS'), '-'
        name, offset = matches[0]
        return name, f'{int(offset//60):02d}:{offset%60:06.3f}'
    rows = []
    for name, stream in sources(args.logs):
        orientations = {}; pending = []
        aim = previous = evidence = session = None
        for line in stream:
            r = json.loads(line)
            if r.get('event') == 'orientation_cycle':
                orientations[(r.get('session'), r.get('cycleId'))] = r
                continue
            if r.get('event') == 'aiming_cycle':
                aim = r
                if r.get('session') != session:
                    previous = evidence = None
                    session = r.get('session')
                values = [r.get(k) for k in ('targetLatitude', 'targetLongitude', 'targetFixMs', 'targetSenderMs')]
                if None in values:
                    continue
                fix = tuple(values)
                if previous and fix[2] == previous[2]:
                    continue
                if previous:
                    dt = (fix[3]-previous[3])/1000
                    if dt > 0 and fix[2] > previous[2]:
                        lat1, lon1, lat2, lon2 = map(math.radians, (previous[0], previous[1], fix[0], fix[1]))
                        h = math.sin((lat2-lat1)/2)**2+math.cos(lat1)*math.cos(lat2)*math.sin((lon2-lon1)/2)**2
                        speed = 6371000*2*math.asin(math.sqrt(max(0,min(1,h))))/dt*3.6
                        if speed > args.speed:
                            evidence = (r['epochMs'], r['timestamp'], speed, dt)
                    else:
                        evidence = None
                previous = fix
            elif r.get('event') == 'retreat_started' and evidence and r.get('session') == session:
                elapsed = (r['epochMs']-evidence[0])/1000
                if 0 <= elapsed <= args.within:
                    mp4, offset = media(r['timestamp'])
                    rows.append([r['timestamp'][11:23], mp4, offset, evidence[1][11:23],
                                 f'{evidence[2]:.2f}', f'{evidence[3]:.3f}', f'{elapsed:.3f}',
                                 f'{r["distanceM"]:.2f}' if r.get('distanceM') is not None else '-'])
                    pending.append((len(rows)-1, (r.get('session'), r.get('cycleId'))))
                    if args.first_only:
                        evidence = None
        for index, key in pending:
            orientation = orientations.get(key, {})
            pitch = orientation.get('gimbalPitchDeg') if orientation.get('gimbalAttitudeFresh') else None
            age = orientation.get('gimbalAttitudeAgeMs')
            rows[index].extend([f'{pitch:.2f}' if pitch is not None else '-', str(age) if age is not None else '-'])
    headers = ['Retreat time', 'MP4', 'Offset', 'Speed sample', 'km/h', 'GPS span s', 'After s', 'Distance m', 'Actual pitch deg', 'Gimbal age ms']
    widths = [max(len(headers[i]), max((len(row[i]) for row in rows), default=0)) for i in range(len(headers))]
    print(' | '.join(v.ljust(w) for v,w in zip(headers,widths)))
    print('-+-'.join('-'*w for w in widths))
    for row in rows:
        print(' | '.join(v.ljust(w) for v,w in zip(row,widths)))
    print(f'\n{len(rows)} actual retreats; simple GPS speed >{args.speed:g} km/h within preceding {args.within:g} s.')
    print('Speed sample is controller receipt/consumption time; GPS span uses sender time. No ride detection or direction filter.')
    print('Actual pitch is measured at the retreat-start cycle, joined by session/cycle ID. Missing or stale measurement = -.')


if __name__ == '__main__':
    main()
