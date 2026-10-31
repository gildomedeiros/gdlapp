import json,collections,math
from pathlib import Path
root=Path('C:/Users/gildo/temp/408');out=Path('C:/Users/gildo/gdlapp/cam3/exports/vt408')
all_results=[]
def nonzero(x):return any(abs(x.get(k) or 0)>1e-5 for k in ['submittedForwardMps','submittedRightMps'])
for p in sorted(root.glob('*.jsonl')):
 rows=[];errors=[]
 for i,line in enumerate(p.open(encoding='utf-8-sig'),1):
  try:rows.append(json.loads(line))
  except Exception as e:errors.append([i,str(e)])
 movements=[x for x in rows if x.get('event')=='movement_cycle'];aim={x.get('cycleId'):x for x in rows if x.get('event')=='aiming_cycle'}
 events=collections.Counter(x.get('event') for x in rows)
 def count(key):return dict(collections.Counter(str(x.get(key)) for x in movements))
 groups=[]
 for x in movements:
  key=(x.get('comeToMePhase',x.get('phase')),x.get('comeToMeReason',x.get('reason')),x.get('routeStatus'),x.get('boundaryRecoveryFlow'),nonzero(x))
  if groups and groups[-1]['key']==key:
   groups[-1]['end']=x.get('timestamp');groups[-1]['cycles']+=1;groups[-1]['last']=x
  else:groups.append({'key':key,'start':x.get('timestamp'),'end':x.get('timestamp'),'cycles':1,'first':x,'last':x})
 journeys={}
 for x in movements:
  jid=x.get('journeyId');
  if not jid:continue
  a=journeys.setdefault(jid,{'first':x.get('timestamp'),'last':x.get('timestamp'),'commands':0,'statuses':collections.Counter(),'blocks':collections.Counter(),'recovery':collections.Counter()})
  a['last']=x.get('timestamp');a['commands']+=nonzero(x);a['statuses'][str(x.get('routeStatus'))]+=1;a['blocks'][str(x.get('pathBlockReason'))]+=1;a['recovery'][str(x.get('routeRecoveryStatus'))]+=1
 def brief(x):
  a=aim.get(x.get('cycleId'),{})
  keys=['timestamp','cycleId','comeToMePhase','comeToMeReason','phase','reason','journeyId','routeStatus','pathBlockReason','routeRecoveryStatus','routeRecoveryAttempts','boundaryRecoveryFlow','currentBoundaryDistanceM','currentSurferDirectDistanceM','capturedSurferDirectDistanceM','requiredPathClearanceMetres','requiredPlanningClearanceMetres','failedSegmentClearanceM','failedSegmentBoundaryM','submittedForwardMps','submittedRightMps','returnPending','returnBoundaryDistanceM','returnTargetRemainingM','translationPurpose']
  r={k:x[k] for k in keys if k in x};r.update({k:a[k] for k in ['aircraftLatitude','aircraftLongitude','aircraftHeadingDeg','targetLatitude','targetLongitude','owner','authorityReason'] if k in a});return r
 result={'file':p.name,'parseErrors':errors,'events':dict(events),'first':rows[0] if rows else None,'last':rows[-1] if rows else None,'movementKeys':list(movements[0]) if movements else [],'phaseCounts':count('phase'),'reasonCounts':count('reason'),'pathBlocks':count('pathBlockReason'),'recoveries':count('routeRecoveryStatus'),'boundaryFlows':count('boundaryRecoveryFlow'),'translationCounts':count('translationPurpose'),'nonzeroCommands':sum(map(nonzero,movements)),'journeys':journeys,'runs':[{'key':g['key'],'start':g['start'],'end':g['end'],'cycles':g['cycles'],'first':brief(g['first']),'last':brief(g['last'])} for g in groups], 'otherEvents':[x for x in rows if x.get('event') in ['user_start','configuration_start_blocked','vt40_configuration','retreat_settings_config','journey_outcome','movement_transition','retreat_started','retreat_finished','retreat_boundary_blocked','retreat_boundary_allowed','authority_reason','pause','resumed','stop','state','enable_result','sdk_callback_processed','lora_connection_error','session_end']]}
 all_results.append(result)
 print(json.dumps({k:result[k] for k in ['file','phaseCounts','reasonCounts','pathBlocks','recoveries','boundaryFlows','translationCounts','nonzeroCommands']},default=dict))
(out/'flight_analysis.json').write_text(json.dumps(all_results,indent=2,default=dict),encoding='utf-8')
