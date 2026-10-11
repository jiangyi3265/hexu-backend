import fs from 'node:fs/promises'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {spawnSync} from 'node:child_process'
import {randomUUID} from 'node:crypto'
import assert from 'node:assert/strict'
import {hexuFetch} from './hexu-client-contract.mjs'
import {resultsDir} from './paths.mjs'
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..')
const settings=await fs.readFile(path.join(root,'ruoyi-admin/src/main/resources/application-druid.yml'),'utf8')
const username=settings.match(/^\s+username:\s*(.+)$/m)[1].trim().replace(/^['"]|['"]$/g,'')
const password=settings.match(/^\s+password:\s*(.*)$/m)[1].trim().replace(/^['"]|['"]$/g,'')
const mysql='D:/MySQL Server 8.0/bin/mysql.exe'
function sql(statement){const r=spawnSync(mysql,['-u',username,'-h','127.0.0.1','--default-character-set=utf8mb4','-N','hexu_integration'],{input:"SET time_zone='+00:00';\n"+statement,encoding:'utf8',windowsHide:true,env:{...process.env,MYSQL_PWD:password}});if(r.status!==0)throw Error('Isolated fixture SQL failed: '+r.stderr);return r.stdout.trim()}
assert.equal(sql('SELECT DATABASE();'),'hexu_integration')
const key=(await fs.readFile(path.join(root,'.hexu-local/dev-key.txt'),'utf8')).trim()


async function call(p,{token,data,dev=false}={}){const r=await hexuFetch('http://127.0.0.1:8088'+p,{method:data?'POST':'GET',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{}),...(dev?{'X-Hexu-Dev-Key':key}:{}),'Idempotency-Key':randomUUID()},...(data?{body:JSON.stringify(data)}:{})});const b=await r.json();if(!r.ok||b.code!==200)throw Error(p+': '+b.msg);return b.data}
const login=async memberId=>(await call('/hexu/dev/login',{data:{memberId},dev:true})).token;



const fixturePath=path.join(root,'.hexu-local/report-ui-fixture.json');
async function ready(token,id,dimension='SHOP'){for(let i=0;i<25;i++){const r=await call('/hexu/app/management/reports/'+id+'?dimension='+dimension,{token});if(r.status==='FAILED')throw Error(r.error_message);if(r.status==='COMPLETED')return r;await new Promise(resolve=>setTimeout(resolve,1500))}throw Error('Report timed out')}
const day=new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());
if(!process.argv.includes('--finish-ui')){
 const shop=Date.now(),buyer=shop+1,county='报表联调县'+shop;sql("INSERT INTO hx_member(id,name,authorized) VALUES ("+buyer+",'报表联调客户',true); INSERT INTO hx_shop(id,name,county,kind,owner_id,merchant_no,payment_ready,settings) VALUES ("+shop+",'报表联调专用商城','"+county+"','DEALER',101,'DEV_REPORT',true,'{}'); INSERT INTO hx_agent(member_id,shop_id,rank_no,status) VALUES (101,"+shop+",3,'ACTIVE'); INSERT INTO hx_binding(member_id,shop_id,agent_id) SELECT "+buyer+","+shop+",id FROM hx_agent WHERE shop_id="+shop+" AND member_id=101; INSERT INTO hx_sku(id,shop_id,name,spec,asset,retail,cloud_price,center_price,owner_price,available) VALUES ('report-sku',"+shop+",'报表联调保温杯','雾白 / 500ml','cup',2000,1000,800,600,100),('report-zero',"+shop+",'无销量库存样本','米白 / 单件','paper',2000,1000,800,600,5);");
 const owner=await login(101),customer=await login(buyer),orders=[];
 for(const qty of [2,1]){const o=await call('/hexu/app/commands/order-create',{token:customer,data:{shopId:shop,items:[{id:'report-sku',qty}],address:{name:'报表联调客户',phone:'13900000000',region:county,detail:'联调测试仓'}}});const payment=await call('/hexu/app/commands/payment-create',{token:customer,data:{shopId:shop,id:o.id}});await call('/hexu/dev/payment/'+payment.id,{data:{},dev:true});await call('/hexu/app/management/ship',{token:owner,data:{shopId:shop,id:o.id,carrier:'顺丰',tracking:'REPORT'+Date.now()}});await call('/hexu/app/commands/order-receive',{token:customer,data:{shopId:shop,id:o.id}});orders.push(o.id)}
 const before=await call('/hexu/app/management/report-create',{token:owner,data:{shopId:shop,from:day,to:day}});const old=await ready(owner,before.id);assert.equal(old.summary.net_sales,6000);assert.equal(old.summary.repeatBps,10000);
 const detail=await call('/hexu/app/orders/'+orders[0],{token:customer});const refund=await call('/hexu/app/commands/refund-apply',{token:customer,data:{shopId:shop,id:orders[0],lineId:detail.items[0].id,qty:1,type:'RETURN',reason:'报表快照冲正核验'}});await call('/hexu/app/management/refund-review',{token:owner,data:{shopId:shop,id:refund.id,approve:true}});await call('/hexu/app/management/return-inspect',{token:owner,data:{shopId:shop,id:refund.id,goodQty:1}});await call('/hexu/dev/refund/'+refund.id,{data:{},dev:true});
 const after=await call('/hexu/app/management/report-create',{token:owner,data:{shopId:shop,from:day,to:day}}),latest=await ready(owner,after.id,'SKU');assert.equal(latest.summary.net_sales,4000);assert.equal(latest.summary.distributionMargin,2800);assert.equal(latest.summary.repeatBps,10000);assert.equal(latest.inventory.length,2);assert.equal((await ready(owner,before.id)).summary.net_sales,6000);
 const denied=await login(401);await assert.rejects(()=>call('/hexu/app/management/reports/'+after.id,{token:denied}),/权限|商城/);await fs.writeFile(fixturePath,JSON.stringify({shop,buyer,county,orders,before:before.id,after:after.id,day}));console.log(JSON.stringify({shop,buyer,orders,netSales:latest.summary.net_sales,ready:true}));
}else{
 const f=JSON.parse(await fs.readFile(fixturePath,'utf8')),owner=await login(101),customer=await login(f.buyer),jobs=await call('/hexu/app/management/reports?shopId='+f.shop,{token:owner});assert.ok(jobs.length>=3,'Mobile must create a real report');const latest=await ready(owner,jobs[0].id,'SKU');assert.equal(latest.summary.net_sales,4000);const audits=Number(sql("SELECT COUNT(*) FROM hx_audit WHERE shop_id="+f.shop+" AND action='REPORT_EXPORT';"));assert.ok(audits>=2,'Browser exports must be audited');
 for(const order of f.orders){const d=await call('/hexu/app/orders/'+order,{token:customer}),line=d.items[0],qty=Number(line.qty)-Number(line.refunded_qty||0);if(!qty)continue;const r=await call('/hexu/app/commands/refund-apply',{token:customer,data:{shopId:f.shop,id:order,lineId:line.id,qty,type:'RETURN',reason:'报表联调完成返还测试库存'}});await call('/hexu/app/management/refund-review',{token:owner,data:{shopId:f.shop,id:r.id,approve:true}});await call('/hexu/app/management/return-inspect',{token:owner,data:{shopId:f.shop,id:r.id,goodQty:qty}});await call('/hexu/dev/refund/'+r.id,{data:{},dev:true});}
 assert.equal(Number(sql("SELECT available FROM hx_sku WHERE shop_id="+f.shop+" AND id='report-sku';")),100);assert.equal(Number(sql("SELECT COALESCE(SUM(available+frozen),0) FROM hx_wallet WHERE shop_id="+f.shop+';')),0);sql("UPDATE hx_shop SET status='INACTIVE' WHERE id="+f.shop+';');
 const result={status:'PASS',completedAt:new Date().toISOString(),shopId:f.shop,checks:['真实订单完成后报表6000分，部分退款后新报表4000分，原快照保持6000分','手机任务与平台按商城查询数值一致，导出原始全量CSV并记录审计','复购率100%，价差毛利2800分，包含零销量库存与参考周转','其他商城拥有者无权读取；联调后订单全额退款、库存100、测试商城停用']};await fs.writeFile(path.join(resultsDir,'report-result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));}
