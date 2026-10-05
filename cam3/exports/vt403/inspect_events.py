import json,collections
p='C:/Users/gildo/temp/vt 4.03/logs/cam3_full_2026-10-04_083821_554_1a3054b0.jsonl'
counts=collections.Counter();samples={};events=collections.Counter()
for line in open(p):
 r=json.loads(line);events[r.get('event')]+=1
 if r.get('event')=='movement_cycle':
  k=r.get('plannerEvent');counts[k]+=1;samples.setdefault(k,r)
print(counts)
print([x for x in events if any(s in x for s in ['approach','movement','come'])])
for k,r in samples.items():
 if k!='none':print(k,{f:r.get(f) for f in ['timestamp','phase','reason','journeyKind','plannedTravelM','forwardProgressM','approachRemainingM','positioningMode']})
