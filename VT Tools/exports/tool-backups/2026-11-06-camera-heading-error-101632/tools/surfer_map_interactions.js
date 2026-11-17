// Position cards remain interactive so a shared coordinate needs no typed ID.
let detailsPinned=false, detailsHideTimer=null;
function droneStatusColor(text){
 if(!/^(Aiming|Come to Me|Boundary):/.test(text))return null;
 if(/BLOCKED|STOPPED|RELEASE UNCONFIRMED/.test(text))return '#c40000';
 if(/PAUSED|WAITING|suspended|slowing|cooldown/.test(text))return '#a94700';
 if(text.includes('unknown'))return '#64748b';
 if(/^(Aiming: AIMING|Come to Me: APPROACHING|Come to Me: RETURNING)/.test(text))return '#08752e';
 return '#17324d';
}
function cancelDetailsHide(){clearTimeout(detailsHideTimer);detailsHideTimer=null;}
function clearDetails(){
 clearPairHover();
 cancelDetailsHide();detailsPinned=false;activeMarker=null;tip.hidden=true;
 delete tip.dataset.selectedPoint;
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
function showDetails(marker,e,pin=false,pointId=null){
 cancelDetailsHide();
 if(activeMarker===marker&&!tip.hidden&&(tip.dataset.selectedPoint||null)===pointId){
  if(pin)detailsPinned=true;
  return; // Preserve scrolling, focus and the exact card under the pointer.
 }
 clearDetails();clearLandmarkSelection();detailsPinned=pin;activeMarker=marker;tip.replaceChildren();
 if(pointId)tip.dataset.selectedPoint=pointId;
 const ids=marker.dataset.pairs.split(' ').filter(id=>visiblePairs.has(id)&&(!pointId||id===pointId));
 const surfer=marker.classList.contains('surfer-marker');
 const heading=document.createElement('div');heading.className='position-card-heading';
 const title=document.createElement('strong');
 title.textContent=pointId|| (ids.length>1?ids.length+' positions at this coordinate':marker.dataset.label);
 const close=document.createElement('button');close.type='button';close.textContent='Close';close.onclick=clearDetails;
 heading.append(title,close);tip.append(heading);
 const hint=document.createElement('p');hint.className='position-card-hint';
 hint.textContent=(surfer?'→ next · ← previous. ':'')+'Enter: preview selected frame. Ctrl + left-click a position below for its frame.';tip.append(hint);
 const red=new Set((marker.dataset.redLines||'').split(',').filter(Boolean).map(Number));
 const green=new Set((marker.dataset.greenLines||'').split(',').filter(Boolean).map(Number));
 let card=null;
 filteredDetails(marker,pointId).forEach(({text,index:i})=>{
  const header=text.match(/^(S\d+)(?: ↔|:)/)||text.match(/^D\d+ ↔ (S\d+)/);
  if(header||!card){
   card=document.createElement('article');card.className='position-card';
   if(header){
    card.dataset.capturePoint=header[1];card.tabIndex=0;
    card.setAttribute('aria-label',header[1]+' details; Enter or Ctrl-click to preview frame');
    card.setAttribute('aria-keyshortcuts','Enter');
   }
   tip.append(card);
  }
  const line=document.createElement('span');line.textContent=text;
  const statusColor=droneStatusColor(text);
  if(statusColor){line.style.color=statusColor;line.style.fontWeight='bold';line.title='Latest logged controller state at this GPS receipt. Sent rates are commands, not measured motion.';}
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

function focusSurferPosition(id){
 const marker=pairMarkers.find(n=>n.classList.contains('surfer-marker')&&n.dataset.pairs.split(' ').includes(id));
 if(!marker||marker.style.display==='none'||!visiblePairs.has(id))return;
 // Keep zoom unchanged; pan only when the next marker would be outside view.
 const x=Number(marker.dataset.x),y=Number(marker.dataset.y),padX=box[2]*.08,padY=box[3]*.08;
 if(x<box[0]+padX||x>box[0]+box[2]-padX||y<box[1]+padY||y>box[1]+box[3]-padY){
  box=[x-box[2]/2,y-box[3]/2,box[2],box[3]];draw();
 }
 chooseCard(id);showDetails(marker,null,true,id);
 marker.focus({preventScroll:true});
}

function positionNavigation(){
 const single=visiblePairs.size===1&&eligiblePairs.has(positionFilter.value);
 return {single,points:single?eligiblePoints:eligiblePoints.filter(p=>visiblePairs.has(p.id))};
}

function navigateSurferKey(e){
 if(!['ArrowRight','ArrowLeft','ArrowUp','ArrowDown'].includes(e.key)||e.ctrlKey||e.altKey||e.metaKey||e.shiftKey||e.isComposing)return;
 if(measuring||captureBusy||captureDialog.open||document.getElementById('hide-surfers')?.checked)return;
 const pointControl=e.target===positionFilter||e.target===pick;
 if(!pointControl&&e.target.closest('input,textarea,select,[contenteditable]:not([contenteditable="false"]),[role="textbox"],dialog'))return;
 const {single,points}=positionNavigation();
 const selected=activeMarker?.classList.contains('surfer-marker')&&(detailsPinned||document.activeElement===activeMarker);
 if(!single&&!selected&&!pointControl)return;
 const id=e.target.closest('[data-capture-point]')?.dataset.capturePoint||pick.value;
 // In single-position mode advance that selection. Otherwise keep the map's
 // checked positions intact and skip hidden samples, including shared points.
 const index=points.findIndex(p=>p.id===id);if(index<0)return;
 e.preventDefault();e.stopPropagation();
 const next=points[index+(['ArrowRight','ArrowUp'].includes(e.key)?1:-1)];if(!next)return;
 if(single)showPosition(next.id);
 focusSurferPosition(next.id);
}

function previewHighlightedFrame(e){
 if(e.defaultPrevented||e.ctrlKey||e.altKey||e.metaKey||e.shiftKey||e.isComposing||e.repeat)return;
 if(measuring||drag||captureBusy||captureDialog.open)return;
 if(e.target.closest('input,textarea,select,button,a,summary,[contenteditable]:not([contenteditable="false"]),[role="textbox"],dialog'))return;
 const card=e.target.closest('[data-capture-point]');
 const highlighted=activePairLine||(!tip.hidden?activeMarker:null);
 if(!highlighted||highlighted.style.display==='none')return;
 const ids=highlighted.dataset.pairs.split(' ').filter(id=>visiblePairs.has(id));
 // A shared coordinate previews the selected receipt, including a focused
 // individual card. A connection always refers to its own single receipt.
 const id=[card?.dataset.capturePoint,tip.dataset.selectedPoint,pick.value,...ids].find(id=>ids.includes(id));
 if(!id)return;
 e.preventDefault();e.stopPropagation();capturePoint(id);
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
 n.setAttribute('aria-keyshortcuts','Enter');
 n.addEventListener('pointerenter',e=>{
  if(drag||captureBusy||captureDialog.open||measuring)return;
  // Hover selects either kind of marker, even after another was pinned.
  const same=activeMarker===n&&!tip.hidden;
  const pointId=same?tip.dataset.selectedPoint||null:null;
  if(!same)selectSide(n);
  showDetails(n,e,true,pointId);
  // Focus the iframe as well as the marker so Enter reaches this map.
  n.focus({preventScroll:true});
 });
 n.addEventListener('pointermove',e=>{if(!measuring&&!drag&&!detailsPinned)showDetails(n,e);});
 n.addEventListener('pointerleave',scheduleDetailsHide);
 n.addEventListener('focus',()=>{if(!measuring&&!captureBusy&&!captureDialog.open&&activeMarker!==n){selectSide(n);showDetails(n,null,true);}});
 n.addEventListener('blur',scheduleDetailsHide);
 n.addEventListener('click',e=>{
  if(n.classList.contains('surfer-marker'))selectSide(n);
  if(e.ctrlKey&&e.button===0&&n.classList.contains('surfer-marker')){
   e.preventDefault();e.stopPropagation();captureMarker(n,e);return;
  }
  if(measuring){e.stopPropagation();measurePick(n);clearDetails();}else showDetails(n,e,true);
 });
 n.addEventListener('keydown',e=>{
  if(measuring&&(e.key==='Enter'||e.key===' ')){e.preventDefault();measurePick(n);}
  else if(e.ctrlKey&&e.key==='Enter'&&n.classList.contains('surfer-marker')){e.preventDefault();captureMarker(n);}
 });
});
// Ctrl-click the exact sidebar row without changing its visibility checkbox.
pairChecks.forEach(check=>check.closest('label').addEventListener('click',e=>{
 if(e.ctrlKey&&e.button===0){e.preventDefault();e.stopPropagation();chooseCard(check.dataset.pair);capturePoint(check.dataset.pair);}
},true));
document.addEventListener('keydown',e=>{
 if(e.key==='Enter'){previewHighlightedFrame(e);return;}
 if(captureDialog.open&&(e.key==='ArrowRight'||e.key==='ArrowLeft')){navigateFrameKey(e);return;}
 if(['ArrowRight','ArrowLeft','ArrowUp','ArrowDown'].includes(e.key)){
  if(!captureDialog.open)navigateSurferKey(e);
  return;
 }
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
function setFrameStatus(message){
 const status=captureDialog.querySelector('#frame-status');
 if(status)status.textContent=message;
}
function navigateFrameKey(e){
 if(e.altKey||e.metaKey||e.shiftKey||e.isComposing||e.target.closest('input,textarea,select,[contenteditable]:not([contenteditable="false"]),[role="textbox"]'))return;
 e.preventDefault();e.stopPropagation();
 // One extraction at a time: held keys cannot queue stale frames.
 if(captureBusy)return;
 const direction=e.key==='ArrowRight'?1:-1;
 if(e.ctrlKey){captureVideoStep(direction);return;}
 const {single,points}=positionNavigation(),id=captureDialog.dataset.point;
 const index=points.findIndex(p=>p.id===id);if(index<0)return;
 const rawTime=Date.parse(captureDialog.dataset.timestamp);
 let next=points[index+direction];
 if(captureDialog.dataset.kind==='video'&&Number.isFinite(rawTime)){
  const candidates=points.filter(p=>direction*(Date.parse(sideData[p.id]?.frame||p.time)-rawTime)>0);
  next=direction>0?candidates[0]:candidates[candidates.length-1];
 }
 if(!next){setFrameStatus(direction>0?'Last position.':'First position.');return;}
 capturePoint(next.id,{advanceSelection:single});
}
function frameDownloadLink(id,variant,label,videoTime=null){
 const link=document.createElement('a');link.href='#';link.textContent=label;
 link.dataset.downloadVariant=variant;link.style.marginLeft='16px';
 link.onclick=async e=>{
  e.preventDefault();if(captureBusy)return;
  captureBusy=true;captureDialog.setAttribute('aria-busy','true');link.setAttribute('aria-disabled','true');
  setFrameStatus('Preparing '+variant+' download…');
  try{
   const request={point:id,variant};
   if(videoTime!==null)Object.assign(request,{videoTime,step:0});
   const response=await fetch(mapApi(videoTime===null?'/capture':'/video-frame'),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(request)});
   const result=await response.json();if(!response.ok)throw Error(result.error||'Download failed');
   if(result.variant!==variant)throw Error('Restart the map server to enable clean/enhanced downloads.');
   if(!captureDialog.open||captureDialog.dataset.point!==id)return;
   link.href=result.url;link.download=result.filename;link.onclick=null;
   setFrameStatus(variant==='clean'?'Clean frame ready.':'Enhanced frame ready.');
   link.click();
  }catch(error){
   if(captureDialog.open&&captureDialog.dataset.point===id)setFrameStatus('Download failed: '+error.message);
  }finally{captureBusy=false;captureDialog.removeAttribute('aria-busy');link.removeAttribute('aria-disabled');}
 };
 return link;
}
function renderCapturedFrame(id,result){
 const raw=result.kind==='video';
 captureDialog.replaceChildren();captureDialog.dataset.point=id;
 captureDialog.dataset.kind=raw?'video':'surfer';
 captureDialog.dataset.timestamp=result.timestamp||'';
 captureDialog.dataset.videoTime=raw?String(result.videoTime):'';
 const close=document.createElement('button');close.textContent='Close';close.onclick=()=>captureDialog.close();
 const hint=document.createElement('span');hint.textContent=' ← previous position · → next position · Ctrl+←/→ video frames · W: detect crest · I: ignore crest';hint.style.fontSize='13px';
 const label=document.createElement('p');label.id='frame-label';
 label.textContent=raw?'Video frame — '+result.videoName+' · '+result.videoTime.toFixed(3)+' s'+(result.timestamp?' · '+result.timestamp:'')+' · GPS reference '+id:
  id+' — nearest frame: '+result.timestamp;
 const status=document.createElement('p');status.id='frame-status';status.setAttribute('role','status');status.style.margin='0';
 const img=document.createElement('img');img.src=result.url+'#'+Date.now();img.alt=label.textContent;img.style.maxWidth='85vw';img.style.maxHeight='70vh';
 const picture=document.createElement('div');picture.id='frame-picture';picture.style.cssText='position:relative;display:table;line-height:0';
 img.style.display='block';picture.append(img);
 captureDialog.append(close,hint,label,status,picture,document.createElement('br'));
 if(!raw){
  const telemetry=document.createElement('a');telemetry.href=result.url;telemetry.download=result.filename;telemetry.textContent='Download with telemetry';
  captureDialog.append(telemetry);
 }
 const clean=frameDownloadLink(id,'clean','Download clean',raw?result.videoTime:null);
 if(raw){clean.href=result.url;clean.download=result.filename;clean.onclick=null;clean.style.marginLeft='0';}
 const enhanced=frameDownloadLink(id,'enhanced','Download enhanced',raw?result.videoTime:null);
 enhanced.title='Clean full-resolution frame with richer teal tones, contrast and light sharpening.';
 captureDialog.append(clean,enhanced);clearDetails();
 if(!captureDialog.open)captureDialog.showModal();
 close.focus({preventScroll:true});
 drawWaveFrameOverlay();
}
async function captureVideoStep(step){
 const id=captureDialog.dataset.point,request={point:id,step};
 if(captureDialog.dataset.kind==='video')request.videoTime=Number(captureDialog.dataset.videoTime);
 captureBusy=true;captureDialog.setAttribute('aria-busy','true');
 setFrameStatus('Loading '+(step>0?'next':'previous')+' video frame…');
 try{
  const response=await fetch(mapApi('/video-frame'),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(request)});
  const result=await response.json();if(!response.ok)throw Error(result.error||'Video frame stepping requires a server restart.');
  if(result.kind!=='video'||!Number.isFinite(result.videoTime))throw Error('Restart the map server to enable video-frame stepping.');
  if(captureDialog.open)renderCapturedFrame(id,result);
 }catch(error){if(captureDialog.open)setFrameStatus(error.message);}
 finally{captureBusy=false;captureDialog.removeAttribute('aria-busy');}
}
function captureMarker(marker,e){
 if(captureBusy)return;
 const ids=marker.dataset.pairs.split(' ').filter(id=>visiblePairs.has(id));
 const selected=activeMarker===marker&&!tip.hidden&&tip.dataset.selectedPoint;
 if(selected&&ids.includes(selected)){chooseCard(selected);capturePoint(selected);return;}
 if(ids.length>1){showDetails(marker,e,true);tip.focus({preventScroll:true});return;}
 if(ids.length===1)capturePoint(ids[0]);
}
async function capturePoint(id,{advanceSelection=false}={}){
 if(captureBusy||!(advanceSelection?eligiblePairs:visiblePairs).has(id))return;
 if(location.protocol!=='http:'){setCaptureStatus('Frame capture needs the --serve option. Open the local URL printed by the script.');return;}
 const fromViewer=captureDialog.open;
 captureBusy=true;setCaptureStatus('Capturing '+id+'…');
 if(fromViewer){setFrameStatus('Loading '+id+'…');captureDialog.setAttribute('aria-busy','true');}
 try{
  const response=await fetch(mapApi('/capture'),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({point:id})});
  const result=await response.json();if(!response.ok)throw Error(result.error||'Capture failed');
  // Closing the viewer while extraction runs must not reopen it or change the
  // map selection when the late response arrives.
  if(fromViewer&&!captureDialog.open){setCaptureStatus('Saved '+result.saved);return;}
  if(advanceSelection)showPosition(id);
  chooseCard(id);
  setCaptureStatus('Saved '+result.saved);renderCapturedFrame(id,result);
 }catch(error){
  const message='Capture failed: '+error.message;setCaptureStatus(message);
  if(fromViewer&&captureDialog.open)setFrameStatus(message);
 }
 finally{captureBusy=false;captureDialog.removeAttribute('aria-busy');}
}
