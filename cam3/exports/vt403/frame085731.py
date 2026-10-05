import zipfile,json,datetime
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip');n=next(n for n in z.namelist() if '_083821_' in n);t=datetime.datetime.fromisoformat('2026-10-04T08:57:31.795+10:00').timestamp()*1000;best={}
for l in z.open(n):
 r=json.loads(l);e=r['event'];d=abs(r['epochMs']-t)
 if e in ['movement_cycle','aiming_cycle','gimbal_pitch_cycle'] and (e not in best or d<best[e][0]):best[e]=(d,r)
for e,(d,r) in best.items():print(e,d,json.dumps({k:r.get(k) for k in ['timestamp','state','phase','reason','projectedSeparationM','comeToMeAlignmentErrorM','surferDistanceM','aircraftHeadingDeg','subjectBearingDeg','relativeBearingDeg','targetAgeMs','targetLatitude','targetLongitude','requestedForwardMps','submittedForwardMps','submittedRightMps','riding','speedKmh','approachRemainingM','targetPitchDeg','actualPitchDeg','activeBand','retreatActive','pathBlockReason','journeyKind','decision']}))

