import zipfile,json,collections
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip')
for n in sorted(z.namelist()):
 if not n.endswith('.jsonl'):continue
 minrow=None;aims={};arrivals=set();ride=[];timeouts=[];pauses=[];nonzero_returnride=0;fresh=collections.Counter();lastheight=None;closestheight=None;minret=None;manualanchors=set()
 for l in z.open(n):
  r=json.loads(l);e=r['event']
  if 'height' in e.lower():lastheight=r
  if e=='aiming_cycle':
   if r.get('session')==1 and r.get('state')=='AIMING' and r.get('distanceM') is not None and (minrow is None or r['distanceM']<minrow['distanceM']):minrow=r;closestheight=lastheight
   fresh[r.get('inputProblem')]+=1
  if e=='movement_cycle' and r.get('phase')!='OFF' and r.get('session')==1:
   if r.get('reason')=='fixed_destination_arrived':arrivals.add(r.get('fixedDestinationFixTime'))
   if r.get('rideEvent') in ['ride_started','ride_expired']:ride.append({k:r.get(k) for k in ['timestamp','rideEvent','phase','reason','returnReason','rideRemainingMs']})
   if r.get('phase')=='RETURNING' and r.get('riding') and r.get('submittedForwardMps') not in [None,0]:nonzero_returnride+=1
   if r.get('centralLatitude') is not None:manualanchors.add((r['centralLatitude'],r['centralLongitude']))
  if e=='retreat_started':
   if minret is None or r['startDistanceM']<minret['startDistanceM']:minret=r
 print('\n',n,'UNIQUE_ARRIVALS',len(arrivals),'CENTRAL_ANCHORS',len(manualanchors),'RETURN_MOVING_DURING_RIDE',nonzero_returnride,'RIDE',ride)
 print('MIN_AIMING',{k:minrow.get(k) for k in ['timestamp','state','distanceM','targetLatitude','targetLongitude','aircraftLatitude','aircraftLongitude','targetAgeMs','aircraftAgeMs','decision','submitted']} if minrow else None)
 print('NEAR_HEIGHT',closestheight)
 print('MIN_RETREAT_START',{k:minret.get(k) for k in ['timestamp','startDistanceM','gpsFresh','boundaryStatus','boundarySeawardDistanceM','headingDeg']} if minret else None)
