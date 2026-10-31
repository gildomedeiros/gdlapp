
const svg=document.querySelector('#map svg');
const svgNS='http://www.w3.org/2000/svg';
const measureLayer=document.createElementNS(svgNS,'g');
measureLayer.style.pointerEvents='none';svg.append(measureLayer);
let measuring=false, selected=[];
const measureButton=document.getElementById('measure');
const measureResult=document.getElementById('measurement-result');
function gpsDistance(a,b){
 const rad=Math.PI/180, lat1=Number(a.dataset.lat)*rad,lat2=Number(b.dataset.lat)*rad;
 const dl=(Number(b.dataset.lon)-Number(a.dataset.lon))*rad;
 const h=Math.sin((lat2-lat1)/2)**2+Math.cos(lat1)*Math.cos(lat2)*Math.sin(dl/2)**2;
 return 12742000*Math.asin(Math.sqrt(Math.min(1,Math.max(0,h))));
}
function clearMeasurement(){selected=[];measureLayer.replaceChildren();measureResult.textContent=measuring?'Select the first marker.':'Enable Measure distance, then select two markers.';}
function measurePick(marker){
 if(selected.length===2)clearMeasurement();
 selected.push(marker);
 const ring=document.createElementNS(svgNS,'circle');
 ring.setAttribute('cx',marker.dataset.x);ring.setAttribute('cy',marker.dataset.y);
 ring.setAttribute('r','11');ring.setAttribute('fill','none');ring.setAttribute('stroke','#b218cf');ring.setAttribute('stroke-width','3');ring.setAttribute('vector-effect','non-scaling-stroke');measureLayer.append(ring);
 if(selected.length===1){measureResult.textContent=marker.dataset.label+' selected. Select the second marker.';return;}
 const [a,b]=selected,line=document.createElementNS(svgNS,'line');
 line.setAttribute('x1',a.dataset.x);line.setAttribute('y1',a.dataset.y);line.setAttribute('x2',b.dataset.x);line.setAttribute('y2',b.dataset.y);
 line.setAttribute('stroke','#b218cf');line.setAttribute('stroke-width','3');line.setAttribute('vector-effect','non-scaling-stroke');measureLayer.prepend(line);
 measureResult.textContent=a.dataset.label+' ↔ '+b.dataset.label+': '+gpsDistance(a,b).toFixed(2)+' m horizontal GPS distance (≈ '+(gpsDistance(a,b)/1.75).toFixed(1)+' surfer lengths of 1.75 m). Select another marker to start a new measurement.';
}
measureButton.onclick=()=>{measuring=!measuring;measureButton.setAttribute('aria-pressed',String(measuring));measureButton.textContent=measuring?'Measuring: ON':'Measure distance';clearMeasurement();};
document.getElementById('clear-measure').onclick=clearMeasurement;
const tip=document.getElementById('tooltip');
let activeMarker=null;
const pairChecks=[...document.querySelectorAll('#pair-options input')];
const visiblePairs=new Set(pairChecks.map(n=>n.dataset.pair));
function updatePairs(){
 visiblePairs.clear();pairChecks.filter(n=>n.checked).forEach(n=>visiblePairs.add(n.dataset.pair));
 clearDetails();clearMeasurement();
 svg.querySelectorAll('.pair-line,.surfer-marker,.drone-marker').forEach(n=>{
  n.style.display=n.dataset.pairs.split(' ').some(id=>visiblePairs.has(id))?'':'none';
 });
}
pairChecks.forEach(n=>n.addEventListener('change',updatePairs));
document.getElementById('pairs-all').onclick=()=>{pairChecks.forEach(n=>n.checked=true);updatePairs();};
document.getElementById('pairs-none').onclick=()=>{pairChecks.forEach(n=>n.checked=false);updatePairs();};
svg.querySelectorAll('.surfer-marker,.drone-marker').forEach(n=>{
 const halo=document.createElementNS(svgNS,'circle');
 halo.setAttribute('cx',n.dataset.x);halo.setAttribute('cy',n.dataset.y);
 halo.setAttribute('r','12');halo.setAttribute('class','pair-halo');
 halo.setAttribute('fill','none');halo.setAttribute('stroke','#e85b16');
 halo.setAttribute('stroke-width','3');halo.setAttribute('pointer-events','none');
 n.append(halo);
});
function clearDetails(){activeMarker=null;tip.hidden=true;svg.querySelectorAll('.active-pair').forEach(n=>n.classList.remove('active-pair'));}
function showDetails(marker,e){
 clearDetails();activeMarker=marker;tip.replaceChildren();
 const red=new Set((marker.dataset.redLines||'').split(',').filter(Boolean).map(Number));
 const green=new Set((marker.dataset.greenLines||'').split(',').filter(Boolean).map(Number));
 marker.dataset.detail.split('\n').forEach((text,i)=>{
  const line=document.createElement('span');line.textContent=text;
  if(text.startsWith('Retreat active:') || text.startsWith('Gimbal tilt:'))line.classList.add('key-field');
  if(text.startsWith('Retreat active:'))line.title='Brackets: minimum retreat distance in metres';
  if(text.includes(' | ') && text.endsWith(' m horizontal')){
   const split=text.lastIndexOf(' | '), end=text.lastIndexOf(' horizontal');
   line.textContent=text.slice(0,split+3);
   const distance=document.createElement('span');distance.className='key-field';distance.title='[horizontal GPS distance][filming separation], metres';distance.textContent=text.slice(split+3,end);
   line.append(distance,document.createTextNode(text.slice(end)));
  }
  if(red.has(i)){line.style.color='#c40000';line.style.fontWeight='bold';}
  if(green.has(i) || text.startsWith('Retreat active: YES ')){line.style.color='#08752e';line.style.fontWeight='bold';}
  if(text.startsWith('Gimbal tilt:') && text.includes(' — TILTING')){
   const parts=text.split(' — ');line.textContent=parts.shift()+' — ';
   const status=document.createElement('span');status.textContent=parts.join(' — ');status.style.color='#08752e';status.style.fontWeight='bold';line.append(status);
  }
  if(text.startsWith('Gimbal bands: ')){
   line.textContent='Gimbal bands: ';
   text.slice('Gimbal bands: '.length).split(' | ').forEach((band,j)=>{
    if(j)line.append(document.createTextNode(' | '));
    const entry=document.createElement('span');entry.textContent=band;
    if(band.endsWith(' (selected)')){entry.style.color='#08752e';entry.style.background='#e6f5e9';entry.style.fontWeight='bold';}
    line.append(entry);
   });
  }
  tip.append(line,document.createTextNode('\n'));
 });tip.hidden=false;
 const ids=marker.dataset.pairs.split(' ').filter(id=>visiblePairs.has(id));
 svg.querySelectorAll('.pair-line,.drone-marker,.surfer-marker').forEach(n=>{if(n.dataset.pairs.split(' ').some(id=>ids.includes(id)))n.classList.add('active-pair');});
 const r=marker.getBoundingClientRect();
 const x=e&&e.clientX!==undefined?e.clientX:r.right;
 const y=e&&e.clientY!==undefined?e.clientY:r.top;
 tip.style.left=Math.max(8,Math.min(x+18,window.innerWidth-tip.offsetWidth-8))+'px';
 tip.style.top=Math.max(8,Math.min(y+18,window.innerHeight-tip.offsetHeight-8))+'px';
}
svg.querySelectorAll('.surfer-marker,.drone-marker').forEach(n=>{
 n.addEventListener('pointerenter',e=>showDetails(n,e));
 n.addEventListener('pointermove',e=>{if(!drag)showDetails(n,e);});
 n.addEventListener('pointerleave',clearDetails);

 n.addEventListener('focus',()=>showDetails(n));n.addEventListener('blur',clearDetails);
 n.addEventListener('click',e=>{if(e.ctrlKey&&e.button===0&&n.classList.contains('surfer-marker')){e.preventDefault();e.stopPropagation();captureMarker(n);return;}if(measuring){e.stopPropagation();measurePick(n);clearDetails();}else showDetails(n,e);});
 n.addEventListener('keydown',e=>{if(measuring&&(e.key==='Enter'||e.key===' ')){e.preventDefault();measurePick(n);}});
});
document.addEventListener('keydown',e=>{
 if(e.key!=='Delete' || e.target.closest('input,textarea,select,[contenteditable="true"]'))return;
 const marker=activeMarker || document.activeElement.closest('.surfer-marker,.drone-marker');
 if(!marker || marker.style.display==='none')return;
 e.preventDefault();
 const ids=new Set(marker.dataset.pairs.split(' '));
 pairChecks.forEach(c=>{if(ids.has(c.dataset.pair))c.checked=false;});
 updatePairs();
});
let captureBusy=false;
const captureDialog=document.createElement('dialog');
captureDialog.style.maxWidth='90vw';captureDialog.style.maxHeight='90vh';
document.body.append(captureDialog);
const captureStatus=document.getElementById('capture-status');
async function captureMarker(marker){
 if(captureBusy)return;
 if(location.protocol!=='http:'){captureStatus.textContent='Frame capture needs the --serve option. Open the local URL printed by the script.';return;}
 const ids=marker.dataset.pairs.split(' ').filter(id=>visiblePairs.has(id));
 let id=ids[0];
 if(ids.length>1){id=prompt('Shared position: enter the surfer point to capture ('+ids.join(', ')+')',ids[ids.length-1]);if(!ids.includes(id))return;}
 captureBusy=true;captureStatus.textContent='Capturing '+id+'…';
 try{
  const response=await fetch('/capture',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({point:id})});
  const result=await response.json();if(!response.ok)throw Error(result.error||'Capture failed');
  captureStatus.textContent='Saved '+result.saved;
  captureDialog.replaceChildren();
  const close=document.createElement('button');close.textContent='Close';close.onclick=()=>captureDialog.close();
  const label=document.createElement('p');label.textContent=id+' — nearest frame: '+result.timestamp;
  const img=document.createElement('img');img.src=result.url+'#'+Date.now();img.alt=label.textContent;img.style.maxWidth='85vw';img.style.maxHeight='70vh';
  const link=document.createElement('a');link.href=result.url;link.download=result.filename;link.textContent='Download frame';
  captureDialog.append(close,label,img,document.createElement('br'),link);captureDialog.showModal();
 }catch(error){captureStatus.textContent='Capture failed: '+error.message;}
 finally{captureBusy=false;}
}
const original=svg.getAttribute('viewBox').split(' ').map(Number);
let box=original.slice(), drag=null;
const reference=document.createElementNS(svgNS,'g');
reference.id='surfer-reference';reference.style.cursor='move';
reference.setAttribute('tabindex','0');reference.setAttribute('role','img');
reference.setAttribute('aria-label','Movable 1.75 metre scale reference. Drag to move; use rotation slider.');
// Shape coordinates are metres. With the 0.05 m stroke, total length is
// exactly 1.75 m: head top -0.875 to feet bottom +0.875.
const refShape=document.createElementNS(svgNS,'g');
refShape.setAttribute('fill','#ffdf40');refShape.setAttribute('stroke','#2d2446');
refShape.setAttribute('stroke-width','.05');refShape.setAttribute('stroke-linecap','round');
refShape.setAttribute('stroke-linejoin','round');
const refHead=document.createElementNS(svgNS,'circle');
refHead.setAttribute('cx','0');refHead.setAttribute('cy','-.73');refHead.setAttribute('r','.12');
const refBody=document.createElementNS(svgNS,'path');
refBody.setAttribute('d','M-.09,-.55 L.09,-.55 L.19,-.45 L.34,.05 L.25,.09 L.13,-.22 L.12,.13 L.17,.85 L.05,.85 L0,.25 L-.05,.85 L-.17,.85 L-.12,.13 L-.13,-.22 L-.25,.09 L-.34,.05 L-.19,-.45 Z');
refShape.append(refHead,refBody);
const refHit=document.createElementNS(svgNS,'rect');
refHit.setAttribute('x','-.4');refHit.setAttribute('y','-.9');refHit.setAttribute('width','.8');refHit.setAttribute('height','1.8');refHit.setAttribute('fill','transparent');
const refTitle=document.createElementNS(svgNS,'title');refTitle.textContent='1.75 m reference only — drag to move; slider rotates';
reference.append(refTitle,refHit,refShape);svg.append(reference);
let referenceX=400,referenceY=410,referenceAngle=0,referenceDrag=null;
const unitsPerMetre=Number(svg.dataset.unitsPerMetre);
function drawReference(){reference.setAttribute('transform','translate('+referenceX+' '+referenceY+') rotate('+referenceAngle+') scale('+unitsPerMetre+')');}
drawReference();
document.getElementById('reference-angle').addEventListener('input',e=>{referenceAngle=Number(e.target.value);document.getElementById('reference-degrees').textContent=referenceAngle+'°';drawReference();});
document.getElementById('reference-center').onclick=()=>{referenceX=box[0]+box[2]/2;referenceY=box[1]+box[3]/2;drawReference();};
reference.addEventListener('pointerdown',e=>{if(e.button!==0)return;e.stopPropagation();clearDetails();const p=point(e);referenceDrag={x:p.x-referenceX,y:p.y-referenceY};reference.setPointerCapture(e.pointerId);});
reference.addEventListener('pointermove',e=>{if(!referenceDrag)return;e.stopPropagation();const p=point(e);referenceX=p.x-referenceDrag.x;referenceY=p.y-referenceDrag.y;drawReference();});
for(const event of ['pointerup','pointercancel','lostpointercapture'])reference.addEventListener(event,e=>{referenceDrag=null;e.stopPropagation();});
reference.addEventListener('click',e=>e.stopPropagation());
reference.addEventListener('dblclick',e=>e.stopPropagation());
function draw(){clearDetails();svg.setAttribute('viewBox',box.join(' '));}
function point(event){const p=svg.createSVGPoint();p.x=event.clientX;p.y=event.clientY;return p.matrixTransform(svg.getScreenCTM().inverse());}
function zoom(f,px=box[0]+box[2]/2,py=box[1]+box[3]/2){
 const w=Math.max(original[2]/30,Math.min(original[2]*3,box[2]*f));
 const scale=w/box[2];box=[px+(box[0]-px)*scale,py+(box[1]-py)*scale,w,box[3]*scale];draw();
}
function reset(){box=original.slice();draw();}
document.getElementById('zin').onclick=()=>zoom(.8);
document.getElementById('zout').onclick=()=>zoom(1.25);
document.getElementById('reset').onclick=reset;
svg.addEventListener('wheel',e=>{e.preventDefault();const p=point(e);zoom(e.deltaY<0?.85:1/.85,p.x,p.y);},{passive:false});
svg.addEventListener('pointerdown',e=>{if(e.button!==0 || (e.ctrlKey&&e.target.closest('.surfer-marker')) || (measuring&&e.target.closest('.surfer-marker,.drone-marker')))return;drag={x:e.clientX,y:e.clientY};svg.setPointerCapture(e.pointerId);});
svg.addEventListener('pointermove',e=>{if(!drag)return;
 const inv=svg.getScreenCTM().inverse();
 const dx=e.clientX-drag.x,dy=e.clientY-drag.y;
 box[0]-=inv.a*dx+inv.c*dy;box[1]-=inv.b*dx+inv.d*dy;
 drag={x:e.clientX,y:e.clientY};draw();});
svg.addEventListener('pointerup',()=>drag=null);
svg.addEventListener('pointercancel',()=>drag=null);
svg.addEventListener('lostpointercapture',()=>drag=null);
svg.addEventListener('dblclick',reset);
