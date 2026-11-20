"""Native VTC metre aiming and Canon zoom log interpretation (through 1.0.27)."""
import json
import math
from vtc_activity import Activity,lines as activity_lines,EVENTS as ACTIVITY_EVENTS

ZOOM_EVENTS={'zoom_decision','camera_zoom_response','camera_zoom_command','camera_zoom_confirmed',
             'camera_zoom_ack','camera_zoom_error','camera_zoom_command_error','camera_zoom_skipped',
             'manual_zoom_observation'}
DIAGNOSTIC_EVENTS=ZOOM_EVENTS|{'settings_public_saved','settings_save_failed','settings_public_save_failed',
    'settings_file_resolved','camera_clock_verified','camera_clock_sync_failed','mount_recovery_stop_requested',
    'mount_poll_restored','stop_unconfirmed','calibration_success','calibration_cleared','goto_captured',
    'goto_completed','goto_cancelled','session_end'}


def is_diagnostic(event):
    return event in DIAGNOSTIC_EVENTS|ACTIVITY_EVENTS or event.startswith(('settings_','calibration_','gps_capture_','last_landmark_','camera_recording_'))


def n(value):
    if isinstance(value,bool):return None
    try:value=float(value)
    except (ValueError,TypeError):return None
    return value if math.isfinite(value) else None


def obj(value,kind=dict):
    if isinstance(value,str):
        try:value=json.loads(value)
        except ValueError:return kind()
    return value if isinstance(value,kind) else kind()


def f(value,unit='',digits=2):
    value=n(value)
    return f'{value:.{digits}f}{unit}' if value is not None else 'unavailable'


def optical_zoom(mapping,value):
    """Use only the recorded mapping; shared CCAPI values use a log midpoint."""
    value=n(value);rows=obj(mapping,list)
    pairs=[(n(r.get('zoomX')),n(r.get('ccapiValue'))) for r in rows if isinstance(r,dict)]
    if value is None or len(pairs)<2 or any(x is None or y is None or x<=0 for x,y in pairs):return None
    if any(b[0]<=a[0] or b[1]<a[1] for a,b in zip(pairs,pairs[1:])) or pairs[0][1]==pairs[-1][1]:return None
    exact=[x for x,y in pairs if y==value]
    if exact:return math.sqrt(exact[0]*exact[-1])
    upper=next((i for i,p in enumerate(pairs) if p[1]>value),len(pairs))
    if upper==0:
        left=0;right=next(i for i in range(1,len(pairs)) if pairs[i][1]!=pairs[0][1])
    elif upper==len(pairs):
        right=len(pairs)-1;left=next(i for i in range(right-1,-1,-1) if pairs[i][1]!=pairs[right][1])
    else:left,right=upper-1,upper
    x,a=pairs[left];y,b=pairs[right]
    return max(1,min(65,x*math.exp((value-a)/(b-a)*math.log(y/x))))


def mode(settings):
    if settings.get('zoomMode') in ('off','bands','auto-fit'):return settings['zoomMode']
    if str(settings.get('autoFit','0'))=='1':return 'auto-fit'
    if str(settings.get('autoZoom','0'))=='1':return 'bands'
    return 'off'


def angle(metres,range_m):
    return math.degrees(math.atan2(metres,range_m)) if metres is not None and range_m is not None and range_m>0 else None


class Telemetry:
    def __init__(self):self.reset()
    def reset(self):
        self.activity=Activity()
        self.fov_heading=None;self.fov_heading_at=None;self.fov_camera=None;self.camera=None
        self.zoom={};self.zoom_at=None;self.readback=None;self.readback_at=None;self.readback_ok=False
        self.command=None;self.command_at=None;self.confirmed=None;self.zoom_status='No zoom reports'
        self.zoom_status_at=None;self.last_issue=None;self.mount={};self.settings_status='No settings-save report';self.clock_status='No clock report'

    def observe(self,event,t,row):
        if event=='session_start':self.reset();return
        self.activity.observe(event,t,row)
        if isinstance(row.get('settings'),dict):self.camera=(n(row['settings'].get('latitude')),n(row['settings'].get('longitude')))
        if event=='calibration_cleared':self.fov_heading=None;self.fov_heading_at=None
        if event=='status_snapshot' and row.get('positionFresh') is not False:
            heading=n(obj(row.get('position')).get('headingDegrees'))
            if heading is not None:
                self.fov_heading=heading;self.fov_heading_at=t
                mono=n(row.get('monoMs'));sample=n(row.get('positionSampleMonoMs'))
                if mono is not None and sample is not None and 0<sample<=mono:self.fov_heading_at=t-(mono-sample)
                self.fov_camera=self.camera
        if event=='zoom_decision':self.zoom=obj(row.get('decision'));self.zoom_at=t
        if event=='camera_zoom_response':
            self.readback=n(row.get('zoom'));self.readback_at=t;self.readback_ok=self.readback is not None
            self.zoom_status='Readback received';self.zoom_status_at=t
        if event=='camera_zoom_command':
            self.command=n(row.get('requestedCcapi'));self.command_at=t;self.confirmed=None;self.zoom_status='Command sent; awaiting readback';self.zoom_status_at=t
        if event=='camera_zoom_ack':self.zoom_status='Command acknowledged; awaiting readback';self.zoom_status_at=t
        if event=='camera_zoom_confirmed':self.confirmed=row.get('readbackCcapi');self.zoom_status='Confirmed by readback';self.zoom_status_at=t
        if event in ('camera_zoom_error','camera_zoom_command_error'):
            self.last_issue=str(row.get('exception',row.get('detail',row.get('reason','Camera request failed'))))
            self.zoom_status=event+': '+self.last_issue;self.zoom_status_at=t
            if event=='camera_zoom_error':self.readback_ok=False
        if event=='camera_zoom_skipped':self.zoom_status='Skipped: '+str(row.get('reason','unknown'));self.zoom_status_at=t
        if event in ('disconnected','gps_socket_closed','session_end'):self.readback_ok=False
        if event=='status_snapshot':self.mount={k:row.get(k) for k in ('mountRecovery','waitingForTargetLimits','blockers','pendingMove','gpsStatus','gpsFresh','planner')}
        if event=='settings_public_saved':self.settings_status='Saved and verified' if row.get('readbackVerified') is True or row.get('verified') is True else 'Public settings saved (see event details for verification)'
        if 'settings' in event and ('failed' in event or 'error' in event):self.settings_status=event+': '+str(row.get('exception',row.get('reason','failed')))
        if event=='camera_clock_verified':self.clock_status='Camera clock verified'
        if event=='camera_clock_sync_failed':self.clock_status='Clock sync failed: '+str(row.get('exception',row.get('reason',row.get('detail','unknown'))))

    def annotate(self,p,t):
        p['activity']=self.activity.at(t)
        s=p['settings'];r=p.get('distance');a=p.get('aim') or {};d=p.get('decision') or {}
        metre=n(s.get('magnitudeMetres'))
        p['aimingUnits']='metres' if metre is not None or s.get('aimingUnits')=='metres' else 'degrees'
        p['magnitudeMetres']=metre;p['magnitudeDegrees']=angle(metre,r) if metre is not None else n(s.get('magnitude'))
        p['panToleranceMetres']=n(s.get('panToleranceMetres'));p['tiltToleranceMetres']=n(s.get('tiltToleranceMetres'))
        p['panToleranceDegrees']=angle(p['panToleranceMetres'],r) if p['panToleranceMetres'] is not None else n(s.get('panTolerance'))
        p['tiltToleranceDegrees']=angle(p['tiltToleranceMetres'],r) if p['tiltToleranceMetres'] is not None else n(s.get('tiltTolerance'))
        p['acquisitionMetres']=n(s.get('acquisitionTriggerMetres'))
        p['acquisitionDegrees']=angle(p['acquisitionMetres'],r) if p['acquisitionMetres'] is not None else n(s.get('acquisitionTriggerDegrees'))
        offset=n(p['framing'].get('appliedOffsetDegrees'))
        # VTC AimUnits uses tan, not the perpendicular sin-distance convention.
        p['appliedOffsetMetres']=r*math.tan(math.radians(offset)) if r is not None and offset is not None and abs(offset)<90 else None
        p['zoom']=dict(self.zoom);p['zoom'].pop('settings',None);p['zoom'].pop('mapping',None)
        p['zoomMode']=mode(s);p['zoomDecisionAgeMs']=t-self.zoom_at if self.zoom_at is not None else None
        p['zoomReadbackCcapi']=self.readback;p['zoomReadbackAgeMs']=t-self.readback_at if self.readback_at is not None else None
        p['zoomReadbackFresh']=self.readback_ok and p['zoomReadbackAgeMs'] is not None and 0<=p['zoomReadbackAgeMs']<=5000
        p['opticalZoom']=optical_zoom(s.get('zoomMapping'),self.readback) if p['zoomReadbackFresh'] else None
        factor=n(s.get('wideFrameFactor'));x=p['opticalZoom']
        p['zoomFrameWidth']=r*factor/x if r is not None and factor is not None and x is not None else None
        # Historical fan is explicitly red; do not pass stale values off as a
        # fresh camera/readback estimate in the ordinary telemetry fields.
        last_zoom=optical_zoom(s.get('zoomMapping'),self.readback)
        same_camera=self.fov_camera==(n(s.get('latitude')),n(s.get('longitude')))
        p['fovHeading']=self.fov_heading if same_camera else None
        p['fovHeadingAgeMs']=t-self.fov_heading_at if self.fov_heading_at is not None else None
        p['fovWidthMetres']=r*factor/last_zoom if r is not None and factor is not None and last_zoom is not None else None
        p['fovFresh']=bool(p['zoomReadbackFresh'] and p.get('position') and p['fovHeading'] is not None and p['fovHeadingAgeMs'] is not None and 0<=p['fovHeadingAgeMs']<=(n(s.get('gpsAge')) or 2)*1000)
        p['zoomHfov']=math.degrees(2*math.atan(factor/(2*x))) if factor is not None and x is not None else None
        p['zoomRequestedCcapi']=self.command;p['zoomConfirmedCcapi']=self.confirmed
        p['zoomStatus']=self.zoom_status;p['zoomLastIssue']=self.last_issue
        p['zoomStatusAgeMs']=t-self.zoom_status_at if self.zoom_status_at is not None else None
        p['mount']=dict(self.mount);p['settingsSaveStatus']=self.settings_status;p['cameraClockStatus']=self.clock_status


def extra_lines(p):
    s=p['settings'];d=p.get('decision') or {};z=p.get('zoom') or {};mount=p.get('mount') or {}
    lines=activity_lines(p)
    if p.get('aimingUnits')=='metres':
        lines.extend(['Aiming units: metres · range-dependent angle conversion',
            'Magnitude: '+f(p.get('magnitudeMetres'),' m'),
            'Applied framing: '+f(p.get('appliedOffsetMetres'),' m')+' '+p.get('bracket',''),
            'Tolerance pan / tilt: '+f(p.get('panToleranceMetres'),' m')+' / '+f(p.get('tiltToleranceMetres'),' m'),
            'Acquisition trigger: '+f(p.get('acquisitionMetres'),' m')])
    lines.extend(['Zoom mode: '+p.get('zoomMode','off')+' · '+str(p.get('zoomStatus','unknown')),
        'FOV: '+('unavailable (no prior heading or zoom)' if p.get('fovHeading') is None or p.get('fovWidthMetres') is None else 'fresh (green)' if p.get('fovFresh') else 'stale last-known data (red)')+' · heading age '+f(p.get('fovHeadingAgeMs'),' ms',0)+' · zoom age '+f(p.get('zoomReadbackAgeMs'),' ms',0),
        'Zoom readback: CCAPI '+f(p.get('zoomReadbackCcapi'),digits=0)+' · age '+f(p.get('zoomReadbackAgeMs'),' ms',0)+(' (fresh)' if p.get('zoomReadbackFresh') else ' (stale / unavailable)')+' · optical ≈'+f(p.get('opticalZoom'),'x'),
        'Zoom frame width: ≈'+f(p.get('zoomFrameWidth'),' m',1)+' · desired '+f(s.get('desiredFrameWidthMetres'),' m',1)+' · effective '+f(z.get('effectiveFrameWidthMetres'),' m',1),
        'Zoom decision: '+str(z.get('action','unknown'))+' · '+str(z.get('reason','unknown'))+' · age '+f(p.get('zoomDecisionAgeMs'),' ms',0),
        'Zoom requested: '+f(z.get('requestedOpticalZoom'),'x')+' · CCAPI '+f(z.get('requestedCcapi'),digits=0)+' · pending '+f(z.get('pendingCcapi'),digits=0)+' · confirmed '+f(p.get('zoomConfirmedCcapi'),digits=0),
        'Zoom scale: '+f(z.get('multiplier'),'×')+' · index '+str(z.get('multiplierIndex','?'))+' · clamped '+str(z.get('clamped','unknown'))])
    if 'selectedBandIndex' in z:
        lines.append('Zoom band: #'+str(z['selectedBandIndex'])+' · '+f(z.get('bandMinDistanceMetres'),' m',1)+'–'+('∞' if z.get('bandMaxDistanceMetres') is None else f(z['bandMaxDistanceMetres'],' m',1))+' · base '+f(z.get('bandZoomX'),'x')+' · buffer '+f(z.get('bufferMetres'),' m',1))
    if p.get('zoomLastIssue'):lines.append('Last camera issue: '+p['zoomLastIssue'])
    if d.get('highSpeedPolicy'):
        lines.append('Motor policy: '+str(d['highSpeedPolicy'])+' · requested maximum '+f(d.get('maximumPanningSpeed'),'°/s')+' · effective '+f(d.get('effectiveMaximumPanningSpeed'),'°/s'))
        lines.append('High-speed pan / tilt: '+str(d.get('highSpeedPan','unknown'))+' / '+str(d.get('highSpeedTilt','unknown')))
    recovery=obj(mount.get('mountRecovery'))
    if recovery.get('waiting') or recovery.get('stopRequested'):lines.append('Mount recovery: waiting '+str(recovery.get('waiting'))+' · missed polls '+str(recovery.get('missedPolls','?'))+' · Stop requested '+str(recovery.get('stopRequested')))
    if d.get('highSpeedPolicy'):lines.append('Motor policy: '+str(d['highSpeedPolicy'])+' · effective max '+f(d.get('effectiveMaximumPanningSpeed'),'°/s')+' · high-speed pan/tilt '+str(d.get('highSpeedPan'))+'/'+str(d.get('highSpeedTilt')))
    recovery=obj(mount.get('mountRecovery'))
    if recovery.get('waiting'):lines.append('Mount recovery: waiting · '+f(recovery.get('elapsedMs'),' ms',0)+' · stop requested '+str(recovery.get('stopRequested')))
    if mount.get('waitingForTargetLimits'):lines.append('Target limits: waiting for target inside configured limits')
    if mount.get('blockers'):lines.append('Readiness: '+' · '.join(map(str,mount['blockers'])))
    lines.extend(['Settings: '+p.get('settingsSaveStatus','unknown'),'Camera clock: '+p.get('cameraClockStatus','unknown'),
        'Zoom width = current GPS range × logged wideFrameFactor / readback-mapped optical zoom. Configured HFOV fan remains a separate reference.'])
    return lines


def diagnostic(row,t,line,clock):
    event=row['event'];details={k:v for k,v in row.items() if k not in ('settings','mapping','timestamp','epochMs','monoMs','thread','record','event','body')}
    if isinstance(details.get('decision'),dict):details['decision']={k:v for k,v in details['decision'].items() if k not in ('settings','mapping')}
    return dict(event=event,timeMs=t,timestamp=clock(t),sourceLine=line,details=details)
