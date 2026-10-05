import zipfile,json,datetime
z=zipfile.ZipFile('C:/Users/gildo/temp/vt 4.03/cam3_full_2026-10-04_095812_811_bcc33e7d.zip');n=next(n for n in z.namelist() if '_083821_' in n);t=datetime.datetime.fromisoformat('2026-10-04T09:07:42.341+10:00').timestamp()*1000
rows=[]
for l in z.open(n):
 r=json.loads(l)
 if abs(r['epochMs']-t)<2500 and r['event'] in ['aiming_cycle','target_fix','gps_fix','lora_packet','lora_fix']:rows.append(r)
last=None
for r in rows:
 if r.get('targetSequence')!=last:
  print(json.dumps(r));last=r.get('targetSequence')
