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



# Edit these, or override with command-line arguments. Example values only.
PARAMETERS = {
    'speed_kmh': 4.0,
    'duration_s': 2.0,
    'max_gap_s': 3.0,
    'jump_limit_kmh': 40.0,
}


def main():
    p = argparse.ArgumentParser(description='Find consecutive GPS steps above a shoreward speed for a minimum duration; no acceleration or ride detector.')
    p.add_argument('--logs', default=r'C:\Users\gildo\temp\vt 4.03\logs')
    p.add_argument('--videos', default=r'E:\DCIM\100MEDIA')
    p.add_argument('--speed', type=float, default=PARAMETERS['speed_kmh'], help='Strictly above this shoreward speed, km/h')
    p.add_argument('--duration', type=float, default=PARAMETERS['duration_s'], help='Minimum qualifying sender-clock duration, seconds')
    p.add_argument('--max-gap', type=float, default=PARAMETERS['max_gap_s'], help='Larger sender or receipt gaps break an interval')
    p.add_argument('--csv', help='Optional CSV output')
    p.add_argument('--retreat-distance', type=float, help='Potential retreat trigger when logged direct distance is strictly less than X metres')
    p.add_argument('--retreat-tolerance', type=float, default=1, help='Legacy argument; ignored for strict distance < X triggers')
    p.add_argument('--retreat-window', type=float, default=20, help='Potential trigger lookback before Qualified at; actual-retreat matching uses +/- this window (default 20 seconds)')
    args = p.parse_args()
    if args.speed < 0 or args.duration < 0 or args.max_gap <= 0:
        p.error('speed and duration must be nonnegative; max-gap must be positive')
    if args.retreat_tolerance < 0 or args.retreat_window < 0 or (args.retreat_distance is not None and args.retreat_distance <= 0):
        p.error('retreat distance must be positive; retreat tolerance and window nonnegative')
    videos=[]
    for path in sorted(Path(args.videos).rglob('*')):
        if path.suffix.lower() != '.mp4' or '_review' in path.parts or path.name.startswith('review_'):continue
        try:
            start,duration=video_timing(path);videos.append((path.name,start,duration))
        except (OSError,ValueError,struct.error,IndexError):pass
    def media(epoch):
        matches=[(name,epoch/1000-start) for name,start,duration in videos if start<=epoch/1000<start+duration]
        if len(matches)!=1:return ('-' if not matches else 'AMBIGUOUS','-')
        name,offset=matches[0];return name,f'{int(offset//60):02d}:{offset%60:06.3f}'
    output=[]
    for source,stream in sources(args.logs):
        orientations={};retreats=[];potential_retreats=[];below=False;boundary_session=None;onset=None;boundary_epoch=None;output_start=len(output)
        aim=None;session=None;sea_previous=None;previous=None;run=None
        def finish():
            if run and run['qualifies_at'] is not None:
                mp4,offset=media(run['qualifies_epoch'])
                output.append(dict(start=run['start'],qualified=run['qualifies_at'],qualified_epoch_ms=run['qualifies_epoch'],end=run['end'],duration_s=round(run['duration'],3),minimum_shore_kmh=round(run['minimum'],2),maximum_shore_kmh=round(run['maximum'],2),direct_separation_m=run['direct_m'],qualification_cycle_id=run['cycle_id'],mode=run['mode'],mp4=mp4,offset=offset,source=source,session=session))
        for line in stream:
            r=json.loads(line)
            if r.get('event')=='retreat_started':
                retreats.append(r)
                continue
            if r.get('event')=='orientation_cycle':
                orientations[(r.get('session'),r.get('cycleId'))]=r
                continue
            if r.get('event')=='aiming_cycle':
                aim=r
                if boundary_session!=r.get('session'):
                    below=False;boundary_session=r.get('session');onset=None
                if boundary_epoch is not None and (r['epochMs']<boundary_epoch or r['epochMs']-boundary_epoch>args.max_gap*1000):
                    below=False;onset=None
                boundary_epoch=r['epochMs']
                distance=r.get('distanceM');age=r.get('targetAgeMs')
                usable=r.get('state')!='OFF' and distance is not None and age is not None and age<=args.max_gap*1000
                if args.retreat_distance is not None and usable:
                    now_below=distance<args.retreat_distance
                    if now_below:
                        if not below:
                            onset=dict(timestamp=r['timestamp'],epochMs=r['epochMs'],session=r.get('session'),distanceM=distance)
                        potential_retreats.append(dict(timestamp=r['timestamp'],epochMs=r['epochMs'],session=r.get('session'),distanceM=distance,onset=onset))
                    else:onset=None
                    below=now_below
                elif not usable:
                    below=False;onset=None
                continue
            if r.get('event')!='movement_cycle' or not aim or r.get('cycleId')!=aim.get('cycleId') or r.get('session')!=aim.get('session'):continue
            sea=r.get('seawardBearingDeg')
            if session!=r.get('session') or sea!=sea_previous:
                finish();previous=None;run=None;session=r.get('session');sea_previous=sea
            values=[aim.get(k) for k in ['targetLatitude','targetLongitude','targetFixMs','targetSenderMs','targetAgeMs']]
            if sea is None or r.get('phase')=='OFF' or any(v is None for v in values) or values[-1]>args.max_gap*1000:
                finish();previous=None;run=None;continue
            fix=dict(lat=values[0],lon=values[1],receipt=values[2],sender=values[3],time=r['timestamp'],epoch=r['epochMs'])
            if previous and fix['receipt']==previous['receipt']:continue
            if previous:
                dt=(fix['sender']-previous['sender'])/1000
                receipt_dt=(fix['receipt']-previous['receipt'])/1000
                if dt<=0 or receipt_dt<=0 or dt>args.max_gap or receipt_dt>args.max_gap:
                    finish();run=None;previous=fix;continue
                lat=math.radians((previous['lat']+fix['lat'])/2)
                north=math.radians(fix['lat']-previous['lat'])*6371000
                east=math.radians(fix['lon']-previous['lon'])*6371000*math.cos(lat)
                total=math.hypot(north,east)/dt*3.6
                shore=-(east*math.sin(math.radians(sea))+north*math.cos(math.radians(sea)))/dt*3.6
                if total>PARAMETERS['jump_limit_kmh']:
                    finish();run=None;previous=None;continue
                if shore>args.speed and shore>0:
                    if run is None:
                        run=dict(start=previous['time'],start_sender=previous['sender'],end=fix['time'],duration=0,minimum=shore,maximum=shore,qualifies_at=None,qualifies_epoch=None,mode=r.get('positioningMode','-'))
                    run.update(end=fix['time'],duration=(fix['sender']-run['start_sender'])/1000,minimum=min(run['minimum'],shore),maximum=max(run['maximum'],shore))
                    if run['qualifies_at'] is None and run['duration']>=args.duration:
                        run['qualifies_at']=fix['time'];run['qualifies_epoch']=fix['epoch']
                        run['direct_m']=aim.get('distanceM');run['cycle_id']=r.get('cycleId')
                else:
                    finish();run=None
            previous=fix
        finish()
        for row in output[output_start:]:
            o=orientations.get((row['session'],row['qualification_cycle_id']),{})
            row['actual_gimbal_pitch_deg']=o.get('gimbalPitchDeg') if o.get('gimbalAttitudeFresh') else None
            row['gimbal_age_ms']=o.get('gimbalAttitudeAgeMs')
            trigger_rows=potential_retreats if args.retreat_distance is not None else retreats
            if args.retreat_distance is not None:
                candidates=[r for r in trigger_rows if r.get('session')==row['session'] and 0<=row['qualified_epoch_ms']-r['epochMs']<=args.retreat_window*1000]
                retreat=min(candidates,key=lambda r:r['epochMs']) if candidates else None
            else:
                candidates=[r for r in trigger_rows if r.get('session')==row['session'] and abs(r['epochMs']-row['qualified_epoch_ms'])<=args.retreat_window*1000]
                retreat=min(candidates,key=lambda r:(abs(r['epochMs']-row['qualified_epoch_ms']),r['epochMs'])) if candidates else None
            row['closest_retreat_timestamp']=retreat['timestamp'] if retreat else None
            row['retreat_direct_m']=retreat.get('distanceM') if retreat else None
            row['retreat_delta_s']=(retreat['epochMs']-row['qualified_epoch_ms'])/1000 if retreat else None
            row['retreat_mp4'],row['retreat_offset']=media(retreat['epochMs']) if retreat else ('-','-')
    output.sort(key=lambda r:r['qualified'])
    def number(v):return '-' if v is None else f'{v:.2f}'
    retreat_label='Potential retreat' if args.retreat_distance is not None else 'Closest retreat'
    headers=['#','Interval start','Qualified at','Interval end','Duration s','Min km/h','Max km/h','Direct m','Actual pitch deg','Gimbal age ms','Mode','MP4','Offset at qualification',retreat_label,'Retreat direct m','Delta s','Retreat MP4','Retreat offset']
    rows=[[str(i),r['start'][11:23],r['qualified'][11:23],r['end'][11:23],f"{r['duration_s']:.3f}",str(r['minimum_shore_kmh']),str(r['maximum_shore_kmh']),number(r['direct_separation_m']),number(r['actual_gimbal_pitch_deg']),str(r['gimbal_age_ms']),r['mode'],r['mp4'],r['offset'],r['closest_retreat_timestamp'][11:23] if r['closest_retreat_timestamp'] else '-',number(r['retreat_direct_m']),f"{r['retreat_delta_s']:+.3f}" if r['retreat_delta_s'] is not None else '-',r['retreat_mp4'],r['retreat_offset']] for i,r in enumerate(output,1)]
    widths=[max([len(h)]+[len(row[i]) for row in rows]) for i,h in enumerate(headers)]
    print(' | '.join(h.ljust(w) for h,w in zip(headers,widths)));print('-+-'.join('-'*w for w in widths))
    for row in rows:print(' | '.join(v.ljust(w) for v,w in zip(row,widths)))
    print(f'\n{len(rows)} intervals: shoreward component > {args.speed:g} km/h for >= {args.duration:g} s.')
    print('Speed uses each consecutive GPS pair and sender-clock dt; duration sums consecutive qualifying steps.')
    print('Zero, seaward or below-threshold steps break the interval. Gaps, stale fixes, clock/session resets and >40 km/h total jumps also break it.')
    print('Qualified at = when controller first had enough evidence. Duration is GPS-sampled evidence, not proof of continuous speed between fixes.')
    print('Direct separation and measured gimbal pitch are from the qualification cycle, not interval end. Direct separation is GPS-derived horizontal distance; unavailable/stale pitch = -.')
    print('MP4 offsets use movie creation time and remain provisional. No VT ride detection or acceleration requirement.')
    print('Delta = retreat timestamp minus qualification (+ after, - before); no match = -. Matches stay within the same log/session.')
    if args.retreat_distance is not None:
        print(f'Potential retreat: earliest fresh active sample with direct distance <{args.retreat_distance:g} m within the {args.retreat_window:g} seconds before Qualified at. Intervening crossings do not exclude earlier samples. --retreat-tolerance is ignored.')
        print('These are distance-only candidates, not recorded starts or a full controller simulation: cooldown, boundary and other guards are not applied. Earlier retreat would change the subsequent drone path.')
    else:print(f'Actual retreat matching: nearest start within +/-{args.retreat_window:g} seconds of Qualified at.')
    if args.csv:
        import csv
        with Path(args.csv).open('w',newline='',encoding='utf-8') as f:
            fields=list(output[0]) if output else ['start','qualified','end','duration_s','minimum_shore_kmh','maximum_shore_kmh','mode','mp4','offset','source','session']
            w=csv.DictWriter(f,fieldnames=fields);w.writeheader();w.writerows(output)


if __name__=='__main__':main()
