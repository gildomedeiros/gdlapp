// Side elevation uses the same scale for horizontal and vertical metres.
const sideData=SIDE_DATA;
const layout=document.getElementById('map-layout'), sidebar=document.getElementById('pair-sidebar');
layout.prepend(sidebar);
const boundary=svg.querySelector('#boundary-orientation');
if(boundary){
 const inset=document.createElementNS(svgNS,'svg');inset.setAttribute('viewBox','795 75 300 210');inset.style.width='100%';
 inset.append(boundary);sidebar.append(inset);
}
svg.setAttribute('viewBox','0 0 780 850');
const side=document.createElement('aside');side.id='side-view';
side.innerHTML='<strong>Selected surfer — side view</strong><p id="side-description">Hover over a surfer marker.</p><label>Point <select id="side-point"></select></label><p><label>Drone height above water (m) <input id="side-height" type="number" min="0" step="0.1" placeholder="Enter height"></label></p><label><input id="side-use-takeoff" type="checkbox" checked> Use logged height (assume water at takeoff level)</label><p><label>Vertical field of view (°), optional <input id="side-fov" type="number" min="1" max="179" placeholder="Unknown"></label><button id="calculate-fov">Detect person position</button><span id="fov-status" role="status"></span></p><svg id="side-svg" viewBox="0 0 440 360" style="width:100%;background:#f3f7fa"></svg><p id="side-note"></p><small>Surfer: 1.75 m standing reference. Drone icon is symbolic. Elevation assumes the camera faces toward the surfer; yaw and waves are not represented.</small>';
layout.append(side);
const pick=document.getElementById('side-point'), heightInput=document.getElementById('side-height'), useTakeoff=document.getElementById('side-use-takeoff'), fovInput=document.getElementById('side-fov');
function selectSide(marker){
 pick.replaceChildren();
 marker.dataset.pairs.split(' ').filter(id=>visiblePairs.has(id)).forEach(id=>{const option=document.createElement('option');option.value=id;option.textContent=id;pick.append(option);});
 loadPairFov();drawSide();
}
function drawSide(){
 const p=sideData[pick.value];
 const canvas=document.getElementById('side-svg');canvas.replaceChildren();
 const note=document.getElementById('side-note');
 if(!p){document.getElementById('side-description').textContent='No surfer position selected.';note.textContent='Choose a position in the map sidebar.';return;}
 document.getElementById('side-description').textContent=pick.value+' — '+p.time+' | logged height above takeoff: '+(p.height===null?'unknown':p.height+' m')+' | ride detected: '+(p.riding===true?'YES':p.riding===false?'NO':'unknown');
 const h=useTakeoff.checked?p.height:(heightInput.value===''?null:Number(heightInput.value));
 heightInput.disabled=useTakeoff.checked;
 if(h===null || !Number.isFinite(h) || h<0 || p.distance===null || p.tilt===null){note.textContent='Enter height above water (or explicitly use takeoff height). Distance and logged tilt must also be available.';return;}
 const d=p.distance, angle=-p.tilt*Math.PI/180;
 const hit=Math.tan(angle)>0?h/Math.tan(angle):null;
 const zone=side.dataset.zone;
 const footprintNear=zone==='BOTTOM'?d:0;
 const footprintFar=zone==='TOP'?d:2*d;
 const extent=Math.max(d*1.2,zone?footprintFar*1.08:0,10,hit!==null&&hit<d*3+20?hit:0);
 const scale=Math.min(350/extent,250/Math.max(h,1.75,3));
 const x=45,y=300,dx=x,dy=y-h*scale,sx=x+d*scale;
 function el(tag,attrs,text){const n=document.createElementNS(svgNS,tag);Object.entries(attrs).forEach(([k,v])=>n.setAttribute(k,v));if(text)n.textContent=text;canvas.append(n);return n;}
 if(zone){
  el('polygon',{points:`${dx},${dy} ${x+footprintNear*scale},${y} ${x+footprintFar*scale},${y}`,fill:'#b9ec321c',stroke:'#9bd600','stroke-width':2});
 }
 el('line',{x1:20,y1:y,x2:420,y2:y,stroke:'#4e9cb5'});
 el('line',{x1:dx,y1:dy,x2:dx+30,y2:dy,stroke:'#777','stroke-dasharray':'3 3'});
 el('rect',{x:dx-10,y:dy-4,width:20,height:8,fill:'#2454c5'});
 el('text',{x:dx,y:Math.max(18,dy-12),'font-size':12},'Drone '+h.toFixed(1)+' m');
 // A 1.75 m figure, with both axes scaled equally.
 const top=y-1.75*scale;
 el('circle',{cx:sx,cy:top+.12*scale,r:.12*scale,fill:'#14804a'});
 el('path',{d:`M${sx},${top+.24*scale} L${sx},${y-.7*scale} M${sx-.25*scale},${top+.65*scale} L${sx+.25*scale},${top+.65*scale} M${sx},${y-.7*scale} L${sx-.2*scale},${y} M${sx},${y-.7*scale} L${sx+.2*scale},${y}`,stroke:'#14804a','stroke-width':Math.max(1,.07*scale),fill:'none'});
 el('text',{x:sx,y:y+18,'text-anchor':'middle','font-size':12},'Surfer (1.75 m)');
 if(side.dataset.zone){
  el('text',{x:Math.max(100,Math.min(340,sx)),y:y+34,'text-anchor':'middle','font-size':12,fill:'#08752e'},'In frame: '+side.dataset.zone);
  // Camera image inset, deliberately separate from the metre-scale geometry.
  el('text',{x:360,y:43,'text-anchor':'middle','font-size':10},'Frame position');
  ['TOP','MIDDLE','BOTTOM'].forEach((zone,i)=>{
   el('rect',{x:320,y:50+i*28,width:80,height:28,fill:side.dataset.zone===zone?'#b8e7c6':'white',stroke:'#9baaba'});
   el('text',{x:360,y:68+i*28,'text-anchor':'middle','font-size':10},zone);
  });
 }

 function ray(a,color,dash){const cx=Math.cos(a),sy=Math.sin(a);let len=380; if(cx>0)len=Math.min(len,(420-dx)/cx);if(cx<0)len=Math.min(len,(dx-20)/-cx);if(sy>0)len=Math.min(len,(y-dy)/sy);if(sy<0)len=Math.min(len,(dy-15)/-sy);el('line',{x1:dx,y1:dy,x2:dx+cx*len,y2:dy+sy*len,stroke:color,'stroke-width':2,'stroke-dasharray':dash||''});}
 ray(angle,'#f07818');
 const fov=fovInput.value===''?null:Number(fovInput.value);
 if(fov>0&&fov<180){ray(angle-fov*Math.PI/360,'#19a7cf','5 3');ray(angle+fov*Math.PI/360,'#19a7cf','5 3');}
 el('text',{x:220,y:24,'text-anchor':'middle','font-size':14,fill:'#a94700'},'Actual gimbal tilt: '+p.tilt.toFixed(1)+'°');
 el('text',{x:220,y:348,'text-anchor':'middle','font-size':13},'Horizontal separation: '+d.toFixed(1)+' m');
 note.textContent=(zone?'Green coverage: illustrative '+zone.toLowerCase()+' framing, not a measured FOV. ':'')+(useTakeoff.checked?'Assumption: water is at takeoff elevation. ':'User-specified water-relative height. ')+(fov===null?'FOV unknown; only the orange camera centreline is shown.':fovInput.dataset.estimated==='true'?'Blue dashed lines: estimated FOV from this frame’s detected board.':'Blue dashed lines use your supplied vertical FOV.');
}
const fovButton=document.getElementById('calculate-fov'), fovStatus=document.getElementById('fov-status');
const fovCache=new Map();
function fovKey(){const p=sideData[pick.value];if(!p)return null;return JSON.stringify([p.source,p.frame,p.time]);}
function loadPairFov(){
 const key=fovKey();let saved=fovCache.get(key)||sideData[pick.value]?.position;
 if(!saved){try{saved=JSON.parse(localStorage.getItem('surfer-person-position:'+key));}catch{}}
 side.dataset.zone=saved?saved.zone:'';side.dataset.yFraction=saved?String(saved.yFraction):'';
 fovStatus.textContent=saved?' Person: '+saved.zone+' ('+Math.round(saved.yFraction*100)+'% from top), confidence '+Math.round(saved.detection.confidence*100)+'%.':(sideData[pick.value]?.positionError?' Position unavailable: '+sideData[pick.value].positionError:'');
}
fovButton.onclick=async()=>{
 const p=sideData[pick.value], id=pick.value,key=fovKey();if(!p)return;
 if(location.protocol!=='http:'){fovStatus.textContent='Use --serve and open its local URL.';return;}
 const height=useTakeoff.checked?p.height:(heightInput.value===''?null:Number(heightInput.value));
 fovButton.disabled=true;fovStatus.textContent=' Detecting person…';
 try{
  const response=await fetch(mapApi('/fov'),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({point:id,height})});
  const result=await response.json();if(!response.ok)throw Error(result.error||'Calculation failed');
  fovCache.set(key,result);try{localStorage.setItem('surfer-person-position:'+key,JSON.stringify(result));}catch{}
  if(fovKey()===key){loadPairFov();drawSide();}
 }catch(error){if(fovKey()===key)fovStatus.textContent=' Position unavailable: '+error.message;}
 finally{fovButton.disabled=false;}
};
[pick,heightInput,useTakeoff].forEach(n=>n.addEventListener('input',()=>{loadPairFov();drawSide();}));
fovInput.addEventListener('input',()=>{fovInput.dataset.estimated='false';fovStatus.textContent=' Manual FOV';drawSide();});

if(location.protocol==='http:')fetch(mapApi('/fov-results')).then(r=>r.ok?r.json():[]).then(results=>{
 results.forEach(r=>fovCache.set(JSON.stringify([r.sourceFile,r.frameTimestamp,r.gpsTimestamp]),r));
 if(pick.value){loadPairFov();drawSide();}
}).catch(()=>{});
