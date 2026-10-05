import zipfile,json,datetime
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip');n=next(n for n in z.namelist() if '_083821_' in n);t=datetime.datetime.fromisoformat('2026-10-04T09:07:42.341+10:00').timestamp()*1000;best=None;events=[]
for l in z.open(n):
 r=json.loads(l);e=r['event'];dt=r['epochMs']-t
 if e=='movement_cycle' and (best is None or abs(dt)<best[0]):best=(abs(dt),r)
 if 0<=dt<=30000 and e in ['retreat_started','retreat_repeated','retreat_finished']:events.append(r)
print('FRAME',json.dumps({k:best[1].get(k) for k in ['timestamp','surferDistanceM','projectedSeparationM','retreatActive','phase','reason','riding']}))
for r in events:print(json.dumps(r))
