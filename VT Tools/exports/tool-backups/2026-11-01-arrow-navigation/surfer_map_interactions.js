// Position cards remain interactive so a shared coordinate needs no typed ID.
let detailsPinned=false, detailsHideTimer=null;
function cancelDetailsHide(){clearTimeout(detailsHideTimer);detailsHideTimer=null;}
function clearDetails(){
 cancelDetailsHide();detailsPinned=false;activeMarker=null;tip.hidden=true;
 svg.querySelectorAll('.active-pair').forEach(n=>n.classList.remove('active-pair'));
}
function scheduleDetailsHide(){
 cancelDetailsHide();if(!detailsPinned)detailsHideTimer=setTimeout(clearDetails,220);
}
function positionDetails(marker,e){
 const r=marker.getBoundingClientRect();
 const x=e&&e.clientX!==undefined?e.clientX:r.right;
 const y=e&&e.clientY!==undefined?e.clientY:r.top;
 tip.style.left=Math.max(8,Math.min(x+18,window.innerWidth-tip.offsetWidth-8))+'px';
 tip.style.top=Math.max(8,Math.min(y+18,window.innerHeight-tip.offsetHeight-8))+'px';
}
function showDetails(marker,e,pin=false){
 cancelDetailsHide();
 if(activeMarker===marker&&!tip.hidden){
  if(pin)detailsPinned=true;
  return; // Preserve scrolling, focus and the exact card under the pointer.
 }
 clearDetails();detailsPinned=pin;activeMarker=marker;tip.replaceChildren();
 const ids=marker.dataset.pairs.split(' ').filter(id=>visiblePairs.has(id));
 const surfer=marker.classList.contains('surfer-marker');
 const heading=document.createElement('div');heading.className='position-card-heading';
 const title=document.createElement('strong');
 title.textContent=ids.length>1?ids.length+' positions at this coordinate':marker.dataset.label;
 const close=document.createElement('button');close.type='button';close.textContent='Close';close.onclick=clearDetails;
 heading.append(title,close);tip.append(heading);
 if(surfer){
  const hint=document.createElement('p');hint.className='position-card-hint';
  hint.textContent='Ctrl + left-click a position below to capture its frame.';tip.append(hint);
 }
 const red=new Set((marker.dataset.redLines||'').split(',').filter(Boolean).map(Number));
 const green=new Set((marker.dataset.greenLines||'').split(',').filter(Boolean).map(Number));
 let card=null;
 filteredDetails(marker).forEach(({text,index:i})=>{
  const header=text.match(/^(S\d+)(?: ↔|:)/)||text.match(/^D\d+ ↔ (S\d+)/);
  if(header||!card){
   card=document.createElement('article');card.className='position-card';
   if(surfer&&header){
    card.dataset.capturePoint=header[1];card.tabIndex=0;
    card.setAttribute('aria-label',header[1]+' details; Ctrl-click or Ctrl+Enter to capture frame');
   }
   tip.append(card);
  }
  const line=document.createElement('span');line.textContent=text;
  if(text.startsWith('Retreat active:')||text.startsWith('Gimbal tilt:'))line.classList.add('key-field');
  if(text.startsWith('Ride detected:')){
   line.title='Latest logged movement_cycle.riding at this GPS receipt; before/after ride windows do not change this state.';
   if(text==='Ride detected: YES')line.className='ride-badge';
  }
  if(text.startsWith('Retreat active:'))line.title='Brackets: minimum retreat distance in metres';
  if(text.includes(' | ')&&text.endsWith(' m horizontal')){
   const split=text.lastIndexOf(' | '),end=text.lastIndexOf(' horizontal');line.textContent=text.slice(0,split+3);
   const distance=document.createElement('span');distance.className='key-field';
   distance.title='[horizontal GPS distance][filming separation], metres';distance.textContent=text.slice(split+3,end);
   line.append(distance,document.createTextNode(text.slice(end)));
  }
  if(red.has(i)){line.style.color='#c40000';line.style.fontWeight='bold';}
  if(green.has(i)||text.startsWith('Retreat active: YES ')){line.style.color='#08752e';line.style.fontWeight='bold';}
  if(text.startsWith('Gimbal tilt:')&&text.includes(' — TILTING')){
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
  card.append(line,document.createTextNode('\n'));
 });
 const status=document.createElement('p');status.id='details-capture-status';status.setAttribute('role','status');tip.append(status);
 tip.hidden=false;
 svg.querySelectorAll('.pair-line,.drone-marker,.surfer-marker').forEach(n=>{
  if(n.dataset.pairs.split(' ').some(id=>ids.includes(id)))n.classList.add('active-pair');
 });
 positionDetails(marker,e);
}
tip.addEventListener('pointerenter',cancelDetailsHide);
tip.addEventListener('pointerleave',scheduleDetailsHide);
tip.addEventListener('focusin',()=>{cancelDetailsHide();detailsPinned=true;});
tip.addEventListener('wheel',e=>e.stopPropagation());
function chooseCard(id){
 if(!visiblePairs.has(id))return;
 const marker=pairMarkers.find(n=>n.classList.contains('surfer-marker')&&n.dataset.pairs.split(' ').includes(id));
 if(marker){selectSide(marker);pick.value=id;loadPairFov();drawSide();}
}
tip.addEventListener('click',e=>{
 const card=e.target.closest('[data-capture-point]');if(!card)return;
 cancelDetailsHide();detailsPinned=true;chooseCard(card.dataset.capturePoint);
 if(e.ctrlKey&&e.button===0){e.preventDefault();e.stopPropagation();capturePoint(card.dataset.capturePoint);}
});
tip.addEventListener('keydown',e=>{
 if(e.key==='Escape'){e.preventDefault();clearDetails();return;}
 const card=e.target.closest('[data-capture-point]');
 if(card&&e.ctrlKey&&e.key==='Enter'){e.preventDefault();capturePoint(card.dataset.capturePoint);}
});
svg.querySelectorAll('.surfer-marker,.drone-marker').forEach(n=>{
 n.addEventListener('pointerenter',e=>{if(!detailsPinned){selectSide(n);showDetails(n,e);}});
 n.addEventListener('pointermove',e=>{if(!drag&&!detailsPinned)showDetails(n,e);});
 n.addEventListener('pointerleave',scheduleDetailsHide);
 n.addEventListener('focus',()=>{if(!detailsPinned){selectSide(n);showDetails(n);}});
 n.addEventListener('blur',scheduleDetailsHide);
 n.addEventListener('click',e=>{
  if(n.classList.contains('surfer-marker'))selectSide(n);
  if(e.ctrlKey&&e.button===0&&n.classList.contains('surfer-marker')){
   e.preventDefault();e.stopPropagation();captureMarker(n,e);return;
  }
  if(measuring){e.stopPropagation();measurePick(n);clearDetails();}else showDetails(n,e,true);
 });
 n.addEventListener('keydown',e=>{
  if(e.ctrlKey&&e.key==='Enter'&&n.classList.contains('surfer-marker')){e.preventDefault();captureMarker(n);}
  else if(measuring&&(e.key==='Enter'||e.key===' ')){e.preventDefault();measurePick(n);}
 });
});
// Ctrl-click the exact sidebar row without changing its visibility checkbox.
pairChecks.forEach(check=>check.closest('label').addEventListener('click',e=>{
 if(e.ctrlKey&&e.button===0){e.preventDefault();e.stopPropagation();chooseCard(check.dataset.pair);capturePoint(check.dataset.pair);}
},true));
document.addEventListener('keydown',e=>{
 if(e.key==='Escape'){clearDetails();return;}
 if(e.key!=='Delete'||e.target.closest('input,textarea,select,[contenteditable="true"],#tooltip'))return;
 const marker=activeMarker||document.activeElement.closest('.surfer-marker,.drone-marker');
 if(!marker||marker.style.display==='none')return;
 e.preventDefault();
 const ids=new Set(marker.dataset.pairs.split(' ').filter(id=>visiblePairs.has(id)));
 pairChecks.forEach(c=>{if(ids.has(c.dataset.pair))c.checked=false;});updatePairs();
});
let captureBusy=false;
const captureDialog=document.createElement('dialog');
captureDialog.style.maxWidth='90vw';captureDialog.style.maxHeight='90vh';document.body.append(captureDialog);
const captureStatus=document.getElementById('capture-status');
function setCaptureStatus(message){
 captureStatus.textContent=message;
 const detailStatus=document.getElementById('details-capture-status');if(detailStatus)detailStatus.textContent=message;
}
function captureMarker(marker,e){
 if(captureBusy)return;
 const ids=marker.dataset.pairs.split(' ').filter(id=>visiblePairs.has(id));
 if(ids.length>1){showDetails(marker,e,true);tip.focus({preventScroll:true});return;}
 if(ids.length===1)capturePoint(ids[0]);
}
async function capturePoint(id){
 if(captureBusy||!visiblePairs.has(id))return;
 if(location.protocol!=='http:'){setCaptureStatus('Frame capture needs the --serve option. Open the local URL printed by the script.');return;}
 captureBusy=true;setCaptureStatus('Capturing '+id+'…');
 try{
  const response=await fetch(mapApi('/capture'),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({point:id})});
  const result=await response.json();if(!response.ok)throw Error(result.error||'Capture failed');
  setCaptureStatus('Saved '+result.saved);captureDialog.replaceChildren();
  const close=document.createElement('button');close.textContent='Close';close.onclick=()=>captureDialog.close();
  const label=document.createElement('p');label.textContent=id+' — nearest frame: '+result.timestamp;
  const img=document.createElement('img');img.src=result.url+'#'+Date.now();img.alt=label.textContent;img.style.maxWidth='85vw';img.style.maxHeight='70vh';
  const link=document.createElement('a');link.href=result.url;link.download=result.filename;link.textContent='Download frame';
  captureDialog.append(close,label,img,document.createElement('br'),link);clearDetails();captureDialog.showModal();
 }catch(error){setCaptureStatus('Capture failed: '+error.message);}
 finally{captureBusy=false;}
}
