import zipfile,json
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip');n=next(n for n in z.namelist() if '_091937_' in n);sec=None
for l in z.open(n):
 r=json.loads(l);tm=r['timestamp'][11:23];e=r['event']
 if not '09:38:24'<=tm<='09:38:37':continue
 if e.startswith('retreat_') and e!='retreat_cycle':print(e,json.dumps({k:r.get(k) for k in ['timestamp','reason','distanceM','headingDeg','boundaryStatus']}))
 if e=='aiming_cycle' and tm[:8]!=sec:
  sec=tm[:8];print('AIM',json.dumps({k:r.get(k) for k in ['timestamp','distanceM','targetAgeMs','aircraftHeadingDeg','subjectBearingDeg','relativeBearingDeg','horizontalSpeedMps','targetLatitude','targetLongitude','aircraftLatitude','aircraftLongitude']}))
 if e=='lora_packet':print('GPS',json.dumps({k:r.get(k) for k in ['timestamp','latitude','longitude','accepted','missingBefore']}))
 if e=='movement_cycle' and '09:38:31.1'<=tm<='09:38:31.4':print('MOVE',json.dumps({k:r.get(k) for k in ['timestamp','surferDistanceM','reason','submittedForwardMps','retreatActive','speedKmh','speedJumpRejected']}))
