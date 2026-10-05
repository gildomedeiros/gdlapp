import zipfile,json,math,collections,pathlib
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip');ang=math.radians(88.4188024134645);allout=[];sums=[]
def sea(a,b):
 return math.radians(b[0]-a[0])*6371000*math.cos(ang)+math.radians(b[1]-a[1])*6371000*math.cos(math.radians(b[0]))*math.sin(ang)
for n in sorted(z.namelist()):
 if not n.endswith('.jsonl'):continue
 aim=None;prev=None;run=False;events=[];rides=[];ex=collections.Counter()
 for l in z.open(n):
  r=json.loads(l);e=r['event']
  if e=='aiming_cycle':aim=r
  if e!='movement_cycle':continue
  if r.get('rideEvent')=='ride_started':rides.append(r['epochMs'])
  if r.get('phase')=='OFF' or not aim or aim.get('state')!='AIMING':prev=None;run=False;continue
  if not r.get('newRidePacket') or aim.get('targetLatitude') is None:continue
  coord=(aim['targetLatitude'],aim['targetLongitude'])
  if prev is None:prev=coord;continue
  delta=sea(prev,coord);prev=coord
  if delta>1e-6:run=False;continue
  if delta>=-1e-6 or run:continue
  run=True
  if aim.get('aircraftLatitude') is None:continue
  signed=sea((aim['aircraftLatitude'],aim['aircraftLongitude']),coord)
  if signed<=0:ex['drone_not_shoreward']+=1;continue
  reasons=[]
  if r.get('retreatActive'):reasons.append('retreat_already_active')
  if r.get('riding'):reasons.append('ride_already_detected')
  if r.get('phase')=='RETURNING':reasons.append('return_active')
  if r.get('paused') or r.get('gpsMovementPaused'):reasons.append('paused')
  events.append({'epochMs':r['epochMs'],'time':r['timestamp'],'mode':r.get('positioningMode'),'distance':r.get('surferDistanceM'),'excluded':reasons,'cooldown':r.get('retreatCooldownRemainingMs',0)})
 eligible=[x for x in events if not x['excluded']]
 result={'log':n,'rides':len(rides),'rawWarnings':len(events),'eligible':len(eligible),'exclusions':dict(collections.Counter(reason for x in events for reason in x['excluded'])),'wrongSideWarnings':ex['drone_not_shoreward']}
 for window in [10,20,30]:
  matched=sum(any(x['epochMs']<=t<=x['epochMs']+window*1000 for t in rides) for x in eligible)
  result['matchedNext'+str(window)+'s']=matched;result['unmatchedNext'+str(window)+'s']=len(eligible)-matched
 for x in events:
  x['log']=n;x['rideWithin20s']=any(x['epochMs']<=t<=x['epochMs']+20000 for t in rides);allout.append(x)
 sums.append(result);print(json.dumps(result))
print('TOTAL',json.dumps({k:sum(s.get(k,0) for s in sums) for k in ['rides','rawWarnings','eligible','matchedNext10s','unmatchedNext10s','matchedNext20s','unmatchedNext20s','matchedNext30s','unmatchedNext30s']}))
pathlib.Path('exports/vt403/shoreward_full_day_warnings.json').write_text(json.dumps({'summary':sums,'events':allout},indent=2),encoding='utf-8')
