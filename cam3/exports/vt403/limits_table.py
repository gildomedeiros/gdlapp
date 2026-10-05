import json,zipfile,datetime,collections
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip')
cases=[('yesterday',open('C:/Users/gildo/temp/3.8/cam3_full_2026-10-03_094458_725_8c2c3251.jsonl',encoding='utf-8-sig'),'2026-10-03',['10:07:21.428','10:07:23.597']),('today',z.open(next(n for n in z.namelist() if '_091937_' in n)),'2026-10-04',['09:30:17.712','09:30:30.256'])]
for label,f,date,ss in cases:
 ts={s:datetime.datetime.fromisoformat(date+'T'+s+'+10:00').timestamp()*1000 for s in ss}; rows=collections.defaultdict(list)
 for l in f:
  r=json.loads(l)
  if min(ts.values())-3000<=r.get('epochMs',0)<=max(ts.values())+1000:rows[r['event']].append(r)
 for s,t in ts.items():
  a=min(rows['aiming_cycle'],key=lambda r:abs(r['epochMs']-t));prev=min(rows['aiming_cycle'],key=lambda r:abs(r['epochMs']-(a['epochMs']-1000)));m=min(rows['movement_cycle'],key=lambda r:abs(r['epochMs']-t));r=min(rows['retreat_cycle'],key=lambda r:abs(r['epochMs']-t));dt=(a['epochMs']-prev['epochMs'])/1000
  print(s,json.dumps({'closingMps':(prev['distanceM']-a['distanceM'])/dt,'closingSpanSec':dt,'yawOutput':a.get('submittedYawRate'),'yawMax':a.get('maxYawRate'),'yawAcceleration':a.get('maxYawAcceleration'),'reverseBlocked':a.get('reverseBlocked'),'reportedSurferSpeedKmh':m.get('speedKmh'),'ride':m.get('riding'),'projected':m.get('projectedSeparationM'),'alignment':m.get('comeToMeAlignmentErrorM'),'retreatCooldownMs':m.get('retreatCooldownRemainingMs'),'boundaryDistance':m.get('retreatBoundarySeawardDistanceM'),'boundaryStatus':m.get('retreatBoundaryStatus'),'retreatElapsedMs':r.get('elapsedMs'),'retreatRemainingMs':r.get('remainingMs'),'retreatSpeed':r.get('speedMps'),'retreatDurationMs':r.get('durationMs'),'retreatCooldownSettingMs':r.get('cooldownMs')}))
