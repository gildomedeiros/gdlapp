import zipfile,json,datetime
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip');n=next(n for n in z.namelist() if '_091937_' in n);arrival=None;best=[]
for l in z.open(n):
 r=json.loads(l)
 if r.get('event')!='movement_cycle':continue
 tm=r['timestamp'][11:23]
 if r.get('reason')=='fixed_destination_arrived':arrival=tm
 if '09:27:00'<=tm<='09:30:31' and r.get('surferDistanceM') is not None and r.get('phase')=='HOLDING' and not r.get('retreatActive') and not r.get('retreatCooldownRemainingMs'):
  best.append((abs(r['surferDistanceM']-25),r,arrival))
for d,r,a in sorted(best,key=lambda x:x[0])[:5]:print(json.dumps({k:r.get(k) for k in ['timestamp','surferDistanceM','projectedSeparationM','phase','reason','submittedForwardMps','submittedRightMps','fixedDestinationFixTime']})+' arrival='+str(a))
