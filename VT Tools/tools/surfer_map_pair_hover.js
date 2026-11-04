// The same fields as the cards, displayed as a wrapping floating SVG label.
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
 for(const word of text.match(/\s+|\S+/g)||[]){
  const span=document.createElementNS(svgNS,'tspan');span.textContent=word;
  span.setAttribute('fill',color);if(bold)span.setAttribute('font-weight','bold');row.append(span);
 }
}
function showPairHover(line,event){
 if(drag||measuring||captureBusy||captureDialog.open||line.style.display==='none')return;
 if(activePairLine!==line){
  clearDetails();clearLandmarkSelection();
  activePairLine=line;line.classList.add('pair-hover');
  pairHoverLayer.dataset.point=line.dataset.pairs;
  const red=new Set((line.dataset.redLines||'').split(',').filter(Boolean).map(Number));
  for(const [index,text] of line.dataset.compact.split('\n').entries()){
   const row=document.createElementNS(svgNS,'text');
   row.style.whiteSpace='pre';
   row.setAttribute('font-family','Arial');row.setAttribute('stroke','#f3f7fa');
   row.setAttribute('paint-order','stroke');row.setAttribute('stroke-linejoin','round');
   if(red.has(index)){
    pairHoverSpan(row,text,'#c40000',true);
   }else if(/^S\d+ ↔/.test(text)&&text.includes(' | ')){
    const split=text.indexOf(' | ')+3;
    pairHoverSpan(row,text.slice(0,split),'#17324d');pairHoverSpan(row,text.slice(split),'#a94700',true);
   }else if(text.startsWith('Gimbal tilt:')&&text.includes(' — TILTING')){
    const split=text.indexOf(' — ');
    pairHoverSpan(row,text.slice(0,split),'#a94700',true);pairHoverSpan(row,text.slice(split),'#08752e',true);
   }else if(text.startsWith('Gimbal bands: ')){
    pairHoverSpan(row,'Gimbal bands: ','#17324d');
    text.slice('Gimbal bands: '.length).split(' | ').forEach((band,i)=>{
     if(i)pairHoverSpan(row,' | ','#17324d');
     const selected=band.endsWith(' (selected)');pairHoverSpan(row,band,selected?'#08752e':'#17324d',selected);
    });
   }else{
    const color=droneStatusColor(text)||(text==='Ride detected: YES'?'#8226a0':text.startsWith('Retreat active: YES')?'#08752e':
     text.startsWith('Retreat active: NO')||text.startsWith('Gimbal tilt:')&&!text.endsWith('unavailable')?'#a94700':'#17324d');
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
 const maxWidth=Math.max(1,Math.min(440*px,view.right-view.left-16*px));
 function layout(fontSize){
  let width=0,y=0;
  const lineHeight=(fontSize+3)*px;
  for(const row of rows){
   row.setAttribute('x',0);row.setAttribute('y',0);
   row.setAttribute('font-size',fontSize*px);row.setAttribute('stroke-width',2.5*px);
   let x=0;
   const spans=[...row.children];
   for(let i=0;i<spans.length;i++){
    const span=spans[i];
    const length=span.getComputedTextLength?.()||span.textContent.length*fontSize*.6*px;
    if(length>maxWidth&&span.textContent.length>1){
     // Long timestamps/coordinates still fit a narrow map, without truncation.
     const letters=[...span.textContent].map(c=>{const s=span.cloneNode();s.textContent=c;return s;});
     span.replaceWith(...letters);spans.splice(i,1,...letters);i--;continue;
    }
    if(x>0&&x+length>maxWidth){x=0;y+=lineHeight;}
    span.setAttribute('x',x);span.setAttribute('y',y+fontSize*px);
    x+=length;width=Math.max(width,x);
   }
   y+=row.textContent?lineHeight:lineHeight*.4;
  }
  return {width,height:y};
 }
 let fontSize=12,extent=layout(fontSize);
 while(extent.height>view.bottom-view.top-16*px&&fontSize>6)extent=layout(--fontSize);
 const {width,height}=extent;
 const cursor=pairHoverPointer?point(pairHoverPointer):{x:Number(activePairLine.dataset.x),y:Number(activePairLine.dataset.y)};
 const x=Math.max(view.left+8*px,Math.min(view.right-width-8*px,cursor.x+12*px));
 const y=Math.max(view.top+8*px,Math.min(view.bottom-height-8*px,cursor.y-height-8*px));
 pairHoverLayer.setAttribute('transform',`translate(${x} ${y})`);
}
svg.querySelectorAll('.pair-line').forEach(line=>{
 line.setAttribute('aria-keyshortcuts','Enter');
 line.addEventListener('pointerenter',e=>{
  if(drag||measuring||captureBusy||captureDialog.open||line.style.display==='none')return;
  // Focus first: blur of the previous marker may schedule a hide timer,
  // which showPairHover then cancels while selecting this connection.
  line.focus({preventScroll:true});showPairHover(line,e);
 });
 line.addEventListener('pointermove',e=>showPairHover(line,e));
 line.addEventListener('pointerleave',()=>{if(activePairLine===line)clearPairHover();});
 line.addEventListener('focus',()=>showPairHover(line));
 line.addEventListener('blur',()=>{if(activePairLine===line)clearPairHover();});
});
svg.addEventListener('pointerdown',clearPairHover);
svg.addEventListener('pointerleave',clearPairHover);
