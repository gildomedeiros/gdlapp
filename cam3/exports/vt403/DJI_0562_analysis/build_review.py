import csv,json,math,bisect
from pathlib import Path
from datetime import datetime,timedelta,timezone
from PIL import Image,ImageDraw
root=Path('E:/DCIM/100MEDIA/DJI_0562_review')
out=Path('exports/vt403/DJI_0562_analysis')
frames=list(csv.DictReader((root/'frame_times.csv').open()))
# Camera movie-header creation time established by the MP4 reader.
import runpy
utils=runpy.run_path('exports/vt403/test_vt403_rides_standalone.py')
start,duration=utils['video_timing'](Path('E:/DCIM/100MEDIA/DJI_0562.MP4'))
selected=[frames[i] for i in range(0,len(frames),5)]
for page in range(math.ceil(len(selected)/20)):
    canvas=Image.new('RGB',(1920,5*292),'#171717');draw=ImageDraw.Draw(canvas)
    for j,row in enumerate(selected[page*20:(page+1)*20]):
        im=Image.open(root/'frames'/row['frame']);im.thumbnail((480,270))
        x=(j%4)*480;y=(j//4)*292
        canvas.paste(im,(x,y));offset=float(row['original_video_offset_seconds'])
        stamp=datetime.fromtimestamp(start+offset,timezone(timedelta(hours=10)))
        draw.text((x+8,y+273),f'{row["frame"]} | {offset:.3f}s | {stamp:%H:%M:%S}',fill='white')
    canvas.save(out/f'overview_{page+1}.jpg')
logs=Path('C:/Users/gildo/temp/vt 4.03/logs/cam3_full_2026-10-04_083821_554_1a3054b0.jsonl')
events={e:[] for e in ['aiming_cycle','movement_cycle','orientation_cycle','retreat_cycle','retreat_started','lora_packet']}
for line in logs.open():
    r=json.loads(line)
    if r.get('event') in events and start*1000-10000<=r['epochMs']<=(start+300)*1000+10000:
        events[r['event']].append(r)
for e in events:events[e].sort(key=lambda r:r['epochMs'])
def near(e,t):
    items=events[e]
    if not items:return None
    times=[r['epochMs'] for r in items];i=bisect.bisect_left(times,t)
    candidates=items[max(0,i-1):min(len(items),i+1)]
    return min(candidates,key=lambda r:abs(r['epochMs']-t))
rows=[]
for frame in frames:
    offset=float(frame['original_video_offset_seconds']);t=(start+offset)*1000
    a=near('aiming_cycle',t);m=near('movement_cycle',t);g=near('orientation_cycle',t)
    rows.append(dict(frame=frame['frame'],video_offset_s=offset,epoch_ms=t,
                     timestamp=datetime.fromtimestamp(t/1000,timezone(timedelta(hours=10))).isoformat(timespec='milliseconds'),
                     aiming=a,movement=m,gimbal=g,visual_label='not_reviewed'))
(out/'frame_telemetry_metadata.json').write_text(json.dumps(dict(video='DJI_0562.MP4',clock_basis='MP4 mvhd creation time treated as UTC recording start; no independent clock-offset validation',frames=rows),indent=2))
print('Frames',len(rows),'events',{k:len(v) for k,v in events.items()})
print('GIMBAL SAMPLE',json.dumps(events['orientation_cycle'][0] if events['orientation_cycle'] else None))
canvas=Image.new('RGB',(1920,4*292),'#171717');draw=ImageDraw.Draw(canvas)
for j,i in enumerate(range(199,215)):
    row=frames[i];im=Image.open(root/'frames'/row['frame']);im.thumbnail((480,270));x=j%4*480;y=j//4*292;canvas.paste(im,(x,y));draw.text((x+8,y+273),f'{row["frame"]} {float(row["original_video_offset_seconds"]):.3f}s',fill='white')
canvas.save(out/'takeoff_detail.jpg')
for i in [197,202,204,205,206,207,210,215,225,240,260]:
    r=rows[i];a=r['aiming'] or {};m=r['movement'] or {};g=r['gimbal'] or {}
    print(r['timestamp'],{k:a.get(k) for k in ['distanceM','targetAgeMs','relativeBearingDeg','horizontalSpeedMps']},{k:m.get(k) for k in ['speedKmh','projectedSeparationM','retreatActive','requestedForwardMps']},{k:g.get(k) for k in ['gimbalPitchDeg','heightAboveTakeoffM']})

# Independent signed shoreward speed from fresh target fixes, using sender time.
fixes=[];last_key=None
for a in events['aiming_cycle']:
    key=(a.get('targetFixMs'),a.get('targetSenderMs'))
    if key==last_key or a.get('targetLatitude') is None:continue
    last_key=key;fixes.append(a)
def signed_speed(p,q):
    dt=(q['targetSenderMs']-p['targetSenderMs'])/1000
    if dt<=0:return None
    lat=math.radians((p['targetLatitude']+q['targetLatitude'])/2)
    north=math.radians(q['targetLatitude']-p['targetLatitude'])*6371000
    east=math.radians(q['targetLongitude']-p['targetLongitude'])*6371000*math.cos(lat)
    sea=math.radians(88.4188024134645)
    return -(east*math.sin(sea)+north*math.cos(sea))/dt*3.6
flat=[]
for i,r in enumerate(rows):
    a=r['aiming'] or {};m=r['movement'] or {};g=r['gimbal'] or {};t=r['epoch_ms']
    seen=[f for f in fixes if f['epochMs']<=t]
    q=seen[-1] if seen else None;speed=None;acc=None
    if q and q.get('targetSenderMs') is not None:
        refs=[p for p in seen[:-1] if p.get('targetSenderMs') is not None and 1000<=q['targetSenderMs']-p['targetSenderMs']<=2000]
        if refs:speed=signed_speed(refs[-1],q)
    label='not individually reviewed';framing='not individually reviewed';reviewed=False
    if i%5==0 or 199<=i<=214 or i in [197,202,204,205,206,207]:
        reviewed=True
        if i<199:label='waiting / paddling; exact stroke or wave push not classified';framing='visible in sampled frame'
        elif i<=204:label='paddling / wave approach';framing='visible'
        elif i<=205:label='takeoff / crouch';framing='near lower edge; poor margin'
        elif i<=206:label='ride moving toward lower edge';framing='near lower edge; poor margin'
        elif i<=213:label='target not identifiable';framing='missing target in sample; exit versus occlusion uncertain'
        elif i<240:label='post-ride whitewater; identity uncertain';framing='identity uncertain'
        else:label='waiting / paddling after ride';framing='visible in sampled frame'
    r.update(visual_label=label,framing=framing,individually_reviewed=reviewed)
    for key in ['aiming','movement','gimbal']:
        r[key+'_match_delta_ms']=r[key]['epochMs']-t if r[key] else None
    prev=rows[max(0,i-1)]['aiming'] or {};prev_m=rows[max(0,i-1)]['movement'] or {}
    dt=(t-rows[max(0,i-1)]['epoch_ms'])/1000
    close=(prev['distanceM']-a['distanceM'])/dt if dt and prev.get('distanceM') is not None and a.get('distanceM') is not None else None
    depression=math.degrees(math.atan2(g['heightAboveTakeoffM'],a['distanceM'])) if g.get('heightAboveTakeoffM') is not None and a.get('distanceM') else None
    flat.append(dict(frame=r['frame'],timestamp=r['timestamp'],video_offset_s=r['video_offset_s'],visual_label=label,framing=framing,individually_reviewed=reviewed,direct_separation_m=a.get('distanceM'),projected_separation_m=m.get('projectedSeparationM'),gps_age_ms=a.get('targetAgeMs'),shoreward_speed_kmh=speed,closing_rate_mps=close,height_above_takeoff_m=g.get('heightAboveTakeoffM'),actual_pitch_deg=g.get('gimbalPitchDeg'),geometric_depression_deg=depression,yaw_error_deg=a.get('relativeBearingDeg'),drone_measured_speed_mps=a.get('horizontalSpeedMps'),requested_forward_mps=m.get('requestedForwardMps'),retreat_active=m.get('retreatActive'),logged_detector_speed_kmh=m.get('speedKmh'),aiming_match_delta_ms=r['aiming_match_delta_ms']))
with (out/'frame_telemetry.csv').open('w',newline='',encoding='utf-8') as f:
    for i,r in enumerate(flat):
        p=flat[max(0,i-1)];dt=r['video_offset_s']-p['video_offset_s']
        r['shoreward_acceleration_mps2_sampled']=((r['shoreward_speed_kmh']-p['shoreward_speed_kmh'])/3.6/dt if dt and r['shoreward_speed_kmh'] is not None and p['shoreward_speed_kmh'] is not None else None)
        r['acceleration_note']='Difference of sampled overlapping GPS speed windows; noisy, not an independent accelerometer measurement'
    w=csv.DictWriter(f,fieldnames=list(flat[0]));w.writeheader();w.writerows(flat)
(out/'frame_telemetry_metadata.json').write_text(json.dumps(dict(video='DJI_0562.MP4',clock_basis='MP4 mvhd creation time treated as UTC recording start; not independently validated',visual_review='Whole duration sampled every 5.005 s, plus 1.001 s samples around takeoff. Unsampled observations explicitly unclassified. Blue-shirt surfer assumed target.',limitations=['Not continuous video inspection','No automatic ground truth for wave pushback or intent','GPS-derived speeds and closing rate are noisy','Height relative to takeoff is not verified height over water','Logged speedKmh may remain frozen during ride timer; not current surfer speed'],frames=rows),indent=2))
print('EARLY EVENT',[(r['timestamp'],r['shoreward_speed_kmh'],r['closing_rate_mps']) for r in flat[202:211]])
