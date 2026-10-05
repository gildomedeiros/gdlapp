import zipfile,json,datetime,math,pathlib,xml.etree.ElementTree as ET
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip');n=next(n for n in z.namelist() if '_095812_' in n);t=datetime.datetime.fromisoformat('2026-10-04T10:04:16.487+10:00').timestamp()*1000;b=None;configs=[]
for l in z.open(n):
 r=json.loads(l)
 if r.get('event')=='aiming_cycle':
  d=abs(r['epochMs']-t)
  if b is None or d<b[0]:b=(d,r)
 if 'config' in r.get('event','') or 'shoreline' in r.get('event',''):configs.append(r)
print('CONFIG',json.dumps(configs[:8])[:16000])
pathlib.Path('exports/vt403/snapshot100416_data.json').write_text(json.dumps(b[1]),encoding='utf-8')
