// Real packaged authenticated HTTP boundaries + explicitly stubbed delayed browser result.
import fs from 'node:fs/promises'
import assert from 'node:assert/strict'
import { chromium, expect } from '../frontend/node_modules/@playwright/test/index.mjs'
const instance = JSON.parse(await fs.readFile(process.argv[2], 'utf8'))
const profiles = JSON.parse(await fs.readFile(instance.profilesFile, 'utf8'))
if (new URL(instance.base).hostname !== '127.0.0.1') throw new Error('Local QA only')
async function call(route, profile, data) {
  const user = profiles[profile]
  const response = await fetch(instance.base + '/api' + route, {method:data?'POST':'GET',headers:{'Content-Type':'application/json',...(user?{Authorization:'Basic '+Buffer.from(`${user.username}:${user.password}`).toString('base64')}:{})},...(data?{body:JSON.stringify(data)}:{})})
  return {status:response.status,body:await response.json(),cacheControl:response.headers.get('cache-control')}
}
assert.equal((await call('/question-jobs', 'anonymous', {question:'private test'})).status,401)
const created = await call('/question-jobs','hr',{question:'private test',documentIds:['absent-fixture']})
assert.equal(created.status,202);assert.equal(created.body.answer,null);assert.equal(created.cacheControl,'no-store')
assert.equal((await call(`/question-jobs/${created.body.id}`,'reader')).status,404)
let failed
for(let i=0;i<50;i++) {
  failed = await call(`/question-jobs/${created.body.id}`,'hr')
  if(failed.body.status!=='RUNNING') break
  await new Promise(resolve=>setTimeout(resolve,100))
}
assert.equal(failed.body.status,'FAILED');assert.equal(failed.body.answer,null);assert.equal(failed.body.error.httpStatus,404)
const browser=await chromium.launch({executablePath:'/usr/bin/chromium',headless:true})
try {
  const page=await browser.newPage()
  await page.goto(instance.base,{waitUntil:'networkidle'})
  async function login(profile) {
    await page.getByLabel('帳號',{exact:true}).fill(profiles[profile].username)
    await page.getByLabel('密碼',{exact:true}).fill(profiles[profile].password)
    await page.getByRole('button',{name:'登入',exact:true}).click()
    await expect(page.locator('.identity')).toContainText(profiles[profile].username)
  }
  await login('hr')
  await page.route('**/api/question-jobs',route=>route.fulfill({status:202,contentType:'application/json',body:JSON.stringify({id:'synthetic-delayed-job',status:'RUNNING',answer:null,error:null,elapsedMs:0})}))
  let release,entered
  const blocked=new Promise(resolve=>{release=resolve}), started=new Promise(resolve=>{entered=resolve})
  await page.route('**/api/question-jobs/synthetic-delayed-job',async route=>{
    entered();await blocked
    await route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({id:'synthetic-delayed-job',status:'COMPLETE',answer:{sources:[],generated:[{text:'QA_LATE_PRIVATE_RESPONSE',chunkIds:[]}],segments:[],pipeline:[],evaluation:{},status:'ANSWER_GENERATED'},error:null,elapsedMs:1})})
  })
  await page.locator('#question').fill('Test delayed private response')
  await page.getByRole('button',{name:/尋找證據/}).click()
  await started
  await page.getByRole('button',{name:/登出/}).click()
  await expect(page.getByRole('heading',{name:'登入文件知識庫'})).toBeVisible()
  await login('reader')
  release()
  await expect(page.locator('.error')).toContainText('IDENTITY_CHANGED')
  assert.ok(!(await page.locator('body').innerText()).includes('QA_LATE_PRIVATE_RESPONSE'))
  await expect(page.locator('.generated-answer')).toHaveCount(0)
  const result={checkedAt:new Date().toISOString(),result:'PASS',realHttp:{anonymousPost:401,creation:202,otherOwnerGet:404,ownerFailedError:404,noStore:true},browserDelayedPrivateResponseDiscarded:true,browserQuestionAndPollResponsesAreExplicitStubs:true}
  const output=`work/job-auth-verification-${Date.now()}.json`
  await fs.writeFile(output,JSON.stringify(result,null,2)+'\n',{flag:'wx'});console.log(JSON.stringify({output,...result}))
}finally{await browser.close()}
