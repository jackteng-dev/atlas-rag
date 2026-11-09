// Real HTTPS HTTP/browser, credentials read only from ignored runtime files; no model/API stubs.
import fs from 'node:fs/promises'
import path from 'node:path'
import assert from 'node:assert/strict'
import {chromium,expect} from '../frontend/node_modules/@playwright/test/index.mjs'
const root=path.resolve(new URL('..',import.meta.url).pathname)
const instance=JSON.parse(await fs.readFile(process.argv[2],'utf8'))
const profiles=JSON.parse(await fs.readFile(instance.profilesFile,'utf8'))
const base=process.env.AKA_DEMO_BASE??(await fs.readFile(path.join(root,'work/public-url.txt'),'utf8')).trim()
const external=new URL(base).hostname!=='127.0.0.1'
if(external&&(!base.startsWith('https://')||!process.env.HTTPS_PROXY||process.env.NODE_USE_ENV_PROXY!=='1'))throw new Error('External checks need HTTPS, inherited proxy and Node env-proxy support')
const stamp=Date.now(),checks=[],transports=[]
async function call(route,profile,method='GET',data){
 const account=profiles[profile]
 const response=await fetch(base+'/api'+route,{method,headers:{'Content-Type':'application/json',...(account?{Authorization:'Basic '+Buffer.from(`${account.username}:${account.password}`).toString('base64')}:{})},...(data?{body:JSON.stringify(data)}:{}),signal:AbortSignal.timeout(30000),redirect:'error'})
 const raw=await response.text();return{status:response.status,body:raw?JSON.parse(raw):null}
}
async function ask(question,profile,documentIds){
 const created=await call('/question-jobs',profile,'POST',{question,...(documentIds?{documentIds}:{})})
 assert.equal(created.status,202);assert.equal(created.body.answer,null)
 const deadline=Date.now()+300000;let polls=0
 while(Date.now()<deadline){
  const current=await call('/question-jobs/'+created.body.id,profile);assert.equal(current.status,200);polls++
  if(current.body.status==='FAILED')throw new Error(current.body.error.code)
  if(current.body.status==='COMPLETE'){transports.push({profile,creationStatus:202,pollStatus:200,polls,elapsedMs:current.body.elapsedMs});return current.body.answer}
  await new Promise(resolve=>setTimeout(resolve,2000))
 }
 throw new Error('Demo question exceeded five minutes')
}
async function verifySources(answer,profile){
 let sources=0,citations=0;const ids=new Set()
 for(const source of answer.sources){
  assert.equal(source.href,`/api/documents/${source.documentId}/chunks/${source.chunkId}?version=${source.version}`)
  const canonical=await call(source.href.slice(4),profile);assert.equal(canonical.status,200)
  for(const key of ['documentId','chunkId','version','heading','excerpt','lineStart','lineEnd'])assert.equal(canonical.body[key],source[key])
  ids.add(source.chunkId);sources++
 }
 for(const item of answer.generated)for(const id of item.chunkIds){assert.ok(ids.has(id));citations++}
 return{sources,citations}
}
assert.equal((await call('/documents','anonymous')).status,401)
assert.equal((await call('/question-jobs','anonymous','POST',{question:'test'})).status,401)
const hrDoc=await call('/documents/demo-hr-benefits','hr');assert.equal(hrDoc.status,200)
const sourceRoute=`/documents/demo-hr-benefits/chunks/${hrDoc.body.chunks[0].id}?version=${hrDoc.body.version}`
for(const profile of ['reader','hr','admin','outsider']){
 const status=await call('/status',profile);assert.equal(status.status,200);assert.equal(status.body.authentication,'BASIC_SERVER_ACCOUNTS');assert.equal(status.body.actor,profiles[profile].username)
 const doc=await call('/documents/demo-hr-benefits',profile)
 const canonical=await call(sourceRoute,profile)
 const allowed=['hr','admin'].includes(profile)
 assert.equal(doc.status,allowed?200:404);assert.equal(canonical.status,allowed?200:404)
 const update=await call('/documents/demo-hr-benefits',profile,'PUT',{title:hrDoc.body.title,markdown:hrDoc.body.markdown,expectedVersion:hrDoc.body.version,metadata:hrDoc.body.metadata})
 assert.equal(update.status,profile==='admin'?200:403)
 checks.push({profile,roles:status.body.roles,departments:status.body.departments,privateDocumentStatus:doc.status,privateSourceStatus:canonical.status,updateStatus:update.status})
}
const denied=await call('/questions','reader','POST',{question:'HR 示範員工每月的學習補助是多少元？',documentIds:['demo-hr-benefits']})
assert.equal(denied.status,404)
const scoped=await ask('HR 示範員工每月的學習補助是多少元？','hr',['demo-hr-benefits'])
assert.equal(scoped.status,'ANSWER_GENERATED');assert.ok(scoped.generated.some(s=>s.text.includes('3000')))
assert.ok(scoped.modelInputTokens>0 && scoped.modelOutputTokens>0)
assert.ok(scoped.sources.every(s=>s.documentId==='demo-hr-benefits'))
const hrSourceChecks=await verifySources(scoped,'hr')
const browser=await chromium.launch({executablePath:process.env.AKA_CHROMIUM_PATH??'/usr/bin/chromium',headless:true,...(external?{proxy:{server:process.env.HTTPS_PROXY}}:{})})
let browserAnswer,desktop,mobile
try{
 const page=await browser.newPage({viewport:{width:1440,height:1000}});const errors=[]
 page.on('pageerror',error=>errors.push(error.message))
 assert.equal((await page.goto(base,{waitUntil:'networkidle',timeout:60000})).status(),200)
 for(const profile of ['reader','hr','admin']){
  const account=profiles[profile]
  await page.getByLabel('帳號',{exact:true}).fill(account.username)
  await page.getByLabel('密碼',{exact:true}).fill(account.password)
  await page.getByRole('button',{name:'登入',exact:true}).click()
  await expect(page.locator('.identity')).toContainText(account.username)
  await expect(page.locator('.actor-scope')).toContainText(profile==='admin'?'ADMIN':profile==='hr'?'HR':'VIEWER')
  const list=page.locator('.document-list')
  await expect(list).toContainText('Atlas 混合檢索 RAG 架構')
  const listText=await list.innerText()
  assert.equal(listText.includes('人資補助規範'),profile!=='reader')
  await page.getByRole('button',{name:/文件資料庫/}).click()
  await expect(page.getByRole('button',{name:'＋ 匯入文件',exact:true})).toHaveCount(profile==='admin'?1:0)
  if(profile==='hr'){
   const card=page.locator('.document-card').filter({hasText:'demo-hr-benefits'})
   await card.getByRole('button',{name:'檢視原文',exact:true}).click()
   await expect(page.getByRole('dialog')).toBeVisible()
   assert.equal(await page.locator('.markdown-label textarea').getAttribute('readonly'),'')
   await expect(page.getByRole('button',{name:/儲存新版本/})).toHaveCount(0)
   await page.getByRole('button',{name:'關閉編輯器',exact:true}).click()
  }
  await page.getByRole('button',{name:/知識問答/}).click()
  if(profile==='admin'){
   await page.locator('.document-choice').filter({hasText:'人資補助規範'}).locator('input').check()
   await page.locator('#question').fill('HR 示範員工每月的學習補助是多少元？')
   const complete=page.waitForResponse(async response=>response.url().includes('/api/question-jobs/')&&response.request().method()==='GET'&&response.status()===200&&(await response.json()).status==='COMPLETE',{timeout:300000});complete.catch(()=>{})
   await page.getByRole('button',{name:/尋找證據/}).click()
   browserAnswer=(await(await complete).json()).answer
   assert.equal(browserAnswer.status,'ANSWER_GENERATED')
   await expect(page.locator('.generated-answer')).toContainText('3000')
   await page.locator('.generated-answer .citation').first().click()
   await page.getByRole('button',{name:/核對目前來源版本/}).click()
   await expect(page.getByText('已核對：目前文件版本與引用原文一致。',{exact:true})).toBeVisible({timeout:30000})
   desktop=`work/screenshots/role-demo/${stamp}-desktop.png`;mobile=`work/screenshots/role-demo/${stamp}-mobile.png`
   await fs.mkdir(path.join(root,'work/screenshots/role-demo'),{recursive:true})
   await page.screenshot({path:path.join(root,desktop),fullPage:true})
   await page.setViewportSize({width:390,height:844})
   assert.ok(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth))
   await page.screenshot({path:path.join(root,mobile),fullPage:true})
  }
  await page.getByRole('button',{name:/登出/}).click()
  await expect(page.getByRole('heading',{name:'登入文件知識庫'})).toBeVisible()
  await expect(page.locator('.document-choice')).toHaveCount(0)
  await expect(page.locator('.generated-answer')).toHaveCount(0)
  await expect(page.getByRole('dialog')).toHaveCount(0)
 }
 assert.deepEqual(errors,[])
}finally{await browser.close()}
const browserSourceChecks=await verifySources(browserAnswer,'admin')
const evidence={checkedAt:new Date().toISOString(),base,external,pageStatus:200,anonymousApiStatus:401,checks,readerScopedQuestionStatus:denied.status,hrQuestionStatus:scoped.status,hrModelUsage:{inputTokens:scoped.modelInputTokens,outputTokens:scoped.modelOutputTokens},hrSourceChecks,browserQuestionStatus:browserAnswer.status,browserSourceChecks,transports,sourceButtonVerification:'PASS',readOnlyLibrary:'PASS',logoutClearsPrivateWorkspace:'PASS',mobileOverflow:false,pageErrors:[],screenshots:{desktop,mobile},allDataIsFictional:true,noApiOrModelStubs:true,paidApiCalls:0}
const output=`work/role-demo-verification-${stamp}.json`
await fs.writeFile(path.join(root,output),JSON.stringify(evidence,null,2)+'\n',{flag:'wx'})
console.log(JSON.stringify({result:'PASS',output,...evidence},null,2))
