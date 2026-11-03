// Run with playwright on NODE_PATH. Optional PLAYWRIGHT_CHROMIUM_EXECUTABLE.
const assert=require('node:assert/strict');
const http=require('node:http');
const path=require('node:path');
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
    references=[dict(kind="wave",label="Wave line · test",sourceFile="fixture",session=1,pairs=["S0","S1","S2","S3"],pointA=dict(latitude=-27.9504,longitude=153.4299),pointB=dict(latitude=-27.9496,longitude=153.4299)),
                dict(kind="boundary",label="Boundary line",sourceFile="fixture",session=1,pairs=["S0","S1","S2","S3"],latitude=-27.9502,longitude=153.43005,bearingDegrees=0),
                dict(kind="boundary",label="Boundary line",sourceFile="fixture",session=2,pairs=["S0","S1","S2","S3"],latitude=-27.9502,longitude=153.4301,bearingDegrees=0),
                dict(kind="start",label="Start (surfer)",sourceFile="fixture",session=None,pairs=["S0","S1","S2","S3"],latitude=-27.95,longitude=153.43)]
    write_outputs(points,target,Path(folder),timezone.utc,initial_ride="all",landmarks=references)
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
  // This symmetric fixture puts S0 at the draggable scale figure's default
  // position; move that independent overlay aside before testing GPS hits.
  await frame.evaluate(()=>{referenceX=900;drawReference();});
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

  // The scale is drawn on the bottom-left complete visible grid square.
  const scaleInfo=()=>frame.evaluate(()=>{
   const map=document.querySelector('#map > svg'),group=document.getElementById('map-scale');
   const triangle=document.getElementById('scale-triangle'),bounds=triangle.getBBox();
   const matrix=map.getScreenCTM(),mapRect=map.getBoundingClientRect(),rect=triangle.getBoundingClientRect();
   const step=Number(map.dataset.gridStep),gx=Number(map.dataset.gridX),gy=Number(map.dataset.gridY);
   const screen=(x,y)=>{const p=map.createSVGPoint();p.x=x;p.y=y;return p.matrixTransform(matrix);};
   const candidates=[];
   for(let column=-60;column<=60;column++)for(let row=-60;row<=60;row++){
    const x=gx+column*step,y=gy+row*step,a=screen(x,y),b=screen(x+step,y+step);
    if(a.x>=mapRect.left+1.99&&a.y>=mapRect.top+1.99&&b.x<=mapRect.right-1.99&&b.y<=mapRect.bottom-1.99)candidates.push({x,y});
   }
   candidates.sort((a,b)=>a.x-b.x||b.y-a.y);
   const gridRect=document.getElementById('map-grid').getBoundingClientRect();
   const labels=['horizontal','vertical','diagonal'].map(side=>document.getElementById('scale-'+side));
   return {gridCoversView:Math.abs(gridRect.left-mapRect.left)<.01&&Math.abs(gridRect.top-mapRect.top)<.01&&Math.abs(gridRect.right-mapRect.right)<.01&&Math.abs(gridRect.bottom-mapRect.bottom)<.01,hidden:group.style.display==='none',horizontal:parseFloat(labels[0].textContent),vertical:parseFloat(labels[1].textContent),
    diagonal:parseFloat(labels[2].textContent),width:bounds.width,height:bounds.height,perimeter:triangle.getTotalLength(),
    x:bounds.x,y:bounds.y,step,expectedCell:candidates[0],distance:step/Number(map.dataset.unitsPerMetre),
    pixels:rect.width,pixelsPerMetre:Number(map.dataset.unitsPerMetre)*Math.hypot(matrix.a,matrix.b),
    inside:rect.left>=mapRect.left&&rect.right<=mapRect.right&&rect.top>=mapRect.top&&rect.bottom<=mapRect.bottom,
    labelsInside:labels.every(label=>{const b=label.getBoundingClientRect();return b.left>=mapRect.left&&b.right<=mapRect.right&&b.top>=mapRect.top&&b.bottom<=mapRect.bottom;})};
  });
  const verifyScale=async()=>{
   await frame.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
   const s=await scaleInfo();
   assert(s.gridCoversView,'Grid must cover the entire SVG viewport');
   assert(!s.hidden&&s.inside&&s.labelsInside,'Grid scale and all labels must remain inside the map');
   assert.equal(s.horizontal,s.vertical);
   assert(Math.abs(s.horizontal-s.distance)<=.051,'Side labels must show this grid cell distance');
   assert(Math.abs(s.diagonal-s.distance*Math.SQRT2)<=.051,'Diagonal label must show this grid cell diagonal');
   assert.equal(s.width,s.step);assert.equal(s.height,s.step);
   assert.equal(s.x,s.expectedCell.x);assert.equal(s.y,s.expectedCell.y,'Choose the lowest, leftmost complete visible cell');
   assert(Math.abs(s.perimeter-s.width*(2+Math.SQRT2))<.01);
   assert(Math.abs(s.pixels-s.distance*s.pixelsPerMetre)<.03,'Scale must cover exactly one real grid square');
   return s;
  };
  await press('Escape');
  // References are quiet until hovered; nearby lines never show both labels.
  const visibleLabels=()=>frame.locator('.landmark-label').evaluateAll(nodes=>nodes.filter(n=>getComputedStyle(n).visibility==='visible'&&n.getClientRects().length).length);
  await page.mouse.move(1600,1100);
  assert.equal(await visibleLabels(),0);
  const refs=frame.locator('.map-landmark');
  for(let i=0;i<await refs.count();i++){
   const group=refs.nth(i),hit=await group.locator('.landmark-hit').boundingBox();
   const start=await group.evaluate(n=>n.classList.contains('start-point'));
   await page.mouse.move(hit.x+hit.width*(start?.92:.5),hit.y+hit.height*(start?.5:.2));
   assert.equal(await visibleLabels(),1,'Only the hovered reference label is shown: '+i+' '+JSON.stringify({hit,errors,state:await frame.evaluate(()=>({active:activeLandmark?.getAttribute('class'),hover:[...document.querySelectorAll(':hover')].slice(-4).map(n=>n.getAttribute('class')||n.tagName)}))}));
   assert(await group.evaluate(n=>n.classList.contains('active-landmark')),'Hover selects this reference');
   const inside=await frame.locator('.active-landmark-label').evaluate(n=>{const a=n.getBoundingClientRect(),b=n.ownerSVGElement.getBoundingClientRect();return a.left>=b.left&&a.top>=b.top&&a.right<=b.right&&a.bottom<=b.bottom;});
   assert(inside,'Hovered label stays within the map');
   await page.mouse.move(1600,1100);assert.equal(await visibleLabels(),0);
  }
  await frame.locator('.start-point').focus();assert.equal(await visibleLabels(),1,'Tab focus also reveals a label');
  await frame.locator('#hide-start-point').evaluate(n=>{n.checked=true;n.dispatchEvent(new Event('change'));});
  assert.equal(await visibleLabels(),0,'Hiding an active reference clears its label');
  await frame.locator('#hide-start-point').uncheck();
  await marker('S0').hover();assert.equal(await selected(),'S0','The start reference must not intercept the surfer at the same coordinate');
  await press('ArrowRight');assert.equal(await selected(),'S1');
  await press('Escape');
  for(const [id,selector] of [['hide-wave-line','.wave-line'],['hide-boundary-line','.boundary-line'],['hide-start-point','.start-point']]){
   assert.equal(await frame.locator(selector).count(),selector==='.boundary-line'?2:1);
   assert(await frame.locator(selector).evaluateAll(nodes=>nodes.every(n=>n.style.display==='')));
   await frame.locator('#'+id).check();assert(await frame.locator(selector).evaluateAll(nodes=>nodes.every(n=>n.style.display==='none')));
   await frame.locator('#'+id).uncheck();assert(await frame.locator(selector).evaluateAll(nodes=>nodes.every(n=>n.style.display==='')));
  }
  await frame.locator('#fit-ride').click();
  const anchorsFit=await frame.evaluate(()=>{
   const svg=document.querySelector('#map > svg'),map=svg.getBoundingClientRect();
   return [...document.querySelectorAll('.landmark-anchor')].every(n=>{const p=svg.createSVGPoint();p.x=Number(n.dataset.x);p.y=Number(n.dataset.y);const a=p.matrixTransform(svg.getScreenCTM());return a.x>=map.left&&a.x<=map.right&&a.y>=map.top&&a.y<=map.bottom;});
  });
  assert(anchorsFit,'Fit includes the wave endpoints, boundary anchor and surfer start');
  const originalScale=await verifyScale();
  await frame.evaluate(()=>zoom(.5));
  const zoomedScale=await verifyScale();
  assert.equal(zoomedScale.horizontal,originalScale.horizontal,'Grid distance stays constant through zoom');
  assert(Math.abs(zoomedScale.pixels-2*originalScale.pixels)<.03,'Scale grows with the actual grid');
  const mapBounds=await frame.locator('#map > svg').boundingBox();
  await page.mouse.move(mapBounds.x+mapBounds.width*.15,mapBounds.y+mapBounds.height*.15);
  await page.mouse.down();await page.mouse.move(mapBounds.x+mapBounds.width*.45,mapBounds.y+mapBounds.height*.35,{steps:5});await page.mouse.up();
  await verifyScale();
  await frame.locator('#fit-ride').click();await verifyScale();
  await frame.evaluate(()=>{box[0]-=1500;box[1]+=1500;draw();});await verifyScale();
  await frame.locator('#fit-ride').click();await verifyScale();
  await page.setViewportSize({width:1300,height:1050});await verifyScale();
  await page.setViewportSize({width:1700,height:1150});await verifyScale();
  await frame.evaluate(()=>zoom(.02));
  assert.equal(await frame.locator('#map-scale').evaluate(n=>n.style.display),'none','Do not label a clipped cell as a full square');
  await frame.locator('#fit-ride').click();await verifyScale();
  // Measure any pair of logged markers. Use the start rim so the GPS marker
  // at exactly the same coordinate still receives its own centre clicks.
  await frame.locator('#map-controls > summary').click();
  await frame.locator('#measure').click();
  const clickStart=async()=>{
   const hit=await frame.locator('.start-point > .landmark-hit').boundingBox();
   await page.mouse.click(hit.x+hit.width*.92,hit.y+hit.height*.5);
  };
  const clickDrone=()=>frame.locator('.drone-marker').first().click();
  const ruler=()=>frame.evaluate(()=>{
   const group=document.getElementById('map-measurement'),line=document.getElementById('measurement-line'),label=document.getElementById('measurement-distance');
   return {count:selected.length,names:selected.map(p=>p.dataset.label),distance:Number(group.dataset.metres),
    text:label?.textContent,ticks:group.querySelectorAll('.measurement-tick').length,
    fontPixels:label?Number(label.getAttribute('font-size'))*Math.hypot(svg.getScreenCTM().a,svg.getScreenCTM().b):null,
    endpoints:line&&['x1','y1','x2','y2'].map(name=>Number(line.getAttribute(name))),
    expected:selected.flatMap(p=>[Number(p.dataset.x),Number(p.dataset.y)])};
  });
  await clickStart();assert.equal((await ruler()).count,1);
  assert.match((await frame.locator('#measurement-result').textContent()),/Start \(surfer\) selected/);
  await clickDrone();
  let m=await ruler();
  assert.equal(m.text,'22.24 m');assert.equal(m.names[0],'Start (surfer)');
  assert(Math.abs(m.distance-22.2389853)<.001);assert(m.ticks>2);
  assert.deepEqual(m.endpoints,m.expected,'The ruler connects the actual coordinates, not the click offset');
  assert(Math.abs(m.fontPixels-13)<.001);
  await frame.evaluate(()=>zoom(.7));
  m=await ruler();assert.equal(m.text,'22.24 m');assert.deepEqual(m.endpoints,m.expected);
  assert(Math.abs(m.fontPixels-13)<.001,'Ruler text keeps its screen size after zoom');
  await page.setViewportSize({width:1400,height:1050});
  await frame.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
  assert(Math.abs((await ruler()).fontPixels-13)<.001,'Ruler text stays readable after resize');
  await page.setViewportSize({width:1700,height:1150});await frame.locator('#fit-ride').click();
  const waveEnds=frame.locator('.wave-line .landmark-anchor');
  await waveEnds.nth(0).click();assert.equal((await ruler()).count,1,'A third pick starts a new measurement');
  await waveEnds.nth(1).focus();await press('Enter');
  m=await ruler();assert.equal(m.text,'88.96 m');assert.match(m.names[0],/endpoint A/);assert.match(m.names[1],/endpoint B/);
  await frame.locator('.boundary-line .landmark-anchor').first().click();
  await marker('S3').click();
  m=await ruler();assert.equal(m.count,2);assert.match(m.names[0],/Boundary line · anchor/);assert.equal(m.names[1],'S3');
  assert(m.distance>55&&m.distance<57);
  await clickStart();await marker('S0').click();
  m=await ruler();assert.equal(m.text,'0.00 m','Coincident markers have zero distance');
  assert(!await frame.locator('#map-measurement').evaluate(n=>/NaN|Infinity/.test(n.outerHTML)));
  await frame.locator('#clear-measure').click();assert.equal((await ruler()).count,0);
  await clickStart();await clickDrone();
  await frame.locator('#map-controls > summary').click();
  await frame.locator('#map').screenshot({path:path.join(__dirname,'..','exports','marker-ruler-preview.png')});
  await frame.locator('#hide-start-point').check();assert.equal((await ruler()).count,0,'Hiding a measured layer clears the ruler');
  await frame.locator('#hide-start-point').uncheck();
  await frame.locator('#map-controls > summary').click();await frame.locator('#measure').click();
  assert.equal(await frame.locator('.landmark-anchor').first().evaluate(n=>getComputedStyle(n).display),'none');
  await frame.locator('#map-controls > summary').click();
  await frame.locator('#map').screenshot({path:path.join(__dirname,'..','exports','full-grid-landmarks-preview.png')});
  assert.deepEqual(errors,[]);
  console.log('PASS: browser navigation, hover labels, all-marker rulers, GPS distances, zero distance, zoom/resize, clearing, layers and grid scale.');
 }finally{
  if(browser)await browser.close();
  await new Promise(resolve=>server.close(resolve));
 }
}
main().catch(error=>{console.error(error);process.exitCode=1;});
