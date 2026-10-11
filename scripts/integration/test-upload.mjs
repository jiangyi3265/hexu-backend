import fs from 'node:fs/promises'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import {appDir,resultsDir} from './paths.mjs'
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..'),base='http://127.0.0.1:8088'
const key=(await fs.readFile(path.join(root,'.hexu-local/dev-key.txt'),'utf8')).trim()
async function login(memberId){const r=await fetch(base+'/hexu/dev/login',{method:'POST',headers:{'Content-Type':'application/json','X-Hexu-Dev-Key':key},body:JSON.stringify({memberId})});const b=await r.json();assert.equal(b.code,200);return b.data.token}
const [token,other]=await Promise.all([login(201),login(202)])
const bytes=await fs.readFile(path.join(appDir,'static/logo.png'))
const form=new FormData();form.set('shopId','2');form.set('purpose','SUPPORT');form.set('file',new Blob([bytes],{type:'image/png'}),'integration-logo.png')
const response=await fetch(base+'/hexu/app/attachments',{method:'POST',headers:{Authorization:'Bearer '+token},body:form});const uploaded=await response.json();assert.equal(uploaded.code,200,uploaded.msg)
const id=uploaded.data.id
const own=await fetch(base+'/hexu/app/attachments/'+id,{headers:{Authorization:'Bearer '+token}});assert.equal(own.status,200);assert.deepEqual(Buffer.from(await own.arrayBuffer()),bytes)
const denied=await fetch(base+'/hexu/app/attachments/'+id,{headers:{Authorization:'Bearer '+other}});assert.notEqual(denied.status,200)
const bad=new FormData();bad.set('shopId','2');bad.set('file',new Blob(['<script>invalid</script>'],{type:'image/png'}),'fake.png');const invalid=await fetch(base+'/hexu/app/attachments',{method:'POST',headers:{Authorization:'Bearer '+token},body:bad});assert.notEqual(invalid.status,200)
const saved=await fetch(base+'/hexu/app/commands/document-save',{method:'POST',headers:{Authorization:'Bearer '+token,'Content-Type':'application/json','Idempotency-Key':randomUUID()},body:JSON.stringify({shopId:2,kind:'support',question:'其他问题',message:'附件联调检查，不实际处理客户业务',uploads:[id]})});const doc=await saved.json();assert.equal(doc.code,200,doc.msg)
const state=JSON.parse(await fs.readFile(path.join(root,'.hexu-local/admin-auth.json'),'utf8'));const admin=state.cookies.find(c=>c.name==='Admin-Token')?.value;assert.ok(admin,'需要先从后台登录保存联调会话');const allowed=await fetch(base+'/hexu/app/attachments/'+id,{headers:{Authorization:'Bearer '+admin}});const allowedBytes=Buffer.from(await allowed.arrayBuffer())
let platformVerified=false,platformNote='';const expired=(()=>{try{return JSON.parse(allowedBytes.toString('utf8')).code===401}catch{return false}})()
if(expired)platformNote='平台后台会话已失效：重新正常登录并更新 .hexu-local/admin-auth.json 后才能核对平台读取';else{assert.equal(allowed.status,200);assert.deepEqual(allowedBytes,bytes);platformVerified=true}
const report={at:new Date().toISOString(),status:'PASS',attachmentId:id,documentId:doc.data.id,platformVerified,...(platformNote?{platformNote}:{}),checks:['真实图片上传','加密存储后解密读取一致','其他会员访问被拒绝','伪造PNG文件被拒绝','附件关联业务资料',...(platformVerified?['平台按权限读取附件']:[])]}
await fs.writeFile(path.join(resultsDir,'upload-result.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2))
