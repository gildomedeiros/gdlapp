// Exercise the real replay/capture HTTP routes with synthetic GPS and video pixels.
const assert=require('node:assert/strict');
const {spawn}=require('node:child_process');
const readline=require('node:readline');
const path=require('node:path');
const {chromium}=require('playwright');
const fixture=String.raw`
from pathlib import Path
from datetime import timezone
from PIL import Image
from test_surfer_yaw_sim import LogReplayTests
from surfer_gps_map import select_packets,pair_drones,write_outputs
from surfer_map_app import MapApp,create_server
from surfer_map_capture import stamp_card
import surfer_map_capture,telemetry_selected_frames
import threading,json
test=LogReplayTests();test.setUp()
try:
 raw=[]
 for i,p in enumerate(test.points.values()):
  raw.append(dict(p,time_ms=1000+i*500,accepted=True,droneHeadingDeg=2,videoFrameTimestamp='1970-01-01T00:00:01Z',videoId='1',videoPlaybackSeconds=1))
 target,points=select_packets(raw,'1970-01-01T00:00:01Z',9,timezone.utc)
 pair_drones(points,{str(test.source):[dict(time_ms=990,latitude=0,longitude=0,timestamp='1970-01-01T00:00:00.990Z',age=0,fix=0,sourceLine=3)]})
 output=test.folder/'map';write_outputs(points,target,output,timezone.utc,initial_ride='all')
 def capture(point,video_folder,output,ffmpeg,gap,variant):
  output=Path(output);output.mkdir(parents=True,exist_ok=True);dest=output/(point['point']+'.png')
  Image.new('RGB',(1600,900),(40,120,145)).save(dest)
  if variant=='telemetry':stamp_card(dest,point,gap)
  return dest
 surfer_map_capture.capture_variant=capture
 telemetry_selected_frames.ffmpeg_path=lambda _: 'test'
 app=MapApp(app_dir=test.folder/'app');ident='a'*32
 app.snapshots[ident]=dict(id=ident,points={p['point']:p for p in points},output=output,cache_output=output,images={},lock=threading.Lock(),settings=dict(utc_offset='+00:00',video_folder='',ffmpeg='',gap_red_ms=1000))
 server=create_server(app)
 print(json.dumps(dict(url='http://'+server.address+'/maps/'+ident+'/')),flush=True)
 server.serve_forever()
finally:test.doCleanups();test.temp.cleanup()
`;
async function main(){
 const child=spawn(process.env.PYTHON||'python',['-X','utf8','-u','-c',fixture],{cwd:__dirname,windowsHide:true});
 let errors='';child.stderr.on('data',d=>errors+=d);
 const lines=readline.createInterface({input:child.stdout});
 let browser;
 try{
  const metadata=await new Promise((resolve,reject)=>{
   const timer=setTimeout(()=>reject(Error('Fixture timeout '+errors)),20000);
   lines.on('line',line=>{if(line.startsWith('{"url"')){clearTimeout(timer);resolve(JSON.parse(line));}});
   child.on('exit',code=>{clearTimeout(timer);reject(Error('Fixture exited '+code+' '+errors));});
  });
  browser=await chromium.launch({headless:true,executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE});
  const page=await browser.newPage({viewport:{width:1500,height:1100}}),pageErrors=[];
  page.on('pageerror',e=>pageErrors.push(e.message));
  await page.goto(metadata.url);await page.waitForSelector('#pair-options input');
  assert.deepEqual(pageErrors,[]);
  await page.locator('#yaw-sim-panel>summary').click();
  assert.equal(await page.locator('#yaw-sim-run').isDisabled(),true);
  assert.equal(await page.evaluate(()=>yawSimulationToken()),null);
  await page.locator('#yaw-sim-enabled').check();await page.locator('#yaw-sim-run').click();
  await page.waitForFunction(()=>document.querySelector('#yaw-sim-status').textContent.startsWith('Ready:'));
  const token=await page.evaluate(()=>yawSimulationToken());assert.match(token,/^[a-f0-9]{32}$/);
  await page.evaluate(()=>{const node=[...document.querySelectorAll('.surfer-marker')].find(n=>n.dataset.pairs.split(' ').includes('S9'));showDetails(node,null,true,'S9');});
  assert.match(await page.locator('#tooltip').innerText(),/offsetMagnitude\[SIM\]: \+5\.00° \[S00\]/);
  await page.evaluate(()=>clearDetails());
  const line=page.locator('.pair-line[data-pairs="S9"]');
  await line.dispatchEvent('pointerenter',{clientX:750,clientY:420});
  assert.match(await page.locator('#pair-line-hover').textContent(),/offsetMagnitude\[SIM\]: \+5\.00° \[S00\]/);
  assert.equal(await page.locator('#yaw-sim-rays line').count(),3);
  const csv=await (await page.request.get(new URL(await page.locator('#yaw-sim-csv').getAttribute('href'),metadata.url).href)).text();
  assert.match(csv,/parameter_magnitude/);assert.equal(csv.trim().split(/\r?\n/).length,11);
  await page.locator('#pairs-none').click();await page.locator('input[data-pair="S9"]').check();
  assert.equal(await page.evaluate(()=>yawSimulationRow('S9').appliedOffset),5);
  await page.evaluate(()=>capturePoint('S9'));
  await page.waitForSelector('dialog[open] img');
  const frame=page.locator('dialog[open] img');await frame.evaluate(img=>img.decode());
  assert.match(await frame.getAttribute('src'),new RegExp('sim-'+token));
  if(process.env.YAW_SIM_SCREENSHOT)await page.screenshot({path:process.env.YAW_SIM_SCREENSHOT});
  await page.locator('dialog[open] button').first().click();
  await line.dispatchEvent('pointerleave');assert.equal(await page.locator('#yaw-sim-rays line').count(),0);
  await page.locator('#yaw-sim-enabled').uncheck();
  assert.equal(await page.evaluate(()=>yawSimulationToken()),null);
  assert.equal(await page.locator('#yaw-sim-csv').isHidden(),true);
  assert.equal(await page.evaluate(()=>yawSimulationLines('S9').length),0);
  await page.locator('#yaw-sim-enabled').check();
  await page.locator('#yaw-sim-panel details>summary').click();
  await page.locator('[data-yaw-param="magnitude"]').fill('8');
  await page.locator('#yaw-sim-run').click();
  await page.waitForFunction(()=>document.querySelector('#yaw-sim-status').textContent.startsWith('Ready:'));
  assert.equal(await page.evaluate(()=>yawSimulationRow('S9').desiredOffset),8);
  assert.notEqual(await page.evaluate(()=>yawSimulationToken()),token);
  assert.deepEqual(pageErrors,[]);
  console.log('Yaw replay browser: cards, hover, rays, full-log selection, CSV, frame capture and settings passed.');
 }finally{await browser?.close();lines.close();child.kill();}
}
main().catch(error=>{console.error(error);process.exitCode=1;});
