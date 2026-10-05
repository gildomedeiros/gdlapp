import zipfile,json
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip')
for n,lo,hi in [('cam3_full_2026-10-04_083821_554_1a3054b0.jsonl','08:56:33','08:56:41'),('cam3_full_2026-10-04_091937_120_b916420d.jsonl','09:38:24','09:38:33')]:
 lastsec=None
 for l in z.open(n):
  r=json.loads(l);time=r['timestamp'][11:19];e=r['event']
  if not lo<=time<=hi:continue
  if e.startswith('retreat_') and e!='retreat_cycle':print(e,{k:r.get(k) for k in ['timestamp','reason','distanceM','startDistanceM','gpsFresh']})
  if e=='movement_cycle' and time!=lastsec:
   lastsec=time;print({k:r.get(k) for k in ['timestamp','surferDistanceM','speedKmh','riding','reason','translationPurpose','submittedForwardMps','retreatActive','retreatCooldownRemainingMs']})
