// Run with playwright on NODE_PATH. Optional PLAYWRIGHT_CHROMIUM_EXECUTABLE.
const assert=require('node:assert/strict');
const http=require('node:http');
const {spawnSync}=require('node:child_process');
const {chromium}=require('playwright');

const fixtureCode=String.raw`from pathlib import Path
from datetime import datetime,timezone
import tempfile
from surfer_gps_map import select_packets,pair_drones,write_outputs,pair_distance
points=[]
for i in range(4):
    t=10000+i*1000
    points.append(dict(time_ms=t,sourceFile="fixture",sourceLine=i+1,latitude=-27.95+(0 if i<2 else i*.0001),longitude=153.43,receivedTimestamp=datetime.fromtimestamp(t/1000,timezone.utc).isoformat(),sequence=i,accepted=True,gimbalTiltDeg=-35,heightAboveTakeoffM=8.5))
target,points=select_packets(points,"1970-01-01 00:00:10",3,timezone.utc)
pair_drones(points,{"fixture":[dict(time_ms=9000,sourceLine=0,latitude=-27.9502,longitude=153.43,timestamp="1970-01-01T00:00:09+00:00",age=0,fix=0)]})
for p in points:p["droneSurferDistanceMetres"]=pair_distance(p)
root=Path.cwd().parent/"exports"
root.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix="browser-test-",dir=root) as folder:
    write_outputs(points,target,Path(folder),timezone.utc,initial_ride="all")
    print((Path(folder)/"surfer-gps.html").read_text(encoding="utf-8"))
`;

async function main(){
 const generated=spawnSync(process.env.PYTHON||'python',['-X','utf8','-c',fixtureCode],{cwd:__dirname,encoding:'utf8',windowsHide:true});
 assert.equal(generated.status,0,generated.stderr||String(generated.error));
 const wrapper='<!doctype html><style>body{margin:0}iframe{display:block;width:100%;height:950px;border:0}</style><label>Selection setting <input id="setting" value="15"></label><button id="show">Show map</button><iframe id="map-frame" src="/map"></iframe>';
 const server=http.createServer((req,res)=>{
  res.setHeader('Content-Type','text/html; charset=utf-8');
  res.end(req.url==='/map'?generated.stdout:wrapper);
 });
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 let browser;
 try{
  browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE?{executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE}:{})});
  const page=await browser.newPage({viewport:{width:1700,height:1150}}),errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  await page.route('**/fov-results',route=>route.fulfill({json:[]}));
  await page.goto('http://127.0.0.1:'+server.address().port);
  const frame=page.frames().find(f=>f.url().endsWith('/map'));
  await frame.locator('.surfer-marker').first().waitFor();
  const marker=id=>frame.locator('.surfer-marker[data-pairs~="'+id+'"]');
  const selected=()=>frame.locator('#side-point').inputValue();
  const press=key=>page.keyboard.press(key);

  // Real keyboard dispatch goes to the focused document, not the hovered one.
  await page.locator('#show').click();
  await marker('S0').hover();
  assert.equal(await selected(),'S0');
  await press('ArrowRight');
  assert.equal(await selected(),'S1','Hover must give the embedded map keyboard focus without clicking it');
  await press('ArrowLeft');assert.equal(await selected(),'S0');
  await press('ArrowRight');await press('ArrowRight');assert.equal(await selected(),'S2');

  // Focus can also be left in a parent setting or in the map sidebar.
  await page.locator('#setting').focus();
  await marker('S3').hover();
  await press('ArrowLeft');assert.equal(await selected(),'S2','Hover must take focus from the outer settings');
  await frame.locator('#speed-threshold').focus();
  await marker('S0').hover();
  await press('ArrowRight');assert.equal(await selected(),'S1','Hover must take focus from a sidebar input');
  const selectedMarker=await frame.evaluate(()=>document.activeElement.classList.contains('surfer-marker'));
  assert(selectedMarker,'The actual marker, not just its highlight, receives focus');

  // Entering an input deliberately restores normal text-editing arrow keys.
  await frame.locator('#speed-threshold').focus();
  await press('ArrowLeft');assert.equal(await selected(),'S1');
  await page.locator('#setting').focus();
  await press('ArrowRight');assert.equal(await selected(),'S1');

  // Hovering elsewhere replaces the pinned position; the cursor may then leave.
  await marker('S3').hover();
  await page.mouse.move(1600,1100);
  await press('ArrowLeft');assert.equal(await selected(),'S2');
  await press('ArrowLeft');assert.equal(await selected(),'S1');
  await press('ArrowRight');assert.equal(await selected(),'S2');
  assert.deepEqual(errors,[]);
  console.log('PASS: real browser hover focus, embedded-map Left/Right, shared coordinates, sidebar/parent inputs, and persistent selection.');
 }finally{
  if(browser)await browser.close();
  await new Promise(resolve=>server.close(resolve));
 }
}
main().catch(error=>{console.error(error);process.exitCode=1;});
