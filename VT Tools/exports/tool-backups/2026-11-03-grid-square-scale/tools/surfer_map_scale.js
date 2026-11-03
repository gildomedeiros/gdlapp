// A screen-anchored ruler: lengths use the main map's actual SVG transform.
svg.querySelector('#map-scale-static')?.remove();
const scalePanel=document.createElement('div');
scalePanel.id='map-scale';scalePanel.hidden=true;
Object.assign(scalePanel.style,{position:'absolute',left:'12px',bottom:'12px',pointerEvents:'none',
 background:'rgba(255,255,255,.94)',border:'1px solid #cbd7e2',borderRadius:'6px'});
const scaleCanvas=document.createElementNS(svgNS,'svg');
scaleCanvas.setAttribute('viewBox','0 0 224 208');scaleCanvas.setAttribute('role','img');
// Override the main map SVG's full-width/full-height rules.
Object.assign(scaleCanvas.style,{width:'224px',height:'208px',display:'block'});
scalePanel.append(scaleCanvas);
const mapContainer=document.getElementById('map');
mapContainer.style.position='relative';mapContainer.append(scalePanel);
function scaleElement(tag,attributes,text){
 const node=document.createElementNS(svgNS,tag);
 Object.entries(attributes).forEach(([name,value])=>node.setAttribute(name,value));
 if(text!==undefined)node.textContent=text;
 scaleCanvas.append(node);return node;
}
function scaleText(value){
 const decimals=value<1?Math.max(2,1-Math.floor(Math.log10(value))):1;
 return Number(value.toFixed(decimals))+' m';
}
function updateMapScale(){
 const matrix=svg.getScreenCTM?.();
 const pixelsPerMetre=matrix&&Math.hypot(matrix.a,matrix.b)*unitsPerMetre;
 if(!Number.isFinite(pixelsPerMetre)||pixelsPerMetre<=0)return;
 // Select a readable round distance, keeping the ruler compact at any zoom.
 const maxMetres=140/pixelsPerMetre;
 const magnitude=10**Math.floor(Math.log10(maxMetres));
 const metres=[1,2,2.5,5,10].filter(n=>n*magnitude<=maxMetres).at(-1)*magnitude;
 const length=metres*pixelsPerMetre,diagonal=metres*Math.SQRT2;
 const x=52,y=174;
 scaleCanvas.replaceChildren();
 scaleCanvas.setAttribute('aria-label','Map scale: horizontal and vertical '+scaleText(metres)+', diagonal '+scaleText(diagonal)+' at 45 degrees.');
 scalePanel.dataset.metres=String(metres);
 scaleElement('text',{x:12,y:19,'font-size':12,fill:'#46586a'},'Map scale');
 scaleElement('path',{id:'scale-triangle',d:`M${x},${y-length} V${y} H${x+length} Z`,
  fill:'none',stroke:'#111','stroke-width':3,'stroke-linejoin':'round'});
 const label={'font-family':'Arial','font-size':12,fill:'#111','text-anchor':'middle'};
 scaleElement('text',{...label,id:'scale-horizontal',x:x+length/2,y:195},scaleText(metres));
 scaleElement('text',{...label,id:'scale-vertical',x:x-9,y:y-length/2+4,'text-anchor':'end'},scaleText(metres));
 scaleElement('text',{...label,id:'scale-diagonal',x:x+length/2+14,y:y-length/2-11},scaleText(diagonal));
 scalePanel.hidden=false;
}
if(typeof ResizeObserver!=='undefined')new ResizeObserver(updateMapScale).observe(svg);
window.addEventListener('resize',updateMapScale);
