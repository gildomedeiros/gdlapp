import sys,json,re,bisect,datetime,subprocess,csv,zipfile,html
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from PIL import Image,ImageDraw,ImageFont
base=Path('C:/Users/gildo/gdlapp/cam3/exports/vt408/surf20261030');sys.path.insert(0,str(base/'frame-tools'));import imageio_ffmpeg
ff=imageio_ffmpeg.get_ffmpeg_exe();root=Path('E:/DCIM/100MEDIA');out=base/'ride-frames';out.mkdir(exist_ok=True)
def epoch(s):return datetime.datetime.fromisoformat(s).replace(tzinfo=datetime.timezone(datetime.timedelta(hours=10))).timestamp()
def play(s):
 h,m,v=s.replace(',','.').split(':');return int(h)*3600+int(m)*60+float(v)
files=[]
for p in root.glob('*.SRT'):
 matches=re.findall(r'(\d\d:\d\d:\d\d,\d{3}) --> [^\n]+\n[^\n]+\n(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})',p.read_text(encoding='utf-8-sig',errors='replace'));vid=list(root.glob(p.stem+'*.MP4'))
 if matches and vid:files.append((vid[0],[epoch(t) for _,t in matches],[play(t) for t,_ in matches]))
rides=json.loads((base/'rides-and-gaps.json').read_text());jobs=[]
for i,r in enumerate(rides,1):
 for role in ['start','end']:
  clock=r[role];ts=epoch('2026-10-30 '+clock);job=dict(ride=i,role=role,clock=clock,video='',playbackSeconds='',srtClock='',file='',status='missing_footage')
  for video,times,positions in files:
   if times[0]<=ts<=times[-1]+.1:
    idx=min(bisect.bisect_left(times,ts),len(times)-1)
    if idx and abs(times[idx-1]-ts)<abs(times[idx]-ts):idx-=1
    name=f'ride-{i:02}-{role}-{clock.replace(":", "").replace(".", "-")}-{video.stem}.jpg';job.update(video=video.name,playbackSeconds=positions[idx],srtClock=datetime.datetime.fromtimestamp(times[idx],datetime.timezone(datetime.timedelta(hours=10))).isoformat(),file=name,status='pending');break
  jobs.append(job)
def extract(j):
 if j['status']!='pending':return j
 cmd=[ff,'-hide_banner','-loglevel','error','-nostdin','-ss',str(j['playbackSeconds']),'-i',str(root/j['video']),'-frames:v','1','-q:v','2','-threads','2',str(out/j['file']),'-y']
 res=subprocess.run(cmd,capture_output=True,text=True);j['status']='extracted' if res.returncode==0 and (out/j['file']).exists() else 'failed';j['error']=res.stderr[-400:] if res.returncode else ''
 print(j['ride'],j['role'],j['status'],flush=True);return j
with ThreadPoolExecutor(max_workers=2) as pool:jobs=list(pool.map(extract,jobs))
fontpath='C:/Windows/Fonts/arial.ttf';font=ImageFont.truetype(fontpath,21);small=ImageFont.truetype(fontpath,17)
for flight,ids in enumerate([range(1,10),range(10,19),range(19,24)],1):
 cellw=800;cellh=515;sheet=Image.new('RGB',(1600,len(ids)*cellh),(22,24,29));draw=ImageDraw.Draw(sheet)
 for row,i in enumerate(ids):
  for col,role in enumerate(['start','end']):
   j=next(z for z in jobs if z['ride']==i and z['role']==role);x=col*cellw;y=row*cellh
   draw.text((x+10,y+8),f'Ride {i:02} {role.upper()} | {j["clock"]} Brisbane',font=font,fill='white')
   if j['status']=='extracted':
    with Image.open(out/j['file']) as im:
     im.thumbnail((780,439));sheet.paste(im,(x+10,y+40))
    pos=j['playbackSeconds'];draw.text((x+10,y+482),f'{j["video"]} | {int(pos//60):02}:{pos%60:06.3f}',font=small,fill='#d5d8e1')
   else:draw.text((x+40,y+230),'NO MATCHING FOOTAGE',font=font,fill='#ffb86b')
 sheet.save(out/f'flight-{flight}-start-end.jpg',quality=91)
keys=list(dict.fromkeys(k for j in jobs for k in j))
with (out/'frame-index.csv').open('w',newline='',encoding='utf-8-sig') as f:w=csv.DictWriter(f,fieldnames=keys);w.writeheader();w.writerows(jobs)
(out/'frame-index.json').write_text(json.dumps(jobs,indent=2));parts=['<!doctype html><meta charset="utf-8"><title>VT408 ride frames</title><style>body{background:#181a20;color:white;font:16px Arial}section{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin-bottom:25px}img{width:100%}figure{margin:0}a{color:#9dd9ff}</style><h1>VT408 ride start / 20-second end frames</h1><p>30 October 2026, Brisbane UTC+10. Ends are VT timer-window ends, not confirmed wave ends. Playback mapped from SRT timestamps, without clock adjustment. Images extracted directly from MP4; contact sheets add captions.</p>']
for i in range(1,24):
 parts.append(f'<h2>Ride {i}</h2><section>')
 for j in [z for z in jobs if z['ride']==i]:
  parts.append('<figure><figcaption>'+html.escape(f'{j["role"].upper()} {j["clock"]} | {j["video"]}')+'</figcaption>')
  if j['status']=='extracted':parts.append(f'<a href="{j["file"]}"><img loading="lazy" src="{j["file"]}"></a>')
  else:parts.append('<p>No matching footage</p>')
  parts.append('</figure>')
 parts.append('</section>')
(out/'index.html').write_text(''.join(parts),encoding='utf-8')
with zipfile.ZipFile(base/'VT408-ride-start-end-frames.zip','w',compression=zipfile.ZIP_STORED) as z:
 for p in out.iterdir():
  if p.is_file():z.write(p,p.name)
print('RESULT',len([j for j in jobs if j['status']=='extracted']),'extracted;',[(j['ride'],j['role'],j['status']) for j in jobs if j['status']!='extracted'],flush=True)
