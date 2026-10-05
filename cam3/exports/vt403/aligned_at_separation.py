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



# Example filter values; change here or override with arguments.
PARAMETERS = {
    'separation_m': 28.0,
    'separation_tolerance_m': 1.0,
    'heading_error_deg': 5.0,
    'max_gps_age_ms': 1000,
}


def main():
    p=argparse.ArgumentParser(description='Find drone heading aligned to latest surfer GPS position at a direct horizontal separation.')
    p.add_argument('--logs',default=r'C:\Users\gildo\temp\vt 4.03\logs')
    p.add_argument('--videos',default=r'E:\DCIM\100MEDIA')
    p.add_argument('--separation',type=float,default=PARAMETERS['separation_m'])
    p.add_argument('--tolerance',type=float,default=PARAMETERS['separation_tolerance_m'])
    p.add_argument('--error',type=float,default=PARAMETERS['heading_error_deg'])
    p.add_argument('--max-gps-age',type=float,default=PARAMETERS['max_gps_age_ms'],help='Maximum surfer GPS age in milliseconds')
    p.add_argument('--all',action='store_true',help='Print every matching control cycle instead of first cycle in each matching interval')
    p.add_argument('--csv',help='Optional CSV output path')
    args=p.parse_args()
    if args.separation<=0 or args.tolerance<0 or not 0<=args.error<=180 or args.max_gps_age<0:
        p.error('separation must be positive, tolerances/age nonnegative, heading error at most 180')
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
    output=[]
    for source,stream in sources(args.logs):
        aims=[];orientations={};pitches={};commands={}
        for line in stream:
            item=json.loads(line)
            key=(item.get('session'),item.get('cycleId'))
            if item.get('event')=='aiming_cycle':aims.append(item)
            elif item.get('event')=='orientation_cycle':orientations[key]=item
            elif item.get('event')=='gimbal_pitch_cycle':pitches[key]=item
            elif item.get('event')=='gimbal_pitch_command':commands.setdefault(item.get('session'),[]).append(item)
        for items in commands.values():items.sort(key=lambda item:item['epochMs'])
        matching=False;session=None;last_epoch=None
        for r in aims:
            if session!=r.get('session') or (last_epoch is not None and (r['epochMs']<last_epoch or r['epochMs']-last_epoch>1000)):
                matching=False
            session=r.get('session');last_epoch=r['epochMs']
            fields=[r.get(k) for k in ['aircraftLatitude','aircraftLongitude','aircraftHeadingDeg','targetLatitude','targetLongitude','targetAgeMs']]
            if any(v is None for v in fields) or fields[-1]>args.max_gps_age or r.get('state')=='OFF':
                matching=False;continue
            lat1,lon1,heading,lat2,lon2,age=fields
            a,b,c,d=map(math.radians,[lat1,lon1,lat2,lon2])
            h=math.sin((c-a)/2)**2+math.cos(a)*math.cos(c)*math.sin((d-b)/2)**2
            distance=6371000*2*math.asin(math.sqrt(max(0,min(1,h))))
            bearing=math.degrees(math.atan2(math.sin(d-b)*math.cos(c),math.cos(a)*math.sin(c)-math.sin(a)*math.cos(c)*math.cos(d-b)))%360
            error=(bearing-heading+180)%360-180
            qualifies=distance>0 and abs(distance-args.separation)<=args.tolerance and abs(error)<=args.error
            if qualifies and (args.all or not matching):
                mp4,offset=media(r['epochMs'])
                key=(session,r.get('cycleId'));o=orientations.get(key,{});pitch=pitches.get(key,{})
                actual=o.get('gimbalPitchDeg') if o.get('gimbalAttitudeFresh') else None
                commanded=pitch.get('targetPitchDeg')
                if commanded is None:
                    prior=[item for item in commands.get(session,[]) if item['epochMs']<=r['epochMs']]
                    commanded=prior[-1].get('targetPitchDeg') if prior else None
                pitch_error=actual-commanded if actual is not None and commanded is not None else None
                relative=o.get('gimbalYawRelativeToAircraftDeg') if o.get('gimbalRelativeYawFresh') else None
                aircraft_yaw=o.get('aircraftYawDeg') if o.get('aircraftAttitudeFresh') else None
                camera_yaw=(aircraft_yaw+relative)%360 if aircraft_yaw is not None and relative is not None else None
                camera_error=(bearing-camera_yaw+180)%360-180 if camera_yaw is not None else None
                output.append(dict(timestamp=r['timestamp'],direct_m=round(distance,2),heading_deg=round(heading,2),surfer_bearing_deg=round(bearing,2),heading_error_deg=round(error,2),actual_gimbal_pitch_deg=actual,commanded_pitch_deg=commanded,pitch_command_error_deg=pitch_error,camera_yaw_deg=camera_yaw,camera_yaw_error_deg=camera_error,gimbal_age_ms=o.get('gimbalAttitudeAgeMs'),gps_age_ms=age,aircraft_age_ms=r.get('aircraftAgeMs'),mp4=mp4,offset=offset,source=source,session=session))
            matching=qualifies
    output.sort(key=lambda r:r['timestamp'])
    def angle(v):return '-' if v is None else f'{v:+.2f}'
    headers=['#','Timestamp','Direct m','Heading deg','Surfer bearing','Heading error','Actual pitch','Command pitch','Pitch cmd error','Camera yaw error','Gimbal age ms','GPS age ms','Drone age ms','MP4','Offset']
    rows=[[str(i),r['timestamp'],str(r['direct_m']),str(r['heading_deg']),str(r['surfer_bearing_deg']),angle(r['heading_error_deg']),angle(r['actual_gimbal_pitch_deg']),angle(r['commanded_pitch_deg']),angle(r['pitch_command_error_deg']),angle(r['camera_yaw_error_deg']),str(r['gimbal_age_ms']),str(r['gps_age_ms']),str(r['aircraft_age_ms']),r['mp4'],r['offset']] for i,r in enumerate(output,1)]
    widths=[max([len(h)]+[len(row[i]) for row in rows]) for i,h in enumerate(headers)]
    print(' | '.join(h.ljust(w) for h,w in zip(headers,widths)));print('-+-'.join('-'*w for w in widths))
    for row in rows:print(' | '.join(v.ljust(w) for v,w in zip(row,widths)))
    print(f'\nMatches: {len(rows)}. Direct separation {args.separation:g} +/- {args.tolerance:g} m; absolute heading error <= {args.error:g} degrees; GPS age <= {args.max_gps_age:g} ms.')
    print('Uses latest recorded surfer coordinates, not the saved come-to-me destination. GPS position is an estimate, not ground truth.')
    print('Gimbal measurements joined by same session/cycle; command target falls back to latest prior command in same session. Pitch cmd error = measured minus commanded pitch. Camera yaw error = surfer bearing minus (measured aircraft yaw + relative gimbal yaw), wrapped.')
    print('Pitch command error measures command tracking, not vertical framing error to the surfer; surfer elevation is unavailable. Unavailable/stale measurements = -.')
    print('Default: first matching cycle per interval; --all prints every matching cycle. MP4 clock alignment is provisional.')
    if args.csv:
        import csv
        with Path(args.csv).open('w',newline='',encoding='utf-8') as f:
            fields=list(output[0]) if output else ['timestamp','direct_m','heading_deg','surfer_bearing_deg','heading_error_deg','gps_age_ms','aircraft_age_ms','mp4','offset','source','session']
            w=csv.DictWriter(f,fieldnames=fields);w.writeheader();w.writerows(output)


if __name__=='__main__':main()
