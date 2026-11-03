// All measurement endpoints use logged geographic coordinates and the map's
// existing projection. The movable scale figure is not a GPS marker.
const measureLayer=document.createElementNS(svgNS,'g');
measureLayer.id='map-measurement';measureLayer.style.pointerEvents='none';
measureLayer.setAttribute('role','img');svg.append(measureLayer);
let measuring=false,selected=[];
const measureButton=document.getElementById('measure');
const measureResult=document.getElementById('measurement-result');
function gpsDistance(a,b){
 const rad=Math.PI/180,lat1=Number(a.dataset.lat)*rad,lat2=Number(b.dataset.lat)*rad;
 const dl=(Number(b.dataset.lon)-Number(a.dataset.lon))*rad;
 const h=Math.sin((lat2-lat1)/2)**2+Math.cos(lat1)*Math.cos(lat2)*Math.sin(dl/2)**2;
 return 12742000*Math.asin(Math.sqrt(Math.min(1,Math.max(0,h))));
}
function measureElement(tag,attrs,text,parent=measureLayer){
 const node=document.createElementNS(svgNS,tag);
 Object.entries(attrs).forEach(([key,value])=>node.setAttribute(key,String(value)));
 if(text!==undefined)node.textContent=text;
 parent.append(node);return node;
}
function rulerInterval(wanted){
 const power=10**Math.floor(Math.log10(wanted));
 return [1,2,5,10].find(n=>n*power>=wanted)*power;
}
function updateMeasurement(){
 measureLayer.replaceChildren();delete measureLayer.dataset.metres;
 measureLayer.removeAttribute('aria-label');
 if(!selected.length)return;
 // Keep the ruler above paths/labels without intercepting clicks.
 svg.append(measureLayer);
 const matrix=svg.getScreenCTM?.(),scale=matrix?Math.hypot(matrix.a,matrix.b):1;
 const px=1/(scale>0?scale:1),color='#9816ad';
 const stroke={stroke:color,'stroke-width':2,'vector-effect':'non-scaling-stroke',fill:'none'};
 for(const p of selected)measureElement('circle',{...stroke,class:'measurement-endpoint',cx:p.dataset.x,cy:p.dataset.y,r:13*px});
 if(selected.length<2)return;
 const [a,b]=selected,x=Number(a.dataset.x),y=Number(a.dataset.y),bx=Number(b.dataset.x),by=Number(b.dataset.y);
 const dx=bx-x,dy=by-y,length=Math.hypot(dx,dy),distance=gpsDistance(a,b);
 const ux=length>0?dx/length:1,uy=length>0?dy/length:0,nx=-uy,ny=ux;
 let angle=Math.atan2(dy,dx)*180/Math.PI;if(angle>90)angle-=180;if(angle<-90)angle+=180;
 measureLayer.dataset.metres=String(distance);
 measureLayer.setAttribute('aria-label',a.dataset.label+' to '+b.dataset.label+': '+distance.toFixed(2)+' m horizontal distance');
 measureElement('line',{x1:x,y1:y,x2:bx,y2:by,stroke:'white','stroke-width':5,'vector-effect':'non-scaling-stroke'});
 measureElement('line',{...stroke,id:'measurement-line',x1:x,y1:y,x2:bx,y2:by});
 const tick=(fraction,size,major)=>{
  const tx=x+dx*fraction,ty=y+dy*fraction;
  measureElement('line',{...stroke,class:major?'measurement-tick major':'measurement-tick',
   x1:tx-nx*size*px,y1:ty-ny*size*px,x2:tx+nx*size*px,y2:ty+ny*size*px});
 };
 tick(0,9,true);if(length>0)tick(1,9,true);
 if(distance>0&&length/px>35){
  // Bound the tick count even when the endpoints are far outside the viewport.
  const step=rulerInterval(distance/Math.min(60,Math.max(1,length/px/65)));
  const minor=step/5,count=Math.min(300,Math.ceil(distance/minor));
  for(let i=1;i<count;i++){
   const metres=i*minor;if(metres>=distance)break;
   const major=i%5===0,fraction=metres/distance;
   if(major||length/px*minor/distance>=8)tick(fraction,major?7:4,major);
   if(major){
    const tx=x+dx*fraction+nx*20*px,ty=y+dy*fraction+ny*20*px;
    measureElement('text',{'font-family':'Arial','font-size':10*px,'text-anchor':'middle',
     fill:color,stroke:'white','stroke-width':3*px,'paint-order':'stroke',
     transform:`translate(${tx} ${ty}) rotate(${angle})`},Number(metres.toPrecision(4))+' m');
   }
  }
 }
 const total=distance.toFixed(2)+' m',width=(total.length*8+16)*px;
 const label=measureElement('g',{id:'measurement-label',
  transform:`translate(${(x+bx)/2} ${(y+by)/2}) rotate(${angle})`});
 measureElement('rect',{x:-width/2,y:-34*px,width,height:23*px,rx:4*px,
  fill:'white',stroke:color,'stroke-width':1,'vector-effect':'non-scaling-stroke'},undefined,label);
 measureElement('text',{id:'measurement-distance',x:0,y:-18*px,'font-family':'Arial',
  'font-size':13*px,'font-weight':'bold','text-anchor':'middle',fill:color},total,label);
}
function clearMeasurement(){
 selected=[];updateMeasurement();
 measureResult.textContent=measuring?'Select two markers: surfer, drone, start, wave endpoint or boundary anchor.':'Enable Measure distance, then select two markers.';
}
function measurePick(marker){
 if(!['x','y','lat','lon'].every(k=>marker.dataset[k]!==undefined&&marker.dataset[k]!==''&&Number.isFinite(Number(marker.dataset[k]))))return;
 if(selected.length===2)clearMeasurement();
 selected.push({dataset:{...marker.dataset}});
 clearDetails();clearLandmarkSelection();updateMeasurement();
 if(selected.length===1){measureResult.textContent=marker.dataset.label+' selected. Select the second marker.';return;}
 const [a,b]=selected;
 measureResult.textContent=a.dataset.label+' ↔ '+b.dataset.label+': '+gpsDistance(a,b).toFixed(2)+' m horizontal GPS distance. Select another marker to start a new measurement.';
}
function landmarkMeasureTarget(target){
 return target.closest('.landmark-anchor')||target.closest('.start-point')?.querySelector('.landmark-anchor');
}
svg.addEventListener('click',e=>{
 if(!measuring||e.button!==0)return;
 const marker=landmarkMeasureTarget(e.target);
 if(marker){e.preventDefault();e.stopPropagation();measurePick(marker);}
});
svg.addEventListener('keydown',e=>{
 if(!measuring||!['Enter',' '].includes(e.key))return;
 const marker=landmarkMeasureTarget(e.target);
 if(marker){e.preventDefault();e.stopPropagation();measurePick(marker);}
});
measureButton.onclick=()=>{
 measuring=!measuring;svg.classList.toggle('map-measuring',measuring);
 svg.querySelectorAll('.landmark-anchor').forEach(n=>n.setAttribute('tabindex',measuring?'0':'-1'));
 measureButton.setAttribute('aria-pressed',String(measuring));
 measureButton.textContent=measuring?'Measuring: ON':'Measure distance';
 clearDetails();clearLandmarkSelection();clearMeasurement();
};
document.getElementById('clear-measure').onclick=clearMeasurement;
