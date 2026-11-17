// Real-browser KML downloads: original coordinates, checked pairs and logged wave lines.
const assert=require('node:assert/strict');
const http=require('node:http');
const path=require('node:path');
const {spawnSync}=require('node:child_process');
const {chromium}=require('playwright');
const fixture=String.raw`from pathlib import Path
from datetime import datetime,timezone
import tempfile
from surfer_gps_map import select_packets,pair_drones,write_outputs
from surfer_pitch_samples import annotate_ride_windows
source='flight & <one>.jsonl'
points=[]
for i in range(4):
    t=10000+i*1000
    points.append(dict(time_ms=t,sourceFile=source,sourceLine=i+1,
        latitude=-27.95+(0 if i<2 else i*.00001),longitude=153.43,
        receivedTimestamp=datetime.fromtimestamp(t/1000,timezone.utc).isoformat(),
        sequence=100+i,accepted=True,gimbalTiltDeg=-35,heightAboveTakeoffM=8.5))
target,points=select_packets(points,'1970-01-01 00:00:10',3,timezone.utc)
pair_drones(points,{source:[dict(time_ms=9000,sourceLine=9,latitude=-27.9502123456789,
    longitude=153.430123456789,timestamp='1970-01-01T00:00:09+00:00',age=0,fix=0)]})
points[3].update(droneLatitude=None,droneLongitude=None,droneReportedTimestamp=None)
for i,p in enumerate(points):
    p.update(surferSpeedSincePreviousKmh=[10,15,20,None][i],seawardBearingDeg=0,retreatActive=i==1)
annotate_ride_windows(points,[(10000,source)],0,1,timezone.utc)
waves=[dict(kind='wave',label='Wave line & original',sourceFile=source,session=1,pairs=['S0','S1'],
    pointA=dict(latitude=-27.9504,longitude=153.4299),pointB=dict(latitude=-27.9496,longitude=153.4299)),
    dict(kind='wave',label='Other wave line',sourceFile=source,session=2,pairs=['S2','S3'],
    pointA=dict(latitude=-27.9504,longitude=153.4301),pointB=dict(latitude=-27.9496,longitude=153.4301))]
root=Path.cwd().parent/'exports';root.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix='kml-test-',dir=root) as folder:
    write_outputs(points,target,Path(folder),timezone.utc,initial_ride='all',landmarks=waves)
    print((Path(folder)/'surfer-gps.html').read_text(encoding='utf-8'))
`;
async function main(){
 const generated=spawnSync(process.env.PYTHON||'python',['-X','utf8','-c',fixture],{cwd:__dirname,encoding:'utf8',windowsHide:true});
 assert.equal(generated.status,0,generated.stderr||String(generated.error));
 const server=http.createServer((req,res)=>{
  const json=req.url.endsWith('-results');res.setHeader('Content-Type',json?'application/json':'text/html; charset=utf-8');
  res.end(json?'[]':generated.stdout);
 });
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 let browser;
 try{
  browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE?{executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE}:{})});
  const page=await browser.newPage({acceptDownloads:true,viewport:{width:1600,height:1100}}),errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  await page.goto('http://127.0.0.1:'+server.address().port);
  const button=page.locator('#download-selected-kml'),wave=page.locator('#kml-include-wave');
  assert(await wave.isChecked(),'Logged wave line is included by default');
  await page.locator('#hide-seaward').uncheck();
  async function download(){
   const event=page.waitForEvent('download');await button.click();const file=await event;
   const chunks=[];for await(const chunk of await file.createReadStream())chunks.push(chunk);
   const xml=Buffer.concat(chunks).toString('utf8');
   const data=await page.evaluate(text=>{
    const doc=new DOMParser().parseFromString(text,'application/xml');
    return {error:doc.querySelector('parsererror')?.textContent,ns:doc.documentElement.namespaceURI,
     marks:[...doc.querySelectorAll('Placemark')].map(mark=>({
      name:mark.querySelector('name')?.textContent,coords:mark.querySelector('coordinates')?.textContent,
      when:mark.querySelector('when')?.textContent,mode:mark.querySelector('altitudeMode')?.textContent,
      source:mark.querySelector('Data[name="sourceFile"] value')?.textContent,
      style:mark.querySelector('styleUrl')?.textContent,type:mark.querySelector('Point')?'point':'line'}))};
   },xml);
   assert(!data.error,data.error);assert.equal(data.ns,'http://www.opengis.net/kml/2.2');
   return {...data,filename:file.suggestedFilename()};
  }
  await page.locator('#pairs-none').click();assert(await button.isDisabled(),'Empty selection cannot download');
  await page.locator('#pair-options input[data-pair="S1"]').check();
  let file=await download();
  assert.equal(file.filename,'surfer-drone-S1.kml');
  assert.deepEqual(file.marks.map(p=>p.name),['S1','D1','Wave line & original']);
  assert.equal(file.marks[0].coords,'153.43,-27.95,0','Use GPS coordinates in longitude,latitude order');
  assert.equal(file.marks[1].coords,'153.430123456789,-27.9502123456789,0','Preserve drone coordinate precision');
  assert.equal(file.marks[0].when,'1970-01-01T00:00:11+00:00');
  assert.equal(file.marks[1].when,'1970-01-01T00:00:09+00:00','Use the drone report timestamp');
  assert.equal(file.marks[0].source,'flight & <one>.jsonl','Escape XML metadata');
  assert.equal(file.marks[1].style,'#retreat');
  assert.equal(file.marks[2].coords,'153.4299,-27.9504,0 153.4299,-27.9496,0');
  assert(file.marks.every(p=>p.mode==='clampToGround'));
  // Display-only layer switches must not remove requested GPS or wave geometry.
  for(const id of ['hide-drones','hide-surfers','hide-wave-line'])await page.locator('#'+id).check();
  assert.deepEqual((await download()).marks,file.marks);
  for(const id of ['hide-drones','hide-surfers','hide-wave-line'])await page.locator('#'+id).uncheck();
  await wave.uncheck();assert.deepEqual((await download()).marks.map(p=>p.name),['S1','D1']);await wave.check();
  await page.locator('#pair-options input[data-pair="S0"]').check();
  file=await download();
  assert.deepEqual(file.marks.map(p=>p.name),['S0','S1','D0','D1','Wave line & original'],'Keep distinct packets at a shared coordinate');
  await page.locator('#pairs-all').click();
  await page.locator('#speed-threshold').fill('15');await page.locator('#hide-slow-speed').check();
  assert.deepEqual((await download()).marks.map(p=>p.name),['S1','S2','S3','D1','D2','Wave line & original','Other wave line']);
  await page.locator('#hide-seaward').check();
  assert.deepEqual((await download()).marks.map(p=>p.name),['S3','Other wave line'],'Exclude checked but filtered-out pairs and unrelated waves');
  assert.match(await page.locator('#selected-kml-status').textContent(),/1 without drone GPS/);
  await page.locator('#hide-seaward').uncheck();await page.locator('#hide-slow-speed').uncheck();
  await page.locator('#ride-filter').selectOption('R1');
  assert.deepEqual((await download()).marks.map(p=>p.name),['S0','S1','D0','D1','Wave line & original'],'Respect the selected ride');
  await page.locator('#position-filter').selectOption('S1');
  await page.locator('#selected-kml-controls').screenshot({path:path.join(__dirname,'..','exports','selected-kml-controls.png')});
  assert.deepEqual(errors,[]);
  console.log('PASS: real KML downloads, checked/shared points, exact GPS/timestamps, wave default, optional wave, filters, missing drone and layer independence.');
 }finally{
  if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));
 }
}
main().catch(error=>{console.error(error);process.exitCode=1;});
