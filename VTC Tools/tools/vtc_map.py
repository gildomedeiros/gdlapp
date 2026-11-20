"""VTC Tools: read-only shore-camera log map, served locally with stdlib only."""
import argparse
from bisect import bisect_right
from collections import Counter, OrderedDict
import csv
from datetime import datetime, timedelta, timezone
import io
import json
import math
from pathlib import Path
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading
from urllib.parse import urlsplit
import uuid
import xml.etree.ElementTree as ET
from vt_geometry import travel_bearing, offset_point
from vtc_telemetry import Telemetry,extra_lines,diagnostic,DIAGNOSTIC_EVENTS,ZOOM_EVENTS,is_diagnostic
from vtc_activity import EVENTS as ACTIVITY_EVENTS

ROOT=Path(__file__).resolve().parent.parent
VERSION='0.4.0'
TZ=timezone(timedelta(hours=10))
RELEVANT={'session_start','settings_snapshot','status_snapshot','gps_accepted','gps_invalid',
          'gps_socket_closed','disconnected','aim_cycle','continuous_decision','continuous_rates',
          'goto_captured','goto_progress','goto_completed','goto_cancelled','calibration_success',
          'aim_start','aim_paused','gps_pause','gps_resumed','stop_requested','stop_confirmed','stop_unconfirmed'}
RELEVANT|=DIAGNOSTIC_EVENTS|ZOOM_EVENTS|{'framing_observation','calibration_cleared','session_end'}
RELEVANT|=ACTIVITY_EVENTS


def number(value):
    if isinstance(value,bool):return None
    try:value=float(value)
    except (TypeError,ValueError):return None
    return value if math.isfinite(value) else None


def coords(lat,lon):
    lat,lon=number(lat),number(lon)
    return (lat,lon) if lat is not None and lon is not None and abs(lat)<=90 and abs(lon)<=180 else None


def distance(a,b):
    p,q=math.radians(a[0]),math.radians(b[0]);d=math.radians(b[1]-a[1])
    h=math.sin((q-p)/2)**2+math.cos(p)*math.cos(q)*math.sin(d/2)**2
    return 12742000*math.asin(math.sqrt(max(0,min(1,h))))


def clock(ms):
    return datetime.fromtimestamp(ms/1000,TZ).isoformat(timespec='milliseconds')


def event_time(row):
    value=number(row.get('epochMs'))
    if value is not None:return value
    try:
        value=datetime.fromisoformat(str(row.get('timestamp','')).replace('Z','+00:00'))
        return (value.replace(tzinfo=TZ) if value.tzinfo is None else value).timestamp()*1000
    except ValueError:return None


def fmt(value,unit='',digits=2):
    n=number(value)
    return f'{n:.{digits}f}{unit}' if n is not None else 'unavailable'


def heading_error(target,heading):
    return (target-heading+180)%360-180


def aim_metres(p,heading,tilt):
    """Intersect pointing with the vertical plane through the surfer, normal
    to the camera-to-surfer ground bearing. Positive = right / above surfer."""
    d=number(p.get('distance'));bearing=number(p.get('bearing'));h=number(heading);pitch=number(tilt)
    settings=p['settings'];height=number(settings.get('height'));target=number(settings.get('targetHeight'))
    if d is None or bearing is None or h is None:return None,None
    delta=heading_error(h,bearing)
    if abs(delta)>=89.9:return None,None
    depth=d/math.cos(math.radians(delta))
    pan=d*math.tan(math.radians(delta))
    vertical=height-target+depth*math.tan(math.radians(pitch)) if height is not None and target is not None and pitch is not None and abs(pitch)<89.9 else None
    return pan,vertical


def relative_metres(value,positive,negative):
    if value is None:return 'unavailable'
    if abs(value)<.005:return '0.00 m — centred on surfer'
    return f'{abs(value):.2f} m '+(positive if value>0 else negative)+' of surfer' if positive=='right' else f'{abs(value):.2f} m '+(positive if value>0 else negative)+' surfer'


def detail_lines(p):
    camera=p.get('camera');pos=p.get('position') or {};aim=p.get('aim') or {}
    framing=p.get('framing') or {};decision=p.get('decision') or {};settings=p['settings'];gps=p['gps']
    actual_pan,actual_tilt=aim_metres(p,pos.get('headingDegrees'),pos.get('tiltDegrees'))
    fresh=number(p.get('aimAgeMs')) is not None and p['aimAgeMs']<=(number(settings.get('gpsAge')) or 2)*1000
    target_pan,target_tilt=aim_metres(p,aim.get('targetHeadingDegrees'),aim.get('targetTiltDegrees')) if fresh else (None,None)
    result=[p['id']+' · GPS #'+str(gps.get('sequence','?'))+' · '+p['timestamp'],
        'Surfer: '+str(p['latitude'])+', '+str(p['longitude']),
        'Camera: '+(', '.join(map(str,camera)) if camera else 'unavailable'),
        'Surfer since previous packet: '+fmt(p.get('travel'),' m',1)+' · '+fmt(p.get('speed'),' km/h',1),
        'Actual pan error: '+relative_metres(actual_pan,'right','left'),
        'Actual tilt error: '+relative_metres(actual_tilt,'above','below'),
        'Camera position report age: '+fmt(p.get('positionAgeMs'),' ms',0),
        'Aim: '+str(p.get('state','unknown'))+' · '+str(p.get('message','')),
        'Pan target: '+relative_metres(target_pan,'right','left'),
        'Tilt target: '+relative_metres(target_tilt,'above','below'),
        'Framing: '+str(framing.get('mode','unknown'))+' · '+str(framing.get('reason','unknown')),
        'Applied offset: '+fmt(p.get('appliedOffsetMetres'),' m')+' '+p.get('bracket','')+' · magnitude '+fmt(p.get('magnitudeMetres'),' m'),
        'Sideways / estimated frame width: '+fmt(p.get('sideways'),' m',1)+' / '+fmt(p.get('zoomFrameWidth'),' m',1),
        'Surfer–base distance: '+fmt(p.get('distance'),' m',1)+' horizontal',
        'Pan / tilt rates: '+fmt(decision.get('panRateDegreesPerSecond'),'°/s')+' / '+fmt(decision.get('tiltRateDegreesPerSecond'),'°/s'),
        'Reversal blocked: pan '+str(decision.get('panReversalBlocked','unknown'))+' · tilt '+str(decision.get('tiltReversalBlocked','unknown')),
        'LoRa: RSSI '+fmt(gps.get('rssi'),' dBm',0)+' · SNR '+fmt(gps.get('snr'),' dB',1)+' · satellites '+str(gps.get('satellites','?')),
        'GPS continuity: '+str(p.get('continuity','unknown'))+' · source line '+str(p['sourceLine']),
        'Source: '+p['source']]
    if p.get('videoReferences'):
        result[0]=p['id']+' · GPS #'+str(gps.get('sequence','?'))+' · '+' '.join(ref['label'] for ref in p['videoReferences'])
        result.insert(1,'Log timestamp: '+p['timestamp'])
    if p.get('goto'):result.append('Saved GoTo: '+json.dumps(p['goto'],ensure_ascii=False))
    if p.get('aimAgeMs') is not None:result.append('Latest earlier aim_cycle age: '+fmt(p['aimAgeMs'],' ms',0))
    telemetry_lines=extra_lines(p)
    priority=('Wave ride:','GoTo:','Continuous pan:','Last GoTo activation:')
    index=next(i for i,line in enumerate(result) if line.startswith('Aim:'))+1
    result[index:index]=[line for line in telemetry_lines if line.startswith(priority)]
    result.extend(line for line in telemetry_lines if not line.startswith(priority))
    result.append('Heading/tilt are calibration and motor-count estimates. Configured-HFOV width uses lens/target heights and tilt; zero roll assumed.')
    return result


def load_file(path):
    """Only real accepted fixes create points. Context is joined causally by time/line."""
    events=[];warnings=[];counts=Counter();diagnostics=[];telemetry=Telemetry()
    with path.open(encoding='utf-8-sig') as stream:
        for line_no,line in enumerate(stream,1):
            if not line.strip():continue
            try:r=json.loads(line)
            except ValueError:
                warnings.append(f'{path.name}:{line_no}: malformed JSON skipped');continue
            if not isinstance(r,dict):continue
            counts[r.get('event','unknown')]+=1
            if r.get('event') not in RELEVANT and not is_diagnostic(str(r.get('event',''))):continue
            t=event_time(r)
            if t is None:
                warnings.append(f'{path.name}:{line_no}: event has no valid timestamp');continue
            events.append((t,line_no,r))
    events.sort(key=lambda item:(item[0],item[1]))
    settings={};status={};aim={};decision={};framing={};goto=None
    pos=None;pos_at=None;aim_at=None;state='unknown';message='';previous=None
    version='unknown';segment=0;continuity=None;points=[];last_context=None
    for t,line,r in events:
        event=r['event'];rawmono=number(r.get('monoMs'))
        if event=='session_start':
            telemetry.reset()
            settings={};status={};aim={};decision={};framing={};goto=None;pos=None;pos_at=None;aim_at=None;previous=None;segment+=1
            version=str(r.get('version','unknown'));state='unknown';message=''
        if event in ('settings_snapshot','status_snapshot'):
            incoming=r.get('settings')
            if isinstance(incoming,dict):
                before=coords(settings.get('latitude'),settings.get('longitude'))
                settings=dict(incoming)
                if before!=coords(settings.get('latitude'),settings.get('longitude')):previous=None;segment+=1
        if event=='status_snapshot':
            status=r;state=r.get('state','unknown');message=r.get('message','')
            if isinstance(r.get('framing'),dict):framing=dict(r['framing'])
            goto=r.get('savedGoto')
            if r.get('positionFresh') is not False and isinstance(r.get('position'),dict):
                pos=dict(r['position']);sample=number(r.get('positionSampleMonoMs'))
                pos_at=t-max(0,rawmono-sample) if sample is not None and sample>0 and rawmono is not None and rawmono>=sample else t
            else:pos=None;pos_at=None
        if event in ('gps_invalid','gps_socket_closed','disconnected'):
            previous=None;segment+=1
            if event=='disconnected':pos=None;pos_at=None;aim={};aim_at=None;decision={};state='DISCONNECTED';framing={}
        if event in ('stop_requested','stop_unconfirmed','aim_paused','gps_pause','session_end'):
            state=event.upper();message=r.get('reason',r.get('detail',''))
        if event=='continuous_decision':decision=dict(r)
        if event=='aim_cycle':
            aim=dict(r);aim_at=t
            if isinstance(r.get('framing'),dict):framing=dict(r['framing'])
        if event=='framing_observation' and isinstance(r.get('framing'),dict):framing=dict(r['framing'])
        if event in ('calibration_cleared','session_end'):
            pos=None;pos_at=None;aim={};aim_at=None;decision={}
        telemetry.observe(event,t,r)
        if is_diagnostic(event):diagnostics.append(diagnostic(r,t,line,clock))
        if event in ('goto_captured','goto_progress'):goto=r.get('target')
        if event in ('goto_completed','goto_cancelled'):goto=None
        if event!='gps_accepted':continue
        gps=r.get('telemetry') or {};coordinate=coords(gps.get('latitude'),gps.get('longitude'))
        if not coordinate:
            warnings.append(f'{path.name}:{line}: invalid accepted GPS skipped');previous=None;segment+=1;continue
        if continuity is not None and r.get('continuity')!=continuity:previous=None;segment+=1
        continuity=r.get('continuity')
        camera=coords(settings.get('latitude'),settings.get('longitude'))
        landmark=coords(settings.get('landmarkLat'),settings.get('landmarkLon'))
        max_age=number(settings.get('gpsAge')) or 2
        age=t-pos_at if pos_at is not None else None
        fresh_pos=pos if age is not None and 0<=age<=max_age*1000 else None
        range_m=distance(camera,coordinate) if camera else None
        bearing=travel_bearing(*camera,*coordinate) if camera and range_m>.01 else None
        travel=speed=travel_bearing_deg=pan_movement=None
        if previous and 0<t-previous['timeMs']<=max_age*1000:
            travel=distance((previous['latitude'],previous['longitude']),coordinate)
            speed=travel/(t-previous['timeMs'])*3600
            if travel>.01:travel_bearing_deg=travel_bearing(previous['latitude'],previous['longitude'],*coordinate)
            before_heading=number((previous.get('position') or {}).get('headingDegrees'))
            current_heading=number((fresh_pos or {}).get('headingDegrees'))
            if before_heading is not None and current_heading is not None:pan_movement=heading_error(current_heading,before_heading)
        elif previous:segment+=1
        offset=number(framing.get('appliedOffsetDegrees'));hfov=number(settings.get('hfov'))
        pitch=number((fresh_pos or {}).get('tiltDegrees'));height=number(settings.get('height'));target_height=number(settings.get('targetHeight'))
        sideways=range_m*abs(math.sin(math.radians(offset))) if range_m is not None and offset is not None else None
        width=None
        if all(value is not None for value in (range_m,hfov,pitch,height,target_height,offset)) and 0<hfov<180:
            depth=range_m*math.cos(math.radians(offset))*math.cos(math.radians(pitch))-(height-target_height)*math.sin(math.radians(pitch))
            if depth>0:width=2*depth*math.tan(math.radians(hfov/2))
        p=dict(id='S'+str(len(points)),timeMs=t,timestamp=clock(t),source=str(path.resolve()),sourceLine=line,version=version,
            latitude=coordinate[0],longitude=coordinate[1],gps=gps,continuity=continuity,segment=segment,
            camera=camera,landmark=landmark,settings=dict(settings),position=fresh_pos,positionAgeMs=age,
            aim=dict(aim),aimAgeMs=t-aim_at if aim_at is not None else None,decision=dict(decision),framing=dict(framing),
            goto=goto,state=state,message=message,distance=range_m,bearing=bearing,travel=travel,speed=speed,
            sideways=sideways,frameWidth=width,travelBearingDeg=travel_bearing_deg,panMovementDeg=pan_movement,
            bracket='' if offset is None else '[0S0]' if abs(offset)<.005 else '[S00]' if offset>0 else '[00S]')
        telemetry.annotate(p,t)
        p['details']=detail_lines(p);points.append(p);previous=p
    return dict(name=path.name,source=str(path.resolve()),version=version,points=points,events=dict(counts),warnings=warnings,diagnostics=diagnostics,
                first=points[0]['timestamp'] if points else None,last=points[-1]['timestamp'] if points else None,
                startMs=events[0][0] if events else None,endMs=events[-1][0] if events else None)


def parse_selection(value,points):
    value=value.strip()
    if not value:return None
    try:
        if re.fullmatch(r'\d\d:\d\d(?::\d\d(?:\.\d+)?)?',value):
            dates=sorted({p['timestamp'][:10] for p in points})
            if len(dates)!=1:raise ValueError('Use a full date/time when the selected log spans multiple dates.')
            value=dates[0]+'T'+value
        dt=datetime.fromisoformat(value.replace('Z','+00:00'))
        return (dt.replace(tzinfo=TZ) if dt.tzinfo is None else dt).timestamp()*1000
    except ValueError as error:raise ValueError('Timestamp: use HH:MM:SS or YYYY-MM-DDTHH:MM:SS+10:00. '+str(error)) from error


def select_points(points,selections):
    if not selections or not any(str(s.get('at','')).strip() for s in selections):return points
    if not isinstance(selections,list) or len(selections)>3:raise ValueError('Use up to three timestamps.')
    times=[p['timeMs'] for p in points];indices=set()
    for row in selections:
        t=parse_selection(str(row.get('at','')),points)
        if t is None:continue
        count=number(row.get('following',15))
        if count is None or count!=int(count) or not 0<=count<=10000:raise ValueError('Following packets must be a whole number from 0 to 10000.')
        start=bisect_right(times,t)-1
        if start<0:continue
        indices.update(range(start,min(start+int(count)+1,len(points))))
    return [points[i] for i in sorted(indices)]


def csv_data(points):
    fields=['id','timestamp','source','sourceLine','version','latitude','longitude','distance','speed','travel','state','positionAgeMs','sideways','frameWidth','bracket']
    out=io.StringIO(newline='');writer=csv.DictWriter(out,fieldnames=fields+['cameraLatitude','cameraLongitude','sequence','heading','tilt','appliedOffset','details'])
    extra=['aimingUnits','magnitudeMetres','magnitudeDegrees','appliedOffsetMetres','panToleranceMetres','tiltToleranceMetres','acquisitionMetres',
           'zoomMode','zoomReadbackCcapi','zoomReadbackAgeMs','opticalZoom','zoomFrameWidth','zoomRequestedCcapi','zoomConfirmedCcapi','zoomStatus','zoomDecisionAgeMs']
    writer=csv.DictWriter(out,fieldnames=writer.fieldnames+extra)
    writer.writeheader()
    for p in points:
        row={k:p.get(k) for k in fields};c=p.get('camera') or (None,None);position=p.get('position') or {}
        row.update(cameraLatitude=c[0],cameraLongitude=c[1],sequence=p['gps'].get('sequence'),heading=position.get('headingDegrees'),tilt=position.get('tiltDegrees'),appliedOffset=p['framing'].get('appliedOffsetDegrees'),details='\n'.join(p['details']))
        row.update({k:p.get(k) for k in extra})
        writer.writerow({k:("'"+v if isinstance(v,str) and v.startswith(('=','+','-','@')) else v) for k,v in row.items()})
    return ('\ufeff'+out.getvalue()).encode('utf-8')


def kml_data(points,include_references=True):
    ns='http://www.opengis.net/kml/2.2';ET.register_namespace('',ns)
    def tag(parent,name,text=None):
        el=ET.SubElement(parent,'{'+ns+'}'+name)
        if text is not None:el.text=str(text)
        return el
    root=ET.Element('{'+ns+'}kml');doc=tag(root,'Document');tag(doc,'name','VTC Tools selected positions')
    def point(name,coordinate,description,timestamp=None):
        mark=tag(doc,'Placemark');tag(mark,'name',name);tag(mark,'description',description)
        if timestamp:tag(tag(mark,'TimeStamp'),'when',timestamp)
        geometry=tag(mark,'Point');tag(geometry,'altitudeMode','clampToGround');tag(geometry,'coordinates',f'{coordinate[1]:.12f},{coordinate[0]:.12f},0')
    cameras=set();landmarks=set()
    for p in points:
        point(p['id']+' · GPS #'+str(p['gps'].get('sequence','?')),(p['latitude'],p['longitude']),'\n'.join(p['details']),p['timestamp'])
        if include_references:
            for kind,seen in (('camera',cameras),('landmark',landmarks)):
                c=p.get(kind)
                if c and tuple(c) not in seen:point(kind.title(),c,'Logged VTC '+kind+' coordinates');seen.add(tuple(c))
    return ET.tostring(root,encoding='utf-8',xml_declaration=True)


class App:
    def __init__(self,root=ROOT):
        self.root=Path(root);self.lock=threading.RLock();self.snapshots=OrderedDict();self.cache={}
        self.subtitle_jobs=OrderedDict()
        self.preferences=self.root/'exports/preferences.json'
        self.config=dict(folder='',recursive=False,history=[],selections=[dict(at='',following=15) for _ in range(3)])
        self.config['video']=dict(folder='',clockBasis='utc',correctionSeconds=0)
        from vtc_subtitles import IDS
        self.config['subtitleFields']=list(IDS)
        try:
            raw=json.loads(self.preferences.read_text(encoding='utf-8'))
            for key in self.config:
                if key in raw:self.config[key]=raw[key]
        except (OSError,ValueError):pass

    def save(self):
        self.preferences.parent.mkdir(parents=True,exist_ok=True)
        temp=self.preferences.with_suffix('.tmp');temp.write_text(json.dumps(self.config,indent=2),encoding='utf-8');temp.replace(self.preferences)

    def catalog(self,request):
        folder=Path(str(request.get('folder',''))).expanduser()
        if not str(request.get('folder','')).strip() or not folder.is_dir():raise ValueError('Enter an existing VTC log folder.')
        paths=sorted(folder.rglob('*.jsonl') if request.get('recursive') is True else folder.glob('*.jsonl'))
        # VTC's standard prefix avoids parsing VT/other application logs by mistake.
        paths=[p for p in paths if p.name.upper().startswith('VTC_')]
        if not paths:raise ValueError('No VTC_*.jsonl logs found. Export/copy the VTC JSONL logs into this folder.')
        with self.lock:
            self.files={str(i):p.resolve() for i,p in enumerate(paths)}
            self.config.update(folder=str(folder.resolve()),recursive=request.get('recursive') is True);self.save()
            return dict(files=[dict(id=k,name=v.name) for k,v in self.files.items()])

    def load(self,request):
        with self.lock:
            files=getattr(self,'files',{})
            automatic=request.get('file','auto')=='auto'
            path=files.get(str(request.get('file')))
            if not files or not automatic and path is None:raise ValueError('Refresh the VTC log folder first.')
            selections=request.get('selections',[])
            if not isinstance(selections,list) or len(selections)>3 or not all(isinstance(s,dict) for s in selections):raise ValueError('Use up to three timestamp rows.')
            from vtc_video import options,resolve
            video=options(request.get('video',self.config['video']))
            resolved=[];video_times=[]
            for i,row in enumerate(selections):
                selected_row=dict(row)
                if row.get('video'):
                    result=resolve(row,video);selected_row['at']=result['timestamp'];video_times.append(dict(row=i,**result))
                resolved.append(selected_row)
            def read(path):
                stat=path.stat();key=(str(path),stat.st_size,stat.st_mtime_ns)
                if key not in self.cache:self.cache[key]=load_file(path)
                return self.cache[key]
            if automatic:
                sessions=[read(p) for p in files.values()]
                active=[row for row in resolved if str(row.get('at','')).strip()]
                matches={};chosen={}
                for row in active:
                    all_points=[p for session in sessions for p in session['points']]
                    t=parse_selection(str(row['at']),all_points)
                    candidates=[s for s in sessions if s['startMs'] is not None and s['startMs']<=t<=s['endMs']]
                    if not candidates:raise ValueError('No VTC session covers '+clock(t)+'. Check the video metadata clock/correction or the log folder.')
                    if len(candidates)>1:raise ValueError('Overlapping VTC sessions at '+clock(t)+': '+', '.join(s['name'] for s in candidates)+'. Use the optional log override to disambiguate.')
                    session=candidates[0];matches[session['source']]=session
                    for point in select_points(session['points'],[row]):chosen[(point['source'],point['sourceLine'])]=point
                if not active:
                    matches={s['source']:s for s in sessions}
                    chosen={(p['source'],p['sourceLine']):p for s in sessions for p in s['points']}
                ordered=sorted(chosen.values(),key=lambda p:(p['timeMs'],p['source'],p['sourceLine']))
                full_ordered=sorted((p for session in matches.values() for p in session['points']),key=lambda p:(p['timeMs'],p['source'],p['sourceLine']))
                point_ids={(p['source'],p['sourceLine']):'S'+str(i) for i,p in enumerate(full_ordered)}
                points=[]
                for i,point in enumerate(ordered):
                    point=dict(point,id=point_ids[(point['source'],point['sourceLine'])]);point['details']=detail_lines(point);points.append(point)
                counts=Counter();diagnostics=[];warnings=[];full=[]
                for s in matches.values():
                    counts.update(s['events']);warnings.extend(s['warnings']);full.extend(s['points'])
                    diagnostics.extend(dict(e,source=s['source']) for e in s['diagnostics'])
                full.sort(key=lambda p:p['timeMs']);diagnostics.sort(key=lambda e:e['timeMs'])
                names=[s['name'] for s in matches.values()]
                data=dict(name='Auto: '+'; '.join(names),source='; '.join(matches),version=', '.join(sorted({s['version'] for s in matches.values()})),points=full,
                    events=dict(counts),diagnostics=diagnostics,warnings=warnings,first=full[0]['timestamp'] if full else None,last=full[-1]['timestamp'] if full else None)
            else:
                data=read(path);points=select_points(data['points'],resolved)
            # Map selected ranges back to their clip clock, including following
            # packets. Work on copies so cached logs never retain old clip labels.
            references={}
            for video_time in video_times:
                row=resolved[video_time['row']]
                for candidate in select_points(data['points'],[row]):
                    seconds=video_time['playbackSeconds']+(candidate['timeMs']-parse_selection(video_time['timestamp'],data['points']))/1000
                    if seconds<0 or video_time['durationSeconds'] is not None and seconds>video_time['durationSeconds']:continue
                    stem=Path(video_time['video']).stem;digits=re.search(r'(\d+)$',stem)
                    clip=str(int(digits[1])) if digits else stem
                    whole=int(seconds);playback=(f'{whole//3600:02d}:' if whole>=3600 else '')+f'{whole//60%60:02d}:{whole%60:02d}'
                    ref=dict(filename=video_time['video'],playbackSeconds=round(seconds,3),label=f'[{clip},{playback}]')
                    group=references.setdefault((candidate['source'],candidate['sourceLine']),[])
                    if ref not in group:group.append(ref)
            points=[dict(p,videoReferences=references.get((p['source'],p['sourceLine']),[])) for p in points]
            for point in points:point['details']=detail_lines(point)
            ident=uuid.uuid4().hex;self.snapshots[ident]=dict(data=data,points={p['id']:p for p in points})
            while len(self.snapshots)>12:self.snapshots.popitem(last=False)
            history=list(self.config.get('history',[]))
            for row in resolved:
                value=str(row.get('at','')).strip()
                if value:history=[value]+[h for h in history if h!=value]
            self.config.update(history=history[:20],selections=selections,video=video);self.save()
            return dict(id=ident,name=data['name'],version=data['version'],points=points,total=len(data['points']),events=data['events'],
                        warnings=data['warnings'][:25],history=history[:20],first=data['first'],last=data['last'],diagnostics=data['diagnostics'],videoTimes=video_times)

    def start_subtitles(self,request):
        from vtc_subtitles import validate_fields,export_video
        from vtc_video import options
        fields=validate_fields(request.get('fields'))
        video=options(request.get('video'));row=request.get('row')
        if not isinstance(row,dict) or not row.get('video'):raise ValueError('Select an MP4 to export.')
        row=dict(row)
        with self.lock:
            if any(job['status']=='running' for job in self.subtitle_jobs.values()):raise ValueError('A subtitle export is already running.')
            files=getattr(self,'files',{})
            selected=request.get('file','auto')
            if not files:raise ValueError('Refresh the VTC log folder first.')
            if selected!='auto' and selected not in files:raise ValueError('Unknown log override. Refresh logs.')
            paths=list(files.values()) if selected=='auto' else [files[selected]]
            ident=uuid.uuid4().hex;job=dict(id=ident,status='running',message='Preparing subtitles…',exports=[])
            self.subtitle_jobs[ident]=job
            while len(self.subtitle_jobs)>12:self.subtitle_jobs.popitem(last=False)
        def progress(text):
            with self.lock:job['message']=text
        def worker():
            try:
                report,files_out=export_video(paths,video,row,fields,self.root/'exports/subtitles'/ident,progress)
                with self.lock:
                    job.update(status='done',message='Subtitles ready.',report=report,files=files_out,
                        exports=[dict(name=p.name,url=f'/api/subtitles/{ident}/files/{i}') for i,p in enumerate(files_out)])
                    self.config['subtitleFields']=fields;self.save()
            except Exception as error:
                with self.lock:job.update(status='error',message=str(error))
        threading.Thread(target=worker,daemon=True).start()
        return dict(id=ident,status='running')

    def export(self,request):
        with self.lock:
            snapshot=self.snapshots.get(request.get('snapshot'))
            if snapshot is None:raise ValueError('Map expired. Show positions again.')
            if request.get('format')=='events':
                source=snapshot['data']
                return json.dumps(dict(source=source['source'],loggedVersion=source['version'],events=source['events'],diagnostics=source['diagnostics']),ensure_ascii=False,indent=2).encode('utf-8'),'json'
            ids=request.get('ids')
            if not isinstance(ids,list) or not ids or not all(isinstance(i,str) and i in snapshot['points'] for i in ids):raise ValueError('Select at least one valid map position.')
            chosen=set(ids);points=[p for k,p in snapshot['points'].items() if k in chosen]
            kind=request.get('format')
            if kind not in ('kml','csv'):raise ValueError('Choose KML or CSV.')
            return (kml_data(points,request.get('references') is not False) if kind=='kml' else csv_data(points)),kind


def create_server(app,port=0):
    class Handler(BaseHTTPRequestHandler):
        def reply(self,status,payload,kind='application/json; charset=utf-8',download=None):
            if not isinstance(payload,bytes):payload=json.dumps(payload,ensure_ascii=False,allow_nan=False).encode('utf-8')
            self.send_response(status);self.send_header('Content-Type',kind);self.send_header('Content-Length',str(len(payload)))
            self.send_header('Cache-Control','no-store');self.send_header('X-Content-Type-Options','nosniff');self.send_header('X-Frame-Options','SAMEORIGIN')
            if download:self.send_header('Content-Disposition','attachment; filename="'+download+'"')
            self.end_headers()
            try:self.wfile.write(payload)
            except (BrokenPipeError,ConnectionResetError):pass

        def allowed(self,post=False):
            origin='http://'+self.server.address
            return self.headers.get('Host')==self.server.address and self.headers.get('Origin') in ((origin,) if post else (None,origin))

        def do_GET(self):
            if not self.allowed():return self.reply(403,dict(error='Local origin required.'))
            path=urlsplit(self.path).path
            if path=='/':return self.reply(200,(app.root/'tools/vtc_map.html').read_bytes(),'text/html; charset=utf-8')
            if path=='/api/state':return self.reply(200,dict(config=app.config,version=VERSION))
            job_match=re.fullmatch(r'/api/subtitles/([a-f0-9]{32})(?:/files/(\d+))?',path)
            if job_match:
                with app.lock:
                    job=app.subtitle_jobs.get(job_match[1])
                    if job is None:return self.reply(404,dict(error='Unknown subtitle export.'))
                    if job_match[2] is None:return self.reply(200,{k:v for k,v in job.items() if k!='files'})
                    index=int(job_match[2]);files=job.get('files',[])
                    if index>=len(files):return self.reply(404,dict(error='Subtitle file unavailable.'))
                    file=files[index]
                kinds={'.srt':'application/x-subrip; charset=utf-8','.ass':'text/x-ssa; charset=utf-8','.zip':'application/zip','.json':'application/json; charset=utf-8'}
                return self.reply(200,file.read_bytes(),kinds[file.suffix],file.name.encode('ascii','replace').decode('ascii').replace('"',''))
            return self.reply(404,dict(error='Not found.'))

        def do_POST(self):
            if not self.allowed(True):return self.reply(403,dict(error='Local origin required.'))
            try:
                length=int(self.headers.get('Content-Length','0'))
                if not 0<length<=2000000:raise ValueError('Invalid request size.')
                request=json.loads(self.rfile.read(length))
                if not isinstance(request,dict):raise ValueError('Expected a request object.')
                path=urlsplit(self.path).path
                if path=='/api/files':return self.reply(200,app.catalog(request))
                if path=='/api/videos':
                    from vtc_video import catalog
                    return self.reply(200,dict(files=catalog(request.get('folder'))))
                if path=='/api/video-time':
                    from vtc_video import resolve
                    return self.reply(200,resolve(request.get('row',{}),request.get('video')))
                if path=='/api/map':return self.reply(200,app.load(request))
                if path=='/api/subtitles':return self.reply(202,app.start_subtitles(request))
                if path=='/api/export':
                    data,kind=app.export(request)
                    return self.reply(200,data,'application/vnd.google-earth.kml+xml' if kind=='kml' else 'application/json; charset=utf-8' if kind=='json' else 'text/csv; charset=utf-8',('vtc-session-events.' if kind=='json' else 'vtc-selected.')+kind)
                return self.reply(404,dict(error='Not found.'))
            except (ValueError,TypeError,OSError,KeyError) as error:return self.reply(400,dict(error=str(error)))
    server=ThreadingHTTPServer(('127.0.0.1',port),Handler);server.address=f'127.0.0.1:{server.server_port}'
    return server


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--port',type=int,default=0);parser.add_argument('--folder')
    args=parser.parse_args();app=App()
    if args.folder:app.config['folder']=args.folder
    server=create_server(app,args.port)
    print(f'VTC Tools {VERSION}: http://{server.address}/',flush=True)
    print('Open this address in your browser. Keep this window open; Ctrl+C stops the server.',flush=True)
    try:server.serve_forever()
    except KeyboardInterrupt:pass
    finally:server.server_close()


if __name__=='__main__':main()
