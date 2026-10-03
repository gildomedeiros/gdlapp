import json, glob, collections, statistics, math, base64
from pathlib import Path

def stats(v):
 v=sorted(x for x in v if isinstance(x,(int,float)) and math.isfinite(x))
 return {k:round(x,2) for k,x in zip(['min','median','p90','max'],[v[0],statistics.median(v),v[min(len(v)-1,math.ceil(len(v)*.9)-1)],v[-1]])} if v else {}
def tm(r): return r['timestamp'][11:23]
out=[]
for p in sorted(glob.glob('C:/Users/gildo/temp/3.8/*.jsonl')):
 rr=[json.loads(l) for l in open(p,encoding='utf-8-sig') if l.strip()]
 ev=lambda e:[r for r in rr if r['event']==e]
 a=ev('aiming_cycle'); m=ev('movement_cycle'); rt=ev('retreat_cycle'); active=[r for r in a if r.get('state')=='AIMING']
 special=[r for r in rr if r['event'] not in ['aiming_cycle','movement_cycle','retreat_cycle','orientation_cycle','gimbal_cycle','lora_datagram','lora_packet','telemetry','command_result']]
 res=dict(file=p,header=rr[0],last=rr[-1],counts=dict(collections.Counter(r['event'] for r in rr)),schemas={e:ev(e)[0] for e in ['movement_cycle','retreat_cycle','lora_packet','lora_gap','gimbal_cycle','orientation_cycle'] if ev(e)},configs=[r for r in rr if r['event'] in ['retreat_settings_config','rotation_speed_config','gimbal_band_config','user_start','retreat_settings']],events=special,states=dict(collections.Counter(r.get('state') for r in a)),phases=dict(collections.Counter(r.get('phase') for r in m)))
 res['stateSeconds']={}
 for r,n in zip(a,a[1:]):res['stateSeconds'][r['state']]=res['stateSeconds'].get(r['state'],0)+max(0,n['monoMs']-r['monoMs'])/1000
 res['aimStats']={k:stats([abs(r[k]) if k=='relativeBearingDeg' and r.get(k)!=None else r.get(k) for r in active]) for k in ['distanceM','relativeBearingDeg','horizontalSpeedMps','targetAgeMs','desiredYawRate','requestedYawRate','submittedYawRate']}
 res['retainedAiming']=sum(r.get('retainedTargetAiming',False) for r in active)
 res['yawReverseBlocked']=sum(r.get('reverseBlocked',False) for r in active)
 res['yawOver15']=sum(abs(r.get('relativeBearingDeg') or 0)>15 for r in active)
 res['packets']=dict(total=len(ev('lora_packet')),accepted=sum(r.get('accepted',False) for r in ev('lora_packet')),gapsMs=stats([y['monoMs']-x['monoMs'] for x,y in zip(ev('lora_packet'),ev('lora_packet')[1:])]),nofix=sum(b'NOFIX' in base64.b64decode(r.get('rawBase64','')) for r in ev('lora_datagram')))
 res['retreatEvents']=[r for r in rr if r['event'].startswith('retreat_') and r['event'] not in ['retreat_cycle','retreat_settings_config','retreat_settings']]
 res['retreatAudit']=dict(staleStarts=[r for r in rr if r['event'] in ['retreat_started','retreat_repeated'] and not r.get('gpsFresh')],overLimit=[r for r in rt if (r.get('startsWithoutFreshGps') or 0)>(r.get('maxStartsWithoutFreshGps') or 0)],stats={k:stats([r.get(k) for r in rt]) for k in ['submittedForwardMps','requestedForwardMps','distanceM','gpsAgeMs']})
 res['yawGroups']={}
 for purpose in sorted({str(r.get('yawPurpose')) for r in m}):
  ids={r['cycleId'] for r in m if str(r.get('yawPurpose'))==purpose}; ar=[r for r in active if r['cycleId'] in ids]
  res['yawGroups'][purpose]=dict(count=len(ar),error=stats([abs(r['relativeBearingDeg']) for r in ar if r.get('relativeBearingDeg')!=None]),blocked=sum(r.get('reverseBlocked',False) for r in ar))
 res['approaches']=[];cur=None;seg=[]
 for r in m:
  if cur and (r['phase']!=cur['phase'] or r.get('paused')):
   res['approaches'].append(dict(start=cur,end=r,last=seg[-1],submitted=stats([q.get('submittedForwardMps') for q in seg])));cur=None;seg=[]
  if r['phase'] in ['APPROACHING','RETURNING'] and not r.get('paused'):
   if cur is None:cur=r
   seg.append(r)
 if cur:res['approaches'].append(dict(start=cur,end=None,last=seg[-1]))
 res['rides']=[r for r in m if r.get('rideEvent') not in [None,'none']]
 res['worstYaw']=sorted(active,key=lambda r:abs(r.get('relativeBearingDeg') or 0),reverse=True)[:5]
 out.append(res)
Path('C:/Users/gildo/gdlapp/cam3/exports/VT38_2026-10-03_analysis.json').write_text(json.dumps(out,indent=2),encoding='utf-8')
for r in out:
 print(json.dumps({k:r[k] for k in ['file','header','last','counts','states','stateSeconds','phases','aimStats','retainedAiming','yawReverseBlocked','yawOver15','packets','yawGroups']},separators=(',',':')))
