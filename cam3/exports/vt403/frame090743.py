import zipfile,json,datetime
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip');n=next(n for n in z.namelist() if '_083821_' in n);t=datetime.datetime.fromisoformat('2026-10-04T09:07:43.943+10:00').timestamp()*1000;best=None;packets={}
for l in z.open(n):
 r=json.loads(l)
 if r['event']=='lora_packet':packets[r.get('sequence')]=r['timestamp']
 if r['event']=='aiming_cycle' and (best is None or abs(r['epochMs']-t)<best[0]):best=(abs(r['epochMs']-t),r)
 if r['event']=='movement_cycle' and abs(r['epochMs']-t)<70:print('MOV',json.dumps({k:r.get(k) for k in ['timestamp','surferDistanceM','projectedSeparationM','comeToMeAlignmentErrorM','retreatActive']}))
r=best[1];print('GPS',packets.get(r.get('targetSequence')),r.get('targetAgeMs'),best[0],r['timestamp'])
