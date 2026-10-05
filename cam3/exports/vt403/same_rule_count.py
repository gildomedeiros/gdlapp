import json,collections
r=json.load(open('exports/vt403/shoreward_full_day_warnings.json',encoding='utf-8'));groups=collections.defaultdict(list)
for e in r['events']:groups[e['log']].append(e)
for n,es in groups.items():print(n,len(es),sum(e['rideWithin20s'] for e in es),sum(not e['rideWithin20s'] for e in es))
es=r['events'];print('TOTAL',len(es),sum(e['rideWithin20s'] for e in es),sum(not e['rideWithin20s'] for e in es))
