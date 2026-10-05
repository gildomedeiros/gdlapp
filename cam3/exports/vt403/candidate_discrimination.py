import json,zipfile,collections
base=json.load(open('exports/vt403/shoreward_full_day_warnings.json',encoding='utf-8'));events=base['events'];bylog=collections.defaultdict(dict)
for x in events:bylog[x['log']][x['epochMs']]=x
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip')
for n,targets in bylog.items():
 for l in z.open(n):
  r=json.loads(l)
  if r['event']=='movement_cycle' and r['epochMs'] in targets:
   targets[r['epochMs']]['speed']=r.get('speedKmh');targets[r['epochMs']]['jumpRejected']=r.get('speedJumpRejected')
for label,es in [('ride-associated',[e for e in events if e['rideWithin20s']]),('unmatched',[e for e in events if not e['rideWithin20s']])]:
 print(label,'n',len(es))
 if label=='ride-associated':
  for e in es:print(e['time'][11:23],e.get('speed'),e['distance'],e['mode'])
for threshold in [4,5,8,10,12,15,18]:
 selected=[e for e in events if e.get('speed') is not None and e['speed']>=threshold and not e.get('jumpRejected')]
 print('SPEED',threshold,'total',len(selected),'matched',sum(e['rideWithin20s'] for e in selected),'unmatched',sum(not e['rideWithin20s'] for e in selected))
for limit in [20,25,30]:
 selected=[e for e in events if e.get('speed') is not None and e['speed']>=4 and not e.get('jumpRejected') and e['distance']<=limit]
 print('SPEED4_DISTANCE',limit,'total',len(selected),'matched',sum(e['rideWithin20s'] for e in selected),'unmatched',sum(not e['rideWithin20s'] for e in selected))
