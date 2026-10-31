import sys,re,datetime,subprocess,json
from pathlib import Path
from PIL import Image,ImageDraw
base=Path(__file__).parent
sys.path.insert(0,str(base/'frame-tools'))
import imageio_ffmpeg
out=base/'paddling-review';out.mkdir(exist_ok=True)
root=Path('E:/DCIM/100MEDIA')
matches=re.findall(r'(\d\d:\d\d:\d\d,\d{3}) --> [^\n]+\n[^\n]+\n(\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3})',(root/'DJI_0626.SRT').read_text(encoding='utf-8-sig'))
sheet=Image.new('RGB',(1600,4*475),'white');draw=ImageDraw.Draw(sheet)
for i,s in enumerate(range(14,22)):
 target=f'2026-10-30 14:03:{s:02}.000'
 play,clock=min(matches,key=lambda a:abs((datetime.datetime.fromisoformat(a[1])-datetime.datetime.fromisoformat(target)).total_seconds()))
 h,m,sec=map(float,play.replace(',','.').split(':'));p=h*3600+m*60+sec
 dest=out/f'ride01-{s}.jpg'
 subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(),'-hide_banner','-loglevel','error','-nostdin','-ss',str(p),'-i',str(root/'DJI_0626.MP4'),'-frames:v','1','-q:v','2',str(dest),'-y'],check=True)
 im=Image.open(dest);im.thumbnail((800,450));x=i%2*800;y=i//2*475;sheet.paste(im,(x,y+25));draw.text((x+10,y+5),clock,fill='black')
sheet.save(out/'ride01-transition.jpg')
print(out/'ride01-transition.jpg')
