#!/usr/bin/env python3
"""Interactive map of actual received LoRa surfer and drone coordinates.

Start the app: python surfer_gps_map.py --serve
Choose folders, wave rides, timestamps, pitch/distance filters or framing samples
on the page, without restarting the server. Existing command-line selections
prefill and open the same app. Without --serve, export files as before:

python surfer_gps_map.py --folder C:/Users/gildo/temp/311026 --at 14:03:21.918 --next 5

Start is the last lora_packet at or before --at; --next 5 means six packets total.
No filtering by accepted status, smoothing, interpolation or speed rejection.
Use --pitch-range MIN MAX for log-first samples around wave rides; cap them
with --max-samples and use --serve for the interactive frame-capture map.
Use --all-rides --before 5 --after 20 --granularity 1 --serve for every ride.
Use --number_of_rides N instead of --all-rides for the first N logged rides.
SVG is a north-up local map, without satellite imagery. KML preserves raw lat/lon.
"""
import argparse
import bisect
import csv
from datetime import datetime
import html
import json
import math
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

from telemetry_around import offset_timezone, parse_time, record_milliseconds, load_video_timeline, offset_point, travel_bearing


def load_boundary(source, target, tz):
    latest=None
    with Path(source).open(encoding='utf-8-sig') as f:
        for line_no,line in enumerate(f,1):
            if not line.strip(): continue
            r=json.loads(line)
            if not isinstance(r,dict) or r.get('event')!='movement_cycle': continue
            t=record_milliseconds(r,tz)
            if t>target or (latest is not None and t<latest['time_ms']): continue
            latest=dict(time_ms=t,latitude=r.get('retreatBoundaryLatitude'),longitude=r.get('retreatBoundaryLongitude'),
                        seawardBearingDeg=r.get('seawardBearingDeg'),sourceLine=line_no,sourceFile=source,
                        timestamp=datetime.fromtimestamp(t/1000,tz).isoformat(timespec='milliseconds'))
    if latest is None or not all(isinstance(latest[k],(int,float)) and math.isfinite(latest[k]) for k in ('latitude','longitude','seawardBearingDeg')):
        print('Boundary unavailable at requested time; no boundary line drawn.',file=sys.stderr)
        return None
    latest['lineBearingDeg']=(latest['seawardBearingDeg']+90)%180
    return latest


def add_video_playback(points, folder, tz, timeline_data=None):
    timelines, errors = timeline_data if timeline_data is not None else load_video_timeline(folder, tz)
    for error in errors:
        print('Video mapping: ' + error, file=sys.stderr)
    for p in points:
        matches=[]
        for ident,times,positions in timelines:
            if not times[0] <= p['time_ms'] <= times[-1]+40:
                continue
            i=min(bisect.bisect_left(times,p['time_ms']),len(times)-1)
            if i and abs(times[i-1]-p['time_ms']) < abs(times[i]-p['time_ms']):
                i-=1
            matches.append((ident,times[i],positions[i]))
        p.update(fileIdPlayback='not covered', videoFrameTimestamp=None, videoFrameOffsetMs=None)
        if len(matches)>1:
            p['fileIdPlayback']='ambiguous video coverage'
        elif matches:
            ident,t,seconds=matches[0]
            total=int(seconds)
            p.update(fileIdPlayback=f'{ident}_{total//60}_{total%60:02}',
                     videoFrameTimestamp=datetime.fromtimestamp(t/1000,tz).isoformat(timespec='milliseconds'),
                     videoFrameOffsetMs=round(t-p['time_ms'],3), videoId=ident, videoPlaybackSeconds=seconds)


def load_packets(folder, recursive, tz):
    packets = []
    drones = {}
    retreat_starts = {}
    retreat_states = {}
    gimbal_states = {}
    gimbal_motion = {}
    gimbal_configs = {}
    first_wave_ride_ms = None
    wave_ride_starts = {}
    ride_states = {}
    seaward_states = {}
    distance_settings = {'minimumDistanceMetres': {}, 'filmingSeparationMetres': {}}
    paths = sorted(folder.rglob('*.jsonl') if recursive else folder.glob('*.jsonl'))
    for path in paths:
        source = str(path.resolve())
        print(f'Reading {path.name}', flush=True)
        with path.open(encoding='utf-8-sig') as f:
            for line_no, line in enumerate(f, 1):
                if not line.strip():
                    continue
                try:
                    r = json.loads(line)
                except ValueError as error:
                    raise ValueError(f'Invalid JSON: {path}:{line_no}') from error
                if not isinstance(r, dict):
                    continue
                if r.get('event') not in ('lora_packet', 'retreat_started', 'retreat_cycle', 'orientation_cycle', 'gimbal_pitch_cycle', 'movement_cycle', 'gimbal_band_config', 'session_start', 'session_end') and not ('aircraftLatitude' in r and 'aircraftLongitude' in r):
                    continue
                t = record_milliseconds(r, tz)
                if r.get('event') in ('movement_cycle', 'session_start', 'session_end'):
                    riding=r.get('riding') if r.get('event')=='movement_cycle' and isinstance(r.get('riding'),bool) else None
                    ride_states.setdefault(source,[]).append((t,line_no,riding,r.get('session')))
                    seaward=r.get('seawardBearingDeg') if r.get('event')=='movement_cycle' else None
                    if not isinstance(seaward,(int,float)) or isinstance(seaward,bool) or not math.isfinite(seaward):seaward=None
                    seaward_states.setdefault(source,[]).append((t,line_no,seaward,r.get('session')))
                if r.get('event') == 'movement_cycle' and r.get('rideEvent') == 'ride_started':
                    first_wave_ride_ms=t if first_wave_ride_ms is None else min(first_wave_ride_ms,t)
                    wave_ride_starts.setdefault(source,set()).add(t)
                for event, raw, field in [('retreat_cycle','minimumDistanceM','minimumDistanceMetres'), ('movement_cycle','filmingDistanceM','filmingSeparationMetres')]:
                    if r.get('event') == event:
                        value=r.get(raw)
                        if not isinstance(value,(int,float)) or isinstance(value,bool) or not math.isfinite(value): value=None
                        distance_settings[field].setdefault(source,[]).append((t,line_no,value))
                if r.get('event') == 'gimbal_band_config':
                    config=r.get('effectiveJson')
                    try:
                        config=json.loads(config) if isinstance(config,str) else config
                    except ValueError:
                        config=None
                    gimbal_configs.setdefault(source,[]).append((t,line_no,config if isinstance(config,dict) else None))
                if r.get('event') == 'gimbal_pitch_cycle':
                    gimbal_motion.setdefault(source,[]).append((t,line_no,r))
                if r.get('event') == 'orientation_cycle':
                    pitch=r.get('gimbalPitchDeg')
                    gimbal_states.setdefault(source,[]).append((t,line_no,pitch if isinstance(pitch,(int,float)) and not isinstance(pitch,bool) and math.isfinite(pitch) else None,r.get('heightAboveTakeoffM')))
                if r.get('event') == 'retreat_started':
                    retreat_starts.setdefault(source, []).append(t)
                if r.get('event') == 'retreat_cycle' and isinstance(r.get('active'),bool):
                    retreat_states.setdefault(source, []).append((t,line_no,r['active']))
                lat, lon = r.get('aircraftLatitude'), r.get('aircraftLongitude')
                if all(isinstance(v, (int,float)) and not isinstance(v,bool) and math.isfinite(v) for v in (lat,lon)) and -90<=lat<=90 and -180<=lon<=180:
                    drones.setdefault(source, []).append(dict(time_ms=t, latitude=lat, longitude=lon,
                        timestamp=datetime.fromtimestamp(t/1000,tz).isoformat(timespec='milliseconds'),
                        age=r.get('aircraftAgeMs'), fix=r.get('aircraftFixMs'), sourceLine=line_no))
                if r.get('event') != 'lora_packet':
                    continue
                packets.append(dict(time_ms=t, receivedTimestamp=datetime.fromtimestamp(t/1000, tz).isoformat(timespec='milliseconds'),
                                    sequence=r.get('sequence'), latitude=r.get('latitude'), longitude=r.get('longitude'),
                                    accepted=r.get('accepted'), senderMs=r.get('senderMs'), session=r.get('session'),
                                    sourceFile=source, sourceLine=line_no))
    packets.sort(key=lambda p: (p['time_ms'], p['sourceFile'], p['sourceLine']))
    wave_ride_starts={source:sorted(times) for source,times in wave_ride_starts.items()}
    for packet in packets:
        packet['firstWaveRideTimeMs']=first_wave_ride_ms
        packet['waveRideStartTimesMs']=wave_ride_starts.get(packet['sourceFile'],[])
    for field, sources in distance_settings.items():
        for rows in sources.values(): rows.sort()
        times={source:[row[0] for row in rows] for source,rows in sources.items()}
        for packet in packets:
            source=packet['sourceFile']
            i=bisect.bisect_right(times.get(source,[]),packet['time_ms'])-1
            packet[field]=sources[source][i][2] if i>=0 else None
    mark_retreat_starts(packets, retreat_starts, tz)
    mark_retreat_active(packets, retreat_states, tz)
    mark_ride_active(packets, ride_states, tz)
    mark_seaward_bearing(packets, seaward_states)
    for source,states in gimbal_states.items():
        states.sort()
    gimbal_times={source:[s[0] for s in states] for source,states in gimbal_states.items()}
    for rows in gimbal_motion.values():
        rows.sort(key=lambda row: (row[0],row[1]))
    motion_times={source:[r[0] for r in rows] for source,rows in gimbal_motion.items()}
    for rows in gimbal_configs.values(): rows.sort(key=lambda row:(row[0],row[1]))
    config_times={source:[r[0] for r in rows] for source,rows in gimbal_configs.items()}
    for p in packets:
        configs=gimbal_configs.get(p['sourceFile'],[])
        ci=bisect.bisect_right(config_times.get(p['sourceFile'],[]),p['time_ms'])-1
        p['gimbalBandConfig']=configs[ci][2] if ci>=0 else None
        rows=gimbal_motion.get(p['sourceFile'],[])
        j=bisect.bisect_right(motion_times.get(p['sourceFile'],[]),p['time_ms'])-1
        p['gimbalActiveBand']=rows[j][2].get('activeBand') if j>=0 else None
        p['gimbalTilting']=None
        p['gimbalTargetPitchDeg']=None
        if j>=0 and p['time_ms']-rows[j][0]<=1000:
            status=rows[j][2]
            p['gimbalTargetPitchDeg']=status.get('targetPitchDeg')
            if status.get('pitchFresh') is True and isinstance(status.get('targetReached'),bool):
                p['gimbalTilting']=status['targetReached'] is False and status.get('commandResult')=='accepted'
        states=gimbal_states.get(p['sourceFile'],[])
        i=bisect.bisect_right(gimbal_times.get(p['sourceFile'],[]),p['time_ms'])-1
        p['heightAboveTakeoffM']=states[i][3] if i>=0 else None
        p['gimbalTiltDeg']=states[i][2] if i>=0 else None
        p['gimbalReportedTimestamp']=datetime.fromtimestamp(states[i][0]/1000,tz).isoformat(timespec='milliseconds') if i>=0 else None
    return packets, drones


def mark_ride_active(packets, states, tz):
    """Latest logged detector state at GPS receipt, within the same log/session.

    Use line order to break equal timestamps. A cycle recorded after a packet
    must not make that earlier packet appear to be riding. Missing state and
    session boundaries remain unknown, rather than guessing a ride duration.
    """
    indexed={source:sorted(rows,key=lambda row:(row[0],row[1])) for source,rows in states.items()}
    keys={source:[(row[0],row[1]) for row in rows] for source,rows in indexed.items()}
    for p in packets:
        source=p['sourceFile'];rows=indexed.get(source,[])
        i=bisect.bisect_right(keys.get(source,[]),(p['time_ms'],p.get('sourceLine',math.inf)))-1
        row=rows[i] if i>=0 else None
        if row is not None and p.get('session') is not None and row[3] is not None and p['session']!=row[3]:row=None
        p['waveRiding']=row[2] if row is not None else None
        p['rideStateTimestamp']=datetime.fromtimestamp(row[0]/1000,tz).isoformat(timespec='milliseconds') if row is not None else None
        p['rideStateSourceLine']=row[1] if row is not None else None


def mark_seaward_bearing(packets, states):
    """Attach the logged sea direction at receipt, without borrowing future state."""
    indexed={source:sorted(rows,key=lambda row:(row[0],row[1])) for source,rows in states.items()}
    keys={source:[(row[0],row[1]) for row in rows] for source,rows in indexed.items()}
    for p in packets:
        source=p['sourceFile'];rows=indexed.get(source,[])
        i=bisect.bisect_right(keys.get(source,[]),(p['time_ms'],p.get('sourceLine',math.inf)))-1
        row=rows[i] if i>=0 else None
        if row is not None and p.get('session') is not None and row[3] is not None and p['session']!=row[3]:row=None
        p['seawardBearingDeg']=row[2] if row is not None else None


def ride_text(p):
    active=p.get('waveRiding')
    return 'Ride detected: '+('YES' if active is True else 'NO' if active is False else 'unknown')


def mark_retreat_active(packets, states, tz):
    indexed={source:sorted(rows) for source,rows in states.items()}
    times={source:[r[0] for r in rows] for source,rows in indexed.items()}
    for p in packets:
        source=p['sourceFile']; rows=indexed.get(source,[])
        i=bisect.bisect_right(times.get(source,[]),p['time_ms'])-1
        p['retreatActive']=rows[i][2] if i>=0 else None
        p['retreatStateTimestamp']=datetime.fromtimestamp(rows[i][0]/1000,tz).isoformat(timespec='milliseconds') if i>=0 else None


def mark_retreat_starts(packets, starts, tz):
    starts = {source: sorted(times) for source,times in starts.items()}
    previous = {}
    for p in packets:
        source=p['sourceFile']; t=p['time_ms']; before=previous.get(source)
        times=starts.get(source, [])
        # First packet has no known preceding receipt interval; do not attach old events.
        hits=times[bisect.bisect_right(times,before):bisect.bisect_right(times,t)] if before is not None else []
        p['retreatStartedSincePreviousPacket'] = bool(hits) if before is not None else None
        p['retreatStartTimestamps'] = '; '.join(datetime.fromtimestamp(x/1000,tz).isoformat(timespec='milliseconds') for x in hits)
        previous[source]=t


def pair_drones(points, drones):
    indexed = {}
    for source, rows in drones.items():
        rows.sort(key=lambda r:(r['time_ms'],r['sourceLine']))
        indexed[source] = ([r['time_ms'] for r in rows], rows)
    for n,p in enumerate(points):
        p.update(dronePoint=f'D{n}', droneLatitude=None, droneLongitude=None,
                 droneReportedTimestamp=None, droneReportLagMs=None, droneGpsAgeAtSurferReceiptMs=None,
                 droneFixMs=None, droneSourceLine=None, droneSurferDistanceMetres=None,
                 droneDisplacementMetres=None, previousDronePoint=None)
        times, rows = indexed.get(p['sourceFile'], ([], []))
        i = bisect.bisect_right(times,p['time_ms'])-1
        if i < 0:
            continue
        d=rows[i]; lag=p['time_ms']-d['time_ms']
        age=d['age']+lag if isinstance(d['age'],(int,float)) and math.isfinite(d['age']) else None
        p.update(droneLatitude=d['latitude'],droneLongitude=d['longitude'],
                 droneReportedTimestamp=d['timestamp'], droneReportLagMs=lag,
                 droneGpsAgeAtSurferReceiptMs=age, droneFixMs=d['fix'], droneSourceLine=d['sourceLine'])
        p['droneSurferDistanceMetres'] = pair_distance(p)
        if n and points[n-1]['sourceFile']==p['sourceFile'] and points[n-1].get('droneLatitude') is not None:
            prev=points[n-1]
            p['previousDronePoint']=prev['dronePoint']
            p['droneDisplacementMetres']=pair_distance(dict(latitude=prev['droneLatitude'],longitude=prev['droneLongitude'],droneLatitude=p['droneLatitude'],droneLongitude=p['droneLongitude']))


def pair_distance(p):
    if p.get('droneLatitude') is None:
        return None
    a,b=math.radians(p['latitude']),math.radians(p['droneLatitude'])
    dl=math.radians(p['droneLongitude']-p['longitude'])
    h=math.sin((b-a)/2)**2+math.cos(a)*math.cos(b)*math.sin(dl/2)**2
    return 12742000*math.asin(math.sqrt(min(1,max(0,h))))


def drone_next_bearing(points, index):
    if 'droneNextBearingDeg' in points[index]:
        return points[index]['droneNextBearingDeg']
    if index+1 >= len(points):
        return None
    a,b=points[index],points[index+1]
    if a['sourceFile'] != b['sourceFile'] or a.get('droneLatitude') is None or b.get('droneLatitude') is None:
        return None
    if (a['droneLatitude'],a['droneLongitude']) == (b['droneLatitude'],b['droneLongitude']):
        return None
    return travel_bearing(a['droneLatitude'],a['droneLongitude'],b['droneLatitude'],b['droneLongitude'])


def select_packets(packets, at, count, tz):
    if re.fullmatch(r'\d{2}:\d{2}:\d{2}(?:\.\d+)?', at):
        dates = {p['receivedTimestamp'][:10] for p in packets}
        if len(dates) != 1:
            raise ValueError('Logs cover multiple dates (or no GPS packets). Supply --at "YYYY-MM-DD HH:MM:SS.mmm".')
        at = next(iter(dates)) + ' ' + at
    target = parse_time(at, tz).timestamp() * 1000
    before = [i for i, p in enumerate(packets) if p['time_ms'] <= target]
    if not before:
        raise ValueError('No received surfer packet at or before the requested timestamp')
    i = before[-1]
    selected = packets[i:i+count+1]
    if len(selected) < count+1:
        print(f'Only {len(selected)} packets available, requested {count+1}.', file=sys.stderr)
    for n, p in enumerate(selected):
        lat, lon = p['latitude'], p['longitude']
        if not all(isinstance(v, (int, float)) and not isinstance(v, bool) and math.isfinite(v) for v in (lat, lon)) or not (-90 <= lat <= 90 and -180 <= lon <= 180):
            raise ValueError(f'Selected packet {p["sequence"]} has invalid coordinates; refusing to silently skip it')
        p['point'] = f'S{n}'
        p['secondsFromRequestedTime'] = round((p['time_ms'] - target)/1000, 3)
        p['surferNextBearingDeg'] = None
        following = packets[i+n+1] if i+n+1 < len(packets) else None
        if following is not None and following['sourceFile'] == p['sourceFile']:
            a,b=following['latitude'],following['longitude']
            if all(isinstance(v,(int,float)) and not isinstance(v,bool) and math.isfinite(v) for v in (a,b)) and -90<=a<=90 and -180<=b<=180 and (a,b)!=(lat,lon):
                p['surferNextBearingDeg']=travel_bearing(lat,lon,a,b)
        previous = packets[i+n-1] if i+n > 0 else None
        p['sincePreviousReceivedMs'] = round(p['time_ms']-previous['time_ms'], 3) if previous is not None else None
        p['surferTravelSincePreviousMetres'] = None
        p['surferSpeedSincePreviousKmh'] = None
        p['surferTravelBearingDeg'] = None
        if previous is not None and previous['sourceFile'] == p['sourceFile']:
            a,b = previous['latitude'],previous['longitude']
            if all(isinstance(v,(int,float)) and not isinstance(v,bool) and math.isfinite(v) for v in (a,b)) and -90<=a<=90 and -180<=b<=180:
                distance=pair_distance(dict(latitude=lat,longitude=lon,droneLatitude=a,droneLongitude=b))
                p['surferTravelSincePreviousMetres']=distance
                if distance > 0:
                    p['surferTravelBearingDeg']=travel_bearing(a,b,lat,lon)
                elapsed=p['time_ms']-previous['time_ms']
                if elapsed>0:
                    p['surferSpeedSincePreviousKmh']=distance/elapsed*3600
    return target, selected


def setting_text(p, key):
    value=p.get(key)
    return f'{value:g}' if isinstance(value,(int,float)) and not isinstance(value,bool) and math.isfinite(value) else 'unknown'


def retreat_text(p):
    state=p.get('retreatActive')
    return 'Retreat active: '+('YES' if state is True else 'NO' if state is False else 'unknown')+f' [{setting_text(p, "minimumDistanceMetres")}] m'


def gimbal_bands_text(p):
    config=p.get('gimbalBandConfig')
    if not isinstance(config,dict) or not isinstance(config.get('bands'),list):
        return 'Gimbal bands: unavailable'
    bands=[]
    lower=0
    for index,band in enumerate(config['bands'],1):
        if not isinstance(band,dict): continue
        upper=band.get('maxDistanceMetres'); pitch=band.get('pitchDegrees')
        if not isinstance(pitch,(int,float)) or isinstance(pitch,bool) or not math.isfinite(pitch): continue
        if upper is None:
            label=f'>{lower:g}'
        elif isinstance(upper,(int,float)) and not isinstance(upper,bool) and math.isfinite(upper):
            label=f'{lower:g}–{upper:g}'
        else: continue
        selected=' (selected)' if p.get('gimbalActiveBand')==index else ''
        bands.append(f'{label} m: {pitch:g}°'+selected)
        if upper is not None: lower=upper
    if not bands: return 'Gimbal bands: unavailable'
    # Two short rows instead of a long JSON payload.
    rows=['Gimbal bands: '+' | '.join(bands[i:i+3]) for i in range(0,len(bands),3)]
    buffer=config.get('bufferMetres')
    if isinstance(buffer,(int,float)) and not isinstance(buffer,bool) and math.isfinite(buffer):
        rows.append(f'Band buffer: {buffer:g} m')
    return '\n'.join(rows)


def gimbal_tilt_text(p):
    tilt=p.get('gimbalTiltDeg')
    text=f'Gimbal tilt: {tilt:g}°' if tilt is not None else 'Gimbal tilt: unavailable'
    if p.get('gimbalTilting') is True:
        target=p.get('gimbalTargetPitchDeg')
        text+=' — TILTING'+(f' → {target:g}°' if isinstance(target,(int,float)) else '')+' (command active)'
    return text


def make_svg(points, gap_red_ms=1000, boundary=None):
    # Projection is only for drawing. Original coordinates are unchanged in outputs.
    lat0, lon0 = points[0]['latitude'], points[0]['longitude']
    xy = [(6371000*math.radians(p['longitude']-lon0)*math.cos(math.radians(lat0)),
           6371000*math.radians(p['latitude']-lat0)) for p in points]
    drone_xy = {p['point']:(6371000*math.radians(p['droneLongitude']-lon0)*math.cos(math.radians(lat0)),
                          6371000*math.radians(p['droneLatitude']-lat0)) for p in points if p.get('droneLatitude') is not None}
    xs, ys = zip(*(xy+list(drone_xy.values())))
    span = max(max(xs)-min(xs), max(ys)-min(ys), 10) * 1.4
    cx, cy = (min(xs)+max(xs))/2, (min(ys)+max(ys))/2
    def screen(x,y): return 400+(x-cx)*680/span, 410-(y-cy)*680/span
    height=850
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" data-units-per-metre="{680/span:.12f}" data-grid-x="60" data-grid-y="70" data-grid-step="136" data-grid-columns="5" data-grid-rows="5" width="1100" height="{height}" viewBox="0 0 1100 {height}">',
           f'<rect width="1100" height="{height}" fill="#f3f7fa"/>',
           '<g font-family="Arial" fill="#17324d"><text x="25" y="30" font-size="20">Received surfer GPS — north up</text>',
           '<text x="25" y="55" font-size="13">Surfer: green; purple ring = ride detected. Drone: blue = retreat off, orange = active, grey = unknown.</text>',
           '<text x="755" y="95" font-size="18">N ↑</text>']
    for fraction in range(6):
        x=60+fraction*136
        out.append(f'<path d="M{x},70 V750 M60,{70+fraction*136} H740" stroke="#d6e0e7" fill="none"/>')
    if boundary:
        # Orientation-only inset. Its location is not a geographic boundary anchor.
        bx,by=925,170
        angle=math.radians(boundary['lineBearingDeg'])
        dx,dy=math.sin(angle)*45,-math.cos(angle)*45
        out.append('<g id="boundary-orientation"><rect x="805" y="85" width="280" height="190" rx="8" fill="white" stroke="#ccd6df"/>')
        out.append('<text x="825" y="110" font-size="14">Boundary orientation only</text>')
        out.append('<text x="1000" y="145" font-size="13">N ↑</text>')
        out.append(f'<path d="M{bx-dx:.2f},{by-dy:.2f} L{bx+dx:.2f},{by+dy:.2f}" stroke="#c52b34" stroke-width="3"/>')
        out.append(f'<text x="825" y="240" font-size="13" fill="#c52b34">{boundary["lineBearingDeg"]:.1f}° / {(boundary["lineBearingDeg"]+180):.1f}° — not a mapped position</text></g>')
    screens=[screen(x,y) for x,y in xy]
    drone_groups={}
    for p,(sx,sy) in zip(points,screens):
        if p['point'] not in drone_xy: continue
        dx,dy=screen(*drone_xy[p['point']])
        out.append(f'<path class="pair-line" data-pairs="{p["point"]}" d="M{sx:.2f},{sy:.2f} L{dx:.2f},{dy:.2f}" stroke="#386dcc" stroke-width="1.5" stroke-dasharray="6 5"/>')
        drone_groups.setdefault((dx,dy),{}).setdefault(p.get('retreatActive'),[]).append(p['point'])
    for (dx,dy),states in drone_groups.items():
        # Shared coordinates can represent different retreat states. Split the
        # icon into colour strips instead of hiding one state behind another.
        width=10/len(states)
        for j,(active,labels) in enumerate(states.items()):
            color='#e87500' if active is True else ('#2454c5' if active is False else '#888888')
            details=[]
            bearing=None
            for point_index,p in enumerate(points):
                if p['point'] not in labels: continue
                displacement=p.get('droneDisplacementMetres'); tilt=p.get('gimbalTiltDeg')
                details.append(f'{p["dronePoint"]} ↔ {p["point"]}')
                details.append(f'Displacement from {p["previousDronePoint"]}: {displacement:.2f} m' if displacement is not None else 'Displacement: unavailable (no previous paired drone position)')
                details.append(gimbal_tilt_text(p))
                details.append(f'Gimbal reported: {p.get("gimbalReportedTimestamp") or "unavailable"}')
                details.append(retreat_text(p))
                details.append(f'Video playback: {p.get("fileIdPlayback", "not mapped")}')
                bearing=drone_next_bearing(points,point_index)
                details.extend(gimbal_bands_text(p).split('\n'))
                details.append('')
            detail=html.escape('\n'.join(details),quote=True).replace('\n','&#10;')
            geo=next(p for p in points if p['point'] in labels)
            marker_label=', '.join('D'+label[1:] for label in labels)
            out.append(f'<g class="drone-marker" data-lat="{geo["droneLatitude"]}" data-lon="{geo["droneLongitude"]}" data-x="{dx}" data-y="{dy}" data-label="{marker_label}" data-pairs="{" ".join(labels)}" data-detail="{detail}" tabindex="0" aria-label="{detail}"><rect x="{dx-7+j*14/len(states):.2f}" y="{dy-7:.2f}" width="{14/len(states):.2f}" height="14" rx="1" fill="{color}" stroke="#17324d" stroke-width="0.8"/>')
            if bearing is not None:
                out.append(f'<path class="drone-direction" d="M0,-5 L3,1 L1,0 L1,5 L-1,5 L-1,0 L-3,1 Z" transform="translate({dx:.2f} {dy:.2f}) rotate({bearing:.3f})" fill="white" stroke="none"/>')
            out.append('</g>')
    groups={}
    for p, (x,y) in zip(points,screens):
        groups.setdefault((p['latitude'],p['longitude']),[]).append(p)
    for (lat,lon), ps in groups.items():
        x,y=screen(6371000*math.radians(lon-lon0)*math.cos(math.radians(lat0)),6371000*math.radians(lat-lat0))
        color='#14804a' if all(p['accepted'] is True for p in ps) else '#d96b16'
        label=', '.join(p['point'] for p in ps)
        details=[]
        red_lines=[]
        green_lines=[]
        for p in ps:
            distance=pair_distance(p)
            details.append(f'{p["point"]} ↔ {p.get("dronePoint", "?")} | [{distance:.1f}][{setting_text(p, "filmingSeparationMetres")}] m horizontal' if distance is not None else f'{p["point"]}: no prior drone GPS')
            travel=p.get('surferTravelSincePreviousMetres')
            speed=p.get('surferSpeedSincePreviousKmh')
            travel_text=f'{travel:.1f} m' if travel is not None else 'unknown'
            speed_text=f'{speed:.1f} km/h' if speed is not None else 'unknown'
            details.append(f'Surfer since previous packet: {travel_text} | {speed_text}')
            bearing=p.get('surferNextBearingDeg')
            details.append(f'Arrow direction: {bearing:.1f}° toward next received GPS' if bearing is not None else 'Arrow direction: next coordinates unchanged or unavailable')
            details.append(ride_text(p))
            state=p.get('retreatActive')
            details.append(retreat_text(p))
            tilt=p.get('gimbalTiltDeg')
            details.append(gimbal_tilt_text(p))
            if p.get('sincePreviousReceivedMs') is not None and p['sincePreviousReceivedMs'] > gap_red_ms:
                red_lines.append(len(details)+2)
            details.extend([f'Video playback: {p.get("fileIdPlayback", "not mapped")}',
                f'Packet: {p["sequence"]} | accepted: {p["accepted"]}',
                f'Received: {p["receivedTimestamp"]} (' + (f'{p["sincePreviousReceivedMs"]:g} ms after previous packet' if p.get('sincePreviousReceivedMs') is not None else 'previous receipt unavailable') + ')', f'Surfer: {lat}, {lon}',
                f'Drone: {p.get("droneLatitude")}, {p.get("droneLongitude")}', ''])
            details.extend(gimbal_bands_text(p).split('\n'))
        detail=html.escape('\n'.join(details), quote=True).replace('\n', '&#10;')
        ids=' '.join(p['point'] for p in ps)
        red_ids=','.join(map(str,red_lines))
        green_ids=','.join(map(str,green_lines))
        # Shared coordinates use the latest packet's direction; every packet's
        # individual bearing remains in hover details. No invented direction.
        bearing=ps[-1].get('surferNextBearingDeg')
        icon=(f'<path class="travel-arrow" d="M0,-9 L6,6 L0,3 L-6,6 Z" transform="translate({x:.2f} {y:.2f}) rotate({bearing:.3f})" fill="{color}" stroke="white" stroke-width="1"/>'
              if bearing is not None else f'<circle class="position-dot" cx="{x:.2f}" cy="{y:.2f}" r="5" fill="{color}"/>')
        riding_count=sum(p.get('waveRiding') is True for p in ps)
        if riding_count:
            mode='all' if riding_count==len(ps) else 'mixed'
            dash=' stroke-dasharray="4 3"' if mode=='mixed' else ''
            icon=f'<circle class="ride-ring" data-ride-state="{mode}" cx="{x:.2f}" cy="{y:.2f}" r="10.5" fill="none" stroke="#9b2dbd" stroke-width="2.5" vector-effect="non-scaling-stroke"{dash}/>'+icon
        out.append(f'<g class="surfer-marker" data-lat="{lat}" data-lon="{lon}" data-x="{x}" data-y="{y}" data-label="{label}" data-pairs="{ids}" data-detail="{detail}" data-red-lines="{red_ids}" data-green-lines="{green_ids}" tabindex="0" aria-label="{detail}"><circle cx="{x:.2f}" cy="{y:.2f}" r="12" fill="transparent"/>{icon}</g>')
    grid_metres=span/5
    side_label=f'{grid_metres:.1f}'.rstrip('0').rstrip('.')+' m'
    diagonal_label=f'{grid_metres*math.sqrt(2):.1f}'.rstrip('0').rstrip('.')+' m'
    out.append('<g id="map-scale-static" pointer-events="none"><path d="M60,614 V750 H196 M60,750 L196,614" stroke="#d7ae00" stroke-width="3" stroke-linejoin="round" fill="none"/>')
    out.append(f'<g font-size="13" fill="#111" stroke="#f3f7fa" stroke-width="3" paint-order="stroke"><text x="128" y="774" text-anchor="middle">{side_label}</text>')
    out.append(f'<text x="48" y="686" text-anchor="end">{side_label}</text><text x="128" y="669" text-anchor="middle">{diagonal_label}</text></g></g>')
    out.append('</g></svg>')
    return ''.join(out)


def write_outputs(points, target, output, tz, gap_red_ms=1000, boundary=None, selection_info=None, initial_ride='first'):
    output.mkdir(parents=True, exist_ok=True)
    svg=make_svg(points, gap_red_ms, boundary)
    (output/'surfer-gps.svg').write_text(svg,encoding='utf-8')
    fields=list(dict.fromkeys(k for p in points for k in p if k!='time_ms'))
    with (output/'surfer-gps.csv').open('w',encoding='utf-8-sig',newline='') as f:
        w=csv.DictWriter(f,fieldnames=fields,extrasaction='ignore');w.writeheader();w.writerows(points)
    table='<table><tr>'+''.join('<th>'+html.escape(k)+'</th>' for k in fields)+'</tr>'
    for p in points:
        table+='<tr>'+''.join('<td>'+html.escape(str(p.get(k)))+'</td>' for k in fields)+'</tr>'
    table+='</table>'
    controls='<div class="controls"><button id="zin">Zoom +</button><button id="zout">Zoom −</button><button id="reset">Reset</button><button id="measure" aria-pressed="false">Measure distance</button><button id="clear-measure">Clear measurement</button> Scroll to zoom · drag to pan · double-click to reset</div><p id="measurement-result" role="status" aria-live="polite">Enable Measure distance, then select two markers.</p>'
    controls+='<div class="controls"><strong>1.75 m surfer reference</strong> — drag the figure; rotate: <input id="reference-angle" type="range" min="0" max="359" value="0" aria-label="Reference surfer rotation"><output id="reference-degrees">0°</output><button id="reference-center">Move reference to view centre</button></div><p>Scale guide only: head-to-foot length is 1.75 m on the map plane, not a standing person’s footprint or a measured GPS position.</p>'
    pair_controls='<section id="ride-filters" aria-label="Map filters"><label for="ride-filter">Wave ride</label><select id="ride-filter"></select><p id="ride-window-info"></p><label for="position-filter">Surfer position</label><select id="position-filter"></select><div class="position-nav"><button id="position-prev">Previous</button><button id="position-next">Next</button></div><button id="fit-ride">Fit ride</button><p id="position-filter-status" role="status" aria-live="polite"></p></section><details open><summary>Choose positions</summary><button id="pairs-all">Show all</button><button id="pairs-none">Hide all</button><div id="pair-options">'
    pair_controls=pair_controls.replace('</section><details open>', '</section><fieldset id="map-layers"><legend>Map layers</legend><label><input id="hide-drones" type="checkbox"> Hide drone positions</label><label><input id="hide-connections" type="checkbox"> Hide connection lines</label><label><input id="hide-surfers" type="checkbox"> Hide surfer positions</label><label title="Hide seaward-pointing surfer arrows and their paired drones/lines, using the logged sea direction. Stationary or unknown directions remain visible."><input id="hide-seaward" type="checkbox"> Hide seaward positions</label><label title="Uses the speed threshold below. Hide slower surfer positions and their paired drones/lines; unknown speeds remain visible."><input id="hide-slow-speed" type="checkbox"> Hide speed below threshold</label></fieldset><fieldset id="surfer-highlights"><legend>Surfer highlights</legend><label><input id="show-ride-rings" type="checkbox" checked> <span class="ring-key ride-key" aria-hidden="true"></span> Ride detected</label><label><input id="show-speed-rings" type="checkbox"> <span class="ring-key speed-key" aria-hidden="true"></span> Speed threshold</label><label class="speed-threshold-label" for="speed-threshold">Speed ≥ <input id="speed-threshold" type="number" min="0" step="0.1" value="15" aria-label="Speed threshold (km/h)"> km/h</label><p id="speed-highlight-help" role="status">Uses GPS speed since the previous packet, as shown in the position card.</p></fieldset><p class="ride-legend">Purple: ride detected. Cyan: speed ≥ threshold. Dashed rings: mixed shared positions.</p><details open>', 1)
    for p in points:
        label=html.escape(p['point'],quote=True)
        drone=html.escape(str(p.get('dronePoint','no drone')))
        badge=' <strong class="ride-badge" title="Ride detected at this GPS receipt">RIDE</strong>' if p.get('waveRiding') is True else ''
        pair_controls+=f'<label class="pair-option" title="Ctrl + left-click this position to capture its frame"><input type="checkbox" data-pair="{label}" checked> {label} ↔ {drone}{badge}<small class="pair-metadata"></small></label>'
    pair_controls+='</div><p>Show/hide applies to the selected ride. Hover or focus a marker and press Delete to hide its shown pairs. Use checkboxes to show several positions. Ctrl + left-click a position to capture its frame.</p></details>'
    script='''<script>
const svg=document.querySelector('#map svg');
const svgNS='http://www.w3.org/2000/svg';
// App maps have immutable selection URLs; old standalone maps use the root API.
function mapApi(path){const match=location.pathname.match(/^\\/maps\\/([a-f0-9]+)\\//);return (match?'/maps/'+match[1]:'')+path;}
const measureLayer=document.createElementNS(svgNS,'g');
measureLayer.style.pointerEvents='none';svg.append(measureLayer);
let measuring=false, selected=[];
const measureButton=document.getElementById('measure');
const measureResult=document.getElementById('measurement-result');
function gpsDistance(a,b){
 const rad=Math.PI/180, lat1=Number(a.dataset.lat)*rad,lat2=Number(b.dataset.lat)*rad;
 const dl=(Number(b.dataset.lon)-Number(a.dataset.lon))*rad;
 const h=Math.sin((lat2-lat1)/2)**2+Math.cos(lat1)*Math.cos(lat2)*Math.sin(dl/2)**2;
 return 12742000*Math.asin(Math.sqrt(Math.min(1,Math.max(0,h))));
}
function clearMeasurement(){selected=[];measureLayer.replaceChildren();measureResult.textContent=measuring?'Select the first marker.':'Enable Measure distance, then select two markers.';}
function measurePick(marker){
 if(selected.length===2)clearMeasurement();
 selected.push(marker);
 const ring=document.createElementNS(svgNS,'circle');
 ring.setAttribute('cx',marker.dataset.x);ring.setAttribute('cy',marker.dataset.y);
 ring.setAttribute('r','11');ring.setAttribute('fill','none');ring.setAttribute('stroke','#b218cf');ring.setAttribute('stroke-width','3');ring.setAttribute('vector-effect','non-scaling-stroke');measureLayer.append(ring);
 if(selected.length===1){measureResult.textContent=marker.dataset.label+' selected. Select the second marker.';return;}
 const [a,b]=selected,line=document.createElementNS(svgNS,'line');
 line.setAttribute('x1',a.dataset.x);line.setAttribute('y1',a.dataset.y);line.setAttribute('x2',b.dataset.x);line.setAttribute('y2',b.dataset.y);
 line.setAttribute('stroke','#b218cf');line.setAttribute('stroke-width','3');line.setAttribute('vector-effect','non-scaling-stroke');measureLayer.prepend(line);
 measureResult.textContent=a.dataset.label+' ↔ '+b.dataset.label+': '+gpsDistance(a,b).toFixed(2)+' m horizontal GPS distance (≈ '+(gpsDistance(a,b)/1.75).toFixed(1)+' surfer lengths of 1.75 m). Select another marker to start a new measurement.';
}
measureButton.onclick=()=>{measuring=!measuring;measureButton.setAttribute('aria-pressed',String(measuring));measureButton.textContent=measuring?'Measuring: ON':'Measure distance';clearMeasurement();};
document.getElementById('clear-measure').onclick=clearMeasurement;
const tip=document.getElementById('tooltip');
let activeMarker=null;
__MAP_FILTER_SCRIPT__
svg.querySelectorAll('.surfer-marker,.drone-marker').forEach(n=>{
 const halo=document.createElementNS(svgNS,'circle');
 halo.setAttribute('cx',n.dataset.x);halo.setAttribute('cy',n.dataset.y);
 halo.setAttribute('r','12');halo.setAttribute('class','pair-halo');
 halo.setAttribute('fill','none');halo.setAttribute('stroke','#e85b16');
 halo.setAttribute('stroke-width','3');halo.setAttribute('pointer-events','none');
 n.append(halo);
});
__MAP_INTERACTION_SCRIPT__
__SIDE_VIEW_SCRIPT__
const original=svg.getAttribute('viewBox').split(' ').map(Number);
let box=original.slice(), drag=null;
const reference=document.createElementNS(svgNS,'g');
reference.id='surfer-reference';reference.style.cursor='move';
reference.setAttribute('tabindex','0');reference.setAttribute('role','img');
reference.setAttribute('aria-label','Movable 1.75 metre scale reference. Drag to move; use rotation slider.');
// Shape coordinates are metres. With the 0.05 m stroke, total length is
// exactly 1.75 m: head top -0.875 to feet bottom +0.875.
const refShape=document.createElementNS(svgNS,'g');
refShape.setAttribute('fill','#ffdf40');refShape.setAttribute('stroke','#2d2446');
refShape.setAttribute('stroke-width','.05');refShape.setAttribute('stroke-linecap','round');
refShape.setAttribute('stroke-linejoin','round');
const refHead=document.createElementNS(svgNS,'circle');
refHead.setAttribute('cx','0');refHead.setAttribute('cy','-.73');refHead.setAttribute('r','.12');
const refBody=document.createElementNS(svgNS,'path');
refBody.setAttribute('d','M-.09,-.55 L.09,-.55 L.19,-.45 L.34,.05 L.25,.09 L.13,-.22 L.12,.13 L.17,.85 L.05,.85 L0,.25 L-.05,.85 L-.17,.85 L-.12,.13 L-.13,-.22 L-.25,.09 L-.34,.05 L-.19,-.45 Z');
refShape.append(refHead,refBody);
const refHit=document.createElementNS(svgNS,'rect');
refHit.setAttribute('x','-.4');refHit.setAttribute('y','-.9');refHit.setAttribute('width','.8');refHit.setAttribute('height','1.8');refHit.setAttribute('fill','transparent');
const refTitle=document.createElementNS(svgNS,'title');refTitle.textContent='1.75 m reference only — drag to move; slider rotates';
reference.append(refTitle,refHit,refShape);svg.append(reference);
let referenceX=400,referenceY=410,referenceAngle=0,referenceDrag=null;
const unitsPerMetre=Number(svg.dataset.unitsPerMetre);
__MAP_SCALE_SCRIPT__
function drawReference(){reference.setAttribute('transform','translate('+referenceX+' '+referenceY+') rotate('+referenceAngle+') scale('+unitsPerMetre+')');}
drawReference();
document.getElementById('reference-angle').addEventListener('input',e=>{referenceAngle=Number(e.target.value);document.getElementById('reference-degrees').textContent=referenceAngle+'°';drawReference();});
document.getElementById('reference-center').onclick=()=>{referenceX=box[0]+box[2]/2;referenceY=box[1]+box[3]/2;drawReference();};
reference.addEventListener('pointerdown',e=>{if(e.button!==0)return;e.stopPropagation();clearDetails();const p=point(e);referenceDrag={x:p.x-referenceX,y:p.y-referenceY};reference.setPointerCapture(e.pointerId);});
reference.addEventListener('pointermove',e=>{if(!referenceDrag)return;e.stopPropagation();const p=point(e);referenceX=p.x-referenceDrag.x;referenceY=p.y-referenceDrag.y;drawReference();});
for(const event of ['pointerup','pointercancel','lostpointercapture'])reference.addEventListener(event,e=>{referenceDrag=null;e.stopPropagation();});
reference.addEventListener('click',e=>e.stopPropagation());
reference.addEventListener('dblclick',e=>e.stopPropagation());
function draw(){clearDetails();svg.setAttribute('viewBox',box.join(' '));updateMapScale();}
function point(event){const p=svg.createSVGPoint();p.x=event.clientX;p.y=event.clientY;return p.matrixTransform(svg.getScreenCTM().inverse());}
function zoom(f,px=box[0]+box[2]/2,py=box[1]+box[3]/2){
 const w=Math.max(original[2]/30,Math.min(original[2]*3,box[2]*f));
 const scale=w/box[2];box=[px+(box[0]-px)*scale,py+(box[1]-py)*scale,w,box[3]*scale];draw();
}
function reset(){fitRide();}
document.getElementById('zin').onclick=()=>zoom(.8);
document.getElementById('zout').onclick=()=>zoom(1.25);
document.getElementById('reset').onclick=reset;
svg.addEventListener('wheel',e=>{e.preventDefault();const p=point(e);zoom(e.deltaY<0?.85:1/.85,p.x,p.y);},{passive:false});
svg.addEventListener('pointerdown',e=>{if(e.button!==0 || (e.ctrlKey&&e.target.closest('.surfer-marker')) || (measuring&&e.target.closest('.surfer-marker,.drone-marker')))return;drag={x:e.clientX,y:e.clientY};svg.setPointerCapture(e.pointerId);});
svg.addEventListener('pointermove',e=>{if(!drag)return;
 const inv=svg.getScreenCTM().inverse();
 const dx=e.clientX-drag.x,dy=e.clientY-drag.y;
 box[0]-=inv.a*dx+inv.c*dy;box[1]-=inv.b*dx+inv.d*dy;
 drag={x:e.clientX,y:e.clientY};draw();});
svg.addEventListener('pointerup',()=>drag=null);
svg.addEventListener('pointercancel',()=>drag=null);
svg.addEventListener('lostpointercapture',()=>drag=null);
svg.addEventListener('dblclick',reset);
initializeMapFilters();
</script>'''
    from surfer_map_filters import map_filter_data
    filter_data=dict(map_filter_data(points),initialRide=initial_ride)
    filter_js=Path(__file__).with_name('surfer_map_filters.js').read_text(encoding='utf-8').replace('MAP_FILTER_DATA',json.dumps(filter_data).replace('<','\\u003c'))
    script=script.replace('__MAP_FILTER_SCRIPT__',filter_js)
    interaction_js=Path(__file__).with_name('surfer_map_interactions.js').read_text(encoding='utf-8')
    script=script.replace('__MAP_INTERACTION_SCRIPT__',interaction_js)
    scale_js=Path(__file__).with_name('surfer_map_scale.js').read_text(encoding='utf-8')
    script=script.replace('__MAP_SCALE_SCRIPT__',scale_js)
    side_data={p['point']:dict(distance=pair_distance(p),tilt=p.get('gimbalTiltDeg'),height=p.get('heightAboveTakeoffM'),time=p['receivedTimestamp'],source=p.get('sourceFile'),frame=p.get('videoFrameTimestamp'),position=p.get('personPosition'),positionError=p.get('personPositionError'),riding=p.get('waveRiding')) for p in points}
    side_js=Path(__file__).with_name('surfer_side_view.js').read_text(encoding='utf-8').replace('SIDE_DATA',json.dumps(side_data).replace('<','\\u003c'))
    script=script.replace('__SIDE_VIEW_SCRIPT__',side_js)
    heading = ('Selected samples — first: ' if selection_info else 'Requested: ') + datetime.fromtimestamp(target/1000,tz).isoformat(timespec='milliseconds')
    introduction = selection_info or 'S0 = last received packet at or before the requested time. Following points are successive received packets, including rejected packets.'
    (output/'surfer-gps.html').write_text('<!doctype html><meta charset="utf-8"><title>Received surfer GPS</title><style>body{font-family:Arial}#map{border:1px solid #ccd;overflow:hidden;background:#f3f7fa}#map svg{display:block;width:100%;height:calc(100vh - 90px);touch-action:none;cursor:grab}#map-controls{margin-bottom:8px}#map-controls>summary{cursor:pointer;padding:10px;background:#eef3f7;font-weight:bold}#map-controls[open]~#map-layout #map svg{height:70vh}button{padding:8px 14px;margin:5px}td,th{border:1px solid #ccc;padding:6px;text-align:left}table{border-collapse:collapse}.pair-line{opacity:.35}.pair-line.active-pair{opacity:1;stroke:#e85b16;stroke-width:3}.pair-halo{display:none}.active-pair>.pair-halo{display:block}.drone-marker.active-pair>rect{stroke:#e85b16;stroke-width:3}#side-view{flex:0 0 360px;padding:12px;box-sizing:border-box;border:1px solid #ccd;font-size:13px}#side-view input[type=number]{width:80px}#map-layout{display:flex;gap:12px;align-items:flex-start}#map{flex:1;min-width:0}#pair-sidebar{flex:0 0 260px;box-sizing:border-box;padding:10px;border:1px solid #ccd;background:#f8fafc}#pair-sidebar summary{cursor:pointer;font-weight:bold}#pair-sidebar button{padding:6px 10px;margin:8px 2px}#pair-sidebar p{font-size:12px;color:#46586a}.ride-badge{background:#f5e8fa;color:#8226a0;font-size:12px;font-weight:bold;padding:1px 4px;border-radius:3px}.pair-option{display:block;margin:8px 0}#pair-options{max-height:max(140px,calc(100vh - 410px));overflow:auto}.pair-option[hidden]{display:none!important}.pair-metadata{display:block;margin:3px 0 0 24px;color:#46586a;font-size:11px}#ride-filters{border-bottom:1px solid #ccd;margin-bottom:12px;padding-bottom:4px}#ride-filters label{display:block;font-size:13px;font-weight:bold;margin:0 0 6px}#ride-filters select{box-sizing:border-box;width:100%;padding:6px 3px;color:#17324d;background:white;border:1px solid #aebdca;border-radius:3px}#ride-filters .position-nav{display:flex;gap:4px}#ride-filters .position-nav button{flex:1;margin:8px 0 0}#ride-filters #fit-ride{margin-left:0}#ride-window-info{margin:7px 0 12px}#position-filter-status{margin:2px 0 8px;font-weight:bold}@media(max-width:650px){#map-layout{flex-direction:column}#side-view{flex:auto;width:100%}#map{width:100%;box-sizing:border-box}#pair-sidebar{flex:auto;width:100%}#pair-options{max-height:160px}.pair-option{display:inline-block;margin:6px 12px 6px 0}}.surfer-marker{cursor:help}.key-field{background:#fff0dc;color:#a94700;font-weight:bold;border-radius:3px;padding:0 3px;box-decoration-break:clone}#tooltip{position:fixed;z-index:10;white-space:pre-line;background:#fff;color:#17324d;border:1px solid #9baaba;border-radius:8px;padding:12px;box-shadow:0 3px 16px #0003;font-size:14px;pointer-events:auto;width:min(420px,calc(100vw - 50px));max-height:85vh;overflow:auto;box-sizing:border-box;overscroll-behavior:contain}#map-layers{border:0;border-top:1px solid #ccd;margin:8px 0 14px;padding:8px 0}#map-layers legend{font-weight:bold;font-size:13px;padding:0 4px 0 0}#map-layers label{display:block;font-size:13px;margin:7px 0}#surfer-highlights{border:0;border-top:1px solid #ccd;margin:8px 0 14px;padding:8px 0}#surfer-highlights legend{font-weight:bold;font-size:13px;padding:0 4px 0 0}#surfer-highlights label{display:block;font-size:13px;margin:7px 0}#speed-threshold{width:72px;padding:4px;box-sizing:border-box}.ring-key{display:inline-block;width:10px;height:10px;border:2px solid;border-radius:50%;vertical-align:middle}.ride-key{border-color:#9b2dbd}.speed-key{border-color:#008baa}#speed-highlight-help[data-invalid="true"]{color:#b00020}.position-card-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;white-space:normal}.position-card-heading button{margin:0;padding:4px 9px}.position-card-hint{font-size:12px;white-space:normal;color:#46586a;margin:8px 0}.position-card{white-space:pre-line;padding:8px 2px;border-top:1px solid #d9e2eb}.position-card[data-capture-point]{cursor:pointer}.position-card[data-capture-point]:hover,.position-card[data-capture-point]:focus{background:#eef5ff;outline:1px solid #9baaba}#details-capture-status{font-size:12px;white-space:normal;overflow-wrap:anywhere}</style><details id="map-controls"><summary>Map controls &amp; info — expand / collapse</summary><h2>'+html.escape(heading)+'</h2><p>'+html.escape(introduction)+' Each D is the latest prior drone report from the same log. Distances are horizontal GPS-coordinate calculations, not independent range measurements. Identical coordinates share a marker. Point labels belong only to this map.</p>'+controls+'</details><div id="map-layout"><div id="map">'+svg+'</div><aside id="pair-sidebar" aria-label="Pair visibility">'+pair_controls+'<p>Ctrl + left-click a surfer to capture its nearest frame.</p><p id="capture-status" role="status" style="overflow-wrap:anywhere"></p></aside></div><div id="tooltip" tabindex="-1" aria-label="Position details" hidden></div><details><summary>All packet details</summary>'+table+'</details>'+script,encoding='utf-8')
    ns='http://www.opengis.net/kml/2.2';ET.register_namespace('',ns)
    def e(parent,name,text=None):
        node=ET.SubElement(parent,'{'+ns+'}'+name);node.text=text;return node
    root=ET.Element('{'+ns+'}kml'); doc=e(root,'Document');e(doc,'name','Raw received surfer GPS')
    if boundary:
        e(doc,'description',f'Boundary orientation only: {boundary["lineBearingDeg"]:.1f}° / {boundary["lineBearingDeg"]+180:.1f}°. Actual boundary position is deliberately not plotted.')
        (output/'boundary.json').write_text(json.dumps(boundary,indent=2),encoding='utf-8')
    for p in points:
        mark=e(doc,'Placemark');e(mark,'name',p['point']+' · packet '+str(p['sequence']))
        e(mark,'description',json.dumps(p,indent=2));point=e(mark,'Point');e(point,'coordinates',f'{p["longitude"]},{p["latitude"]},0')
        if p.get('droneLatitude') is not None:
            mark=e(doc,'Placemark');e(mark,'name',p['dronePoint']);e(mark,'description',json.dumps(p,indent=2))
            style=e(mark,'Style');icon=e(style,'IconStyle')
            state=p.get('retreatActive')
            e(icon,'color','ff0075e8' if state is True else 'ffff5500' if state is False else 'ff888888')
            point=e(mark,'Point');e(point,'coordinates',f'{p["droneLongitude"]},{p["droneLatitude"]},0')
            mark=e(doc,'Placemark');e(mark,'name',p['point']+' — '+p['dronePoint']+f': {pair_distance(p):.1f} m')
            e(mark,'description','Dashed pairing connector only; dash endpoints are drawing geometry, not additional GPS observations.')
            style=e(mark,'Style');line=e(style,'LineStyle');e(line,'color','ffff5500');e(line,'width','2')
            multi=e(mark,'MultiGeometry')
            # KML has no portable dash style; small line segments draw the connector.
            for i in range(12):
                coords=[]
                for f in (i/12,(i+.55)/12):
                    lon=p['longitude']+(p['droneLongitude']-p['longitude'])*f
                    lat=p['latitude']+(p['droneLatitude']-p['latitude'])*f
                    coords.append(f'{lon},{lat},0')
                e(e(multi,'LineString'),'coordinates',' '.join(coords))
    ET.ElementTree(root).write(output/'surfer-gps.kml',encoding='utf-8',xml_declaration=True)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--folder',help='Log folder; can be set on the map when using --serve');p.add_argument('--at')
    p.add_argument('--next',type=int,default=5,help='Packets after the start packet (default 5 = six plotted positions)')
    p.add_argument('--output');p.add_argument('--utc-offset',default='+10:00');p.add_argument('--recursive',action='store_true')
    p.add_argument('--video-folder',default='E:/DCIM/100MEDIA',help='SRT folder for nearest video playback mapping')
    p.add_argument('--gap-red-ms',type=float,default=1000,help='Highlight receipt gaps greater than this many milliseconds (default 1000)')
    p.add_argument('--serve',action='store_true',help='Start the map app; choose folders, rides, timestamps and filters in the browser')
    p.add_argument('--auto-fov',action='store_true',help='Precompute TOP/MIDDLE/BOTTOM person position for selected frames before writing the map; reuse cached detections')
    p.add_argument('--ffmpeg',help='Optional FFmpeg executable for --serve or --auto-fov')
    ride_options=p.add_mutually_exclusive_group()
    ride_options.add_argument('--all-rides',action='store_true',help='Map every logged ride window, with no sample cap by default; supports --before/--after/--granularity and --serve. Overrides --at/--next')
    ride_options.add_argument('--number_of_rides','--number-of-rides',type=int,metavar='N',help='Map the first N logged wave rides chronologically across source logs; same before/after windows and granularity as --all-rides, with no sample cap by default')
    p.add_argument('--pitch-range','--pitch_range',nargs=2,type=float,metavar=('MIN','MAX'),help='Actual logged gimbal pitch, inclusive; sample only around wave rides. Overrides --at/--next; use --serve for the active map')
    p.add_argument('--distance-range','--distance_range',nargs=2,type=float,metavar=('MIN','MAX'),help='Horizontal surfer-drone distance in metres, inclusive; filters ride sampling or combines with --pitch-range using AND')
    p.add_argument('--max-samples',type=int,help='Maximum selected map samples, earliest first (default 50 for pitch-only sampling; no cap for --all-rides/--number_of_rides)')
    p.add_argument('--get-fov-samples',action='store_true',help='Search all logs for balanced frame-position × distance-band samples; overrides --at/--next/--auto-fov')
    p.add_argument('--sample-count',type=int,default=3,help='Samples per frame-position × distance-band combination (default 3)')
    p.add_argument('--sample-bands',type=float,nargs='+',default=[0,20,30,40],help='Increasing distance edges in metres (default 0 20 30 40)')
    p.add_argument('--sample-interval','--granularity',type=float,help='Seconds per interval for --all-rides/--number_of_rides (last packet, default 1); otherwise candidate spacing per log/pitch or distance band/FOV with tilt changes >=3 degrees retained (default 5). 0 keeps every match')
    p.add_argument('--sample-before','--before',type=float,default=5,help='Seconds before EACH logged ride start to include (default 5)')
    p.add_argument('--sample-after','--after',type=float,default=20,help='Seconds after EACH logged ride start to include (default 20)')
    p.add_argument('--sample-seed',type=int,default=0,help='Deprecated: ignored; sample scanning is chronological')
    p.add_argument('--sample-max-checks',type=int,default=0,help='Optional scan limit; 0 scans until quotas fill or candidates run out')
    a=p.parse_args()
    a.provided_options={arg.split('=')[0] for arg in sys.argv[1:] if arg.startswith('--')}
    if len(sys.argv)==1:a.serve=True
    ride_mode=a.all_rides or a.number_of_rides is not None
    if a.number_of_rides is not None and a.number_of_rides<1:p.error('--number_of_rides must be positive')
    if a.sample_interval is None:a.sample_interval=1 if ride_mode else 5
    if a.max_samples is None and not ride_mode:a.max_samples=50
    if not a.serve and not a.folder:p.error('--folder is required without --serve')
    if not a.serve and not ride_mode and not a.get_fov_samples and a.pitch_range is None and not a.at:p.error('Provide --at, --all-rides, --number_of_rides, --pitch-range or --get-fov-samples')
    if a.get_fov_samples and (a.pitch_range is not None or ride_mode):p.error('--get-fov-samples is separate from --pitch-range/--all-rides/--number_of_rides; choose one sampling mode')
    if a.pitch_range is not None and not all(math.isfinite(v) for v in a.pitch_range):p.error('--pitch-range requires two finite angles')
    if a.distance_range is not None:
        if a.pitch_range is None and not ride_mode:p.error('--distance-range requires --pitch-range, --all-rides or --number_of_rides')
        if any(not math.isfinite(v) or v<0 for v in a.distance_range):p.error('--distance-range requires two finite nonnegative distances')
    if a.max_samples is not None and a.max_samples<1:p.error('--max-samples must be positive')
    if not math.isfinite(a.sample_before) or a.sample_before<0:p.error('--sample-before must be finite and nonnegative')
    if not math.isfinite(a.sample_interval) or a.sample_interval<0:p.error('--sample-interval must be finite and nonnegative')
    if not math.isfinite(a.sample_after) or a.sample_after<0:p.error('--sample-after must be finite and nonnegative')
    if a.sample_count<1:p.error('--sample-count must be positive')
    if a.sample_max_checks<0:p.error('--sample-max-checks must be nonnegative')
    if len(a.sample_bands)<2 or any(not math.isfinite(v) or v<0 for v in a.sample_bands) or any(b<=a for a,b in zip(a.sample_bands,a.sample_bands[1:])):
        p.error('--sample-bands needs at least two nonnegative, strictly increasing edges')
    if a.next<0:p.error('--next must be nonnegative')
    if not math.isfinite(a.gap_red_ms) or a.gap_red_ms<0:p.error('--gap-red-ms must be finite and nonnegative')
    try:
        if a.serve:
            from surfer_map_app import serve_app
            serve_app(a)
            return
        tz=offset_timezone(a.utc_offset); packets,drones=load_packets(Path(a.folder),a.recursive,tz)
        if a.pitch_range is not None or ride_mode:
            from surfer_pitch_samples import run_pitch_samples, run_ride_samples
            runner=run_ride_samples if ride_mode else run_pitch_samples
            points,out,description=runner(packets,drones,a,tz)
            if not points:
                print('No matching GPS samples inside wave-ride windows; no interactive map generated.')
                return
            target=points[0]['time_ms']
            boundary=load_boundary(points[0]['sourceFile'],target,tz)
            if a.auto_fov:
                from surfer_map_capture import precompute_positions
                precompute_positions(points,out,a.video_folder,a.ffmpeg)
            write_outputs(points,target,out,tz,a.gap_red_ms,boundary,selection_info=description)
            print('Interactive ride map:' if ride_mode else 'Interactive pitch map:',(out/'surfer-gps.html').resolve())
            return
        if a.get_fov_samples:
            from surfer_fov_samples import run_samples
            points,out=run_samples(packets,drones,a,tz)
            if not points:
                print('No matching samples found; no interactive map generated.')
                return
            # Retain sample IDs and frame detections, so the console table,
            # workbook, captures and interactive map refer to the same points.
            write_outputs(points,points[0]['time_ms'],out,tz,a.gap_red_ms)
            print('Interactive sample map:',(out/'surfer-gps.html').resolve())
            return
        target,points=select_packets(packets,a.at,a.next,tz)
        pair_drones(points,drones)
        boundary=load_boundary(points[0]['sourceFile'],target,tz)
        add_video_playback(points,a.video_folder,tz)
        out=Path(a.output) if a.output else Path.cwd()/('surfer-gps-'+datetime.fromtimestamp(target/1000,tz).strftime('%Y%m%d-%H%M%S-%f'))
        if a.auto_fov:
            from surfer_map_capture import precompute_positions
            precompute_positions(points,out,a.video_folder,a.ffmpeg)
        write_outputs(points,target,out,tz,a.gap_red_ms,boundary)
        for point in points:print(point['point'],point['receivedTimestamp'],'packet',point['sequence'],point['latitude'],point['longitude'],'accepted=',point['accepted'])
        print('Map:',(out/'surfer-gps.html').resolve())
    except (ValueError,OSError) as error:p.exit(2,f'Error: {error}\n')


if __name__=='__main__':main()

