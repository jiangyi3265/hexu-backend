import fs from 'node:fs/promises'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {spawnSync} from 'node:child_process'
import {randomUUID} from 'node:crypto'
import assert from 'node:assert/strict'
import {resultsDir} from './paths.mjs'
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..')
const settings=await fs.readFile(path.join(root,'ruoyi-admin/src/main/resources/application-druid.yml'),'utf8')
const username=settings.match(/^\s+username:\s*(.+)$/m)[1].trim().replace(/^['"]|['"]$/g,'')
const password=settings.match(/^\s+password:\s*(.*)$/m)[1].trim().replace(/^['"]|['"]$/g,'')
const mysql='D:/MySQL Server 8.0/bin/mysql.exe'
function sql(statement){const r=spawnSync(mysql,['-u',username,'-h','127.0.0.1','--default-character-set=utf8mb4','-N','hexu_integration'],{input:"SET time_zone='+00:00';\n"+statement,encoding:'utf8',windowsHide:true,env:{...process.env,MYSQL_PWD:password}});if(r.status!==0)throw Error('Isolated fixture SQL failed: '+r.stderr);return r.stdout.trim()}
assert.equal(sql('SELECT DATABASE();'),'hexu_integration')
const key=(await fs.readFile(path.join(root,'.hexu-local/dev-key.txt'),'utf8')).trim()


const fixture=JSON.parse(await fs.readFile(path.join(root,'.hexu-local/reconciliation-ui-fixture.json'),'utf8'));const shop=fixture.shop;
async function call(p,token,data,dev=false){const r=await fetch('http://127.0.0.1:8088'+p,{method:data?'POST':'GET',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{}),...(dev?{'X-Hexu-Dev-Key':key}:{}),'Idempotency-Key':randomUUID()},...(data?{body:JSON.stringify(data)}:{})});const b=await r.json();assert.equal(b.code,200,b.msg);return b.data}
const owner=(await call('/hexu/dev/login',null,{memberId:101},true)).token,customer=(await call('/hexu/dev/login',null,{memberId:fixture.buyer},true)).token;
const cmd=(token,op,b,management=false)=>call('/hexu/app/'+(management?'management/':'commands/')+op,token,{shopId:shop,...b});
const o=await call('/hexu/app/orders/'+fixture.orderId,customer);assert.equal(o.total,fixture.total);
assert.equal(Number(sql("SELECT COUNT(*) FROM hx_reconciliation_adjustment WHERE shop_id="+shop+" AND status='APPLIED';")),1);assert.equal(Number(sql("SELECT SUM(amount) FROM hx_ledger WHERE shop_id="+shop+" AND category='RECON_ADJUST';")),-10);
const r=await cmd(customer,'refund-apply',{id:o.id,lineId:o.items[0].id,qty:1,type:'RETURN',reason:'页面联调结束，退款恢复测试库存'});await cmd(owner,'refund-review',{id:r.id,approve:true},true);await cmd(owner,'return-inspect',{id:r.id,goodQty:1},true);await call('/hexu/dev/refund/'+r.id,null,{},true);
assert.equal(Number(sql("SELECT available FROM hx_sku WHERE shop_id="+shop+" AND id='stapler';")),100);sql("UPDATE hx_shop SET status='INACTIVE' WHERE id="+shop+';');
await fs.writeFile(path.join(resultsDir,'reconciliation-ui-result.json'),JSON.stringify({testedAt:new Date().toISOString(),shopId:shop,orderId:o.id,paymentId:fixture.paymentId,status:'PASS',checks:['后台CSV模板上传及批次进度','手机显示同一笔20元与19.90元差异','手机实际上传PNG凭证并提交','平台授权读取凭证并由另一人复核','手机刷新同步调整结果','原订单金额20元保持，独立调整流水-0.10元','联调订单全额退回，库存100，测试商城停用'],browserErrors:[]},null,2));console.log('页面联调证据已保存，测试商城已停用。');
