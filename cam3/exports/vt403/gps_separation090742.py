import zipfile,json
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip');n=next(n for n in z.namelist() if '_083821_' in n)
for l in z.open(n):
 r=json.loads(l)
 if r['event']=='movement_cycle' and r['timestamp'] in ['2026-10-04T09:07:42.165+10:00','2026-10-04T09:07:43.188+10:00']:
  print(json.dumps({k:r.get(k) for k in ['timestamp','surferDistanceM','projectedSeparationM','comeToMeAlignmentErrorM','retreatActive']}))
