import zipfile,json,datetime
z=zipfile.ZipFile('C:/Users/gildo/temp/v4 test 2.zip');n=next(n for n in z.namelist() if '_061049_' in n)
t=datetime.datetime.fromisoformat('2026-10-04T06:12:49.824+10:00').timestamp()*1000
rows=[json.loads(l) for l in z.open(n)]
for event in ['movement_cycle','aiming_cycle','retreat_cycle']:
 r=min((r for r in rows if r['event']==event),key=lambda r:abs(r['epochMs']-t));print(event,json.dumps(r))
