// Run with jsdom on NODE_PATH: node tools/test_surfer_map_ui.cjs
const assert=require('node:assert/strict');
const fs=require('node:fs');
const {JSDOM,VirtualConsole}=require('jsdom');
const {spawnSync}=require('node:child_process');
const fixtureCode=String.raw`from pathlib import Path
from datetime import datetime,timezone
import tempfile
from surfer_gps_map import select_packets,pair_drones,write_outputs,pair_distance
from surfer_pitch_samples import annotate_ride_windows
points=[]
for i in range(4):
    t=10000+i*1000
    points.append(dict(time_ms=t,sourceFile="fixture",sourceLine=i+1,latitude=-27.95+(0 if i<2 else i*.00001),longitude=153.43,receivedTimestamp=datetime.fromtimestamp(t/1000,timezone.utc).isoformat(),sequence=i,accepted=True,gimbalTiltDeg=-35,heightAboveTakeoffM=8.5))
target,points=select_packets(points,"1970-01-01 00:00:10",3,timezone.utc)
pair_drones(points,{"fixture":[dict(time_ms=9000,sourceLine=0,latitude=-27.9502,longitude=153.43,timestamp="1970-01-01T00:00:09+00:00",age=0,fix=0)]})
for p in points:p["droneSurferDistanceMetres"]=pair_distance(p)
for i,p in enumerate(points):p["waveRiding"]=[False,True,True,None][i]
for i,p in enumerate(points):p["surferSpeedSincePreviousKmh"]=[10,15,20,None][i]
for p in points:p["seawardBearingDeg"]=0
annotate_ride_windows(points,[(10000,"fixture"),(13000,"fixture")],0,1,timezone.utc)
root=Path.cwd().parent/"exports"
root.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="ui-test-",dir=root) as folder:
    write_outputs(points,target,Path(folder),timezone.utc,initial_ride="all")
    print((Path(folder)/"surfer-gps.html").read_text(encoding="utf-8"))
`;
function fixtureHtml(){
 if(process.argv[2])return fs.readFileSync(process.argv[2],'utf8');
 const result=spawnSync(process.env.PYTHON||'python',['-X','utf8','-c',fixtureCode],{cwd:__dirname,encoding:'utf8',windowsHide:true});
 if(result.status!==0)throw Error(result.stderr||String(result.error));
 return result.stdout;
}

const errors=[],captures=[],captureOverrides=[],downloads=[];
const frameResponse=(id,variant='telemetry')=>{
 const filename=id+(variant==='telemetry'?'':'-'+variant)+'.png';
 return {ok:true,json:async()=>({saved:filename,url:'/frames/'+filename,filename,variant,timestamp:new Date((10+Number(id.slice(1)))*1000).toISOString()})};
};
const videoResponse=request=>{
 const time=(request.videoTime??(10+Number(request.point.slice(1))))+request.step*.04;
 const variant=request.variant||'clean',filename='video-'+Math.round(time*1000)+'-'+variant+'.png';
 return {ok:true,json:async()=>({kind:'video',point:request.point,variant,videoTime:time,videoName:'DJI_0001.MP4',
  filename,url:'/frames/'+filename,saved:filename,timestamp:new Date(time*1000).toISOString()})};
};
const virtualConsole=new VirtualConsole();
virtualConsole.on('jsdomError',error=>errors.push(error.message));
const dom=new JSDOM(fixtureHtml(),{
 url:'http://127.0.0.1:9999/maps/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/',
 runScripts:'dangerously',pretendToBeVisual:true,virtualConsole,
 beforeParse(window){
  window.prompt=()=>{throw Error('The shared-position text prompt must never open');};
  window.fetch=async(url,options)=>{
   if(url.endsWith('/fov-results'))return {ok:true,json:async()=>[]};
   assert(url.endsWith('/capture')||url.endsWith('/video-frame'));
   const request=JSON.parse(options.body),id=request.point,video=url.endsWith('/video-frame');
   const variant=request.variant||(video?'clean':'telemetry');captures.push({id,url,variant,step:request.step,videoTime:request.videoTime});
   return captureOverrides.length?captureOverrides.shift()(id,variant,request):video?videoResponse(request):frameResponse(id,variant);
  };
  window.HTMLDialogElement.prototype.showModal=function(){assert(!this.open,'Only open the frame viewer once');this.open=true;};
  window.HTMLDialogElement.prototype.close=function(){this.open=false;};
 }
});
const w=dom.window,d=w.document;
d.addEventListener('click',e=>{
 const link=e.target.closest('a[download]');
 if(link){e.preventDefault();downloads.push({filename:link.download,url:link.href});}
},true);
const settle=()=>new Promise(resolve=>setTimeout(resolve,0));
const click=(node,ctrlKey=false)=>node.dispatchEvent(new w.MouseEvent('click',{bubbles:true,cancelable:true,button:0,ctrlKey}));
const hover=node=>node.dispatchEvent(new w.MouseEvent('pointerenter',{clientX:100,clientY:100}));
const check=id=>d.querySelector('#pair-options input[data-pair="'+id+'"]');
const marker=id=>[...d.querySelectorAll('.surfer-marker')].find(n=>n.dataset.pairs.split(' ').includes(id));
const toggle=(id,value)=>{const n=d.getElementById(id);n.checked=value;n.dispatchEvent(new w.Event('change',{bubbles:true}));};
const changeRide=value=>{const n=d.getElementById('ride-filter');n.value=value;n.dispatchEvent(new w.Event('change',{bubbles:true}));};
const shown=selector=>[...d.querySelectorAll(selector)].filter(n=>n.style.display!=='none');
const checked=()=>[...d.querySelectorAll('#pair-options input:checked')].map(n=>n.dataset.pair);
const closeCapture=()=>d.querySelector('dialog').close();
const arrow=(key,target=d.activeElement,extra={})=>{
 const event=new w.KeyboardEvent('keydown',{bubbles:true,cancelable:true,key,...extra});
 target.dispatchEvent(event);return event;
};
(async()=>{
 await settle();
 assert.deepEqual(errors,[]);
 assert.equal(d.querySelectorAll('#map-layers input').length,5);
 const shared=marker('S0');
 assert(shared.dataset.pairs.includes('S1'));
 assert.equal(shared.querySelector('.ride-ring').dataset.rideState,'mixed');
 assert.equal(marker('S2').querySelector('.ride-ring').dataset.rideState,'all');
 assert.equal(marker('S3').querySelector('.ride-ring'),null);
 assert.equal(check('S1').closest('label').querySelector('.ride-badge').textContent,'RIDE');
 assert.equal(check('S0').closest('label').querySelector('.ride-badge'),null);
 const originalSelection=checked(),threshold=d.getElementById('speed-threshold');
 const speedValue=value=>{threshold.value=String(value);threshold.dispatchEvent(new w.Event('input',{bubbles:true}));};
 assert.equal(d.getElementById('show-ride-rings').checked,true);
 assert.equal(d.getElementById('show-speed-rings').checked,false);
 assert.equal(d.querySelectorAll('.speed-ring').length,0);
 toggle('show-ride-rings',false);
 assert.equal(d.querySelectorAll('.ride-ring').length,0);
 assert.deepEqual(checked(),originalSelection);
 toggle('show-speed-rings',true);
 assert.equal(shared.querySelector('.speed-ring').dataset.speedState,'mixed','15 km/h includes the threshold endpoint');
 assert.equal(marker('S2').querySelector('.speed-ring').dataset.speedState,'all');
 assert.equal(marker('S3').querySelector('.speed-ring'),null,'Unknown speed is never a match');
 speedValue(20);
 assert.equal(shared.querySelector('.speed-ring'),null);
 assert.equal(marker('S2').querySelector('.speed-ring').dataset.speedState,'all');
 speedValue(20.1);assert.equal(d.querySelectorAll('.speed-ring').length,0);
 speedValue(0);
 assert.equal(shared.querySelector('.speed-ring').dataset.speedState,'all');
 assert.equal(marker('S3').querySelector('.speed-ring'),null);
 speedValue('');assert.equal(d.querySelectorAll('.speed-ring').length,0);
 assert.equal(threshold.getAttribute('aria-invalid'),'true');
 speedValue(-1);assert.equal(d.querySelectorAll('.speed-ring').length,0);
 speedValue(15);assert.equal(threshold.getAttribute('aria-invalid'),'false');
 toggle('show-ride-rings',true);
 assert.equal(marker('S2').querySelectorAll('.ride-ring,.speed-ring').length,2);
 assert(Number(marker('S2').querySelector('.speed-ring').getAttribute('r'))>Number(marker('S2').querySelector('.ride-ring').getAttribute('r')));
 toggle('hide-surfers',true);assert.equal(shown('.surfer-marker').length,0);
 toggle('hide-surfers',false);
 changeRide('R2');
 assert(shown('.surfer-marker').every(n=>n.querySelector('.speed-ring')===null));
 assert.equal(d.getElementById('show-speed-rings').checked,true);
 assert.equal(threshold.value,'15');
 changeRide('all');
 assert.equal(shared.querySelector('.speed-ring').dataset.speedState,'mixed');
 check('S1').checked=false;check('S1').dispatchEvent(new w.Event('change',{bubbles:true}));
 assert.equal(shared.querySelector('.speed-ring'),null,'A hidden fast packet cannot highlight a shared coordinate');
 check('S1').checked=true;check('S1').dispatchEvent(new w.Event('change',{bubbles:true}));
 assert.equal(shared.querySelector('.speed-ring').dataset.speedState,'mixed');
 assert.deepEqual(checked(),originalSelection);
 toggle('show-speed-rings',false);
 assert.equal(d.querySelectorAll('.speed-ring').length,0);
 assert.equal(captures.length,0,'Highlight controls do not request video captures');

 // Sea direction is north in this fixture: S1 and S2 point seaward, while
 // S0 is stationary and S3 has no following receipt. Shared coordinates keep S0.
 assert.equal(d.getElementById('hide-seaward').checked,false);
 toggle('hide-seaward',true);
 assert.deepEqual(checked(),originalSelection,'Direction filtering must preserve sample checkboxes');
 assert.deepEqual(shown('.surfer-marker').map(n=>n.dataset.label),['S0','S3']);
 assert.equal(shared.querySelector('.ride-ring'),null,'Hidden ride packets cannot contribute rings');
 assert(check('S1').closest('label').hidden);
 assert(check('S2').closest('label').hidden);
 assert(!check('S0').closest('label').hidden);
 assert(!d.querySelector('#position-filter option[value="S1"]'));
 assert(!d.querySelector('#position-filter option[value="S2"]'));
 assert.equal(d.getElementById('position-filter-status').textContent,'2 of 2 positions shown');
 assert(d.getElementById('ride-window-info').textContent.includes('2 seaward positions hidden'));
 assert(shown('.pair-line').every(n=>!n.dataset.pairs.split(' ').some(id=>id==='S1'||id==='S2')));
 hover(shared);
 assert.deepEqual([...d.querySelectorAll('#tooltip [data-capture-point]')].map(n=>n.dataset.capturePoint),['S0']);
 assert.equal(d.getElementById('side-point').value,'S0');
 toggle('show-speed-rings',true);
 assert(shown('.surfer-marker').every(n=>!n.querySelector('.speed-ring')),'Hidden fast points cannot contribute rings');
 toggle('show-speed-rings',false);
 changeRide('R1');
 assert.deepEqual(shown('.surfer-marker').map(n=>n.dataset.label),['S0']);
 changeRide('R2');
 assert.deepEqual(shown('.surfer-marker').map(n=>n.dataset.label),['S3']);
 changeRide('all');
 toggle('hide-drones',true);toggle('hide-connections',true);
 toggle('hide-seaward',false);
 assert.equal(shown('.drone-marker').length,0);
 assert.equal(shown('.pair-line').length,0);
 assert.equal(shown('.surfer-marker').length,3);
 toggle('hide-drones',false);toggle('hide-connections',false);
 check('S2').checked=false;check('S2').dispatchEvent(new w.Event('change',{bubbles:true}));
 toggle('hide-seaward',true);toggle('hide-seaward',false);
 assert.equal(check('S2').checked,false,'Removing the filter cannot restore a manually deselected point');
 check('S2').checked=true;check('S2').dispatchEvent(new w.Event('change',{bubbles:true}));
 const position=d.getElementById('position-filter');
 position.value='S1';position.dispatchEvent(new w.Event('change',{bubbles:true}));
 toggle('hide-seaward',true);
 assert.equal(shown('.surfer-marker').length,0);
 assert.equal(shown('.drone-marker').length,0);
 assert.equal(shown('.pair-line').length,0);
 assert.equal(d.getElementById('side-point').value,'');
 toggle('hide-seaward',false);
 assert.equal(shared.dataset.label,'S1');
 assert.equal(d.getElementById('side-point').value,'S1');
 click(d.getElementById('pairs-all'));
 assert.deepEqual(checked(),originalSelection);
 assert.equal(captures.length,0,'Direction filtering must not request captures');

 // Speed visibility uses the same value as speed rings, without enabling them.
 assert.equal(d.getElementById('hide-slow-speed').checked,false);
 assert.equal(d.getElementById('show-speed-rings').checked,false);
 speedValue(15);toggle('hide-slow-speed',true);
 assert.deepEqual(checked(),originalSelection,'Speed filtering preserves checked positions');
 assert.deepEqual(shown('.surfer-marker').map(n=>n.dataset.label),['S1','S2','S3']);
 assert.equal(d.querySelectorAll('.speed-ring').length,0);
 assert(check('S0').closest('label').hidden);
 assert(!check('S1').closest('label').hidden,'Exactly 15 km/h remains visible');
 assert(!check('S3').closest('label').hidden,'Unknown speed is not assumed to be below threshold');
 assert(!d.querySelector('#position-filter option[value="S0"]'));
 assert(shown('.pair-line').every(n=>!n.dataset.pairs.split(' ').includes('S0')));
 hover(shared);
 assert.deepEqual([...d.querySelectorAll('#tooltip [data-capture-point]')].map(n=>n.dataset.capturePoint),['S1']);
 assert.equal(d.getElementById('side-point').value,'S1');
 speedValue(20);
 assert.deepEqual(shown('.surfer-marker').map(n=>n.dataset.label),['S2','S3']);
 speedValue(20.1);
 assert.deepEqual(shown('.surfer-marker').map(n=>n.dataset.label),['S3']);
 speedValue(10);
 assert.equal(shown('.surfer-marker').length,3,'10 km/h includes the threshold endpoint');
 speedValue(10.1);
 assert.equal(shared.dataset.label,'S1','Typing a new threshold immediately refilters shared positions');
 speedValue(0);
 assert.equal(shared.dataset.label,'S0, S1');
 speedValue('');
 assert.equal(threshold.getAttribute('aria-invalid'),'true');
 assert.equal(shown('.surfer-marker').length,3,'Invalid input temporarily suspends speed filtering');
 speedValue(-1);assert.equal(shown('.surfer-marker').length,3);
 speedValue(15);
 toggle('hide-seaward',true);
 assert.deepEqual(shown('.surfer-marker').map(n=>n.dataset.label),['S3']);
 assert(d.getElementById('ride-window-info').textContent.includes('2 seaward positions hidden'));
 assert(d.getElementById('ride-window-info').textContent.includes('1 below 15 km/h hidden'));
 toggle('hide-seaward',false);
 changeRide('R1');
 assert.deepEqual(shown('.surfer-marker').map(n=>n.dataset.label),['S1']);
 changeRide('all');
 check('S2').checked=false;check('S2').dispatchEvent(new w.Event('change',{bubbles:true}));
 toggle('hide-drones',true);toggle('hide-connections',true);
 toggle('hide-slow-speed',false);
 assert.equal(check('S2').checked,false,'Disabling speed filtering preserves manual deselection');
 assert.equal(shared.dataset.label,'S0, S1');
 assert.equal(shown('.drone-marker').length,0);
 assert.equal(shown('.pair-line').length,0);
 toggle('hide-drones',false);toggle('hide-connections',false);
 check('S2').checked=true;check('S2').dispatchEvent(new w.Event('change',{bubbles:true}));
 assert.deepEqual(checked(),originalSelection);
 assert.equal(captures.length,0,'Speed filtering never requests frames');

 hover(shared);click(shared,true);
 assert.equal(captures.length,0,'Shared coordinates must wait for an exact choice');
 assert.equal(d.querySelectorAll('#tooltip [data-capture-point]').length,2);
 shared.dispatchEvent(new w.MouseEvent('pointerleave'));
 await new Promise(resolve=>setTimeout(resolve,250));
 assert.equal(d.getElementById('tooltip').hidden,false,'Ctrl-click pins the shared cards');
 assert(d.querySelector('#tooltip [data-capture-point="S1"]').textContent.includes('Ride detected: YES'));
 assert(d.querySelector('#tooltip [data-capture-point="S0"]').textContent.includes('Ride detected: NO'));
 const before=checked();
 click(d.querySelector('#tooltip [data-capture-point="S1"]'),true);
 await settle();
 assert.equal(captures.at(-1).id,'S1');
 assert.equal(captures.at(-1).url,'/maps/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/capture');
 assert.equal(d.getElementById('side-point').value,'S1');
 assert.deepEqual(checked(),before);
 closeCapture();
 click(check('S0'),true);await settle();
 assert.equal(captures.at(-1).id,'S0');
 assert.equal(check('S0').checked,true,'Ctrl-click cannot toggle the visibility checkbox');
 assert.deepEqual(checked(),before);
 closeCapture();
 hover(shared);
 const firstCard=d.querySelector('#tooltip [data-capture-point="S0"]');
 shared.dispatchEvent(new w.MouseEvent('pointermove'));
 assert.equal(d.querySelector('#tooltip [data-capture-point="S0"]'),firstCard,'Pointer movement cannot rebuild a scrollable card');
 shared.dispatchEvent(new w.MouseEvent('pointerleave'));
 d.getElementById('tooltip').dispatchEvent(new w.MouseEvent('pointerenter'));
 await new Promise(resolve=>setTimeout(resolve,250));
 assert.equal(d.getElementById('tooltip').hidden,false);
 firstCard.dispatchEvent(new w.KeyboardEvent('keydown',{bubbles:true,cancelable:true,key:'Enter',ctrlKey:true}));
 await settle();assert.equal(captures.at(-1).id,'S0');closeCapture();
 for(const [id,selector] of [['hide-drones','.drone-marker'],['hide-connections','.pair-line'],['hide-surfers','.surfer-marker']]){
  const previous=checked();toggle(id,true);
  assert.equal(shown(selector).length,0);
  assert.deepEqual(checked(),previous,'Layer toggles cannot alter packet selection');
 }
 toggle('hide-surfers',false);
 assert.equal(shown('.drone-marker').length,0);
 assert.equal(shown('.pair-line').length,0);
 assert(shown('.surfer-marker').length>0);
 changeRide('R2');
 assert.deepEqual([...d.querySelectorAll('#pair-options label:not([hidden]) input:checked')].map(n=>n.dataset.pair),['S3']);
 assert.equal(d.getElementById('hide-drones').checked,true);
 assert.equal(shown('.drone-marker').length,0);
 assert.equal(shown('.pair-line').length,0);
 assert.deepEqual(shown('.surfer-marker').map(n=>n.dataset.pairs),['S3']);
 toggle('hide-drones',false);toggle('hide-connections',false);
 changeRide('all');
 check('S0').checked=false;check('S0').dispatchEvent(new w.Event('change',{bubbles:true}));
 assert.equal(shared.querySelector('.ride-ring').dataset.rideState,'all','Only the selected riding packet controls the ring');
 const selected=checked();
 toggle('hide-surfers',true);toggle('hide-surfers',false);
 assert.deepEqual(checked(),selected,'Restoring layers cannot restore a deselected position');
 hover(shared);click(shared,true);await settle();
 assert.equal(captures.at(-1).id,'S1','Only the visible shared packet is captured');
 closeCapture();
 hover(marker('S2'));click(marker('S2'),true);await settle();
 assert.equal(captures.at(-1).id,'S2','Single-coordinate Ctrl-click still captures directly');
 closeCapture();
 check('S1').checked=false;check('S0').checked=true;check('S0').dispatchEvent(new w.Event('change',{bubbles:true}));
 assert.equal(shared.querySelector('.ride-ring'),null,'No ride ring when only the non-riding packet is selected');
 // Hover selects the starting point. Left/Right navigation preserves checked
 // positions and visits distinct receipts even at the same coordinates.
 changeRide('all');
 threshold.focus();
 hover(shared);
 assert.equal(d.activeElement,shared,'Hover transfers keyboard focus from a field to the surfer');
 assert(shared.classList.contains('active-pair'));
 shared.dispatchEvent(new w.MouseEvent('pointerleave'));
 await new Promise(resolve=>setTimeout(resolve,250));
 assert(!d.getElementById('tooltip').hidden,'Hover selection persists after leaving the marker');
 const beforeKeys=checked(),capturesBeforeKeys=captures.length;
 assert.equal(d.getElementById('side-point').value,'S0');
 assert(arrow('ArrowRight').defaultPrevented,'Hover supplies keyboard focus without a click');
 assert.equal(d.getElementById('side-point').value,'S1');
 assert.deepEqual([...d.querySelectorAll('#tooltip [data-capture-point]')].map(n=>n.dataset.capturePoint),['S1']);
 assert.equal(d.activeElement,shared);
 assert.deepEqual(checked(),beforeKeys);
 assert(arrow('ArrowLeft').defaultPrevented);
 assert.equal(d.getElementById('side-point').value,'S0');
 arrow('ArrowLeft');assert.equal(d.getElementById('side-point').value,'S0','First point does not wrap');
 arrow('ArrowRight');arrow('ArrowRight');
 assert.equal(d.getElementById('side-point').value,'S2');
 assert.equal(d.activeElement,marker('S2'));
 assert(marker('S2').classList.contains('active-pair'));
 assert(!shared.classList.contains('active-pair'));
 arrow('ArrowRight');arrow('ArrowRight');
 assert.equal(d.getElementById('side-point').value,'S3','Last point does not wrap');
 assert.deepEqual(checked(),beforeKeys);
 assert.equal(captures.length,capturesBeforeKeys,'Arrow keys must not capture frames');
 for(const target of [threshold,d.getElementById('side-height'),d.getElementById('ride-filter')]){
  assert(!arrow('ArrowLeft',target).defaultPrevented,'Editing controls keep native arrow keys');
  assert.equal(d.getElementById('side-point').value,'S3');
 }
 assert(!arrow('ArrowLeft',marker('S3'),{ctrlKey:true}).defaultPrevented);
 assert.equal(d.getElementById('side-point').value,'S3');
 toggle('hide-surfers',true);
 assert(!arrow('ArrowLeft',marker('S3')).defaultPrevented);
 toggle('hide-surfers',false);
 click(d.querySelector('.drone-marker'));
 assert(!arrow('ArrowLeft',d.body).defaultPrevented,'A drone selection cannot start surfer navigation');
 click(d.querySelector('#tooltip .position-card-heading button'));
 hover(shared);
 assert(arrow('ArrowRight',d.body).defaultPrevented,'Hover alone selects a surfer');
 assert.equal(d.getElementById('side-point').value,'S1');
 hover(shared);
 assert.equal(d.getElementById('side-point').value,'S1','Re-entering the shared marker preserves the exact keyboard-selected record');
 hover(marker('S3'));
 assert.equal(d.getElementById('side-point').value,'S3','Hover replaces the previous keyboard selection');
 assert(marker('S3').classList.contains('active-pair'));
 assert(!shared.classList.contains('active-pair'));
 assert(arrow('ArrowLeft',d.body).defaultPrevented);
 assert.equal(d.getElementById('side-point').value,'S2');
 marker('S3').dispatchEvent(new w.MouseEvent('pointermove'));
 assert.equal(d.getElementById('side-point').value,'S2','Movement over the old marker cannot undo a keyboard step');
 assert(arrow('ArrowUp').defaultPrevented,'Up remains a map alias');
 assert.equal(d.getElementById('side-point').value,'S3');
 assert(arrow('ArrowDown').defaultPrevented,'Down remains a map alias');
 assert.equal(d.getElementById('side-point').value,'S2');

 // Manual hides and the seaward filter are respected; layer toggles stay set.
 check('S1').checked=false;check('S1').dispatchEvent(new w.Event('change',{bubbles:true}));
 click(shared);arrow('ArrowRight',shared);
 assert.equal(d.getElementById('side-point').value,'S2','Skip manually hidden S1');
 click(d.getElementById('pairs-all'));toggle('hide-seaward',true);
 toggle('hide-drones',true);toggle('hide-connections',true);
 click(shared);arrow('ArrowRight',shared);
 assert.equal(d.getElementById('side-point').value,'S3','Skip seaward S1 and S2');
 assert.equal(shown('.drone-marker').length,0);
 assert.equal(shown('.pair-line').length,0);
 toggle('hide-seaward',false);toggle('hide-drones',false);toggle('hide-connections',false);
 changeRide('R1');click(shared);arrow('ArrowRight',shared);arrow('ArrowRight');
 assert.equal(d.getElementById('side-point').value,'S1','Do not leave the filtered ride');
 changeRide('all');
 speedValue(15);toggle('hide-slow-speed',true);
 hover(shared);assert.equal(d.getElementById('side-point').value,'S1');
 arrow('ArrowRight',d.body);assert.equal(d.getElementById('side-point').value,'S2');
 arrow('ArrowLeft');arrow('ArrowLeft');
 assert.equal(d.getElementById('side-point').value,'S1','Do not navigate into a below-threshold position');
 toggle('hide-slow-speed',false);

 // A single-position dropdown advances through the eligible positions too.
 position.value='S1';position.dispatchEvent(new w.Event('change',{bubbles:true}));
 arrow('ArrowRight',position);
 assert.deepEqual(checked(),['S2']);
 assert.equal(d.getElementById('side-point').value,'S2');
 arrow('ArrowLeft');assert.deepEqual(checked(),['S1']);
 click(d.getElementById('pairs-all'));click(shared);arrow('ArrowRight',shared);
 assert.equal(d.getElementById('side-point').value,'S1');
 click(shared,true);await settle();
 assert.equal(captures.at(-1).id,'S1','Ctrl-click captures the exact keyboard-selected shared point');
 // The open viewer navigates frames in place using the same filtered ordering.
 const viewer=d.querySelector('dialog'),frameId=()=>viewer.dataset.point;
 const frameKey=async key=>{assert(arrow(key,viewer.querySelector('button')).defaultPrevented);await settle();};
 const frameSelection=checked();
 assert(!arrow('ArrowUp',viewer.querySelector('button')).defaultPrevented,'Frame viewer no longer uses Up');
 assert(!arrow('ArrowDown',viewer.querySelector('button')).defaultPrevented,'Frame viewer no longer uses Down');
 await frameKey('ArrowRight');
 assert(viewer.open);
 assert.equal(frameId(),'S2');
 assert.equal(captures.at(-1).id,'S2');
 assert(d.getElementById('frame-label').textContent.startsWith('S2 —'));
 assert(viewer.querySelector('img').src.includes('/frames/S2.png'));
 assert.equal(viewer.querySelector('a').download,'S2.png');
 assert.equal(d.getElementById('side-point').value,'S2');
 assert.deepEqual(checked(),frameSelection);
 await frameKey('ArrowLeft');assert.equal(frameId(),'S1');
 await frameKey('ArrowLeft');assert.equal(frameId(),'S0','Shared coordinates retain separate frames');
 const atFirst=captures.length;
 await frameKey('ArrowLeft');assert.equal(captures.length,atFirst,'No wrap or extraction before first position');
 assert.equal(d.getElementById('frame-status').textContent,'First position.');
 assert(!arrow('ArrowRight',viewer.querySelector('button'),{altKey:true}).defaultPrevented);
 assert.equal(captures.length,atFirst);
 closeCapture();

 // Frame navigation respects manual hides, ride selection and seaward filtering.
 check('S1').checked=false;check('S1').dispatchEvent(new w.Event('change',{bubbles:true}));
 click(check('S0'),true);await settle();
 await frameKey('ArrowRight');assert.equal(frameId(),'S2','Skip manually hidden frame');
 closeCapture();changeRide('R1');
 click(check('S1'),true);await settle();
 const atRideEnd=captures.length;
 await frameKey('ArrowRight');assert.equal(captures.length,atRideEnd);
 assert.equal(d.getElementById('frame-status').textContent,'Last position.');
 await frameKey('ArrowLeft');assert.equal(frameId(),'S0');
 closeCapture();changeRide('all');toggle('hide-seaward',true);
 click(check('S0'),true);await settle();
 await frameKey('ArrowRight');assert.equal(frameId(),'S3','Skip seaward frames');
 await frameKey('ArrowLeft');assert.equal(frameId(),'S0');
 closeCapture();toggle('hide-seaward',false);

 // In single-position mode, update the selection only after successful capture.
 position.value='S1';position.dispatchEvent(new w.Event('change',{bubbles:true}));
 click(check('S1'),true);await settle();
 await frameKey('ArrowRight');assert.equal(frameId(),'S2');
 assert.deepEqual(checked(),['S2']);
 captureOverrides.push(()=>({ok:false,json:async()=>({error:'Missing video for this position'})}));
 await frameKey('ArrowRight');
 assert(viewer.open);
 assert.equal(frameId(),'S2','Failed capture retains the displayed frame');
 assert(viewer.querySelector('img').src.includes('/frames/S2.png'));
 assert.deepEqual(checked(),['S2']);
 assert(d.getElementById('frame-status').textContent.includes('Missing video'));
 await frameKey('ArrowRight');assert.equal(frameId(),'S3','Retry starts from the displayed position');

 // Busy arrows are consumed without queuing captures or changing the old frame.
 let finishCapture;
 captureOverrides.push(id=>new Promise(resolve=>{finishCapture=()=>resolve(frameResponse(id));}));
 arrow('ArrowLeft',viewer.querySelector('button'));
 const busyCount=captures.length;
 assert.equal(frameId(),'S3');
 assert.deepEqual(checked(),['S3']);
 assert.equal(viewer.getAttribute('aria-busy'),'true');
 assert(d.getElementById('frame-status').textContent.includes('Loading S2'));
 arrow('ArrowLeft',viewer.querySelector('button'),{repeat:true});arrow('ArrowRight',viewer.querySelector('button'));
 assert.equal(captures.length,busyCount,'Only one in-flight capture');
 finishCapture();await settle();
 assert.equal(frameId(),'S2');
 assert.deepEqual(checked(),['S2']);
 assert(!viewer.hasAttribute('aria-busy'));
 captureOverrides.push(id=>new Promise(resolve=>{finishCapture=()=>resolve(frameResponse(id));}));
 arrow('ArrowLeft',viewer.querySelector('button'));
 closeCapture();finishCapture();await settle();
 assert(!viewer.open,'A late frame must not reopen a closed viewer');
 assert.equal(frameId(),'S2');
 assert.deepEqual(checked(),['S2'],'Closing cancels the pending selection change');
 // Each export belongs to the displayed point and leaves its telemetry preview intact.
 click(check('S2'),true);await settle();
 const downloadLink=variant=>viewer.querySelector('[data-download-variant="'+variant+'"]');
 assert.equal(viewer.querySelector('a').textContent,'Download with telemetry');
 assert.equal(downloadLink('clean').textContent,'Download clean');
 assert.equal(downloadLink('enhanced').textContent,'Download enhanced');
 const previewUrl=viewer.querySelector('img').src,selectionBeforeDownload=checked();
 click(viewer.querySelector('a'));
 assert.equal(downloads.at(-1).filename,'S2.png');
 click(downloadLink('clean'));await settle();
 assert.equal(captures.at(-1).variant,'clean');
 assert.equal(downloads.at(-1).filename,'S2-clean.png');
 assert(downloads.at(-1).url.endsWith('/frames/S2-clean.png'));
 assert.equal(frameId(),'S2');
 assert.equal(viewer.querySelector('img').src,previewUrl);
 assert.deepEqual(checked(),selectionBeforeDownload);
 const readyCount=captures.length;
 click(downloadLink('clean'));await settle();
 assert.equal(captures.length,readyCount,'Ready download links need no repeat extraction');
 captureOverrides.push(()=>({ok:false,json:async()=>({error:'Enhancement failed'})}));
 const downloadsBeforeFailure=downloads.length;
 click(downloadLink('enhanced'));await settle();
 assert.equal(downloads.length,downloadsBeforeFailure);
 assert(d.getElementById('frame-status').textContent.includes('Enhancement failed'));
 assert.equal(viewer.querySelector('img').src,previewUrl);
 captureOverrides.push(id=>frameResponse(id));
 click(downloadLink('enhanced'));await settle();
 assert.equal(downloads.length,downloadsBeforeFailure,'An old server cannot provide telemetry as a clean/enhanced download');
 assert(d.getElementById('frame-status').textContent.includes('Restart the map server'));
 click(downloadLink('enhanced'));await settle();
 assert.equal(downloads.at(-1).filename,'S2-enhanced.png');
 assert.equal(captures.at(-1).variant,'enhanced');
 await frameKey('ArrowRight');assert.equal(frameId(),'S3');
 captureOverrides.push((id,variant)=>new Promise(resolve=>{finishCapture=()=>resolve(frameResponse(id,variant));}));
 click(downloadLink('clean'));
 const preparingCount=captures.length;
 arrow('ArrowLeft',viewer.querySelector('button'));click(downloadLink('enhanced'));
 assert.equal(captures.length,preparingCount,'Downloads share the one-request-at-a-time guard');
 assert.equal(frameId(),'S3');
 finishCapture();await settle();
 assert.equal(downloads.at(-1).filename,'S3-clean.png');
 captureOverrides.push((id,variant)=>new Promise(resolve=>{finishCapture=()=>resolve(frameResponse(id,variant));}));
 click(downloadLink('enhanced'));
 const beforeClosing=downloads.length;
 closeCapture();finishCapture();await settle();
 assert(!viewer.open);
 assert.equal(downloads.length,beforeClosing,'Closing while preparing suppresses a late automatic download');
 // Ctrl+horizontal arrows use the actual video cursor without selecting GPS rows.
 changeRide('all');click(check('S1'),true);await settle();
 const beforeVideo=checked(),videoKey=async key=>{assert(arrow(key,viewer.querySelector('button'),{ctrlKey:true}).defaultPrevented);await settle();};
 await videoKey('ArrowRight');
 assert.equal(captures.at(-1).step,1);
 assert(captures.at(-1).url.endsWith('/video-frame'));
 assert.equal(captures.at(-1).videoTime,undefined,'First step starts from the displayed GPS frame mapping');
 assert.equal(viewer.dataset.kind,'video');
 assert(Math.abs(Number(viewer.dataset.videoTime)-11.04)<1e-8);
 assert(d.getElementById('frame-label').textContent.includes('Video frame'));
 assert(![...viewer.querySelectorAll('a')].some(n=>n.textContent.includes('telemetry')));
 assert.deepEqual(checked(),beforeVideo);
 assert.equal(d.getElementById('side-point').value,'S1','Raw stepping cannot invent or move a GPS selection');
 await videoKey('ArrowRight');
 assert(Math.abs(captures.at(-1).videoTime-11.04)<1e-8);
 assert(Math.abs(Number(viewer.dataset.videoTime)-11.08)<1e-8);
 await videoKey('ArrowLeft');
 assert.equal(captures.at(-1).step,-1);
 assert(Math.abs(Number(viewer.dataset.videoTime)-11.04)<1e-8);
 click(downloadLink('clean'));
 assert.equal(downloads.at(-1).filename,'video-11040-clean.png');
 click(downloadLink('enhanced'));await settle();
 assert.equal(captures.at(-1).step,0);
 assert(Math.abs(captures.at(-1).videoTime-11.04)<1e-8);
 assert.equal(downloads.at(-1).filename,'video-11040-enhanced.png','Enhanced download must use the current raw frame');
 await frameKey('ArrowLeft');
 assert.equal(viewer.dataset.kind,'surfer');
 assert.equal(frameId(),'S1','Plain Left returns to the nearest previous GPS frame after raw stepping');
 await frameKey('ArrowRight');assert.equal(frameId(),'S2');
 await videoKey('ArrowLeft');
 assert(Math.abs(Number(viewer.dataset.videoTime)-11.96)<1e-8);
 await frameKey('ArrowRight');assert.equal(frameId(),'S2');
 captureOverrides.push(()=>({ok:false,json:async()=>({error:'Last frame of this video.'})}));
 await videoKey('ArrowRight');
 assert.equal(viewer.dataset.kind,'surfer');
 assert.equal(frameId(),'S2');
 assert(d.getElementById('frame-status').textContent.includes('Last frame'));
 captureOverrides.push((id,variant,request)=>new Promise(resolve=>{finishCapture=()=>resolve(videoResponse(request));}));
 arrow('ArrowRight',viewer.querySelector('button'),{ctrlKey:true});
 const busyVideoCount=captures.length;
 arrow('ArrowRight',viewer.querySelector('button'),{ctrlKey:true,repeat:true});
 assert.equal(captures.length,busyVideoCount);
 closeCapture();finishCapture();await settle();
 assert(!viewer.open,'Late raw video result cannot reopen the viewer');
 assert.deepEqual(checked(),beforeVideo);
 assert.deepEqual(errors,[]);
 console.log('PASS: speed visibility/ring independence, combined filters, map/frame/video navigation, exports, boundaries and busy/closed viewer handling.');
 dom.window.close();
})().catch(error=>{console.error(error);dom.window.close();process.exitCode=1;});
