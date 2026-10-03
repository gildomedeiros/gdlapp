import csv,json
from pathlib import Path
from datetime import datetime

root=Path(__file__).resolve().parent
data=json.loads((root/'VT38_2026-10-03_analysis.json').read_text(encoding='utf-8'))
details={d['file']:d for d in json.loads((root/'VT38_details.json').read_text())}
periods=list(csv.DictReader((root/'VT38_retreat_periods.csv').open(newline='')))
def seconds(t):
 h,m,s=t.split(':');return int(h)*3600+int(m)*60+float(s)
def overlap(a,b,c,d):return max(0,min(b,d)-max(a,c))
rides=[]
for run in data:
 name=Path(run['file']).name
 for start in run['rides']:
  if start['rideEvent']!='ride_started':continue
  end=next((r for r in run['rides'] if r['rideEvent']=='ride_expired' and r['rideStartedAtMs']==start['rideStartedAtMs']),None)
  st=start['timestamp'].split('T')[1].split('+')[0];et=end['timestamp'].split('T')[1].split('+')[0] if end else ''
  a,b=seconds(st),seconds(et) if end else seconds(st)+20
  retreats=[r for r in periods if r['file']==name and overlap(a,b,seconds(r['start']),seconds(r['end']))>0]
  gaps=[g for g in details[name]['gapsOver3s'] if overlap(a,b,seconds(g['start']),seconds(g['end']))>0]
  rides.append(dict(file=name,start=st,timerExpiry=et,observedTimerSeconds=round((end['monoMs']-start['monoMs'])/1000,3) if end else None,triggerSpeedKmh=round(start['speedKmh'],2),configuredRideSeconds=start['rideDurationMs']/1000,retreatPeriodsOverlapping=len(retreats),retreatOverlapSeconds=round(sum(overlap(a,b,seconds(r['start']),seconds(r['end'])) for r in retreats),3),gpsGapsOver3sOverlapping=len(gaps),longestOverlappingPacketGapSeconds=max((g['seconds'] for g in gaps),default=0),startRecord=start['record'],expiryRecord=end['record'] if end else None))
with (root/'VT38_wave_rides.csv').open('w',newline='',encoding='utf-8') as f:
 w=csv.DictWriter(f,fieldnames=rides[0].keys());w.writeheader();w.writerows(rides)
lines=['Detected wave-ride timeline (added from logged ride events)','All 17 entries are app-detected ride windows on 3 October 2026, UTC+10. Start requires the configured GPS speed detector; the end shown is expiry of the fixed 20-second timer, not observed physical wave end. Trigger speed is the logged detection speed, not maximum speed over the ride. No video confirmation was available.','', 'Start         Timer expiry  Trigger km/h  Retreat overlap  Packet gap >3 s']
for r in rides:
 lines.append(f"{r['start']:<13} {r['timerExpiry']:<13} {r['triggerSpeedKmh']:>7.2f}       {r['retreatOverlapSeconds']:>6.3f} s         {r['longestOverlappingPacketGapSeconds']:>5.3f} s")
lines.extend(['','Counts by active session: 08:48 session 0; 08:52 session 8; 09:27 session 2; 09:46 session 7.',f"{sum(r['retreatPeriodsOverlapping']>0 for r in rides)} of 17 detected ride windows overlapped retreat commands. {sum(r['gpsGapsOver3sOverlapping']>0 for r in rides)} overlapped a packet gap longer than three seconds. Gap duration is the full interval between received packets, including portions outside the ride window. Retreat overlap is the overlap of logged period intervals, not integrated physical motion.",'09:06:29.002 ride detection occurred during retreat after GPS freshness returned at 09:06:27.974. 09:36:52.978 detection followed the 6.478-second packet gap ending 09:36:51.926. These sequences do not prove the GPS gap caused detection or identify the physical wave start.','10:06:22.488 and 10:16:02.019 show that ride detection and retreat can coexist. Current code preserves ride yaw while retreat owns backward translation.','The final session returns at 10:04:30.846 and 10:12:58.103 occurred about five minutes after the preceding ride timer expiries (09:59:22.001 and 10:07:48.043); their logged cause was no_ride_timeout.','Complete event record references and overlap values are in VT38_wave_rides.csv.'])
appendix='\n'.join(lines)+'\n'
(root/'VT38_wave_rides.txt').write_text(appendix,encoding='utf-8')
report=root/'VT38_flight_findings.txt'
text=report.read_text(encoding='utf-8').split('\nDetected wave-ride timeline (added from logged ride events)')[0]
report.write_text(text.rstrip()+'\n\n'+appendix,encoding='utf-8')
print(appendix)
