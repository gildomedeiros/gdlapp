import json,datetime
p='C:/Users/gildo/temp/3.8/cam3_full_2026-10-03_094458_725_8c2c3251.jsonl';t=datetime.datetime.fromisoformat('2026-10-03T10:07:21.428+10:00').timestamp()*1000;best={};packets={}
for l in open(p,encoding='utf-8-sig'):
 r=json.loads(l);e=r.get('event');epoch=r.get('epochMs')
 if e=='lora_packet':packets[r.get('sequence')]=r.get('timestamp')
 if epoch and e in ['aiming_cycle','movement_cycle','gimbal_pitch_cycle']:
  d=abs(epoch-t)
  if e not in best or d<best[e][0]:best[e]=(d,r)
for e,(d,r) in best.items():print(e,d,json.dumps(r))
a=best.get('aiming_cycle',(0,{}))[1];print('GPS_RECEIVED',packets.get(a.get('targetSequence')))
