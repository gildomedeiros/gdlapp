import json,collections,math,statistics
from pathlib import Path
out=Path('exports/vt408/surf20261030');results=[]
def stats(v):
 v=sorted(v);return {'min':v[0],'median':statistics.median(v),'p95':v[int(.95*(len(v)-1))],'max':v[-1]} if v else None
for p in sorted(Path('C:/Users/gildo/temp/311026').glob('*.jsonl')):
 if p.stat().st_size<100000000:continue
 aims=[];moves=[];pack=[];gaps=[];ret=[];gim=[];after=[];stopAt=None;lastaim=None;cfg={};retcfg={};returnend=[]
 for l in p.open():
  x=json.loads(l);e=x['event']
  if e=='vt40_configuration':cfg=json.loads(x['effectiveSettingsJson'])
  if e=='retreat_settings_config':retcfg=json.loads(x['effectiveJson'])
  if e=='lora_packet':pack.append(x)
  if e=='lora_gap':gaps.append(x)
  if e=='gimbal_pitch_result':gim.append(x)
  if e=='stop' and 'returning_home' in x.get('detail',''):stopAt=x['epochMs']
  if stopAt and e=='yaw_command':after.append(x)
  if e=='aiming_cycle':
   lastaim=x
   if x['state']=='AIMING':aims.append(x)
  if e=='movement_cycle' and lastaim and lastaim['state']=='AIMING':
   x['_aim']=lastaim;moves.append(x)
  if e in ['retreat_started','retreat_finished']:ret.append(x)
  if e=='movement_cycle' and x['reason']=='return_arrival_tolerance':returnend.append(x)
 starts=[x for x in moves if x['rideEvent']=='ride_started'];ride=[]
 for s in starts:
  z=[x for x in moves if s['epochMs']<=x['epochMs']<s['epochMs']+20000];fresh=[x for x in z if (x['_aim'].get('targetAgeMs') or 0)<=1500];yawerr=[abs(x['_aim']['relativeBearingDeg']) for x in fresh if x['_aim'].get('relativeBearingDeg') is not None];mi=min(z,key=lambda x:x['surferDistanceM'] if x['surferDistanceM'] is not None else 1e9)
  ride.append({'start':s['timestamp'],'startSpeed':s['speedKmh'],'maxSpeed':max(x['speedKmh'] or 0 for x in z),'minimumDistance':mi['surferDistanceM'],'minimumAt':mi['timestamp'],'minimumReason':mi['reason'],'retreatCommands':sum(x['translationPurpose']=='retreat' and abs(x.get('submittedForwardMps') or 0)>1e-6 for x in z),'staleCycles':sum(x['speedEvidence']=='stale_gps' for x in z),'cycles':len(z),'maxGpsAge':max(x['_aim'].get('targetAgeMs') or 0 for x in z),'yawErrorFresh':stats(yawerr)})
 activePackets=[x for x in pack if aims[0]['epochMs']<=x['epochMs']<=aims[-1]['epochMs']];pg=[(b['epochMs']-a['epochMs'])/1000 for a,b in zip(activePackets,activePackets[1:])];missing=sum(x.get('missingBefore',0) for x in activePackets);missmod=collections.Counter()
 for a,b in zip(activePackets,activePackets[1:]):
  if 0<b['sequence']-a['sequence']<10000:
   for q in range(a['sequence']+1,b['sequence']):missmod[q%10]+=1
 retained=[x for x in aims if x.get('retainedTargetAiming')];bad=[x for x in aims if x.get('inputProblem') not in [None,'none']];fresh=[x for x in aims if x.get('targetAgeMs') is not None and x['targetAgeMs']<=1500];yaw=[abs(x['relativeBearingDeg']) for x in fresh if x.get('relativeBearingDeg') is not None]
 mi=min(moves,key=lambda x:x['surferDistanceM'] if x['surferDistanceM'] is not None else 1e9)
 result={'file':p.name,'start':aims[0]['timestamp'],'end':aims[-1]['timestamp'],'filming':cfg['filmingSeparationMetres'],'retreat':retcfg['minimumDistanceMetres'],'rides':ride,'activePackets':len(activePackets),'missing':missing,'missingModulo10':dict(missmod),'packetGaps':stats(pg),'largestGaps':sorted([{'start':a['timestamp'],'end':b['timestamp'],'seconds':(b['epochMs']-a['epochMs'])/1000,'missing':b.get('missingBefore'),'rssi':b.get('rssi'),'snr':b.get('snr')} for a,b in zip(activePackets,activePackets[1:])],key=lambda x:x['seconds'],reverse=True)[:6],'RSSI':stats([x['rssi'] for x in activePackets]),'SNR':stats([x['snr'] for x in activePackets]),'retainedAimCycles':len(retained),'inputProblems':dict(collections.Counter(x.get('inputProblem') for x in bad)),'yawErrorFresh':stats(yaw),'maxTargetAge':max(x.get('targetAgeMs') or 0 for x in aims),'minimumDistance':{k:mi.get(k) for k in ['timestamp','surferDistanceM','reason','translationPurpose','submittedForwardMps','submittedRightMps','retreatActive','currentBoundaryDistanceM']},'minimumAim':mi['_aim'],'gimbalResults':dict(collections.Counter(str(x.get('success',x.get('detail'))) for x in gim)),'commandsAfterRTH':len(after),'returnCompletion':returnend}
 results.append(result);print(json.dumps({k:v for k,v in result.items() if k not in ['minimumAim','returnCompletion','rides']},indent=1));print('RIDES',json.dumps(ride))
(out/'detail.json').write_text(json.dumps(results,indent=2));print('DONE')
