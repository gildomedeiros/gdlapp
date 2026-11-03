svg.style.userSelect='none';
function updateMapLandmarks(view,pixelsPerUnit){
 const px=1/pixelsPerUnit;
 svg.querySelectorAll('.map-landmark').forEach(group=>{
  const label=group.querySelector('.landmark-label');
  let x=Number(label.dataset.x),y=Number(label.dataset.y);
  const boundary=group.querySelector('.boundary-stroke');
  if(boundary){
   const ax=Number(boundary.dataset.x),ay=Number(boundary.dataset.y),dx=Number(boundary.dataset.dx),dy=Number(boundary.dataset.dy);
   let low=-Infinity,high=Infinity;
   for(const [origin,direction,min,max] of [[ax,dx,view.left,view.right],[ay,dy,view.top,view.bottom]]){
    if(Math.abs(direction)<1e-12){if(origin<min||origin>max){low=1;high=0;break;}}
    else{const a=(min-origin)/direction,b=(max-origin)/direction;low=Math.max(low,Math.min(a,b));high=Math.min(high,Math.max(a,b));}
   }
   boundary.style.display=label.style.display=low<=high?'':'none';
   if(low<=high){
    boundary.setAttribute('x1',ax+low*dx);boundary.setAttribute('y1',ay+low*dy);
    boundary.setAttribute('x2',ax+high*dx);boundary.setAttribute('y2',ay+high*dy);
    const t=dy<0?high:low;x=ax+t*dx;y=ay+t*dy;
    x=Math.max(view.left+8*px,Math.min(view.right-120*px,x+9*px));
    y=Math.max(view.top+22*px,Math.min(view.bottom-10*px,y+22*px));
   }
  }else{x+=11*px;y+=(group.classList.contains('start-point')?20:-10)*px;}
  label.setAttribute('x',x);label.setAttribute('y',y);label.setAttribute('font-size',12*px);label.setAttribute('stroke-width',3*px);
  group.querySelectorAll('.landmark-dot').forEach(dot=>dot.setAttribute('r',(dot.classList.contains('start-dot')?7:4)*px));
 });
}
