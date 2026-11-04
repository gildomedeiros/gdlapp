// Persistent, undirected crest estimates anchored at their surfer GPS fixes.
const waveResults=new Map(),waveLayer=document.createElementNS(svgNS,'g');
waveLayer.id='cv-wave-orientations';svg.append(waveLayer);
const waveControls=document.createElement('fieldset');waveControls.id='wave-controls';
waveControls.style.cssText='border:0;border-top:1px solid #ccd;padding:8px 0;font-size:12px';
waveControls.innerHTML='<legend><strong>CV wave crests</strong></legend><label><input id="show-wave-orientations" type="checkbox" checked> Show saved orientations</label><p>Hover a position or open its frame, then press <strong>W</strong>. Dashed magenta lines show estimated crest orientation, not wave travel direction. Uses nearby whitewater edges; unbroken crests may be unavailable.</p><label>Horizontal FOV estimate <input id="wave-hfov" type="number" value="74" min="20" max="120" step="1" style="width:52px"> °</label><p>74° is an uncalibrated starting estimate. Lines use logged camera angles and a flat-water approximation; each marker is 10 m long for comparison, not a measured wave length.</p><p id="wave-status" role="status">No saved crest estimates.</p>';
document.getElementById('pair-sidebar').append(waveControls);
const waveStatus=document.getElementById('wave-status'),waveToggle=document.getElementById('show-wave-orientations');
function waveMessage(text){waveStatus.textContent=text;if(captureDialog.open)setFrameStatus(text);}
function drawWaves(){
 waveLayer.replaceChildren();waveLayer.style.display=waveToggle.checked?'':'none';
 const scale=Number(svg.dataset.unitsPerMetre),lat0=Number(svg.dataset.originLat),lon0=Number(svg.dataset.originLon);
 for(const row of waveResults.values()){
  if(![row.latitude,row.longitude,row.bearingDegrees].every(Number.isFinite))continue;
  const x=Number(svg.dataset.originX)+6371000*(row.longitude-lon0)*Math.PI/180*Math.cos(lat0*Math.PI/180)*scale;
  const y=Number(svg.dataset.originY)-6371000*(row.latitude-lat0)*Math.PI/180*scale;
  const angle=row.bearingDegrees*Math.PI/180,dx=5*scale*Math.sin(angle),dy=-5*scale*Math.cos(angle);
  const group=document.createElementNS(svgNS,'g');group.dataset.key=row.key;group.dataset.bearing=row.bearingDegrees;
  const line=document.createElementNS(svgNS,'line');
  for(const [key,value] of Object.entries({x1:x-dx,y1:y-dy,x2:x+dx,y2:y+dy,stroke:'#ad148c','stroke-width':3,'stroke-dasharray':'6 3','vector-effect':'non-scaling-stroke','pointer-events':'stroke'}))line.setAttribute(key,value);
  const title=document.createElementNS(svgNS,'title');title.textContent=(row.point||row.gpsTimestamp)+' · estimated crest '+row.bearingDegrees+'° / '+(row.bearingDegrees+180).toFixed(1)+'° · HFOV '+row.horizontalFov+'° estimate. '+row.assumptions;
  line.append(title);group.append(line);waveLayer.append(group);
 }
 // Put GPS hit targets above these comparison marks.
 const firstMarker=svg.querySelector('.drone-marker,.surfer-marker');
 if(firstMarker)firstMarker.parentNode.insertBefore(waveLayer,firstMarker);
}
waveToggle.addEventListener('change',drawWaves);
const fitWaves=document.createElement('button');fitWaves.textContent='Fit saved orientations';waveControls.append(fitWaves);
fitWaves.onclick=()=>{
 if(!waveResults.size)return;
 waveToggle.checked=true;drawWaves();
 const bounds=waveLayer.getBBox(),rect=svg.getBoundingClientRect(),aspect=rect.width/rect.height;
 let width=Math.max(80,bounds.width*1.2),height=Math.max(80,bounds.height*1.2);
 if(width/height<aspect)width=height*aspect;else height=width/aspect;
 box=[bounds.x+bounds.width/2-width/2,bounds.y+bounds.height/2-height/2,width,height];draw();
};
function drawWaveFrameOverlay(){
 document.getElementById('frame-wave-overlay')?.remove();
 if(!captureDialog.open||captureDialog.dataset.kind==='video')return;
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
 if(e.key.toLowerCase()!=='w'||e.ctrlKey||e.altKey||e.metaKey||e.isComposing||e.repeat||e.defaultPrevented)return;
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
 const horizontalFov=document.getElementById('wave-hfov').valueAsNumber;
 if(!Number.isFinite(horizontalFov)||horizontalFov<20||horizontalFov>120){waveMessage('Enter a horizontal FOV estimate of 20–120°.');return;}
 captureBusy=true;waveMessage('Detecting crest for '+id+'…');
 try{
  const response=await fetch(mapApi('/wave'),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({point:id,horizontalFov})});
  const result=await response.json();if(!response.ok)throw Error(result.error||'Wave detection failed');
  waveResults.set(result.key,result);drawWaves();drawWaveFrameOverlay();
  waveMessage(id+': estimated crest '+result.bearingDegrees+'°. '+waveResults.size+' saved; magenta edge shown on its frame.');
 }catch(error){waveMessage('Crest unavailable: '+error.message);}
 finally{captureBusy=false;}
});
if(location.protocol==='http:')fetch(mapApi('/wave-results')).then(r=>{if(!r.ok)throw Error('Restart the server to enable wave detection.');return r.json();}).then(rows=>{
 for(const row of rows)if(!waveResults.has(row.key))waveResults.set(row.key,row);
 drawWaves();drawWaveFrameOverlay();if(rows.length)waveStatus.textContent=waveResults.size+' saved crest estimates restored.';
}).catch(error=>{waveStatus.textContent=error.message;});
