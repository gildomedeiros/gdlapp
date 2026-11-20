const assert=require('node:assert/strict');
const {spawn}=require('node:child_process');
const path=require('node:path');
const {chromium}=require('playwright');
const fixture=String.raw`
from pathlib import Path
import tempfile,json,sys
from vtc_map import App,create_server
from test_vtc_map import MapTests
t=MapTests();t.setUp()
t.records[1]['settings'].update(aimingUnits='metres',magnitudeMetres='2',panToleranceMetres='1',tiltToleranceMetres='.5',acquisitionTriggerMetres='3',
    zoomMode='bands',wideFrameFactor='1.648',desiredFrameWidthMetres='40',zoomMapping=[dict(zoomX=1,ccapiValue=0),dict(zoomX=8,ccapiValue=100)])
t.records.extend([dict(event='camera_zoom_response',epochMs=950,zoom=100),
    dict(event='zoom_decision',epochMs=960,decision=dict(zoomMode='bands',action='At requested zoom position',selectedBandIndex=2,bandZoomX=8,bandMinDistanceMetres=60,bandMaxDistanceMetres=200,multiplier=1)),
    dict(event='camera_zoom_error',epochMs=5100,exception='test timeout after final GPS')])
t.write()
t.records[2]['framing'].update(mode='RIDE',rideActive=True,qualifyingSpeedKmh=10,startConfirmations=2,reason='Ride')
# The following aim cycle also carries the ride flag in current VTC.
t.records[4]['framing'].update(mode='RIDE',rideActive=True,qualifyingSpeedKmh=10,startConfirmations=2,reason='Ride')
t.records.append(dict(event='goto_captured',epochMs=1250,target=dict(kind='SURFER',source=dict(sequence=1)),acquisitionTriggerMetres=3))
t.write()
app=App(t.root)
from test_vtc_video import mp4
videos=t.root/'videos';videos.mkdir();mp4(videos/'MVI_0635.MP4',start='1970-01-01T00:00:00+00:00',duration=20)
app.config['video']['folder']=str(videos)
app.config['folder']=str(t.root)
# Serve the real UI while preferences/snapshots use the isolated fixture root.
from vtc_map import ROOT
import shutil
(t.root/'tools').mkdir();shutil.copy2(ROOT/'tools/vtc_map.html',t.root/'tools/vtc_map.html')
server=create_server(app)
print(json.dumps(dict(url='http://'+server.address)),flush=True)
try:server.serve_forever()
finally:server.server_close();t.doCleanups()
`;
async function main(){
 const child=spawn('python',['-X','utf8','-u','-c',fixture],{cwd:__dirname,windowsHide:true});let stderr='',browser;
 child.stderr.on('data',b=>stderr+=b);
 try{
  const url=await new Promise((resolve,reject)=>{let out='';const timer=setTimeout(()=>reject(Error('Fixture timeout '+stderr)),15000);child.stdout.on('data',b=>{out+=b;for(const line of out.split('\n'))if(line.startsWith('{"url"')){clearTimeout(timer);resolve(JSON.parse(line).url);}});child.on('exit',code=>{clearTimeout(timer);reject(Error('Fixture exit '+code+stderr));});});
  browser=await chromium.launch({headless:true,executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE});
  const page=await browser.newPage({viewport:{width:1600,height:1050}}),errors=[];page.on('pageerror',e=>errors.push(e.message));
  await page.goto(url);await page.waitForFunction(()=>!document.getElementById('show').disabled);
  await page.locator('#show').click();await page.waitForSelector('#points input');
  assert.equal(await page.locator('#points input').count(),4);assert.match(await page.locator('#card').innerText(),/Surfer–base distance:/);
  await page.locator('#next').click();assert.match(await page.locator('#selected-label').innerText(),/S1/);
  assert.match(await page.locator('#card').innerText(),/Pan target: [\d.]+ m left of surfer/);
  assert.match(await page.locator('#card').innerText(),/Actual pan error: 0.00 m — centred on surfer/);
  assert.match(await page.locator('#card').innerText(),/Magnitude: 2\.00 m/);
  assert.match(await page.locator('#card').innerText(),/Wave ride: YES/);
  assert.match(await page.locator('#card').innerText(),/GoTo: ACTIVE · SURFER/);
  assert.match(await page.locator('#card').innerText(),/Continuous pan: INACTIVE — fixed GoTo controls movement/);
  assert.match(await page.locator('#card').innerText(),/optical ≈8\.00x/);
  assert.match(await page.locator('#card').innerText(),/Zoom band: #2/);
  assert.equal(await page.locator('#layer-heading').isChecked(),false);
  assert.equal(await page.locator('#aim-layer .actual-heading').count(),0);
  assert.equal(await page.locator('#aim-layer polygon').count(),1);
  assert.equal(await page.locator('#aim-layer polygon').getAttribute('data-fresh'),'true');
  assert.equal(await page.locator('#aim-layer polygon').getAttribute('stroke'),'#14804a');
  await page.locator('#timeline').fill('3');await page.locator('#timeline').dispatchEvent('input');
  assert.equal(await page.locator('#aim-layer polygon').getAttribute('data-fresh'),'false');
  assert.equal(await page.locator('#aim-layer polygon').getAttribute('stroke'),'#c52b34');
  await page.locator('#timeline').fill('1');await page.locator('#timeline').dispatchEvent('input');
  await page.locator('#layer-heading').check();
  assert.equal(await page.locator('#aim-layer .actual-heading').count(),1);
  await page.locator('#layer-heading').uncheck();
  assert.equal(await page.locator('#aim-layer polygon').count(),1);
  assert.equal(await page.locator('.point.active .selection-ring').getAttribute('fill'), 'none');
  await page.locator('#diagnostics>summary').click();
  await page.locator('#event-filter').selectOption('camera_zoom_error');
  assert.match(await page.locator('#event-list').innerText(),/camera_zoom_error/);
  const [eventDownload]=await Promise.all([page.waitForEvent('download'),page.locator('#event-download').click()]);
  const eventText=require('node:fs').readFileSync(await eventDownload.path(),'utf8');
  assert.match(eventText,/test timeout after final GPS/);
  await page.locator('#diagnostics>summary').click();
  await page.locator('.connection[data-pair="S1"]').dispatchEvent('pointerenter',{clientX:700,clientY:350});
  assert.match(await page.locator('#tooltip').innerText(),/Pan target: [\d.]+ m left of surfer/);
  assert.equal(await page.locator('#aim-layer .target-heading').count(),1);
  await page.locator('.connection[data-pair="S1"]').dispatchEvent('pointerleave');
  await page.locator('#measure').check();
  await page.locator('.point[aria-label="Camera"]').dispatchEvent('click');
  await page.locator('.point[aria-label="S1"]').dispatchEvent('click');
  assert.match(await page.locator('#measure-status').innerText(),/Camera ↔ S1: .* m/);
  assert.equal(await page.locator('#ruler line').count(),1);
  await page.locator('#measure').uncheck();
  await page.locator('#hide-all').click();assert.equal(await page.locator('#map .point').count(),0);
  await page.locator('#points input[data-point="S1"]').check();
  const [download]=await Promise.all([page.waitForEvent('download'),page.locator('#kml').click()]);
  const fs=require('node:fs');const kml=fs.readFileSync(await download.path(),'utf8');assert.match(kml,/S1/);assert.match(kml,/Camera/);assert.doesNotMatch(kml,/S0 · GPS/);
  await page.locator('#layer-camera').uncheck();assert.equal(await page.locator('.point[aria-label="Camera"]').count(),0);
  await page.locator('#layer-camera').check();
  await page.locator('#show-all').click();await page.locator('#fit').click();
  await page.screenshot({path:path.join(__dirname,'../map-browser.png'),fullPage:true});
  await page.locator('[aria-label="Timestamp 1"]').fill('1970-01-01T00:00:01Z');
  await page.locator('[aria-label="Following packets 1"]').fill('1');
  await page.locator('[aria-label="Timestamp 2"]').fill('1970-01-01T00:00:01.500Z');
  await page.locator('[aria-label="Following packets 2"]').fill('1');
  await page.locator('#show').click();await page.waitForFunction(()=>document.querySelectorAll('#points input').length===3);
  await page.reload();await page.waitForFunction(()=>document.querySelector('[aria-label="Timestamp 1"]').value.includes('1970'));
  assert.equal(await page.locator('[aria-label="Recent timestamp 1"] option').count(),3);
  const blocked=await page.request.post(url+'/api/map',{data:{file:'0'}});assert.equal(blocked.status(),403);
  await page.locator('#subtitle-panel>summary').click();
  await page.locator('#subtitle-video').selectOption('MVI_0635.MP4');
  const mapCount=await page.locator('#points input').count();
  await page.locator('#subtitle-export').click();
  await page.waitForFunction(()=>document.getElementById('subtitle-status').textContent.startsWith('Subtitles ready:'),null,{timeout:20000});
  assert.equal(await page.locator('#subtitle-links a').count(),4);
  const srtUrl=await page.locator('#subtitle-links a').filter({hasText:'MVI_0635.srt'}).getAttribute('href');
  const srtResponse=await page.request.get(url+srtUrl);assert.equal(srtResponse.status(),200);
  const srtText=await srtResponse.text();assert.match(srtText,/Wave ride: YES/);assert.match(srtText,/GoTo: ACTIVE/);assert.match(srtText,/GPS age:/);
  const assUrl=await page.locator('#subtitle-links a').filter({hasText:'MVI_0635.ass'}).getAttribute('href');
  assert.match(await (await page.request.get(url+assUrl)).text(),/PlayResX: 1920/);
  assert.equal(await page.locator('#points input').count(),mapCount);
  await page.locator('#subtitle-panel>summary').click();
  await page.waitForFunction(()=>document.querySelector('[aria-label="Video filename 1"]').options.length===2);
  for(let i=1;i<=3;i++){
   await page.locator(`[aria-label="Video filename ${i}"]`).selectOption('MVI_0635.MP4');
   await page.locator(`[aria-label="Playback time ${i}"]`).fill(String([1,1.5,2][i-1]));
   await page.locator(`[aria-label="Playback time ${i}"]`).dispatchEvent('change');
   await page.locator(`[aria-label="Following packets ${i}"]`).fill('0');
  }
  await page.locator('#show').click();
  await page.waitForFunction(()=>document.querySelectorAll('#points input').length===3);
  await page.locator('#video-correction').fill('0.5');await page.locator('#video-correction').dispatchEvent('change');
  await page.waitForFunction(()=>document.querySelector('#times fieldset p').textContent.includes('10:00:01.500'));
  await page.locator('#show').click();await page.waitForFunction(()=>document.querySelectorAll('#points input').length===2);
  assert.deepEqual(await page.locator('#points input').evaluateAll(nodes=>nodes.map(n=>n.dataset.point)),['S1','S2']);
  await page.reload();await page.waitForFunction(()=>document.querySelector('[aria-label="Video filename 1"]').value==='MVI_0635.MP4');
  assert.equal(await page.locator('#video-correction').inputValue(),'0.5');
  assert.equal(await page.locator('[aria-label="Playback time 2"]').inputValue(),'1.5');
  if(process.env.VTC_SAMPLE_LOG){
   await page.locator('#folder').fill(path.dirname(process.env.VTC_SAMPLE_LOG));await page.locator('#folder').dispatchEvent('change');await page.locator('#refresh').click();
   await page.waitForFunction(()=>!document.getElementById('show').disabled);
   await page.locator('#files').selectOption({label:path.basename(process.env.VTC_SAMPLE_LOG)});
   await page.locator('#clear-times').click();await page.locator('#show').click();
   await page.waitForFunction(()=>document.querySelectorAll('#points input').length===666);
   await page.locator('#timeline').fill('200');await page.locator('#timeline').dispatchEvent('input');
   assert.match(await page.locator('#card').innerText(),/Magnitude: 2\.00 m/);
   assert.match(await page.locator('#card').innerText(),/Zoom mode: bands/);
   assert.match(await page.locator('#card').innerText(),/Zoom frame width: ≈30\.6 m/);
   assert.match(await page.locator('#event-summary').textContent(),/8 zoom commands · 8 confirmations · 9 camera\/command errors/);
   await page.locator('#setup>summary').click();
   await page.screenshot({path:path.join(__dirname,'../sample-1.0.25-map.png'),fullPage:true});
  }
  assert.deepEqual(errors,[]);
  console.log('PASS: native VTC log map, causal cards, headings/FOV, ruler, point/layer selection, KML, three timestamps, history and local API guard.');
 }finally{await browser?.close();child.kill();}
}
main().catch(e=>{console.error(e);process.exitCode=1;});
