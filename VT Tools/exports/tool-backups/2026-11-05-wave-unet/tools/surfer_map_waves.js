// Persistent crest estimates projected from detected image endpoints onto water.
const waveResults=new Map(),waveLayer=document.createElementNS(svgNS,'g');
const locallyIgnoredWaves=new Set();
waveLayer.id='cv-wave-orientations';svg.append(waveLayer);
const waveControls=document.createElement('fieldset');waveControls.id='wave-controls';
waveControls.style.cssText='border:0;border-top:1px solid #ccd;padding:8px 0;font-size:12px';
waveControls.innerHTML='<legend><strong>CV wave crests</strong></legend><p><strong>W</strong>: detect on the clean video frame. <strong>I</strong>: ignore this frame’s detection. Press W again to retry. Dashed magenta lines show estimated crest position and orientation.</p><label>Horizontal FOV estimate <input id="wave-hfov" type="number" value="74" min="20" max="120" step="1" style="width:52px"> °</label><p>74° is an uncalibrated starting estimate. Placement uses logged drone GPS, camera angles and water at the takeoff height. Check the detected line on the frame before keeping it.</p><p id="wave-status" role="status">No saved crest estimates.</p>';
document.getElementById('pair-sidebar').append(waveControls);
const waveHideLabel=document.createElement('label');
waveHideLabel.innerHTML='<input id="hide-wave-crests" type="checkbox"> Hide wave crest detections';
document.getElementById('map-layers').append(waveHideLabel);
const waveStatus=document.getElementById('wave-status'),waveToggle=document.getElementById('hide-wave-crests');
function waveMessage(text){waveStatus.textContent=text;if(captureDialog.open)setFrameStatus(text);}
function drawWaves(){
 waveLayer.replaceChildren();waveLayer.style.display=waveToggle.checked?'none':'';
 const scale=Number(svg.dataset.unitsPerMetre),lat0=Number(svg.dataset.originLat),lon0=Number(svg.dataset.originLon);
 for(const row of waveResults.values()){
  if(row.ignored||!row.crestEndpoints||row.crestEndpoints.length!==2||!row.crestEndpoints.every(p=>[p.latitude,p.longitude].every(Number.isFinite)))continue;
  const [a,b]=row.crestEndpoints.map(p=>({
   x:Number(svg.dataset.originX)+6371000*(p.longitude-lon0)*Math.PI/180*Math.cos(lat0*Math.PI/180)*scale,
   y:Number(svg.dataset.originY)-6371000*(p.latitude-lat0)*Math.PI/180*scale
  }));
  const group=document.createElementNS(svgNS,'g');group.dataset.key=row.key;group.dataset.bearing=row.bearingDegrees;
  const line=document.createElementNS(svgNS,'line');
  for(const [key,value] of Object.entries({x1:a.x,y1:a.y,x2:b.x,y2:b.y,stroke:'#ad148c','stroke-width':3,'stroke-dasharray':'6 3','vector-effect':'non-scaling-stroke','pointer-events':'stroke'}))line.setAttribute(key,value);
  const title=document.createElementNS(svgNS,'title');title.textContent=(row.point||row.gpsTimestamp)+' · estimated crest '+row.bearingDegrees+'° / '+(row.bearingDegrees+180).toFixed(1)+'° · HFOV '+row.horizontalFov+'° estimate. '+row.assumptions;
  line.append(title);group.append(line);waveLayer.append(group);
 }
 // Put GPS hit targets above these comparison marks.
 const firstMarker=svg.querySelector('.drone-marker,.surfer-marker');
 if(firstMarker)firstMarker.parentNode.insertBefore(waveLayer,firstMarker);
}
waveToggle.addEventListener('change',()=>{drawWaves();drawWaveFrameOverlay();});
const fitWaves=document.createElement('button');fitWaves.textContent='Fit saved orientations';waveControls.append(fitWaves);
fitWaves.onclick=()=>{
 if(!waveResults.size)return;
 waveToggle.checked=false;drawWaves();drawWaveFrameOverlay();
 const bounds=waveLayer.getBBox(),rect=svg.getBoundingClientRect(),aspect=rect.width/rect.height;
 let width=Math.max(80,bounds.width*1.2),height=Math.max(80,bounds.height*1.2);
 if(width/height<aspect)width=height*aspect;else height=width/aspect;
 box=[bounds.x+bounds.width/2-width/2,bounds.y+bounds.height/2-height/2,width,height];draw();
};
function drawWaveFrameOverlay(){
 document.getElementById('frame-wave-overlay')?.remove();
 if(waveToggle.checked||!captureDialog.open||captureDialog.dataset.kind==='video')return;
 const id=captureDialog.dataset.point,p=sideData[id],picture=document.getElementById('frame-picture');
 const result=[...waveResults.values()].find(r=>r.sourceFile===p?.source&&r.gpsTimestamp===p?.time&&r.frameTimestamp===p?.frame&&r.frameTimestamp===captureDialog.dataset.timestamp);
 if(!picture||!result?.imageLine)return;
 const overlay=document.createElementNS(svgNS,'svg');overlay.id='frame-wave-overlay';
 overlay.setAttribute('viewBox',`0 0 ${result.imageWidth} ${result.imageHeight}`);
 overlay.style.cssText='position:absolute;inset:0;width:100%;height:100%;pointer-events:none';
 const line=document.createElementNS(svgNS,'line'),[a,b]=result.imageLine;
 for(const [k,v] of Object.entries({x1:a[0],y1:a[1],x2:b[0],y2:b[1],stroke:'#ee00bb','stroke-width':4,'vector-effect':'non-scaling-stroke'}))line.setAttribute(k,v);
 overlay.append(line);picture.append(overlay);
}
document.addEventListener('keydown',async e=>{
 const action=e.key.toLowerCase();
 if(!['w','i'].includes(action)||e.ctrlKey||e.altKey||e.metaKey||e.isComposing||e.repeat||e.defaultPrevented)return;
 if(e.target.closest('input,textarea,select,[contenteditable]:not([contenteditable="false"]),[role="textbox"]'))return;
 if(measuring||drag||captureBusy)return;
 let id;
 if(captureDialog.open){
  e.preventDefault();
  if(captureDialog.dataset.kind==='video'){waveMessage('Use ← or → to return to a surfer-position frame before detecting its crest.');return;}
  id=captureDialog.dataset.point;
 }else{
  const highlighted=activePairLine||(!tip.hidden?activeMarker:null);
  if(!highlighted||highlighted.style.display==='none')return;
  const ids=highlighted.dataset.pairs.split(' ').filter(id=>visiblePairs.has(id));
  id=[e.target.closest('[data-capture-point]')?.dataset.capturePoint,tip.dataset.selectedPoint,pick.value,...ids].find(id=>ids.includes(id));
 }
 if(!id)return;
 e.preventDefault();e.stopPropagation();
 if(location.protocol!=='http:'){waveMessage('Wave detection needs the running map server.');return;}
 if(action==='i'){
  const p=sideData[id],saved=[...waveResults.values()].find(r=>r.sourceFile===p?.source&&r.gpsTimestamp===p?.time&&r.frameTimestamp===p?.frame);
  if(!saved){waveMessage(id+': no saved crest detection to ignore.');return;}
  captureBusy=true;
  try{
   const response=await fetch(mapApi('/wave'),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({point:id,action:'ignore'})});
   const result=await response.json();if(!response.ok)throw Error(result.error||'Could not ignore detection.');
   locallyIgnoredWaves.add(saved.key);waveResults.delete(saved.key);drawWaves();drawWaveFrameOverlay();
   waveMessage(id+': crest detection ignored. Press W to retry.');
  }catch(error){waveMessage('Could not ignore crest: '+error.message);}
  finally{captureBusy=false;}
  return;
 }
 const horizontalFov=document.getElementById('wave-hfov').valueAsNumber;
 if(!Number.isFinite(horizontalFov)||horizontalFov<20||horizontalFov>120){waveMessage('Enter a horizontal FOV estimate of 20–120°.');return;}
 captureBusy=true;waveMessage('Detecting crest on clean MP4 frame for '+id+'…');
 try{
  const response=await fetch(mapApi('/wave'),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({point:id,horizontalFov})});
  const result=await response.json();if(!response.ok)throw Error(result.error||'Wave detection failed');
  locallyIgnoredWaves.delete(result.key);waveResults.set(result.key,result);drawWaves();drawWaveFrameOverlay();
  waveMessage(id+': estimated crest '+result.bearingDegrees+'°. '+waveResults.size+' saved; magenta edge shown on its frame.');
 }catch(error){waveMessage('Crest unavailable: '+error.message);}
 finally{captureBusy=false;}
});
if(location.protocol==='http:')fetch(mapApi('/wave-results')).then(r=>{if(!r.ok)throw Error('Restart the server to enable wave detection.');return r.json();}).then(rows=>{
 for(const row of rows)if(!row.ignored&&!locallyIgnoredWaves.has(row.key)&&!waveResults.has(row.key))waveResults.set(row.key,row);
 drawWaves();drawWaveFrameOverlay();if(rows.length)waveStatus.textContent=waveResults.size+' saved crest estimates restored.';
}).catch(error=>{waveStatus.textContent=error.message;});
