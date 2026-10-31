import re,json,bisect,datetime,csv
from pathlib import Path
root=Path('E:/DCIM/100MEDIA');out=Path('C:/Users/gildo/gdlapp/cam3/exports/vt408/surf20261030');files=[]
def epoch(s):return datetime.datetime.fromisoformat(s).replace(tzinfo=datetime.timezone(datetime.timedelta(hours=10))).timestamp()
def play(s):
 h,m,sec=s.replace(',','.').split(':');return int(h)*3600+int(m)*60+float(sec)
def fmt(v):
 m,s=divmod(round(v),60);h,m=divmod(m,60);return f'{h:02}:{m:02}:{s:02}' if h else f'{m:02}:{s:02}'
for p in root.glob('*.SRT'):
 text=p.read_text(encoding='utf-8-sig',errors='replace');matches=re.findall(r'(\d\d:\d\d:\d\d,\d{3}) --> [^\n]+\n[^\n]+\n(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})',text)
 if not matches:continue
 times=[epoch(t) for _,t in matches];pos=[play(t) for t,_ in matches];vid=list(root.glob(p.stem+'*.MP4'));files.append({'srt':p.name,'video':vid[0].name if vid else '(MP4 missing: '+p.stem+')','times':times,'positions':pos})
def locate(t):
 ts=epoch('2026-10-30 '+t)
 for f in files:
  if f['times'][0]<=ts<=f['times'][-1]+.1:
   i=bisect.bisect_left(f['times'],ts);i=min(i,len(f['times'])-1)
   if i and abs(f['times'][i-1]-ts)<abs(f['times'][i]-ts):i-=1
   return f['video'],fmt(f['positions'][i])
 return 'not covered',''
rides=json.loads((out/'rides-and-gaps.json').read_text());result=[];csvrows=[]
for i,r in enumerate(rides,1):
 v,pp=locate(r['start']);gs=[]
 for g in r['inside']:
  if g['seconds']<1.5:continue
  a,ap=locate(g['startTime']);b,bp=locate(g['endTime']);z={'clockStart':g['startTime'],'clockEnd':g['endTime'],'video':a,'playbackStart':ap,'playbackEnd':bp,'seconds':g['seconds']};gs.append(z)
  csvrows.append(dict(ride=i,rideClock=r['start'],rideVideo=v,ridePlayback=pp,**z))
 x=dict(ride=i,clock=r['start'],video=v,playback=pp,gaps=gs);result.append(x)
 print(f'| {i} | {r["start"][:8]} | {v} | {pp} | '+('; '.join(f'{g["playbackStart"]}–{g["playbackEnd"]} ({g["seconds"]:.2f}s)' for g in gs) or 'None >=1.5 s')+' |')
(out/'rides-video-locations.json').write_text(json.dumps(result,indent=2))
with (out/'rides-video-locations.csv').open('w',encoding='utf-8-sig',newline='') as f:
 w=csv.DictWriter(f,fieldnames=list(csvrows[0]));w.writeheader();w.writerows(csvrows)

