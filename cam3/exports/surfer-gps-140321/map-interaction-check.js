
const svg=document.querySelector('#map svg');
const tip=document.getElementById('tooltip');
function clearDetails(){tip.hidden=true;svg.querySelectorAll('.active-pair').forEach(n=>n.classList.remove('active-pair'));}
function showDetails(marker,e){
 clearDetails();tip.textContent=marker.dataset.detail;tip.hidden=false;
 const ids=marker.dataset.pairs.split(' ');
 svg.querySelectorAll('.pair-line,.drone-marker').forEach(n=>{if(n.dataset.pairs.split(' ').some(id=>ids.includes(id)))n.classList.add('active-pair');});
 const r=marker.getBoundingClientRect();
 const x=e&&e.clientX!==undefined?e.clientX:r.right;
 const y=e&&e.clientY!==undefined?e.clientY:r.top;
 tip.style.left=Math.max(8,Math.min(x+18,window.innerWidth-tip.offsetWidth-8))+'px';
 tip.style.top=Math.max(8,Math.min(y+18,window.innerHeight-tip.offsetHeight-8))+'px';
}
svg.querySelectorAll('.surfer-marker').forEach(n=>{
 n.addEventListener('pointerenter',e=>showDetails(n,e));
 n.addEventListener('pointermove',e=>{if(!drag)showDetails(n,e);});
 n.addEventListener('pointerleave',clearDetails);
 n.addEventListener('focus',()=>showDetails(n));n.addEventListener('blur',clearDetails);
 n.addEventListener('click',e=>showDetails(n,e));
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
svg.addEventListener('pointerdown',e=>{if(e.button!==0)return;drag={x:e.clientX,y:e.clientY};svg.setPointerCapture(e.pointerId);});
svg.addEventListener('pointermove',e=>{if(!drag)return;
 const inv=svg.getScreenCTM().inverse();
 const dx=e.clientX-drag.x,dy=e.clientY-drag.y;
 box[0]-=inv.a*dx+inv.c*dy;box[1]-=inv.b*dx+inv.d*dy;
 drag={x:e.clientX,y:e.clientY};draw();});
svg.addEventListener('pointerup',()=>drag=null);
svg.addEventListener('pointercancel',()=>drag=null);
svg.addEventListener('lostpointercapture',()=>drag=null);
svg.addEventListener('dblclick',reset);
