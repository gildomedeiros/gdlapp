import zipfile,json
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip');n=next(n for n in z.namelist() if '_091937_' in n);seen=False
for l in z.open(n):
 r=json.loads(l)
 if r.get('event')=='movement_cycle' and r.get('fixedDestinationFixTime')==179431558:
  if not seen or r.get('reason')=='fixed_destination_arrived':print(r['timestamp'],r.get('reason'),r.get('journeyKind'));seen=True
 if r.get('event')=='gimbal_pitch_cycle' and '09:28:41.5'<=r['timestamp'][11:23]<='09:28:42.0':print('PITCH',r['timestamp'],r.get('actualPitchDeg'))
