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

const errors=[],captures=[];
const virtualConsole=new VirtualConsole();
virtualConsole.on('jsdomError',error=>errors.push(error.message));
const dom=new JSDOM(fixtureHtml(),{
 url:'http://127.0.0.1:9999/maps/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/',
 runScripts:'dangerously',pretendToBeVisual:true,virtualConsole,
 beforeParse(window){
  window.prompt=()=>{throw Error('The shared-position text prompt must never open');};
  window.fetch=async(url,options)=>{
   if(url.endsWith('/fov-results'))return {ok:true,json:async()=>[]};
   assert(url.endsWith('/capture'));
   const id=JSON.parse(options.body).point;captures.push({id,url});
   return {ok:true,json:async()=>({saved:id+'.png',url:'/frames/'+id+'.png',filename:id+'.png',timestamp:'1970-01-01T00:00:11Z'})};
  };
  window.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
  window.HTMLDialogElement.prototype.close=function(){this.open=false;};
 }
});
const w=dom.window,d=w.document;
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
(async()=>{
 await settle();
 assert.deepEqual(errors,[]);
 assert.equal(d.querySelectorAll('#map-layers input').length,4);
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
 assert.deepEqual(errors,[]);
 console.log('PASS: capture controls, layer independence, rides, optional highlights, speed thresholds and seaward filtering (shared positions, unknown direction, selection restoration).');
 dom.window.close();
})().catch(error=>{console.error(error);dom.window.close();process.exitCode=1;});
