import json,math
p='C:/Users/gildo/temp/3.8/cam3_full_2026-10-03_094458_725_8c2c3251.jsonl';first=None;frame=None
for l in open(p,encoding='utf-8-sig'):
 r=json.loads(l)
 if r.get('event')=='aiming_cycle' and r.get('session')==1 and r.get('targetLatitude') is not None and r.get('state')=='AIMING' and first is None:first=r
 if r.get('event')=='aiming_cycle' and r.get('timestamp')=='2026-10-03T10:07:21.460+10:00':frame=r
print('START',json.dumps(first))
def dist(a,b,c,d):
 p,q=map(math.radians,[a,c]);x=math.sin((q-p)/2)**2+math.cos(p)*math.cos(q)*math.sin(math.radians(d-b)/2)**2;return 6371000*2*math.atan2(math.sqrt(x),math.sqrt(1-x))
print('DRONE_TO_SURFER_START',dist(first['targetLatitude'],first['targetLongitude'],frame['aircraftLatitude'],frame['aircraftLongitude']))
