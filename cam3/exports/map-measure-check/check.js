
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
 measureResult.textContent=a.dataset.label+' ↔ '+b.dataset.label+': '+gpsDistance(a,b).toFixed(2)+' m horizontal GPS distance. Select another marker to start a new measurement.';
}
measureButton.onclick=()=>{measuring=!measuring;measureButton.setAttribute('aria-pressed',String(measuring));measureButton.textContent=measuring?'Measuring: ON':'Measure distance';clearMeasurement();};
document.getElementById('clear-measure').onclick=clearMeasurement;
const tip=document.getElementById('tooltip');
function clearDetails(){tip.hidden=true;svg.querySelectorAll('.active-pair').forEach(n=>n.classList.remove('active-pair'));}
function showDetails(marker,e){
 clearDetails();tip.replaceChildren();
 const red=new Set((marker.dataset.redLines||'').split(',').filter(Boolean).map(Number));
 const green=new Set((marker.dataset.greenLines||'').split(',').filter(Boolean).map(Number));
 marker.dataset.detail.split('\n').forEach((text,i)=>{
  const line=document.createElement('span');line.textContent=text;
  if(red.has(i)){line.style.color='#c40000';line.style.fontWeight='bold';}
  if(green.has(i)){line.style.color='#08752e';line.style.fontWeight='bold';}
  tip.append(line,document.createTextNode('\n'));
 });tip.hidden=false;
 const ids=marker.dataset.pairs.split(' ');
 svg.querySelectorAll('.pair-line,.drone-marker').forEach(n=>{if(n.dataset.pairs.split(' ').some(id=>ids.includes(id)))n.classList.add('active-pair');});
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
 n.addEventListener('click',e=>{if(measuring){e.stopPropagation();measurePick(n);clearDetails();}else showDetails(n,e);});
 n.addEventListener('keydown',e=>{if(measuring&&(e.key==='Enter'||e.key===' ')){e.preventDefault();measurePick(n);}});
});
const original=svg.getAttribute('viewBox').split(' ').map(Number);
let box=original.slice(), drag=null;
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
svg.addEventListener('pointerdown',e=>{if(e.button!==0 || (measuring&&e.target.closest('.surfer-marker,.drone-marker')))return;drag={x:e.clientX,y:e.clientY};svg.setPointerCapture(e.pointerId);});
svg.addEventListener('pointermove',e=>{if(!drag)return;
 const inv=svg.getScreenCTM().inverse();
 const dx=e.clientX-drag.x,dy=e.clientY-drag.y;
 box[0]-=inv.a*dx+inv.c*dy;box[1]-=inv.b*dx+inv.d*dy;
 drag={x:e.clientX,y:e.clientY};draw();});
svg.addEventListener('pointerup',()=>drag=null);
svg.addEventListener('pointercancel',()=>drag=null);
svg.addEventListener('lostpointercapture',()=>drag=null);
svg.addEventListener('dblclick',reset);
