import zipfile,json,collections,math
z=zipfile.ZipFile(r'C:/Users/gildo/temp/vt404test/cam3_full_2026-10-05_220638_442_05e26c81.zip'); total=collections.Counter(); mislabeled=collections.Counter(); fails=[]
for name in z.namelist():
 for l in z.read(name).splitlines():
  r=json.loads(l)
  if r['event']=='movement_cycle' and r.get('positioningMode')=='diagonal':
   if r.get('projectedSeparationM') is not None:mislabeled[r['reason']]+=1
   if r.get('rideEvent')=='ride_started':total['rides']+=1
  if r['event']=='retreat_started':total['retreat_starts']+=1
  if r['event']=='retreat_cycle' and r.get('enabled') and r.get('active') and r.get('submittedForwardMps') is not None:
   f=r['submittedForwardMps'];total['retreat_command_checks']+=1
   if f>1e-6 or abs(f)>r['speedMps']+1e-6:fails.append((name,r['timestamp'],'speed'))
   if r.get('boundaryBlocked') and abs(f)>1e-6:fails.append((name,r['timestamp'],'blocked nonzero'))
print('retreat',dict(total),'fails',fails[:10]);print('projected contamination',dict(mislabeled))
