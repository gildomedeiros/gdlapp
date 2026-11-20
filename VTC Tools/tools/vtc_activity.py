"""Logged ride / fixed GoTo / continuous motor activity, joined causally."""
import math


EVENTS={'goto_move','goto_reversal_hold','continuous_hold','aim_stopped','fault','control_error','mount_error','gps_gap_hold','gps_timeout_stop_requested','gps_timeout_stop_confirmed','aim_target_wait','aim_auto_resumed'}


def numeric(value):
    return isinstance(value,(int,float)) and not isinstance(value,bool) and math.isfinite(value)


class Activity:
    def __init__(self):self.reset()
    def reset(self):
        self.ride={};self.ride_at=None;self.goto=None;self.goto_state='unknown';self.goto_at=None;self.activation=None
        self.pan=None;self.pan_at=None;self.hold=None;self.hold_at=None;self.reason='No motor report';self.high=None;self.reverse=None;self.tracking=None

    def observe(self,event,t,row):
        if event=='session_start':self.reset();return
        framing=row.get('framing')
        if isinstance(framing,dict) and event in ('status_snapshot','aim_cycle','framing_observation'):
            self.ride=dict(framing);self.ride_at=t
        if event=='goto_captured':
            self.goto=row.get('target') or {};self.goto_state='ACTIVE';self.goto_at=t
            self.activation=dict(timeMs=t,kind=self.goto.get('kind','unknown'),sequence=(self.goto.get('source') or row.get('gps') or {}).get('sequence'),
                triggerMetres=row.get('acquisitionTriggerMetres'),reason=row.get('reason','Acquisition trigger'),rangeMetres=self.goto.get('rangeM'))
        if event in ('goto_progress','goto_move','goto_reversal_hold'):
            if isinstance(row.get('target'),dict):self.goto=row['target']
            self.goto_state='WAITING FOR REVERSAL' if event=='goto_reversal_hold' else 'ACTIVE';self.goto_at=t
        if event in ('goto_completed','goto_cancelled'):
            self.goto_state='COMPLETED' if event=='goto_completed' else 'CANCELLED';self.goto=None;self.goto_at=t
        if event=='status_snapshot':
            self.tracking=row.get('tracking')
            if isinstance(row.get('savedGoto'),dict):self.goto=row['savedGoto'];self.goto_state='ACTIVE';self.goto_at=t
            elif self.goto_state in ('ACTIVE','WAITING FOR REVERSAL'):self.goto=None;self.goto_state='INACTIVE';self.goto_at=t
            rate=row.get('panRateDegreesPerSecond')
            if numeric(rate):self.pan=rate;self.pan_at=t;self.reason=str(row.get('message','Logged motor rate'))
        if event in ('continuous_decision','aim_cycle'):
            if isinstance(row.get('holdPan'),bool):self.hold=row['holdPan'];self.hold_at=t
            if event=='continuous_decision':
                self.high=row.get('highSpeedPan');self.reverse=row.get('panReversalBlocked')
                if numeric(row.get('panRateDegreesPerSecond')):self.pan=row['panRateDegreesPerSecond'];self.pan_at=t
                self.reason='Hold pan: surfer moving toward camera centreline' if self.hold else 'Motor reversal blocked' if self.reverse else 'Continuous correction / tolerance'
        if event in ('continuous_rates','continuous_hold','gps_gap_hold'):
            rate=row.get('panDegreesPerSecond',row.get('panRateDegreesPerSecond',row.get('panRate')))
            if numeric(rate):self.pan=rate;self.pan_at=t
            self.reason=str(row.get('reason','GPS gap: holding last command' if row.get('gpsGap') else 'Logged continuous command'))
        if event in ('aim_stopped','stop_confirmed','disconnected','session_end','gps_timeout_stop_confirmed'):
            self.pan=0.;self.pan_at=t;self.hold=None;self.tracking=False;self.reason=event
            self.goto=None;self.goto_state='INACTIVE';self.goto_at=t
            self.ride={};self.ride_at=None
        if event in ('stop_requested','stop_unconfirmed','fault','control_error'):
            self.pan=None;self.pan_at=t;self.reason=event;self.goto_state='STOP REQUESTED / UNKNOWN';self.goto_at=t

    def at(self,t):
        return dict(ride=dict(self.ride),rideAgeMs=t-self.ride_at if self.ride_at is not None else None,
            gotoState=self.goto_state,gotoKind=(self.goto or {}).get('kind'),gotoAgeMs=t-self.goto_at if self.goto_at is not None else None,
            activation=dict(self.activation) if self.activation else None,
            panRate=self.pan,panAgeMs=t-self.pan_at if self.pan_at is not None else None,holdPan=self.hold,
            holdAgeMs=t-self.hold_at if self.hold_at is not None else None,reason=self.reason,highSpeed=self.high,reversalBlocked=self.reverse,tracking=self.tracking)


def lines(p):
    a=p.get('activity') or {};ride=a.get('ride') or {};age=a.get('rideAgeMs');state=ride.get('rideActive')
    fresh=age is not None and age<=max(float(p['settings'].get('gpsGapTimeout') or 3),float(p['settings'].get('gpsAge') or 2))*1000
    ride_text=('YES' if state else 'NO') if isinstance(state,bool) else 'unknown (no ride flag in log)'
    if isinstance(state,bool) and not fresh:ride_text='STALE — last '+ride_text
    speed=ride.get('qualifyingSpeedKmh');speed_text=f'{speed:.1f} km/h' if numeric(speed) else 'unknown'
    ms=lambda value:f'{value:.0f} ms' if numeric(value) else 'unknown'
    result=['Wave ride: '+ride_text+' · qualifying speed '+speed_text+' · confirmations '+str(ride.get('startConfirmations','?'))+' · '+str(ride.get('reason','no report'))+' · age '+ms(age)]
    result.append('GoTo: '+str(a.get('gotoState','unknown'))+(' · '+str(a['gotoKind']) if a.get('gotoKind') else '')+' · report age '+ms(a.get('gotoAgeMs')))
    activation=a.get('activation')
    if activation:
        from datetime import datetime,timezone,timedelta
        timestamp=datetime.fromtimestamp(activation['timeMs']/1000,timezone(timedelta(hours=10))).isoformat(timespec='milliseconds')
        trigger=activation.get('triggerMetres');trigger_text=f' · trigger {trigger:g} m' if numeric(trigger) else ''
        result.append('GoTo activation: '+timestamp+' · '+activation['kind']+trigger_text+' · '+str(activation['reason'])+(' · GPS #'+str(activation['sequence']) if activation.get('sequence') is not None else ''))
    rate=a.get('panRate');rate_age=a.get('panAgeMs')
    active=a.get('gotoState') in ('ACTIVE','WAITING FOR REVERSAL')
    hold=a.get('holdPan') is True and a.get('holdAgeMs') is not None and a['holdAgeMs']<=3000
    if active:pan='INACTIVE — fixed GoTo controls movement'
    elif rate_age is None or rate_age>5000 or not numeric(rate):pan='UNKNOWN / stale motor report'
    elif abs(rate)>1e-8:pan='ACTIVE · '+('right / clockwise' if rate>0 else 'left / anticlockwise')+(' · high speed' if a.get('highSpeed') else ' · low speed')
    elif hold:pan='HOLD — surfer moving toward camera centreline'
    elif a.get('reversalBlocked'):pan='WAITING — reversal blocked'
    else:pan='STOPPED / within tolerance'
    result.append('Continuous pan: '+pan+' · '+str(a.get('reason','unknown'))+' · report age '+ms(rate_age))
    return result
