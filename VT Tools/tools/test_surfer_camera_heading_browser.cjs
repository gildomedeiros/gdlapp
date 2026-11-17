// Pass a generated one-position map for the recorded GPS 4080 example.
const assert=require('node:assert/strict');
const path=require('node:path');
const {pathToFileURL}=require('node:url');
const {chromium}=require('playwright');

async function main(){
 const file=path.resolve(process.argv[2]||path.join(__dirname,'../real-validation/surfer-gps.html'));
 const browser=await chromium.launch({headless:true,...(process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE?{executablePath:process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE}:{})});
 try{
  const page=await browser.newPage({viewport:{width:1500,height:1000}}),errors=[];
  page.on('pageerror',error=>errors.push(error.message));
  await page.goto(pathToFileURL(file).href);
  const expected='Camera heading error: 6.3° left of surfer';
  await page.locator('.surfer-marker').hover();
  const row=page.locator('#tooltip span').filter({hasText:expected});
  await row.waitFor({state:'visible'});
  assert.equal(await row.evaluate(n=>n.classList.contains('key-field')),true);
  await page.screenshot({path:path.join(path.dirname(file),'position-card.png')});
  await page.locator('.pair-line').hover({force:true});
  const hover=page.locator('#pair-line-hover');
  await hover.waitFor({state:'visible'});
  assert((await hover.textContent()).includes(expected));
  const colored=await hover.locator('text').filter({hasText:expected}).locator('tspan').first().getAttribute('fill');
  assert.equal(colored,'#a94700');
  await page.screenshot({path:path.join(path.dirname(file),'connection-hover.png')});
  await page.locator('.drone-marker').hover({force:true});
  await page.locator('#tooltip span').filter({hasText:expected}).waitFor({state:'visible'});
  assert.deepEqual(errors,[]);
  console.log('PASS: recorded 6.3-degree left error in surfer card, drone card and highlighted connection hover.');
 }finally{await browser.close();}
}
main().catch(error=>{console.error(error);process.exitCode=1;});
