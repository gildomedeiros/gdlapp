// Sidebar filtering never changes packet IDs or the underlying log samples.
const mapFilters=MAP_FILTER_DATA;
const filterPoints=new Map(mapFilters.points.map(p=>[p.id,p]));
const pairChecks=[...document.querySelectorAll('#pair-options input[data-pair]')];
const pairCheckById=new Map(pairChecks.map(c=>[c.dataset.pair,c]));
const pairMarkers=[...svg.querySelectorAll('.surfer-marker,.drone-marker')];
const pairElements=[...svg.querySelectorAll('.pair-line,.surfer-marker,.drone-marker,.map-landmark')];
const rideFilter=document.getElementById('ride-filter');
const positionFilter=document.getElementById('position-filter');
const previousPosition=document.getElementById('position-prev');
const nextPosition=document.getElementById('position-next');
const hideSeaward=document.getElementById('hide-seaward');
const hideSlowSpeed=document.getElementById('hide-slow-speed');
let eligiblePoints=mapFilters.points, eligiblePairs=new Set(eligiblePoints.map(p=>p.id));
let visiblePairs=new Set(eligiblePairs);
const rideHighlight=document.getElementById('show-ride-rings');
const speedHighlight=document.getElementById('show-speed-rings');
const speedThreshold=document.getElementById('speed-threshold');
function selectedSpeedThreshold(){
 const value=speedThreshold?.valueAsNumber;
 return Number.isFinite(value)&&value>=0?value:null;
}
function updateHighlights(){
 const invalid=selectedSpeedThreshold()===null,help=document.getElementById('speed-highlight-help');
 if(help){
  help.textContent=invalid?'Enter a speed threshold of 0 km/h or greater.':'Uses GPS speed since the previous packet, as shown in the position card.';
  help.dataset.invalid=String(invalid);
 }
 speedThreshold?.setAttribute('aria-invalid',String(invalid));
 pairMarkers.forEach(updateMarker);
}
function addMarkerRing(marker,className,stateKey,color,radius,count,total){
 if(!count)return;
 const all=count===total,ring=document.createElementNS(svgNS,'circle');
 ring.setAttribute('class',className);ring.dataset[stateKey]=all?'all':'mixed';
 ring.setAttribute('cx',marker.dataset.x);ring.setAttribute('cy',marker.dataset.y);ring.setAttribute('r',String(radius));
 ring.setAttribute('fill','none');ring.setAttribute('stroke',color);ring.setAttribute('stroke-width','2.5');
 ring.setAttribute('vector-effect','non-scaling-stroke');if(!all)ring.setAttribute('stroke-dasharray','4 3');
 marker.insertBefore(ring,marker.querySelector('.pair-halo'));
}
const layerControls=[
 {input:document.getElementById('hide-drones'),className:'drone-marker'},
 {input:document.getElementById('hide-connections'),className:'pair-line'},
 {input:document.getElementById('hide-surfers'),className:'surfer-marker'},
 {input:document.getElementById('hide-wave-line'),className:'wave-line'},
 {input:document.getElementById('hide-boundary-line'),className:'boundary-line'},
 {input:document.getElementById('hide-start-point'),className:'start-point'}
];
function applyLayerVisibility(){
 pairElements.forEach(n=>{
  const selected=n.dataset.pairs.split(' ').some(id=>visiblePairs.has(id));
  const hidden=layerControls.some(layer=>layer.input?.checked&&n.classList.contains(layer.className));
  n.style.display=selected&&!hidden?'':'none';
 });
 if(activeLandmark?.style.display==='none')clearLandmarkSelection();
}


function option(value,text){const o=document.createElement('option');o.value=value;o.textContent=text;return o;}
function positionDescription(p){
 const offset=p.offsets[rideFilter.value];
 const time=offset===undefined?p.time.slice(11,23):(offset>=0?'+':'')+offset.toFixed(1)+'s';
 return [time,Number.isFinite(p.distance)?p.distance.toFixed(1)+' m':null,
         Number.isFinite(p.pitch)?p.pitch.toFixed(1)+'°':null].filter(Boolean).join(' · ');
}
function filteredDetails(marker,pointId=null){
 let included=false;
 return marker.dataset.detail.split('\n').map((text,index)=>{
  const header=text.match(/^(S\d+)(?: ↔|:)/)||text.match(/^D\d+ ↔ (S\d+)/);
  if(header)included=visiblePairs.has(header[1])&&(!pointId||header[1]===pointId);
  return included?{text,index}:null;
 }).filter(Boolean);
}
function updateMarker(marker){
 const ids=marker.dataset.pairs.split(' ').filter(id=>visiblePairs.has(id));
 if(!ids.length)return;
 const surfer=marker.classList.contains('surfer-marker');
 marker.dataset.label=ids.map(id=>surfer?id:'D'+id.slice(1)).join(', ');
 marker.setAttribute('aria-label',filteredDetails(marker).map(line=>line.text).join('\n'));
 // A shared coordinate must use the latest *visible* packet's real direction.
 const rows=ids.map(id=>filterPoints.get(id)), latest=rows[rows.length-1];
 const bearing=surfer?latest.surferBearing:latest.droneBearing;
 marker.querySelectorAll('.travel-arrow,.position-dot,.drone-direction,.ride-ring,.speed-ring').forEach(n=>n.remove());
 if(surfer){
  if(rideHighlight?.checked!==false)addMarkerRing(marker,'ride-ring','rideState','#9b2dbd',10.5,rows.filter(p=>p.waveRiding===true).length,rows.length);
  const threshold=selectedSpeedThreshold();
  if(speedHighlight?.checked&&threshold!==null){
   addMarkerRing(marker,'speed-ring','speedState','#008baa',15,
    rows.filter(p=>Number.isFinite(p.speedKmh)&&p.speedKmh>=threshold).length,rows.length);
  }
 }
 if(!surfer&&!Number.isFinite(bearing))return;
 const arrow=Number.isFinite(bearing), icon=document.createElementNS(svgNS,arrow?'path':'circle');
 icon.setAttribute('class',surfer?(arrow?'travel-arrow':'position-dot'):'drone-direction');
 icon.setAttribute('fill',surfer?(rows.every(p=>p.accepted===true)?'#14804a':'#d96b16'):'white');
 if(arrow){
  icon.setAttribute('d',surfer?'M0,-9 L6,6 L0,3 L-6,6 Z':'M0,-5 L3,1 L1,0 L1,5 L-1,5 L-1,0 L-3,1 Z');
  icon.setAttribute('transform',`translate(${marker.dataset.x} ${marker.dataset.y}) rotate(${bearing})`);
  icon.setAttribute('stroke',surfer?'white':'none');icon.setAttribute('stroke-width','1');
 }else{icon.setAttribute('cx',marker.dataset.x);icon.setAttribute('cy',marker.dataset.y);icon.setAttribute('r','5');}
 marker.insertBefore(icon,marker.querySelector('.pair-halo'));
}
function updatePairs(){
 clearDetails();clearMeasurement();
 visiblePairs=new Set(pairChecks.filter(c=>c.checked&&eligiblePairs.has(c.dataset.pair)).map(c=>c.dataset.pair));
 applyLayerVisibility();
 pairMarkers.forEach(updateMarker);
 const single=visiblePairs.size===1?[...visiblePairs][0]:null;
 positionFilter.value=single||(!visiblePairs.size?'custom':visiblePairs.size===eligiblePoints.length?'all':'custom');
 positionFilter.querySelector('option[value="custom"]').textContent=visiblePairs.size?'Custom selection ('+visiblePairs.size+')':'No positions selected';
 const index=single?eligiblePoints.findIndex(p=>p.id===single):-1;
 previousPosition.disabled=!eligiblePoints.length||index===0;
 nextPosition.disabled=!eligiblePoints.length||index===eligiblePoints.length-1;
 document.getElementById('position-filter-status').textContent=visiblePairs.size+' of '+eligiblePoints.length+' positions shown';
 // Keep the side view on a visible point, including after Hide all / Delete.
 const desired=visiblePairs.has(pick.value)?pick.value:eligiblePoints.find(p=>visiblePairs.has(p.id))?.id;
 const marker=desired&&pairMarkers.find(n=>n.classList.contains('surfer-marker')&&n.dataset.pairs.split(' ').includes(desired));
 selectSide(marker||{dataset:{pairs:''}});
 if(desired){pick.value=desired;loadPairFov();drawSide();}
}
function showPosition(id){
 pairChecks.forEach(c=>{if(eligiblePairs.has(c.dataset.pair))c.checked=id==='all'||c.dataset.pair===id;});
 updatePairs();
 const chosen=pairCheckById.get(id);
 if(chosen){
  const list=document.getElementById('pair-options');
  const row=chosen.closest('label').getBoundingClientRect(), viewport=list.getBoundingClientRect();
  if(row.top<viewport.top)list.scrollTop+=row.top-viewport.top;
  else if(row.bottom>viewport.bottom)list.scrollTop+=row.bottom-viewport.bottom;
 }
}
function stepPosition(step){
 const index=eligiblePoints.findIndex(p=>p.id===positionFilter.value);
 const next=index<0?(step>0?0:eligiblePoints.length-1):index+step;
 if(next>=0&&next<eligiblePoints.length)showPosition(eligiblePoints[next].id);
}
function fitRide(){
 const markers=pairMarkers.filter(n=>n.dataset.pairs.split(' ').some(id=>eligiblePairs.has(id)));
 svg.querySelectorAll('.map-landmark').forEach(n=>{
  if(n.style.display!=='none'&&n.dataset.pairs.split(' ').some(id=>eligiblePairs.has(id)))markers.push(...n.querySelectorAll('.landmark-anchor'));
 });
 if(!markers.length){box=original.slice();draw();return;}
 const xs=markers.map(n=>Number(n.dataset.x)),ys=markers.map(n=>Number(n.dataset.y));
 const minX=Math.min(...xs),maxX=Math.max(...xs),minY=Math.min(...ys),maxY=Math.max(...ys);
 const rect=svg.getBoundingClientRect(), aspect=rect.width/rect.height||original[2]/original[3];
 const minSpan=Math.max(40,10*unitsPerMetre);
 let width=Math.max(minSpan,(maxX-minX)*1.2+24),height=Math.max(minSpan,(maxY-minY)*1.2+24);
 if(width/height<aspect)width=height*aspect;else height=width/aspect;
 box=[(minX+maxX-width)/2,(minY+maxY-height)/2,width,height];draw();
}
function changeRide(resetSelection=true, refit=true){
 const ride=mapFilters.rides.find(r=>r.id===rideFilter.value);
 const ridePoints=mapFilters.points.filter(p=>!ride||Object.hasOwn(p.offsets,ride.id));
 const directionPoints=ridePoints.filter(p=>!hideSeaward?.checked||p.seaward!==true);
 const threshold=selectedSpeedThreshold(),filterSpeed=hideSlowSpeed?.checked&&threshold!==null;
 eligiblePoints=directionPoints.filter(p=>!filterSpeed||!Number.isFinite(p.speedKmh)||p.speedKmh>=threshold);
 eligiblePairs=new Set(eligiblePoints.map(p=>p.id));
 pairChecks.forEach(c=>{
  const label=c.closest('label'), included=eligiblePairs.has(c.dataset.pair);
  label.hidden=!included;
  if(included){if(resetSelection)c.checked=true;label.querySelector('.pair-metadata').textContent=positionDescription(filterPoints.get(c.dataset.pair));}
 });
 document.getElementById('pair-options').scrollTop=0;
 positionFilter.replaceChildren(option('all','All positions'));
 const custom=option('custom','Custom selection');custom.disabled=true;positionFilter.append(custom);
 eligiblePoints.forEach(p=>positionFilter.append(option(p.id,p.id+' · '+positionDescription(p))));
 document.getElementById('ride-window-info').textContent=ride?
  ride.beforeSeconds+'s before / '+ride.afterSeconds+'s after ride start':
  mapFilters.rides.length?'All loaded ride windows':'All loaded positions';
 if(hideSeaward?.checked)document.getElementById('ride-window-info').textContent+=' · '+(ridePoints.length-directionPoints.length)+' seaward positions hidden';
 if(filterSpeed)document.getElementById('ride-window-info').textContent+=' · '+(directionPoints.length-eligiblePoints.length)+' below '+threshold+' km/h hidden';
 document.getElementById('fit-ride').textContent=ride?'Fit ride':'Fit all';
 updatePairs();if(refit)fitRide();
}
function initializeMapFilters(){
 hideSeaward?.addEventListener('change',()=>changeRide(false,false));
 hideSlowSpeed?.addEventListener('change',()=>changeRide(false,false));
 rideHighlight?.addEventListener('change',updateHighlights);
 speedHighlight?.addEventListener('change',updateHighlights);
 speedThreshold?.addEventListener('input',()=>{
  updateHighlights();
  if(hideSlowSpeed?.checked)changeRide(false,false);
 });
 layerControls.forEach(layer=>layer.input?.addEventListener('change',()=>{
  clearDetails();clearMeasurement();applyLayerVisibility();
 }));

 rideFilter.append(option('all',mapFilters.rides.length?'All rides':'All positions'));
 mapFilters.rides.forEach(r=>rideFilter.append(option(r.id,'Ride '+r.number+' · '+r.timestamp.slice(11,19)+' ('+r.points.length+')')));
 rideFilter.disabled=!mapFilters.rides.length;
 if(mapFilters.rides.length&&mapFilters.initialRide!=='all')rideFilter.value=mapFilters.rides[0].id;
 rideFilter.addEventListener('change',()=>changeRide());
 positionFilter.addEventListener('change',()=>showPosition(positionFilter.value));
 previousPosition.onclick=()=>stepPosition(-1);nextPosition.onclick=()=>stepPosition(1);
 document.getElementById('fit-ride').onclick=fitRide;
 pairChecks.forEach(c=>c.addEventListener('change',updatePairs));
 document.getElementById('pairs-all').onclick=()=>showPosition('all');
 document.getElementById('pairs-none').onclick=()=>showPosition('none');
 changeRide();
}
