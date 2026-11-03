svg.style.userSelect='none';
svg.classList.add('landmark-interactive');
// Labels paint above GPS markers; their hit targets stay behind the markers.
const landmarkLabels=new Map(),landmarkLabelLayer=document.createElementNS(svgNS,'g');
landmarkLabelLayer.id='map-landmark-labels';landmarkLabelLayer.setAttribute('pointer-events','none');
svg.append(landmarkLabelLayer);
let activeLandmark=null,landmarkPointer=null,landmarkViewport=null;
function clearLandmarkSelection(){
 activeLandmark?.classList.remove('active-landmark');
 landmarkLabels.get(activeLandmark)?.classList.remove('active-landmark-label');
 activeLandmark=null;landmarkPointer=null;
}
function selectLandmark(group,event){
 if(group.style.display==='none'||drag)return;
 if(activeLandmark!==group){clearLandmarkSelection();clearDetails();}
 activeLandmark=group;group.classList.add('active-landmark');
 landmarkLabels.get(group).classList.add('active-landmark-label');
 landmarkPointer=event&&Number.isFinite(event.clientX)?point(event):null;
 if(landmarkViewport)updateMapLandmarks(...landmarkViewport);
}
svg.querySelectorAll('.map-landmark').forEach(group=>{
 const label=group.querySelector('.landmark-label');
 landmarkLabels.set(group,label);landmarkLabelLayer.append(label);
 group.addEventListener('pointerenter',e=>selectLandmark(group,e));
 group.addEventListener('pointermove',e=>selectLandmark(group,e));
 group.addEventListener('pointerleave',()=>{if(activeLandmark===group)clearLandmarkSelection();});
 group.addEventListener('focus',()=>selectLandmark(group));
 group.addEventListener('blur',()=>{if(activeLandmark===group)clearLandmarkSelection();});
});
svg.addEventListener('pointerdown',clearLandmarkSelection);
svg.addEventListener('pointerleave',clearLandmarkSelection);
document.addEventListener('keydown',e=>{if(e.key==='Escape')clearLandmarkSelection();});
function updateMapLandmarks(view,pixelsPerUnit){
 landmarkViewport=[view,pixelsPerUnit];
 const px=1/pixelsPerUnit;
 svg.querySelectorAll('.map-landmark').forEach(group=>{
  const label=landmarkLabels.get(group);
  let x=Number(label.dataset.x),y=Number(label.dataset.y);
  const boundary=group.querySelector('.boundary-stroke');
  if(boundary){
   const ax=Number(boundary.dataset.x),ay=Number(boundary.dataset.y),dx=Number(boundary.dataset.dx),dy=Number(boundary.dataset.dy);
   let low=-Infinity,high=Infinity;
   for(const [origin,direction,min,max] of [[ax,dx,view.left,view.right],[ay,dy,view.top,view.bottom]]){
    if(Math.abs(direction)<1e-12){if(origin<min||origin>max){low=1;high=0;break;}}
    else{const a=(min-origin)/direction,b=(max-origin)/direction;low=Math.max(low,Math.min(a,b));high=Math.min(high,Math.max(a,b));}
   }
   const hit=group.querySelector('.landmark-hit');
   boundary.style.display=hit.style.display=label.style.display=low<=high?'':'none';
   if(low<=high){
    boundary.setAttribute('x1',ax+low*dx);boundary.setAttribute('y1',ay+low*dy);
    boundary.setAttribute('x2',ax+high*dx);boundary.setAttribute('y2',ay+high*dy);
    for(const name of ['x1','y1','x2','y2'])hit.setAttribute(name,boundary.getAttribute(name));
    const t=dy<0?high:low;x=ax+t*dx;y=ay+t*dy;
    x=Math.max(view.left+8*px,Math.min(view.right-120*px,x+9*px));
    y=Math.max(view.top+22*px,Math.min(view.bottom-10*px,y+22*px));
   }
  }else{x+=11*px;y+=(group.classList.contains('start-point')?20:-10)*px;}
  if(activeLandmark===group&&landmarkPointer){x=landmarkPointer.x+12*px;y=landmarkPointer.y-12*px;}
  label.setAttribute('font-size',12*px);label.setAttribute('stroke-width',3*px);
  const width=label.getComputedTextLength?.()||label.textContent.length*7*px;
  x=Math.max(view.left+8*px,Math.min(view.right-width-8*px,x));
  y=Math.max(view.top+18*px,Math.min(view.bottom-8*px,y));
  label.setAttribute('x',x);label.setAttribute('y',y);
  // Leave a visible, hoverable rim outside the GPS marker's 12-unit hit area
  // when Start and the first displayed surfer share the same coordinate.
  group.querySelectorAll('.landmark-dot').forEach(dot=>dot.setAttribute('r',dot.classList.contains('start-dot')?Math.max(7*px,14):4*px));
  group.querySelector('circle.landmark-hit')?.setAttribute('r',Math.max(18*px,18));
  group.querySelectorAll('.measure-anchor-hit').forEach(n=>n.setAttribute('r',12*px));
  group.querySelectorAll('.measure-anchor-dot').forEach(n=>n.setAttribute('r',4*px));
 });
}
