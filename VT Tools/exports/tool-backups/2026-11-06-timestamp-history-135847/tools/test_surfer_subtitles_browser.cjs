// Real local MapApp server: field selection, batch job, downloads and map retention.
const assert=require('node:assert/strict');
const {spawn}=require('node:child_process');
const {chromium}=require('playwright');
const fixture=String.raw`
from pathlib import Path
import json, tempfile, threading
from unittest.mock import patch
from surfer_map_app import MapApp,create_server
from test_surfer_subtitles import packet,drones,source_srt
from surfer_drone_status import status_record
root=Path.cwd().parent/'exports';root.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix='subtitle-browser-',dir=root) as directory:
    folder=Path(directory);log=folder/'flight.jsonl';log.write_text('',encoding='utf-8')
    video=folder/'videos';video.mkdir()
    (folder/'empty').mkdir()
    (video/'DJI_0001.MP4').write_bytes(b'test video')
    (video/'DJI_0002.mp4').write_bytes(b'no SRT test')
    (video/'DJI_0001.SRT').write_text(source_srt([999,1000,2501,3000]),encoding='utf-8',newline='')
    rows=[packet(1000,sourceFile=str(log)),packet(3000,2,sourceFile=str(log))]
    for p in rows:
        p['cameraHeadingDeg']=151.3
        p['droneStatus']={'aiming':status_record(dict(event='aiming_cycle',state='AIMING',
            decision='riding_correction',rotationCurve='riding',remainingAngleDeg=-6.326,
            desiredYawRate=-6.326,submitted=True,submittedYawRate=-5.71,reverseBlocked=False),p['time_ms'],0)}
    app=MapApp(app_dir=folder/'app')
    settings=dict(app.config['settings'],folder=str(folder),video_folder=str(video),output=str(folder/'output'),utc_offset='+00:00')
    with patch('surfer_gps_map.load_packets',return_value=(rows,drones(str(log)))):
        job=app.start(dict(settings=settings,mode='timestamp',selection=dict(at='00:00:01',next=0)))
        app.worker.join(10)
        assert app.public_job(job['id'])['status']=='done',app.public_job(job['id'])
        server=create_server(app)
        thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
        print(json.dumps(dict(url='http://'+server.address)),flush=True)
        input()
        server.shutdown();server.server_close();thread.join()
`;
async function main(){
 const child=spawn(process.env.PYTHON||'python',['-X','utf8','-c',fixture],{cwd:__dirname,windowsHide:true});
 let browser,stderr='';child.stderr.on('data',b=>stderr+=b.toString());
 try{
  const url=await new Promise((resolve,reject)=>{
   let out='';const timer=setTimeout(()=>reject(Error('Fixture timeout: '+stderr)),20000);
   child.on('exit',code=>{clearTimeout(timer);if(code)reject(Error(stderr));});
   child.stdout.on('data',chunk=>{
    out+=chunk.toString();for(const line of out.split('\n'))if(line.startsWith('{"url":')){clearTimeout(timer);resolve(JSON.parse(line).url);return;}
   });
  });
  browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE?{executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE}:{})});
  const page=await browser.newPage({viewport:{width:1500,height:1000}}),errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  await page.goto(url);
  await page.waitForFunction(()=>document.querySelectorAll('#subtitle-fields input').length===25);
  await page.locator('#map-frame').waitFor({state:'visible'});
  const map=await page.locator('#map-frame').getAttribute('src');
  const expected='Aiming: [AIMING] [tracking surfer] [riding] [error 6.33° left] [desired 6.33°/s left] [sent 5.71°/s left] [reversal blocked: no]';
  const mapFrame=page.frameLocator('#map-frame');
  await mapFrame.locator('.surfer-marker').first().hover();
  const statusLine=mapFrame.locator('#tooltip span').filter({hasText:expected});
  await statusLine.waitFor({state:'visible'});
  assert.equal(await statusLine.evaluate(n=>getComputedStyle(n).color),'rgb(8, 117, 46)');
  await page.mouse.move(1490,990);
  await mapFrame.locator('body').press('Escape');
  await mapFrame.locator('#tooltip').waitFor({state:'hidden'});
  // Focus invokes the same floating label as pointer hover without relying on
  // a thin SVG stroke's position inside the scrolling application iframe.
  await mapFrame.locator('.pair-line').first().focus();
  const hover=mapFrame.locator('#pair-line-hover');await hover.waitFor({state:'visible'});
  assert((await hover.textContent()).includes(expected));
  assert.equal(await hover.locator('text').filter({hasText:expected}).locator('tspan').first().getAttribute('fill'),'#08752e');
  await mapFrame.locator('.drone-marker').first().hover({force:true});
  await statusLine.waitFor({state:'visible'});
  assert.equal(await page.locator('#subtitle-fields input[value="camera_heading_error"]').isChecked(),true);
  await page.locator('#subtitle-settings summary').click();
  await page.waitForFunction(()=>document.querySelectorAll('#subtitle-video option').length===3);
  assert.deepEqual(await page.locator('#subtitle-video option').allTextContents(),['All','DJI_0001.MP4','DJI_0002.mp4']);
  assert.equal(await page.locator('#subtitle-video').inputValue(),'');
  await page.locator('#subtitle-none').click();assert.equal(await page.locator('#subtitle-export').isDisabled(),true);
  await page.locator('#subtitle-all').click();assert.equal(await page.locator('#subtitle-fields input:checked').count(),25);
  await page.locator('#subtitle-export').click();
  await page.waitForFunction(()=>document.getElementById('status').textContent.includes('Subtitles ready:'),null,{timeout:20000});
  assert.equal(await page.locator('#map-frame').getAttribute('src'),map);
  assert.match(await page.locator('#subtitle-summary').textContent(),/1\/2 videos exported/);
  assert.match(await page.locator('#subtitle-issues').textContent(),/DJI_0002.mp4: No matching original SRT/);
  const srtLink=page.locator('#subtitle-links a').filter({hasText:'DJI_0001.srt'});
  const response=await page.request.get(url+await srtLink.getAttribute('href'));
  assert.equal(response.status(),200);
  const cardHref=await page.locator('#subtitle-links a').filter({hasText:'DJI_0001.ass'}).getAttribute('href');
  const card=await page.request.get(url+cardHref);assert.equal(card.headers()['content-type'],'text/x-ssa; charset=utf-8');
  const ass=await card.text();assert.match(ass,/PlayResX: 1920/);
  const displayed=ass.split('\n').filter(line=>line.startsWith('Dialogue: 2,')).map(line=>line.split(',').slice(9).join(',').replace(/\{[^}]*\}/g,'').trim()).join(' ');
  for(const part of ['[riding]','[error 6.33° left]','[desired 6.33°/s left]','[sent 5.71°/s left]','[reversal blocked: no]'])assert(displayed.includes(part));
  const text=await response.text();
  assert(text.includes(expected));
  assert.match(text,/Camera heading error:/);
  assert.match(text,/GPS age: 1.501 s/);assert.match(text,/face="Arial" size="28"/);assert.match(text,/color="#08752e"[^>]*><b>TILTING/);assert.match(text,/original saved note/);
  await page.locator('#subtitle-none').click();
  await page.locator('#subtitle-fields input[value="gps_age"]').check();
  await page.locator('#subtitle-fields input[value="packet"]').check();
  await page.locator('#subtitle-video').selectOption('DJI_0001.MP4');
  await page.locator('#subtitle-export').click();
  await page.waitForFunction(()=>document.getElementById('status').textContent.includes('Subtitles ready:')&&!document.getElementById('subtitle-export').disabled,null,{timeout:20000});
  const secondLink=await page.locator('#subtitle-links a').filter({hasText:'DJI_0001.srt'}).getAttribute('href');
  const selectedText=await (await page.request.get(url+secondLink)).text();
  assert.match(await page.locator('#subtitle-summary').textContent(),/DJI_0001.MP4 · 1\/1 videos exported/);
  assert.equal(await page.locator('#subtitle-issues').textContent(),'');
  assert.equal(await page.locator('#map-frame').getAttribute('src'),map);
  assert.match(selectedText,/Packet: 2/);assert.doesNotMatch(selectedText,/Gimbal tilt:/);assert.doesNotMatch(selectedText,/Camera heading error:/);
  await page.locator('#subtitle-video').selectOption('DJI_0002.mp4');
  await page.locator('#subtitle-export').click();
  await page.waitForFunction(()=>document.getElementById('status').textContent.includes('Subtitles ready:')&&!document.getElementById('subtitle-export').disabled,null,{timeout:20000});
  assert.match(await page.locator('#subtitle-summary').textContent(),/DJI_0002.mp4 · 0\/1 videos exported/);
  assert.equal(await page.locator('#subtitle-links a').filter({hasText:'DJI_0001'}).count(),0);
  await page.reload();await page.waitForFunction(()=>document.querySelectorAll('#subtitle-fields input').length===25);
  assert.equal(await page.locator('#subtitle-fields input:checked').count(),2);
  assert.equal(await page.locator('#map-frame').getAttribute('src'),map);
  await page.locator('#subtitle-settings summary').click();
  await page.locator('#subtitle-all').click();
  await page.waitForFunction(()=>document.querySelectorAll('#subtitle-video option').length===3);
  assert.equal(await page.locator('#subtitle-video').inputValue(),'');
  await page.locator('#subtitle-video').selectOption('DJI_0001.MP4');
  await page.locator('#subtitle-refresh').click();
  await page.waitForFunction(()=>!document.getElementById('subtitle-export').disabled);
  assert.equal(await page.locator('#subtitle-video').inputValue(),'DJI_0001.MP4');
  await page.locator('#data-settings > summary').click();
  const videoInput=page.locator('#data-settings [name="video_folder"]');
  const videoFolder=await videoInput.inputValue();
  await videoInput.fill(require('node:path').join(require('node:path').dirname(videoFolder),'empty'));
  await videoInput.dispatchEvent('change');
  await page.waitForFunction(()=>document.getElementById('subtitle-video-status').textContent.includes('No MP4 files'));
  assert.equal(await page.locator('#subtitle-export').isDisabled(),true);
  await videoInput.fill(videoFolder);await videoInput.dispatchEvent('change');
  await page.waitForFunction(()=>!document.getElementById('subtitle-export').disabled);
  assert.deepEqual(await page.locator('#subtitle-video option').allTextContents(),['All','DJI_0001.MP4','DJI_0002.mp4']);
  await page.locator('#data-settings > summary').click();
  await page.screenshot({path:require('node:path').join(__dirname,'../subtitle-export-ui.png'),fullPage:true});
  assert.deepEqual(errors,[]);
  console.log('PASS: All and single-video export, missing-SRT selection, refreshed/changed/empty folders, compact aiming tags, subtitle styles and current map retained.');
 }finally{
  if(browser)await browser.close();
  child.stdin.end('\n');
  await new Promise(resolve=>{if(child.exitCode!==null)return resolve();child.once('exit',resolve);setTimeout(()=>{child.kill();resolve();},5000).unref();});
 }
}
main().catch(error=>{console.error(error);process.exitCode=1;});
