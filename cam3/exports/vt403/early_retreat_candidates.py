import zipfile,json,pathlib
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip');out=[]
for n in sorted(z.namelist()):
 if not n.endswith('.jsonl'):continue
 packets=[];rides=[];retreats=[]
 for l in z.open(n):
  r=json.loads(l);e=r['event']
  if e=='movement_cycle':
   if r.get('newRidePacket'):packets.append(r)
   if r.get('rideEvent')=='ride_started':rides.append(r)
  if e=='retreat_started':retreats.append(r)
 for ride in rides:
  t=ride['epochMs'];window=[r for r in packets if t-15000<=r['epochMs']<=t];chain=[];qual=None
  for r in window:
   speed=r.get('speedKmh');valid=speed is not None and speed>=4 and not r.get('speedJumpRejected') and r.get('surferDistanceM') is not None
   if not valid:chain=[];qual=None;continue
   if chain and (r['epochMs']-chain[-1]['epochMs']>1600 or chain[-1]['surferDistanceM']-r['surferDistanceM']<0.2):chain=[];qual=None
   chain.append(r)
   if qual is None and len(chain)>=3 and r['epochMs']-chain[0]['epochMs']>=900:qual=r
  actual=next((r for r in retreats if t-5000<=r['epochMs']<=t+8000),None)
  output={'ride':ride['timestamp'][11:23],'mode':ride.get('positioningMode'),'candidate':qual['timestamp'][11:23] if qual else None,'candidateDistance':qual.get('surferDistanceM') if qual else None,'retreatActiveAtCandidate':qual.get('retreatActive') if qual else None,'actualRetreat':actual['timestamp'][11:23] if actual else None,'leadVsRideSec':(t-qual['epochMs'])/1000 if qual else None,'headStartSec':max(0,(actual['epochMs']-qual['epochMs'])/1000) if qual and actual and not qual.get('retreatActive') else None,'evidence':[(r['timestamp'][11:23],r.get('speedKmh'),r.get('surferDistanceM')) for r in window]};out.append(output)
  print(json.dumps(output))
pathlib.Path('exports/vt403/early_retreat_candidates.json').write_text(json.dumps(out,indent=2),encoding='utf-8')
