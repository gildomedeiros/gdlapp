import json,datetime
p='C:/Users/gildo/temp/3.8/cam3_full_2026-10-03_094458_725_8c2c3251.jsonl';t=datetime.datetime.fromisoformat('2026-10-03T10:07:21.428+10:00').timestamp()*1000;b=None
for l in open(p,encoding='utf-8-sig'):
 r=json.loads(l)
 if 'retreat' in r.get('event','') and r.get('minimumDistanceM') is not None:
  d=abs(r['epochMs']-t)
  if b is None or d<b[0]:b=(d,r)
print(b[1]['timestamp'],b[1]['minimumDistanceM'])
