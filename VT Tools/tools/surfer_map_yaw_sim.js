// Results are immutable full-log replay snapshots, never accumulated by hovering.
const yawSimEnable=document.getElementById('yaw-sim-enabled');
const yawSimRun=document.getElementById('yaw-sim-run');
const yawSimStatus=document.getElementById('yaw-sim-status');
const yawSimCsv=document.getElementById('yaw-sim-csv');
let yawSimResult=null,yawSimGeneration=0,yawSimBusy=false;
const yawSimRays=document.createElementNS(svgNS,'g');
yawSimRays.id='yaw-sim-rays';yawSimRays.setAttribute('pointer-events','none');svg.append(yawSimRays);
function yawSimulationRow(id){return yawSimEnable.checked?yawSimResult?.points[id]:null;}
function yawSimulationToken(){return yawSimEnable.checked?yawSimResult?.token:null;}
function yawSimulationLines(id){return yawSimulationRow(id)?.cardLines||[];}
function yawSimulationDetailLines(lines,id){
 return lines.flatMap(line=>line.text.startsWith('Drone heading:')?[line,...yawSimulationLines(id).map(text=>({text,index:-1}))]:[line]);
}
function updateYawSimulationView(id){
 yawSimRays.replaceChildren();
 const row=id&&yawSimulationRow(id),detail=document.getElementById('yaw-sim-point');
 if(!row){detail.textContent='';return;}
 const degree=value=>Number.isFinite(value)?value.toFixed(2)+'°':'unavailable';
 detail.textContent=id+' · '+row.cardLines.join('\n')+'\n'+row.reason+
  '\nLogged heading '+degree(row.droneHeading)+' · centred '+degree(row.centredBearing)+
  '\nFixed target '+degree(row.baselineTarget)+' · error '+degree(row.baselineError)+
  '\nSim target '+degree(row.simTarget)+' · error '+degree(row.simError)+
  '\nFixed offset '+degree(row.baselineOffset)+' ('+row.baselineSource+')'+
  '\nRide '+(row.riding===true?'yes':row.riding===false?'no':'unknown')+' · logged profile '+(row.rotationProfile||'unknown');
 if(!row.valid)return;
 // Draw at the replay's causally paired drone position, not the map's selection.
 const lat0=Number(svg.dataset.originLat),lon0=Number(svg.dataset.originLon);
 const x=Number(svg.dataset.originX)+6371000*(row.droneLongitude-lon0)*Math.PI/180*Math.cos(lat0*Math.PI/180)*unitsPerMetre;
 const y=Number(svg.dataset.originY)-6371000*(row.droneLatitude-lat0)*Math.PI/180*unitsPerMetre;
 for(const [bearing,color,dash,label] of [[row.centredBearing,'#687785','3 3','centred'],[row.baselineTarget,'#e58713','8 4','fixed'],[row.simTarget,'#8c39c6','','simulated']]){
  if(!Number.isFinite(bearing))continue;
  const ray=document.createElementNS(svgNS,'line'),angle=bearing*Math.PI/180,length=row.distance*unitsPerMetre;
  for(const [key,value] of Object.entries({x1:x,y1:y,x2:x+Math.sin(angle)*length,y2:y-Math.cos(angle)*length,stroke:color,'stroke-width':2.5,'stroke-opacity':.6,'stroke-dasharray':dash,'vector-effect':'non-scaling-stroke'}))ray.setAttribute(key,String(value));
  ray.dataset.kind=label;yawSimRays.append(ray);
 }
}
function invalidateYawSimulation(){
 ++yawSimGeneration;yawSimResult=null;yawSimCsv.hidden=true;yawSimCsv.removeAttribute('href');
 document.getElementById('yaw-sim-summary').textContent='';updateYawSimulationView(null);clearDetails();
 yawSimRun.disabled=!yawSimEnable.checked||yawSimBusy;
 yawSimStatus.textContent=yawSimEnable.checked?'Ready. Run to replay the complete source logs.':'Simulation off.';
 pairMarkers.forEach(updateMarker);
}
yawSimEnable.addEventListener('change',invalidateYawSimulation);
document.querySelectorAll('[data-yaw-param]').forEach(input=>input.addEventListener('input',invalidateYawSimulation));
yawSimRun.addEventListener('click',async()=>{
 if(yawSimBusy||!yawSimEnable.checked)return;
 const inputs=[...document.querySelectorAll('[data-yaw-param]')];
 if(!inputs.every(input=>input.reportValidity()&&Number.isFinite(input.valueAsNumber))){yawSimStatus.textContent='Check simulation settings.';return;}
 if(location.protocol!=='http:'){yawSimStatus.textContent='Simulation requires the local map server (--serve).';return;}
 invalidateYawSimulation();const generation=yawSimGeneration;
 yawSimBusy=true;yawSimRun.disabled=true;yawSimStatus.textContent='Replaying all GPS receipts in the source logs…';
 try{
  const response=await fetch(mapApi('/yaw-simulation'),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({parameters:Object.fromEntries(inputs.map(input=>[input.dataset.yawParam,input.valueAsNumber]))})});
  const result=await response.json();if(!response.ok)throw Error(result.error||'Simulation failed');
  if(generation!==yawSimGeneration)return;
  yawSimResult=result;yawSimCsv.href=result.csvUrl;yawSimCsv.download='yaw-offset-replay.csv';yawSimCsv.hidden=false;
  if(window.parent!==window)window.parent.postMessage({type:'yaw-simulation-updated'},location.origin);
  const s=result.summary;
  yawSimStatus.textContent='Ready: '+s.valid+' valid observations / '+s.observations+' receipts across the full source logs.';
  document.getElementById('yaw-sim-summary').textContent=s.directionSwitches+' direction switches · '+s.quietResets+' quiet resets · '+s.uncertainPeriods+' uncertain periods. Time: + '+s.positiveSeconds.toFixed(1)+'s / − '+s.negativeSeconds.toFixed(1)+'s / centre '+s.centredSeconds.toFixed(1)+'s / unknown '+s.unknownSeconds.toFixed(1)+'s. Targets and errors only; no predicted aircraft motion or yaw commands.';
  pairMarkers.forEach(updateMarker);updateDroneHeadingHover();
 }catch(error){if(generation===yawSimGeneration)yawSimStatus.textContent=error.message;}
 finally{yawSimBusy=false;yawSimRun.disabled=!yawSimEnable.checked;}
});
