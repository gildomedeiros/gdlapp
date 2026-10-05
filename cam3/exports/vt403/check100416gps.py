import zipfile,json
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip');n=next(n for n in z.namelist() if '_095812_' in n);last=None;change=None
for l in z.open(n):
 r=json.loads(l);tm=r['timestamp'][11:23]
 if r['event']=='lora_packet' and r.get('accepted'):
  c=(r.get('latitude'),r.get('longitude'))
  if c!=last:change=tm;last=c
  if '10:04:14'<=tm<='10:04:19':print('GPS',tm,c,'changed',change)
 if r['event']=='aiming_cycle' and '10:04:15.9'<=tm<='10:04:16.9':print('AIM',tm,r.get('aircraftHeadingDeg'),r.get('subjectBearingDeg'),r.get('relativeBearingDeg'))
