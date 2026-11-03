// Label an actual grid square, using the same coordinates as the map grid.
svg.querySelector('#map-scale-static')?.remove();
const gridScale=document.createElementNS(svgNS,'g');
gridScale.id='map-scale';gridScale.style.pointerEvents='none';
gridScale.setAttribute('role','img');svg.append(gridScale);
const scaleGrid={x:Number(svg.dataset.gridX||60),y:Number(svg.dataset.gridY||70),
 step:Number(svg.dataset.gridStep||136),columns:Number(svg.dataset.gridColumns||5),rows:Number(svg.dataset.gridRows||5)};
function scaleElement(tag,attributes,text){
 const node=document.createElementNS(svgNS,tag);
 Object.entries(attributes).forEach(([name,value])=>node.setAttribute(name,value));
 if(text!==undefined)node.textContent=text;
 gridScale.append(node);return node;
}
function scaleText(value){
 const decimals=value<1?Math.max(2,1-Math.floor(Math.log10(value))):1;
 return Number(value.toFixed(decimals))+' m';
}
function updateMapScale(){
 const matrix=svg.getScreenCTM?.();
 if(!matrix)return;
 const pixelsPerUnit=Math.hypot(matrix.a,matrix.b);
 if(!Number.isFinite(pixelsPerUnit)||pixelsPerUnit<=0)return;
 const rect=svg.getBoundingClientRect(),inverse=matrix.inverse();
 const corner=(x,y)=>{const p=svg.createSVGPoint();p.x=x;p.y=y;return p.matrixTransform(inverse);};
 const topLeft=corner(rect.left,rect.top),bottomRight=corner(rect.right,rect.bottom);
 const {x:gx,y:gy,step,columns,rows}=scaleGrid,pad=2/pixelsPerUnit;
 // Pick the leftmost complete visible column and the lowest complete row.
 // When zoomed inside a cell, hide the ruler instead of labelling a fragment
 // as though it were a full square. Panning back restores it automatically.
 const column=Math.max(0,Math.ceil((topLeft.x+pad-gx)/step));
 const lastColumn=Math.min(columns-1,Math.floor((bottomRight.x-pad-gx-step)/step));
 const firstRow=Math.max(0,Math.ceil((topLeft.y+pad-gy)/step));
 const row=Math.min(rows-1,Math.floor((bottomRight.y-pad-gy-step)/step));
 gridScale.replaceChildren();
 if(column>lastColumn||row<firstRow){gridScale.style.display='none';return;}
 const x=gx+column*step,y=gy+(row+1)*step,metres=step/unitsPerMetre;
 const horizontal=scaleText(metres),diagonal=scaleText(metres*Math.SQRT2),px=1/pixelsPerUnit;
 gridScale.style.display='';
 gridScale.dataset.metres=String(metres);gridScale.dataset.column=String(column);gridScale.dataset.row=String(row);
 gridScale.setAttribute('aria-label','Grid square: horizontal and vertical '+horizontal+', diagonal '+diagonal+' at 45 degrees.');
 // Left edge, bottom edge, and rising diagonal match the user's grid sketch.
 scaleElement('path',{id:'scale-triangle',d:`M${x},${y-step} V${y} H${x+step} M${x},${y} L${x+step},${y-step}`,
  fill:'none',stroke:'#d7ae00','stroke-width':3,'vector-effect':'non-scaling-stroke','stroke-linejoin':'round','stroke-linecap':'round'});
 const label={'font-family':'Arial','font-size':12*px,fill:'#111','text-anchor':'middle',
  stroke:'#f3f7fa','stroke-width':3*px,'paint-order':'stroke','stroke-linejoin':'round'};
 const leftRoom=(x-topLeft.x)*pixelsPerUnit>horizontal.length*7+12;
 const bottomRoom=(bottomRight.y-y)*pixelsPerUnit>25;
 scaleElement('text',{...label,id:'scale-horizontal',x:x+step/2,y:y+(bottomRoom?21:-10)*px},horizontal);
 scaleElement('text',{...label,id:'scale-vertical',x:x+(leftRoom?-10:8)*px,y:y-step/2+4*px,'text-anchor':leftRoom?'end':'start'},horizontal);
 scaleElement('text',{...label,id:'scale-diagonal',x:x+step/2,y:y-step/2-13*px},diagonal);
}
if(typeof ResizeObserver!=='undefined')new ResizeObserver(updateMapScale).observe(svg);
window.addEventListener('resize',updateMapScale);
