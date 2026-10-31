import json,datetime,bisect,csv
from pathlib import Path
out=Path('exports/vt408/surf20261030');r=json.loads((out/'rides-and-gaps.json').read_text());v=json.loads((out/'rides-video-locations.json').read_text());A={};O={}
for fn in dict.fromkeys(x['flight'] for x in r):
 aa=[];oo=[]
 for l in (Path('C:/Users/gildo/temp/311026')/fn).open():
  x=json.loads(l)
  if x['event']=='aiming_cycle':aa.append(x)
  if x['event']=='orientation_cycle':oo.append(x)
 A[fn]=([x['epochMs'] for x in aa],aa);O[fn]=([x['epochMs'] for x in oo],oo)
def epoch(t):return datetime.datetime.fromisoformat('2026-10-30T'+t+'+10:00').timestamp()*1000
def near(data,t):
 ts,z=data;i=min(bisect.bisect_left(ts,t),len(z)-1)
 if i and abs(ts[i-1]-t)<abs(ts[i]-t):i-=1
 return z[i]
rows=[];summary=[]
for i,(x,video) in enumerate(zip(r,v),1):
 def reading(t,role):
  ms=epoch(t);a=near(A[x['flight']],ms);o=near(O[x['flight']],ms)
  return dict(ride=i,rideClock=x['start'],video=video['video'],ridePlayback=video['playback'],reading=role,requestedClock=t,aimingTimestamp=a['timestamp'],horizontalDistanceM=a.get('distanceM'),surferGpsAgeMs=a.get('targetAgeMs'),aircraftGpsAgeMs=a.get('aircraftAgeMs'),orientationTimestamp=o['timestamp'],gimbalPitchDeg=o.get('gimbalPitchDeg'),aircraftPitchDeg=o.get('aircraftPitchDeg'),aircraftRollDeg=o.get('aircraftRollDeg'),heightAboveTakeoffM=o.get('heightAboveTakeoffM'),heightFresh=o.get('heightFresh'),gimbalFresh=o.get('gimbalAttitudeFresh'),gapStart='',gapEnd='',gapDurationSeconds='',gapPlaybackStart='',gapPlaybackEnd='')
 z=reading(x['start'],'ride_detection');rows.append(z);summary.append(z);print(i,x['start'][:8],video['video'],video['playback'],round(z['horizontalDistanceM'],2),z['gimbalPitchDeg'],z['aircraftPitchDeg'])
 for g in video['gaps']:
  for t,role in [(g['clockStart'],'gap_start'),(g['clockEnd'],'gap_end')]:
   z=reading(t,role);z.update(gapStart=g['clockStart'],gapEnd=g['clockEnd'],gapDurationSeconds=g['seconds'],gapPlaybackStart=g['playbackStart'],gapPlaybackEnd=g['playbackEnd']);rows.append(z)
with (out/'rides-video-distance-tilt.csv').open('w',newline='',encoding='utf-8-sig') as f:
 w=csv.DictWriter(f,fieldnames=list(rows[0]));w.writeheader();w.writerows(rows)
(out/'rides-distance-tilt.json').write_text(json.dumps(summary,indent=2))
print('Rows',len(rows))
