"""Causal, offline yaw-target replay. No flight commands or video inference."""
from collections import deque
from datetime import datetime, timezone
from functools import lru_cache
import csv
import io
import json
import math
from pathlib import Path
import re

DEFAULTS = dict(magnitude=5., window=1.5, minObservations=3, netChange=3.,
                consistency=.8, supportingChanges=2, supportingChange=.5,
                quietRate=1., quietSeconds=3., slewRate=2., maxGap=1.5,
                maxSensorAge=1.5, baselineOffset=0., horizontalFov=74.)
BOUNDS = dict(magnitude=(0,90), window=(.1,30), minObservations=(3,100),
              netChange=(.1,180), consistency=(.01,1), supportingChanges=(2,100),
              supportingChange=(.01,90), quietRate=(.01,30), quietSeconds=(.1,60),
              slewRate=(.01,90), maxGap=(.1,10), maxSensorAge=(.1,10), baselineOffset=(-180,180), horizontalFov=(20,120))
INTEGER_PARAMS = {'minObservations','supportingChanges'}


def finite(value):
    return isinstance(value,(int,float)) and not isinstance(value,bool) and math.isfinite(value)


def parameters(raw=None):
    if raw is None:raw={}
    if not isinstance(raw,dict) or set(raw)-set(DEFAULTS):raise ValueError('Unknown yaw simulation settings.')
    result=dict(DEFAULTS,**raw)
    for key,value in result.items():
        lo,hi=BOUNDS[key]
        if not finite(value) or not lo<=value<=hi or key in INTEGER_PARAMS and int(value)!=value:
            raise ValueError(f'{key} must be between {lo} and {hi}'+(' (whole number).' if key in INTEGER_PARAMS else '.'))
    return result


def signed(angle):
    return (angle+180)%360-180


def position(lat,lon):
    return finite(lat) and finite(lon) and -90<=lat<=90 and -180<=lon<=180


def bearing_distance(dlat,dlon,slat,slon):
    a,b=math.radians(dlat),math.radians(slat)
    delta=math.radians(slon-dlon)
    h=math.sin((b-a)/2)**2+math.cos(a)*math.cos(b)*math.sin(delta/2)**2
    distance=6371000*2*math.asin(math.sqrt(max(0,min(1,h))))
    if distance<.01:return None,distance
    bearing=math.degrees(math.atan2(math.sin(delta)*math.cos(b),math.cos(a)*math.sin(b)-math.sin(a)*math.cos(b)*math.cos(delta)))%360
    return bearing,distance


def framing_estimate(row,angle,hfov):
    """Horizontal offset and image-row width at the surfer's optical depth.

    Pinhole projection with zero camera roll; yaw is aligned to bearing+angle.
    Z = D*cos(angle)*cos(pitch) - H*sin(pitch), using downward negative pitch.
    W = 2*Z*tan(HFOV/2). H assumes the water is at takeoff elevation.
    This is NOT the whole water footprint (its width varies with image row).
    """
    distance=row.get('distance')
    if not row.get('valid') or not finite(distance):return None,None,'valid paired GPS unavailable'
    offset=abs(distance*math.sin(math.radians(angle)))
    pitch,height=row.get('gimbalPitch'),row.get('heightAboveTakeoff')
    if not finite(pitch) or not finite(height):return offset,None,'fresh camera pitch or height unavailable'
    if not -90<=pitch<=90 or height<0:return offset,None,'invalid camera pitch or height above water'
    depth=distance*math.cos(math.radians(angle))*math.cos(math.radians(pitch))-height*math.sin(math.radians(pitch))
    if depth<=.01:return offset,None,'surfer not in front of the simulated camera plane'
    return offset,2*depth*math.tan(math.radians(hfov)/2),None


def card_lines(row):
    if not row:return []
    def metres(sideways,width):
        side=f'≈{sideways:.1f} m sideways' if finite(sideways) else 'sideways unavailable'
        frame=f'≈{width:.1f} m frame width' if finite(width) else 'frame width unavailable'
        return f' · {side} / {frame}'
    magnitude=f'offsetMagnitude[SIM]: {row["offsetMagnitude"]:.2f}° (fixed)'+metres(row.get('fixedSidewaysMetres'),row.get('fixedFrameWidthMetres'))
    if not row['valid']:
        return ['appliedOffset[SIM]: unavailable — '+row['reason'],magnitude]
    value=row['appliedOffset']
    # The bracket is intended framing at the simulated target, not video CV.
    bracket='[0S0]' if abs(value)<.005 else '[S00]' if value>0 else '[00S]'
    assumptions=f'Frame width[SIM]: HFOV {row["horizontalFov"]:g}° estimate · water at takeoff level'
    if row.get('frameWidthReason'):assumptions+=' · '+row['frameWidthReason']
    return [f'appliedOffset[SIM]: {value:+.2f}° {bracket}'+metres(row.get('appliedSidewaysMetres'),row.get('appliedFrameWidthMetres')),magnitude,
            f'Yaw[SIM]: {row["evidence"]} · desired {row["desiredOffset"]:+.2f}° · quiet {row["quietDuration"]:.1f}s',assumptions]


class Replay:
    def __init__(self,params):
        self.p=parameters(params)
        self.session=object()
        self.reset()

    def reset(self):
        self.observations=deque()
        self.desired=self.applied=0.
        self.quiet_start=self.last_time=None
        self.confirmed=0

    def break_evidence(self):
        self.observations.clear()
        self.quiet_start=self.last_time=None

    def step(self,sample):
        r=dict(sample)
        if sample['sessionKey']!=self.session:
            self.session=sample['sessionKey'];self.reset()
        t=sample['timeMs']/1000
        r.update(valid=False,evidence='unavailable',netChange=None,totalTravel=None,
                 consistency=None,supportingChanges=0,observationCount=0,windowDuration=0.,quietDuration=0.,
                 centredBearing=None,baselineTarget=None,simTarget=None,centredError=None,
                 baselineError=None,simError=None,distance=None,directionSwitch=False,quietReset=False)
        reason=sample.get('invalidReason')
        if not reason:
            b,d=bearing_distance(sample['droneLatitude'],sample['droneLongitude'],sample['latitude'],sample['longitude'])
            r['distance']=d
            if b is None:reason='coincident GPS positions; bearing undefined'
        if reason:
            # Duplicated/rejected packets are not fresh observations. Missing or
            # invalid accepted GPS explicitly breaks continuity, never counts quiet.
            if sample.get('accepted') is True and not sample.get('duplicate'):self.break_evidence()
            r['reason']=reason
        else:
            gap=self.last_time is not None and t-self.last_time>self.p['maxGap']
            if gap:self.break_evidence()
            dt=0. if self.last_time is None else max(0,t-self.last_time)
            self.last_time=t
            q=self.observations
            q.append((t,b))
            boundary=t-self.p['window']
            while len(q)>2 and q[1][0]<=boundary:q.popleft()
            duration=t-q[0][0]
            deltas=[signed(q[i][1]-q[i-1][1]) for i in range(1,len(q))]
            net=sum(deltas);travel=sum(abs(x) for x in deltas)
            consistency=abs(net)/travel if travel else 0.
            direction=1 if net>0 else -1 if net<0 else 0
            supports=sum(1 for x in deltas if direction*x>=self.p['supportingChange'])
            ready=len(q)>=self.p['minObservations'] and duration+1e-9>=self.p['window']
            trend=ready and abs(net)>=self.p['netChange'] and consistency>=self.p['consistency'] and supports>=self.p['supportingChanges']
            quiet=ready and travel/duration<self.p['quietRate']
            evidence='uncertain' if ready else 'building evidence'
            reason='ambiguous bearing movement; holding decision' if ready else 'collecting a full observation window'
            if trend:
                r['directionSwitch']=self.confirmed!=0 and self.confirmed!=direction
                self.confirmed=direction;self.desired=direction*self.p['magnitude'];self.quiet_start=None
                evidence='right' if direction>0 else 'left';reason='sustained relative-bearing trend'
            elif quiet:
                if self.quiet_start is None:self.quiet_start=t
                r['quietDuration']=t-self.quiet_start
                evidence='quiet';reason='quiet timer running; holding decision'
                if r['quietDuration']+1e-9>=self.p['quietSeconds']:
                    r['quietReset']=self.desired!=0
                    self.desired=0.;self.confirmed=0;reason='continuous quiet; recentering'
            else:self.quiet_start=None
            step=self.p['slewRate']*dt
            self.applied+=max(-step,min(step,self.desired-self.applied))
            if abs(self.applied)<1e-10:self.applied=0.
            baseline=sample['baselineOffset']
            r.update(valid=True,evidence=evidence,reason=('GPS gap; '+reason) if gap else reason,
                     netChange=net,totalTravel=travel,consistency=consistency,supportingChanges=supports,
                     observationCount=len(q),windowDuration=duration,centredBearing=b,
                     baselineTarget=(b+baseline)%360,simTarget=(b+self.applied)%360)
            heading=sample.get('droneHeading')
            if finite(heading):
                r.update(centredError=signed(b-heading),baselineError=signed(r['baselineTarget']-heading),simError=signed(r['simTarget']-heading))
        r.update(desiredOffset=self.desired,appliedOffset=self.applied,offsetMagnitude=self.p['magnitude'],confirmedDirection=self.confirmed)
        r['horizontalFov']=self.p['horizontalFov']
        r['appliedSidewaysMetres'],r['appliedFrameWidthMetres'],reason=framing_estimate(r,self.applied,self.p['horizontalFov'])
        r['fixedSidewaysMetres'],r['fixedFrameWidthMetres'],fixed_reason=framing_estimate(r,self.p['magnitude'],self.p['horizontalFov'])
        r['frameWidthReason']='; '.join(dict.fromkeys(v for v in (reason,fixed_reason) if v)) or None
        r['cardLines']=card_lines(r)
        return r


# Cache compact, full-log observations independently of map selection and tuning.
EVENTS={'lora_packet','aiming_cycle','orientation_cycle','movement_cycle','vt40_configuration',
        'session_start','session_end','user_start','user_stop','state'}
KEYS={'event','timestamp','epochMs','session','nextSession','detail','latitude','longitude','sequence',
      'accepted','senderMs','aircraftLatitude','aircraftLongitude','aircraftAgeMs','aircraftHeadingDeg',
      'aircraftYawDeg','aircraftAttitudeFresh','aircraftAttitudeAgeMs','riding','rotationCurve',
      'gimbalPitchDeg','gimbalAttitudeFresh','gimbalAttitudeAgeMs','heightAboveTakeoffM','heightFresh','heightAgeMs',
      'effectiveSettingsJson'}


@lru_cache(maxsize=3)
def load_observations(source,size,mtime,utc_offset):
    from telemetry_around import record_milliseconds,offset_timezone
    tz=offset_timezone(utc_offset)
    events=[]
    with Path(source).open(encoding='utf-8-sig') as stream:
        for line_no,line in enumerate(stream,1):
            if not line.strip():continue
            try:r=json.loads(line)
            except ValueError:raise ValueError(f'Invalid JSON in {Path(source).name}:{line_no}')
            if not isinstance(r,dict) or r.get('event') not in EVENTS:continue
            t=record_milliseconds(r,tz)
            if not finite(t):continue
            events.append((t,line_no,{k:v for k,v in r.items() if k in KEYS}))
    events.sort(key=lambda e:(e[0],e[1]))
    rows=[];epoch=0;session=None;active=True;drone=heading=ride=profile=pose=None;baseline=None;pending={};seen=set()
    for t,line,r in events:
        event=r['event']
        candidate=r.get('session')
        match=re.search(r'\bsession=(\d+)',r.get('detail','')) if event=='state' else None
        if match:candidate=int(match[1])
        reset=event in ('session_start','session_end','user_start','user_stop') or candidate is not None and candidate!=session
        if reset:
            epoch+=1;drone=heading=ride=profile=pose=None;seen=set()
            if event in ('session_start','session_end'):pending={};baseline=None;session=None
            if event in ('session_end','user_stop'):active=False
            elif event in ('session_start','user_start') or candidate is not None:active=True
            if candidate is not None:
                session=candidate;baseline=pending.get(session)
        if event=='vt40_configuration':
            raw=r.get('effectiveSettingsJson')
            try:config=json.loads(raw) if isinstance(raw,str) else raw
            except ValueError:config=None
            value=config.get('yawTargetOffsetDegrees') if isinstance(config,dict) else None
            value=value if finite(value) and -180<=value<=180 else None
            next_session=r.get('nextSession',session)
            pending[next_session]=value
            if next_session==session:baseline=value
        if event=='aiming_cycle':
            drone=(t,r.get('aircraftLatitude'),r.get('aircraftLongitude'),r.get('aircraftAgeMs'))
            profile=(t,r.get('rotationCurve'))
        if event=='orientation_cycle':
            heading=(t,r.get('aircraftYawDeg'),r.get('aircraftAttitudeFresh'),r.get('aircraftAttitudeAgeMs'))
            pose=(t,r)
        if event=='movement_cycle':ride=(t,r.get('riding'))
        if event!='lora_packet':continue
        key=(r.get('sequence'),r.get('senderMs'))
        duplicate=key!=(None,None) and key in seen
        if r.get('accepted') is True:seen.add(key)
        dlat,dlon=(drone[1],drone[2]) if drone else (None,None)
        dage=drone[3]+t-drone[0] if drone and finite(drone[3]) and drone[3]>=0 else None
        hage=heading[3]+t-heading[0] if heading and finite(heading[3]) and heading[3]>=0 else None
        pose_data=pose[1] if pose else {}
        def pose_age(field):
            age=pose_data.get(field)
            return age+t-pose[0] if pose and finite(age) and age>=0 else None
        rows.append(dict(sourceFile=source,sourceLine=line,timeMs=t,timestamp=datetime.fromtimestamp(t/1000,tz).isoformat(timespec='milliseconds'),
                         session=session,sessionKey=f'{epoch}:{session}',active=active,sequence=r.get('sequence'),accepted=r.get('accepted'),duplicate=duplicate,
                         latitude=r.get('latitude'),longitude=r.get('longitude'),droneLatitude=dlat,droneLongitude=dlon,droneAgeMs=dage,
                         droneHeading=heading[1] if heading and heading[2] is True else None,headingAgeMs=hage,
                         gimbalPitch=pose_data.get('gimbalPitchDeg') if pose_data.get('gimbalAttitudeFresh') is True else None,
                         pitchAgeMs=pose_age('gimbalAttitudeAgeMs'),
                         heightAboveTakeoff=pose_data.get('heightAboveTakeoffM') if pose_data.get('heightFresh') is True else None,
                         heightAgeMs=pose_age('heightAgeMs'),
                         riding=ride[1] if ride and 0<=t-ride[0]<=1500 else None,
                         rotationProfile=profile[1] if profile and 0<=t-profile[0]<=1500 else None,
                         loggedBaseline=baseline))
    return rows


def replay_samples(samples,params=None):
    p=parameters(params);engine=Replay(p);rows=[]
    for original in samples:
        s=dict(original);reason=None
        if not s.get('active',True):reason='outside active log session'
        elif s.get('accepted') is not True:reason='packet not accepted; excluded'
        elif s.get('duplicate'):reason='duplicate packet; excluded'
        elif not position(s.get('latitude'),s.get('longitude')):reason='invalid surfer GPS'
        elif not position(s.get('droneLatitude'),s.get('droneLongitude')):reason='no prior valid drone GPS'
        elif not finite(s.get('droneAgeMs')) or not 0<=s['droneAgeMs']<=p['maxSensorAge']*1000:reason='drone GPS stale or freshness unknown'
        s['invalidReason']=reason
        if not finite(s.get('headingAgeMs')) or not 0<=s['headingAgeMs']<=p['maxSensorAge']*1000:s['droneHeading']=None
        for age,field in (('pitchAgeMs','gimbalPitch'),('heightAgeMs','heightAboveTakeoff')):
            if not finite(s.get(age)) or not 0<=s[age]<=p['maxSensorAge']*1000:s[field]=None
        s['baselineOffset']=s.get('loggedBaseline') if finite(s.get('loggedBaseline')) else p['baselineOffset']
        s['baselineSource']='logged yawTargetOffsetDegrees' if finite(s.get('loggedBaseline')) else 'explicit fallback (setting absent)'
        rows.append(engine.step(s))
    return rows


def summary(rows,max_gap):
    result=dict(observations=len(rows),valid=sum(r['valid'] for r in rows),directionSwitches=sum(r['directionSwitch'] for r in rows),
                quietResets=sum(r['quietReset'] for r in rows),uncertainPeriods=0,positiveSeconds=0.,negativeSeconds=0.,centredSeconds=0.,unknownSeconds=0.)
    previous=None
    for row in rows:
        uncertain=row['evidence'] in ('uncertain','building evidence','unavailable')
        same=previous and (row['sourceFile'],row['sessionKey'])==(previous['sourceFile'],previous['sessionKey'])
        if uncertain and (not same or previous['evidence'] not in ('uncertain','building evidence','unavailable')):result['uncertainPeriods']+=1
        if same:
            dt=max(0,(row['timeMs']-previous['timeMs'])/1000)
            key='unknownSeconds'
            if dt<=max_gap and row['valid'] and previous['valid']:
                value=previous['appliedOffset']
                key='positiveSeconds' if value>0 else 'negativeSeconds' if value<0 else 'centredSeconds'
            result[key]+=dt
        previous=row
    return {k:round(v,3) if isinstance(v,float) else v for k,v in result.items()}


def run_for_points(points,raw_params,utc_offset):
    p=parameters(raw_params);rows=[];selected={}
    by_source={}
    for point in points.values():by_source.setdefault(point['sourceFile'],{})[point['sourceLine']]=point['point']
    for source,ids in sorted(by_source.items()):
        stat=Path(source).stat()
        samples=load_observations(source,stat.st_size,stat.st_mtime_ns,utc_offset)
        for row in replay_samples(samples,p):
            row['mapPoint']=ids.get(row['sourceLine'],'')
            if row['mapPoint']:selected[row['mapPoint']]=row
            rows.append(row)
    return dict(parameters=p,points=selected,summary=summary(rows,p['maxGap']),rows=rows)


def csv_bytes(result):
    stream=io.StringIO(newline='')
    rows=result['rows']
    if rows:
        fields=[k for k in rows[0] if k!='cardLines']
        writer=csv.DictWriter(stream,fieldnames=fields+['parameter_'+k for k in result['parameters']])
        writer.writeheader()
        for row in rows:
            data={k:row.get(k) for k in fields}
            data.update({'parameter_'+k:v for k,v in result['parameters'].items()})
            # Source names are local user data, but spreadsheet formulas are not.
            data={k:("'"+v if isinstance(v,str) and v.startswith(('=','+','-','@')) else v) for k,v in data.items()}
            writer.writerow(data)
    return ('\ufeff'+stream.getvalue()).encode('utf-8')


def panel_html():
    labels=dict(magnitude='Offset magnitude (°)',window='Observation window (s)',minObservations='Minimum observations',
                netChange='Minimum net change (°)',consistency='Minimum consistency (0–1)',supportingChanges='Minimum supporting changes',
                supportingChange='Supporting change (°)',quietRate='Quiet below (°/s)',quietSeconds='Quiet duration (s)',
                slewRate='Offset change limit (°/s)',maxGap='Maximum GPS receipt gap (s)',maxSensorAge='Maximum drone sensor age (s)',
                baselineOffset='Fixed-offset fallback (signed °)',horizontalFov='Horizontal FOV estimate (°)')
    fields=''.join(f'<label style="display:block">{labels[k]} <input data-yaw-param="{k}" type="number" style="width:72px" min="{BOUNDS[k][0]}" max="{BOUNDS[k][1]}" step="{1 if k in INTEGER_PARAMS else .01}" value="{v}"></label>' for k,v in DEFAULTS.items())
    return ('<details id="yaw-sim-panel"><summary>Automatic yaw offset [SIM]</summary>'
            '<label><input id="yaw-sim-enabled" type="checkbox"> Enable simulation</label>'
            '<p>Full log replay, independent of selected map points. Experimental settings; recorded drone motion is unchanged.</p>'
            '<details><summary>Simulation settings</summary>'+fields+
            '<p>Consistency 80% and two changes of at least 0.5° are experimental evidence thresholds. Receipt time is used; sender GPS fix age is not known.</p></details>'
            '<button id="yaw-sim-run" type="button" disabled>Run / Update</button>'
            '<a id="yaw-sim-csv" hidden>Download replay CSV</a>'
            '<p id="yaw-sim-status" role="status">Simulation off.</p><p id="yaw-sim-summary"></p>'
            '<p>[S00] surfer left · [0S0] centre · [00S] right: intended framing after alignment, not measured video placement.</p>'
            '<p>Sideways metres / frame width uses full-log GPS, fresh logged pitch and height. Width is at the surfer’s depth, assuming water at takeoff level and zero camera roll. HFOV starts at an uncalibrated 74°; adjust for the lens, zoom and crop. This does not establish whether the surfer is vertically in frame.</p>'
            '<p>Hover a pair: red = logged heading, grey = centred bearing, orange = fixed target, purple = simulated target.</p>'
            '<p id="yaw-sim-point" style="white-space:pre-line"></p></details>')
