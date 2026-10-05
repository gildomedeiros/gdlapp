import zipfile,json
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip');n=next(n for n in z.namelist() if '_091937_' in n);last=None
for l in z.open(n):
 r=json.loads(l);tm=r['timestamp'][11:23]
 if r['event']=='movement_cycle' and '09:38:17'<=tm<='09:38:27':
  v=(r.get('speedKmh'),r.get('speedJumpRejected'))
  if v!=last or r.get('rideEvent')=='ride_started':print(tm,r.get('speedKmh'),r.get('surferDistanceM'),r.get('rideEvent'),r.get('speedJumpRejected'));last=v
