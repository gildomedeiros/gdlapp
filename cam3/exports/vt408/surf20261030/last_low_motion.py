import json,math,csv,datetime
from pathlib import Path
out=Path('exports/vt408/surf20261030');rides=json.loads((out/'rides-and-gaps.json').read_text());old=json.loads((out/'rides-with-nearest-retreat.json').read_text());by={}
for fn in dict.fromkeys(r['flight'] for r in rides):
 g=[];aim=[]
 for l in (Path('C:/Users/gildo/temp/311026')/fn).open():
  x=json.loads(l)
  if x['event']=='lora_packet' and x.get('accepted'):g.append(x)
  if x['event']=='aiming_cycle':aim.append(x)
 by[fn]=(g,aim)
def dist(a,b):
 la,lo=map(math.radians,[a['latitude'],a['longitude']]);lb,lob=map(math.radians,[b['latitude'],b['longitude']]);return 12742000*math.asin(min(1,math.sqrt(math.sin((lb-la)/2)**2+math.cos(la)*math.cos(lb)*math.sin((lob-lo)/2)**2)))
results=[]
for num,(r,previous) in enumerate(zip(rides,old),1):
 ms=datetime.datetime.fromisoformat('2026-10-30T'+r['start']+'+10:00').timestamp()*1000;gps,aim=by[r['flight']];cand=[x for x in gps if ms-60000<=x['epochMs']<ms];best=None
 for i,end in enumerate(cand):
  w=[x for x in cand[:i+1] if end['epochMs']-5000<=x['epochMs']<=end['epochMs']]
  if len(w)<6 or end['epochMs']-w[0]['epochMs']<4500:continue
  if max(b['epochMs']-a['epochMs'] for a,b in zip(w,w[1:]))>1500:continue
  diameter=max(dist(a,b) for i,a in enumerate(w) for b in w[i+1:])
  if diameter<=3:best=(w,diameter)
 z=dict(previous);z.update(lowMotionWindowStart='',lowMotionWindowEnd='',lowMotionDiameterM='',lowMotionPacketCount='',lastLowMotionDroneDistanceM='',distanceLoggedAt='',candidatePaddlingStart='',secondsBeforeRideDetection='',stationaryRule='latest 4.5-5s received-fix window in previous60s; maximum pairwise GPS displacement<=3m; interpacket gaps<=1.5s')
 if best:
  w,d=best;last=w[-1];match=[a for a in aim if a.get('targetSequence')==last['sequence'] and a['epochMs']>=last['epochMs'] and a['epochMs']<last['epochMs']+1500];a=min(match,key=lambda a:a['epochMs']) if match else None
  following=next((x for x in gps if x['epochMs']>last['epochMs']),None)
  z.update(lowMotionWindowStart=w[0]['timestamp'],lowMotionWindowEnd=last['timestamp'],lowMotionDiameterM=d,lowMotionPacketCount=len(w),lastLowMotionDroneDistanceM=a['distanceM'] if a else '',distanceLoggedAt=a['timestamp'] if a else '',candidatePaddlingStart=following['timestamp'] if following else '',secondsBeforeRideDetection=(ms-last['epochMs'])/1000)
 print(num,r['start'][:8],z['lowMotionWindowStart'][11:23],z['lowMotionWindowEnd'][11:23],round(z['lastLowMotionDroneDistanceM'],1) if z['lastLowMotionDroneDistanceM']!='' else 'NONE',z['candidatePaddlingStart'][11:23],round(z['secondsBeforeRideDetection'],1) if z['secondsBeforeRideDetection']!='' else '')
 results.append(z)
(out/'rides-last-low-motion.json').write_text(json.dumps(results,indent=2))
with (out/'rides-last-low-motion.csv').open('w',newline='',encoding='utf-8-sig') as f:w=csv.DictWriter(f,fieldnames=list(results[0]));w.writeheader();w.writerows(results)
