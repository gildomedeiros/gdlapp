import runpy,contextlib,io,json,csv
from pathlib import Path
base=Path('C:/Users/gildo/gdlapp/cam3/exports/vt408/surf20261030')
with contextlib.redirect_stdout(io.StringIO()):m=runpy.run_path(str(base/'map_ride_videos.py'))
r=json.loads((base/'rides-last-low-motion.json').read_text());videos=json.loads((base/'rides-video-locations.json').read_text())
for x,v in zip(r,videos):
 for name,key in [('candidatePaddling','candidatePaddlingStart'),('lastLowMotion','lowMotionWindowEnd'),('retreat','nearestRetreatStart')]:
  clock=x[key][11:23] if x[key] else '';video,play=m['locate'](clock) if clock else ('','');x[name+'Video']=video;x[name+'Playback']=play
 x['gpsGapsClock']='; '.join(g['clockStart']+'-'+g['clockEnd']+f' ({g["seconds"]:.3f}s)' for g in v['gaps']);x['gpsGapsPlayback']='; '.join(g['video']+' '+g['playbackStart']+'-'+g['playbackEnd'] for g in v['gaps'])
 print(x['ride'],x['candidatePaddlingStart'][11:23],x['candidatePaddlingVideo'],x['candidatePaddlingPlayback'],x['lastLowMotionPlayback'])
with (base/'VT408-complete-ride-review.csv').open('w',newline='',encoding='utf-8-sig') as f:
 w=csv.DictWriter(f,fieldnames=list(r[0]));w.writeheader();w.writerows(r)
(base/'VT408-complete-ride-review.json').write_text(json.dumps(r,indent=2))
