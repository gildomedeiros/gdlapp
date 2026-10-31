import json,datetime,csv
from pathlib import Path
out=Path('exports/vt408/surf20261030');results=[];flat=[]
for p in sorted(Path('C:/Users/gildo/temp/311026').glob('*.jsonl')):
 if p.stat().st_size<100000000:continue
 packets=[];rides=[]
 for l in p.open():
  x=json.loads(l)
  if x['event']=='lora_packet' and x.get('accepted'):packets.append(x)
  if x['event']=='movement_cycle' and x.get('rideEvent')=='ride_started':rides.append(x)
 gaps=[]
 for a,b in zip(packets,packets[1:]):
  dt=(b['epochMs']-a['epochMs'])/1000;missing=b.get('missingBefore',0)
  if missing>0 or dt>=1.5:gaps.append(dict(start=a['epochMs'],end=b['epochMs'],startTime=a['timestamp'][11:23],endTime=b['timestamp'][11:23],seconds=dt,missing=missing,previousSequence=a['sequence'],nextSequence=b['sequence']))
 for i,r in enumerate(rides,1):
  s=r['epochMs'];e=s+20000;inside=[g for g in gaps if g['start']<e and g['end']>s];before=max((g for g in gaps if g['end']<=s),key=lambda g:g['end'],default=None);after=min((g for g in gaps if g['start']>=e),key=lambda g:g['start'],default=None)
  z={'flight':p.name,'ride':i,'start':r['timestamp'][11:23],'end':datetime.datetime.fromtimestamp(e/1000,datetime.timezone(datetime.timedelta(hours=10))).strftime('%H:%M:%S.%f')[:12],'inside':inside,'before':before,'after':after};results.append(z)
  def fmt(g):return g['startTime']+'–'+g['endTime']+' '+str(g['seconds'])+'s' if g else 'none'
  print(z['start'],'DURING','; '.join(fmt(g) for g in inside) or 'NONE','BEFORE',fmt(before),round((s-before['end'])/1000,2) if before else '', 'AFTER',fmt(after),round((after['start']-e)/1000,2) if after else '')
  for rel,gs in [('during',inside),('nearest_before',[before] if before else []),('nearest_after',[after] if after else [])]:
   for g in gs:flat.append(dict(flight=p.name,ride=i,rideStart=z['start'],rideEnd=z['end'],relation=rel,**g))
(out/'rides-and-gaps.json').write_text(json.dumps(results,indent=2))
with (out/'rides-and-gaps.csv').open('w',encoding='utf-8-sig',newline='') as f:
 w=csv.DictWriter(f,fieldnames=list(flat[0]));w.writeheader();w.writerows(flat)
print('RIDES',len(results),'withGap',sum(bool(x['inside']) for x in results),'withLongGap',sum(any(g['seconds']>=1.5 for g in x['inside']) for x in results))
