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
root=Path.cwd().parent/'exports';root.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix='subtitle-browser-',dir=root) as directory:
    folder=Path(directory);log=folder/'flight.jsonl';log.write_text('',encoding='utf-8')
    video=folder/'videos';video.mkdir()
    (video/'DJI_0001.MP4').write_bytes(b'test video')
    (video/'DJI_0002.mp4').write_bytes(b'no SRT test')
    (video/'DJI_0001.SRT').write_text(source_srt([999,1000,2501,3000]),encoding='utf-8',newline='')
    rows=[packet(1000,sourceFile=str(log)),packet(3000,2,sourceFile=str(log))]
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
  await page.waitForFunction(()=>document.querySelectorAll('#subtitle-fields input').length===24);
  await page.locator('#map-frame').waitFor({state:'visible'});
  const map=await page.locator('#map-frame').getAttribute('src');
  await page.locator('#subtitle-settings summary').click();
  await page.locator('#subtitle-none').click();assert.equal(await page.locator('#subtitle-export').isDisabled(),true);
  await page.locator('#subtitle-all').click();assert.equal(await page.locator('#subtitle-fields input:checked').count(),24);
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
  assert.match(await card.text(),/PlayResX: 1920/);
  const text=await response.text();
  assert.match(text,/GPS age: 1.501 s/);assert.match(text,/face="Arial" size="28"/);assert.match(text,/color="#08752e"[^>]*><b>TILTING/);assert.match(text,/original saved note/);
  await page.locator('#subtitle-none').click();
  await page.locator('#subtitle-fields input[value="gps_age"]').check();
  await page.locator('#subtitle-fields input[value="packet"]').check();
  await page.locator('#subtitle-export').click();
  await page.waitForFunction(()=>document.getElementById('status').textContent.includes('Subtitles ready:')&&!document.getElementById('subtitle-export').disabled,null,{timeout:20000});
  const secondLink=await page.locator('#subtitle-links a').filter({hasText:'DJI_0001.srt'}).getAttribute('href');
  const selectedText=await (await page.request.get(url+secondLink)).text();
  assert.match(selectedText,/Packet: 2/);assert.doesNotMatch(selectedText,/Gimbal tilt:/);
  await page.reload();await page.waitForFunction(()=>document.querySelectorAll('#subtitle-fields input').length===24);
  assert.equal(await page.locator('#subtitle-fields input:checked').count(),2);
  assert.equal(await page.locator('#map-frame').getAttribute('src'),map);
  await page.locator('#subtitle-settings summary').click();
  await page.locator('#subtitle-all').click();
  await page.screenshot({path:require('node:path').join(__dirname,'../subtitle-export-ui.png'),fullPage:true});
  assert.deepEqual(errors,[]);
  console.log('PASS: 24 fields, all/clear selection, real batch SRT downloads, styles, saved choices and current map retained.');
 }finally{
  if(browser)await browser.close();
  child.stdin.end('\n');
  await new Promise(resolve=>{if(child.exitCode!==null)return resolve();child.once('exit',resolve);setTimeout(()=>{child.kill();resolve();},5000).unref();});
 }
}
main().catch(error=>{console.error(error);process.exitCode=1;});
