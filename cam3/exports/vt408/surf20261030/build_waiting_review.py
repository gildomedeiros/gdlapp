import json,datetime,bisect,re
from pathlib import Path
base=Path(__file__).parent
rides=json.loads((base/'rides-and-gaps.json').read_text())
old=json.loads((base/'VT408-complete-ride-review.json').read_text())
def ms(t):return datetime.datetime.fromisoformat(t).timestamp()*1000
def stamp(t):return datetime.datetime.fromtimestamp(t/1000,datetime.timezone(datetime.timedelta(hours=10))).isoformat(timespec='milliseconds')
data={}
for fn in dict.fromkeys(r['flight'] for r in rides):
 events={e:[] for e in ['lora_packet','aiming_cycle','orientation_cycle','movement_cycle','retreat_cycle']}
 spans=[(ms('2026-10-30T'+r['start']+'+10:00')-65000,ms('2026-10-30T'+r['end']+'+10:00')+1000) for r in rides if r['flight']==fn]
 for line in (Path('C:/Users/gildo/temp/311026')/fn).open():
  x=json.loads(line);e=x.get('event');t=x.get('epochMs',0)
  if e in events and any(a<=t<=b for a,b in spans) and (e!='lora_packet' or x.get('accepted')):events[e].append(x)
 data[fn]=events
 print('Loaded',fn,flush=True)
def near(items,t):
 if not items:return {}
 x=min(items,key=lambda x:abs(x['epochMs']-t))
 return x if abs(x['epochMs']-t)<=500 else {}
files=[]
for p in Path('E:/DCIM/100MEDIA').glob('*.SRT'):
 matches=re.findall(r'(\d\d:\d\d:\d\d,\d{3}) --> [^\n]+\n[^\n]+\n(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})',p.read_text(encoding='utf-8-sig',errors='replace'))
 if not matches:continue
 videos=list(p.parent.glob(p.stem+'*.MP4'));v=videos[0].name if videos else 'MP4 missing: '+p.stem
 times=[ms(t.replace(' ','T')+'+10:00') for _,t in matches]
 pos=[]
 for a,_ in matches:
  h,m,s=map(float,a.replace(',','.').split(':'));pos.append(h*3600+m*60+s)
 files.append((v,times,pos))
def locate(t):
 for v,times,pos in files:
  if times[0]<=t<=times[-1]+100:
   i=min(bisect.bisect_left(times,t),len(times)-1)
   if i and abs(times[i-1]-t)<abs(times[i]-t):i-=1
   return v,f'{int(pos[i]//60):02}:{pos[i]%60:06.3f}'
 return 'not covered',''
rows=[]
for n,(r,previous) in enumerate(zip(rides,old),1):
 t=ms('2026-10-30T'+r['start']+'+10:00');ev=data[r['flight']]
 gps=[x for x in ev['lora_packet'] if t-60000<=x['epochMs']<=t-2000]
 runs=[];run=[]
 for x in gps:
  if run and ((x['latitude'],x['longitude'])!=(run[-1]['latitude'],run[-1]['longitude']) or x['epochMs']-run[-1]['epochMs']>1500):runs.append(run);run=[]
  run.append(x)
 if run:runs.append(run)
 qualifying=[a for a in runs if len(a)>=6 and a[-1]['epochMs']-a[0]['epochMs']>=3000]
 recent=[a for a in qualifying if a[-1]['epochMs']>=t-30000]
 chosen=max(recent or qualifying,key=lambda a:(a[-1]['epochMs']-a[0]['epochMs'],a[-1]['epochMs'])) if qualifying else []
 z={'ride':n,'classification':'known beach activity; not a wave' if n==19 else 'VT ride detection; waiting reference inferred from GPS','date':'2026-10-30','timezone':'Australia/Brisbane UTC+10','sourceLog':str(Path('C:/Users/gildo/temp/311026')/r['flight'])}
 if n==1:chosen=next(a for a in runs if a[0]['timestamp'][11:23]=='14:03:10.264')
 sample=None
 if chosen:
  mid=(chosen[0]['epochMs']+chosen[-1]['epochMs'])/2
  held=[]
  for x in chosen[1:-1]:
   m=near(ev['movement_cycle'],x['epochMs']);rt=near(ev['retreat_cycle'],x['epochMs'])
   if m and m.get('submittedForwardMps')==0 and m.get('submittedRightMps')==0 and not rt.get('active',True):held.append(x)
  sample=min(held or chosen,key=lambda x:abs(x['epochMs']-mid))
  if n==1:sample=next(x for x in chosen if x['timestamp'][11:23]=='14:03:14.265')
 z.update(waitingReferenceStatus='repeated-coordinate waiting candidate' if sample else 'no qualifying repeated-coordinate period; review needed',waitingSelectionRule='Longest exact-coordinate run ending in last 30s; fallback last 60s; >=3s and >=6 packets; gaps <=1.5s; interior holding sample preferred. Ride 1 retains agreed sample.',waitingClusterStart=chosen[0]['timestamp'] if chosen else '',waitingClusterEnd=chosen[-1]['timestamp'] if chosen else '',waitingClusterPackets=len(chosen),waitingClusterDurationSeconds=(chosen[-1]['epochMs']-chosen[0]['epochMs'])/1000 if chosen else '',waitingTimestamp=sample['timestamp'] if sample else '',waitingLatitude=sample['latitude'] if sample else '',waitingLongitude=sample['longitude'] if sample else '',waitingSequence=sample['sequence'] if sample else '')
 for prefix,time in [('waiting',sample['epochMs'] if sample else None),('ride',t)]:
  a=m=o=rt={}
  if time is not None:
   candidates=[x for x in ev['aiming_cycle'] if sample and x.get('targetSequence')==sample['sequence'] and time<=x['epochMs']<=time+500] if prefix=='waiting' else []
   a=min(candidates,key=lambda x:x['epochMs']) if candidates else near(ev['aiming_cycle'],time)
   o=near(ev['orientation_cycle'],a.get('epochMs',time));m=near(ev['movement_cycle'],a.get('epochMs',time));rt=near(ev['retreat_cycle'],a.get('epochMs',time))
  v,p=locate(time) if time else ('','')
  fields={'Video':v,'Playback':p,'TelemetryTimestamp':a.get('timestamp',''),'OrientationTimestamp':o.get('timestamp',''),'DroneSurferDistanceM':a.get('distanceM',''),'GimbalPitchDeg':o.get('gimbalPitchDeg',''),'AircraftPitchDeg':o.get('aircraftPitchDeg',''),'AircraftRollDeg':o.get('aircraftRollDeg',''),'HeightAboveTakeoffM':o.get('heightAboveTakeoffM',''),'FilmingSeparationMetres':m.get('filmingDistanceM',''),'RetreatMinimumDistanceMetres':rt.get('minimumDistanceM',''),'RetreatActive':rt.get('active',''),'RetreatCooldownRemainingMs':rt.get('cooldownRemainingMs',''),'MovementForwardMps':m.get('submittedForwardMps',''),'MovementRightMps':m.get('submittedRightMps',''),'MovementPhase':m.get('phase',''),'MovementReason':m.get('reason',''),'SurferGpsAgeMs':a.get('targetAgeMs',''),'AircraftGpsAgeMs':a.get('aircraftAgeMs','')}
  z.update({prefix+k:v for k,v in fields.items()})
 z.update(rideDetectedTimestamp=stamp(t),waitingToRideSeconds=(t-sample['epochMs'])/1000 if sample else '',rideWindowEndTimestamp='2026-10-30T'+r['end']+'+10:00',rideWindowEndDefinition='VT 20-second window; not actual wave end')
 z['rideWindowEndVideo'],z['rideWindowEndPlayback']=locate(ms(z['rideWindowEndTimestamp']))
 for role,key in [('before','nearestBefore'),('after','nearestAfter')]:
  pass
 z['allGapDetailsJson']=json.dumps({k:v for k,v in r.items() if k not in ['flight','ride','start','end']},separators=(',',':'))
 z['supersededPaddlingEstimateStatus']='Old sliding-window estimates retained below for traceability only; do not use as paddling start or waiting calibration.'
 z.update({'previous_'+k:v for k,v in previous.items()})
 rows.append(z)
 print(n,z['waitingTimestamp'],z['waitingDroneSurferDistanceM'],z['waitingVideo'],z['waitingPlayback'],flush=True)
(base/'waiting-review-data.json').write_text(json.dumps(rows,indent=2))
assert len(rows)==23
assert rows[0]['waitingSequence']==3479 and abs(rows[0]['waitingDroneSurferDistanceM']-30.90782)<.001
