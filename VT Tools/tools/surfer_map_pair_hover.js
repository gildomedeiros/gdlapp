// A floating SVG label, like the reference labels, for just the key pair data.
const pairHoverLayer=document.createElementNS(svgNS,'g');
pairHoverLayer.id='pair-line-hover';pairHoverLayer.setAttribute('pointer-events','none');
pairHoverLayer.setAttribute('role','tooltip');pairHoverLayer.style.display='none';
svg.append(pairHoverLayer);
const pairHoverStyle=document.createElementNS(svgNS,'style');
pairHoverStyle.textContent='.pair-line{cursor:help;outline:none}.pair-line.pair-hover{opacity:1;stroke:#e85b16;stroke-width:3}.map-measuring .pair-line{cursor:grab}';
svg.append(pairHoverStyle);
let activePairLine=null,pairHoverPointer=null;
function clearPairHover(){
 activePairLine?.classList.remove('pair-hover');activePairLine=null;pairHoverPointer=null;
 pairHoverLayer.style.display='none';pairHoverLayer.replaceChildren();delete pairHoverLayer.dataset.point;
}
function pairHoverSpan(row,text,color,bold=false){
 const span=document.createElementNS(svgNS,'tspan');span.textContent=text;
 span.setAttribute('fill',color);if(bold)span.setAttribute('font-weight','bold');row.append(span);
}
function showPairHover(line,event){
 if(drag||measuring||captureBusy||captureDialog.open||line.style.display==='none')return;
 if(activePairLine!==line){
  clearDetails();clearLandmarkSelection();
  activePairLine=line;line.classList.add('pair-hover');
  pairHoverLayer.dataset.point=line.dataset.pairs;
  for(const text of line.dataset.compact.split('\n')){
   const row=document.createElementNS(svgNS,'text');
   row.setAttribute('font-family','Arial');row.setAttribute('stroke','#f3f7fa');
   row.setAttribute('paint-order','stroke');row.setAttribute('stroke-linejoin','round');
   if(text.includes(' | ')){
    const split=text.indexOf(' | ')+3;
    pairHoverSpan(row,text.slice(0,split),'#17324d');pairHoverSpan(row,text.slice(split),'#a94700',true);
   }else if(text.startsWith('Gimbal tilt:')&&text.includes(' — TILTING')){
    const split=text.indexOf(' — ');
    pairHoverSpan(row,text.slice(0,split),'#a94700',true);pairHoverSpan(row,text.slice(split),'#08752e',true);
   }else{
    const color=text==='Ride detected: YES'?'#8226a0':text.startsWith('Retreat active: YES')?'#08752e':
     text.startsWith('Retreat active: NO')||text.startsWith('Gimbal tilt:')&&!text.endsWith('unavailable')?'#a94700':'#17324d';
    pairHoverSpan(row,text,color,color!=='#17324d');
   }
   pairHoverLayer.append(row);
  }
  pairHoverLayer.style.display='';svg.append(pairHoverLayer);
 }
 pairHoverPointer=event&&Number.isFinite(event.clientX)?{clientX:event.clientX,clientY:event.clientY}:null;
 if(landmarkViewport)updatePairHover(...landmarkViewport);
}
function updatePairHover(view,pixelsPerUnit){
 if(!activePairLine)return;
 const px=1/pixelsPerUnit,rows=[...pairHoverLayer.children];
 rows.forEach((row,i)=>{
  row.setAttribute('x',0);row.setAttribute('y',(12+i*16)*px);
  row.setAttribute('font-size',12*px);row.setAttribute('stroke-width',3*px);
 });
 const width=Math.max(...rows.map(row=>row.getComputedTextLength?.()||row.textContent.length*7*px));
 const height=rows.length*16*px;
 const cursor=pairHoverPointer?point(pairHoverPointer):{x:Number(activePairLine.dataset.x),y:Number(activePairLine.dataset.y)};
 const x=Math.max(view.left+8*px,Math.min(view.right-width-8*px,cursor.x+12*px));
 const y=Math.max(view.top+8*px,Math.min(view.bottom-height-8*px,cursor.y-height-8*px));
 pairHoverLayer.setAttribute('transform',`translate(${x} ${y})`);
}
svg.querySelectorAll('.pair-line').forEach(line=>{
 line.addEventListener('pointerenter',e=>showPairHover(line,e));
 line.addEventListener('pointermove',e=>showPairHover(line,e));
 line.addEventListener('pointerleave',()=>{if(activePairLine===line)clearPairHover();});
 line.addEventListener('focus',()=>showPairHover(line));
 line.addEventListener('blur',()=>{if(activePairLine===line)clearPairHover();});
});
svg.addEventListener('pointerdown',clearPairHover);
svg.addEventListener('pointerleave',clearPairHover);
