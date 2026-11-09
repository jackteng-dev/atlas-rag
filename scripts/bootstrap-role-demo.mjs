// Import only recognized seed updates and fictional role fixtures into a newly prepared local demo.
import fs from 'node:fs/promises'
import path from 'node:path'
import {createHash} from 'node:crypto'
const root=path.resolve(new URL('..',import.meta.url).pathname)
const instance=JSON.parse(await fs.readFile(process.argv[2],'utf8'))
const profiles=JSON.parse(await fs.readFile(instance.profilesFile,'utf8'))
if(new URL(instance.base).hostname!=='127.0.0.1')throw new Error('Loopback demo setup only')
const admin=profiles.admin
const authorization='Basic '+Buffer.from(`${admin.username}:${admin.password}`).toString('base64')
async function call(route,method='GET',data){
 const response=await fetch(instance.base+'/api'+route,{method,headers:{'Content-Type':'application/json',Authorization:authorization},...(data?{body:JSON.stringify(data)}:{}),signal:AbortSignal.timeout(30000)})
 if(!response.ok)throw new Error(`Demo setup HTTP ${response.status}`)
 return response.status===204?null:response.json()
}
const initial=await call('/documents')
const seedFiles=(await fs.readdir(path.join(root,'backend/src/main/resources/corpus'))).filter(name=>name.endsWith('.md'))
const seedIds=seedFiles.map(name=>name.slice(0,-3))
if(initial.length!==seedIds.length || initial.some(d=>d.version!==1 || !seedIds.includes(d.id)))throw new Error('Fresh seed store required; user content is never replaced')
for(const doc of initial){
 const seed=await fs.readFile(path.join(root,`backend/src/main/resources/corpus/${doc.id}.md`))
 if(doc.contentHash!==createHash('sha256').update(seed).digest('hex'))throw new Error('Modified seed refused')
}
for(const id of ['architecture','operations']){
 const markdown=await fs.readFile(path.join(root,`backend/src/main/resources/rag-corpus-v4/${id}.md`),'utf8')
 const original=await fs.readFile(path.join(root,`backend/src/main/resources/corpus/${id}.md`))
 const doc=initial.find(d=>d.id===id)
 if(doc.contentHash!==createHash('sha256').update(original).digest('hex'))throw new Error('Modified seed refused')
 await call('/documents/'+id,'PUT',{title:markdown.split('\n')[0].replace(/^# /,''),markdown,expectedVersion:doc.version,metadata:{department:'public',owner:admin.username,allowedRoles:[],tags:['fictional-demo','architecture']}})
}
const markdown=await fs.readFile(path.join(root,'backend/src/main/resources/role-demo-corpus/hr-benefits.md'),'utf8')
await call('/documents','POST',{id:'demo-hr-benefits',title:'人資補助規範（虛構示範）',markdown,metadata:{department:'hr',owner:admin.username,allowedRoles:['HR'],tags:['fictional-demo','hr']}})
console.log(JSON.stringify({result:'PASS',documents:(await call('/documents')).length,fictionalDemoOnly:true,privateDocument:'demo-hr-benefits'}))
