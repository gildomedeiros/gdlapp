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
    p=argparse.ArgumentParser(description='Print completed come-to-me destinations from VT movement logs.')
    p.add_argument('--logs',default=r'C:\Users\gildo\temp\vt 4.03\logs')
    p.add_argument('--videos',default=r'E:\DCIM\100MEDIA')
    args=p.parse_args()
    videos=[]
    for path in sorted(Path(args.videos).rglob('*')):
        if path.suffix.lower()!='.mp4' or path.name.startswith('review_'):continue
        try:
            start,duration=video_timing(path);videos.append((path.name,start,duration))
        except (OSError,ValueError,struct.error,IndexError):pass
    def media(epoch):
        hits=[(name,epoch/1000-start) for name,start,duration in videos if start<=epoch/1000<start+duration]
        if len(hits)!=1:return ('-' if not hits else 'AMBIGUOUS','-')
        name,offset=hits[0];return name,f'{int(offset//60):02d}:{offset%60:06.3f}'
    found=[]
    for source,stream in sources(args.logs):
        seen=set();last_aim=None
        for line in stream:
            r=json.loads(line)
            if r.get('event')=='aiming_cycle':last_aim=r;continue
            if r.get('event')!='movement_cycle' or r.get('reason')!='fixed_destination_arrived':continue
            key=(r.get('session'),r.get('centralGeneration'),r.get('approachStartLatitude'),r.get('approachStartLongitude'),r.get('approachTargetLatitude'),r.get('approachTargetLongitude'),r.get('fixedDestinationFixTime'))
            if key in seen:continue
            seen.add(key)
            mp4,offset=media(r['epochMs'])
            direct=last_aim.get('distanceM') if last_aim and last_aim.get('cycleId')==r.get('cycleId') and last_aim.get('session')==r.get('session') else None
            found.append((r,mp4,offset,direct))
    found.sort(key=lambda x:x[0]['epochMs'])
    def number(value):return '-' if value is None else f'{value:.2f}'
    headers=['#','Reached timestamp','Mode','Journey','MP4','Offset','Direct m','Projected m','Destination remaining m']
    rows=[[str(i),r['timestamp'],str(r.get('positioningMode','-')),str(r.get('journeyKind','-')),mp4,offset,number(direct),number(r.get('projectedSeparationM')),number(r.get('approachRemainingM'))] for i,(r,mp4,offset,direct) in enumerate(found,1)]
    widths=[max([len(h)]+[len(row[i]) for row in rows]) for i,h in enumerate(headers)]
    print(' | '.join(h.ljust(w) for h,w in zip(headers,widths)));print('-+-'.join('-'*w for w in widths))
    for row in rows:print(' | '.join(v.ljust(w) for v,w in zip(row,widths)))
    print(f'\nCome-to-me destinations reached: {len(rows)}')
    print('Uses fixed_destination_arrived; one row per saved journey. Excludes retreat and return-to-central completion.')
    print('Reached means within configured arrival tolerance. MP4 offsets use movie creation time, provisionally.')


if __name__=='__main__':main()
