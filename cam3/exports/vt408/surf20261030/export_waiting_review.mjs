import fs from 'node:fs/promises';
import {createRequire} from 'node:module';
const require=createRequire('C:/Users/gildo/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/package.json');
const {Workbook}=require('@oai/artifact-tool');
const base=new URL('./',import.meta.url);
const rows=JSON.parse(await fs.readFile(new URL('waiting-review-data.json',base),'utf8'));
for(const r of rows){
 r.nearestRetreatStart=r.previous_nearestRetreatStart;
 r.nearestRetreatVideo=r.previous_retreatVideo;
 r.nearestRetreatPlayback=r.previous_retreatPlayback;
 r.nearestRetreatDistanceM=r.previous_retreatTriggerDistanceM;
 r.nearestRetreatSecondsRelativeToRide=r.previous_retreatSecondsRelativeToRide;
 r.gpsGapsClock=r.previous_gpsGapsClock;
 r.gpsGapsVideoPlayback=r.previous_gpsGapsPlayback;
 const gaps=JSON.parse(r.allGapDetailsJson);
 r.gpsGapStartTimestamps=gaps.inside.map(g=>g.startTime).join('; ');
 r.gpsGapEndTimestamps=gaps.inside.map(g=>g.endTime).join('; ');
 r.gpsGapDurationsSeconds=gaps.inside.map(g=>g.seconds).join('; ');
 for(const role of ['before','after']){
  const g=gaps[role]||{};
  r[role+'RideNearestGapStart']=g.startTime||'';
  r[role+'RideNearestGapEnd']=g.endTime||'';
  r[role+'RideNearestGapSeconds']=g.seconds??'';
 }
}
const keys=[...new Set(rows.flatMap(r=>Object.keys(r)))];
const primary=keys.filter(k=>!k.startsWith('previous_') && k!=='allGapDetailsJson');
const headers=[...primary,'allGapDetailsJson',...keys.filter(k=>k.startsWith('previous_'))];
const matrix=[headers,...rows.map(r=>headers.map(k=>r[k]??''))];
const wb=Workbook.create(),sheet=wb.worksheets.add('Waiting and rides');
sheet.getRange('A1').write(matrix);
wb.recalculate();
function col(n){let s='';while(n){n--;s=String.fromCharCode(65+n%26)+s;n=Math.floor(n/26)}return s}
const values=sheet.getRange(`A1:${col(headers.length)}${matrix.length}`).values;
if(values.length!==24||values[0].length!==headers.length)throw Error('Shape mismatch');
for(let i=0;i<matrix.length;i++)for(let j=0;j<headers.length;j++)if(typeof matrix[i][j]==='number' && Math.abs(values[i][j]-matrix[i][j])>1e-9)throw Error(`Changed numeric value ${i},${j}`);
const quote=v=>'"'+String(v??'').replaceAll('"','""')+'"';
// Preserve ISO timestamp text and timezone exactly; workbook auto-types date strings.
await fs.writeFile(new URL('VT408-waiting-and-wave-rides.csv',base),'\ufeff'+matrix.map(r=>r.map(quote).join(',')).join('\r\n')+'\r\n','utf8');
console.log(JSON.stringify({rows:rows.length,columns:headers.length,waitingReferences:rows.filter(r=>r.waitingTimestamp).length,missingWaiting:rows.filter(r=>!r.waitingTimestamp).map(r=>r.ride),file:'VT408-waiting-and-wave-rides.csv'}));
