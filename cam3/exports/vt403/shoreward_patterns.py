import zipfile,json,math
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip')
for n in sorted(z.namelist()):
 if not n.endswith('.jsonl'):continue
 ps=[];rides=[]
 for l in z.open(n):
  r=json.loads(l)
  if r['event']=='lora_packet' and r.get('accepted'):ps.append(r)
  if r['event']=='movement_cycle' and r.get('rideEvent')=='ride_started':rides.append(r)
 for ride in rides:
  t=ride['epochMs'];w=[p for p in ps if t-16000<=p['epochMs']<=t];runs=[];run=[];prev=None;gaps=[]
  for p in w:
   if prev:
    dt=(p['epochMs']-prev['epochMs'])/1000
    if dt>1:gaps.append(round(dt,3))
    b=math.radians(88.4188024134645);north=math.radians(p['latitude']-prev['latitude'])*6371000;east=math.radians(p['longitude']-prev['longitude'])*6371000*math.cos(math.radians(p['latitude']));s=-(north*math.cos(b)+east*math.sin(b))
    if s>1e-6:
     if not run:run=[prev]
     run.append(p)
    elif s< -1e-6:
     if run:runs.append(run);run=[]
    elif run:run.append(p)
   prev=p
  if run:runs.append(run)
  print('RIDE',ride['timestamp'][11:23],ride.get('positioningMode'),'GAPS',gaps)
  for run in runs:
   a=run[0];b=run[-1];angle=math.radians(88.4188024134645);north=math.radians(b['latitude']-a['latitude'])*6371000;east=math.radians(b['longitude']-a['longitude'])*6371000*math.cos(math.radians(b['latitude']));s=-(north*math.cos(angle)+east*math.sin(angle));changes=sum((x['latitude'],x['longitude'])!=(y['latitude'],y['longitude']) for x,y in zip(run,run[1:]))
   print(a['timestamp'][11:23],b['timestamp'][11:23],'shorewardM',round(s,2),'distinctmoves',changes,'onsetLeadSec',round((t-a['epochMs'])/1000,3))
