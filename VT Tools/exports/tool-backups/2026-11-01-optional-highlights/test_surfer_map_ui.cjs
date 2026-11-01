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
 assert.equal(d.querySelectorAll('#map-layers input').length,3);
 const shared=marker('S0');
 assert(shared.dataset.pairs.includes('S1'));
 assert.equal(shared.querySelector('.ride-ring').dataset.rideState,'mixed');
 assert.equal(marker('S2').querySelector('.ride-ring').dataset.rideState,'all');
 assert.equal(marker('S3').querySelector('.ride-ring'),null);
 assert.equal(check('S1').closest('label').querySelector('.ride-badge').textContent,'RIDE');
 assert.equal(check('S0').closest('label').querySelector('.ride-badge'),null);

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
 console.log('PASS: exact shared-card capture, sidebar Ctrl-click, keyboard capture, stable hover cards, layer independence, ride changes, single-point capture and per-packet ride indicators.');
 dom.window.close();
})().catch(error=>{console.error(error);dom.window.close();process.exitCode=1;});
