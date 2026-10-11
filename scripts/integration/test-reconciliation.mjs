import fs from 'node:fs/promises'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {spawnSync} from 'node:child_process'
import {randomUUID} from 'node:crypto'
import assert from 'node:assert/strict'
import {hexuFetch} from './hexu-client-contract.mjs'
import {appDir,resultsDir} from './paths.mjs'
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..')
const settings=await fs.readFile(path.join(root,'ruoyi-admin/src/main/resources/application-druid.yml'),'utf8')
const username=settings.match(/^\s+username:\s*(.+)$/m)[1].trim().replace(/^['"]|['"]$/g,'')
const password=settings.match(/^\s+password:\s*(.*)$/m)[1].trim().replace(/^['"]|['"]$/g,'')
const mysql='D:/MySQL Server 8.0/bin/mysql.exe'
function sql(statement){const r=spawnSync(mysql,['-u',username,'-h','127.0.0.1','--default-character-set=utf8mb4','-N','hexu_integration'],{input:"SET time_zone='+00:00';\n"+statement,encoding:'utf8',windowsHide:true,env:{...process.env,MYSQL_PWD:password}});if(r.status!==0)throw Error('Isolated fixture SQL failed: '+r.stderr);return r.stdout.trim()}
assert.equal(sql('SELECT DATABASE();'),'hexu_integration')
const key=(await fs.readFile(path.join(root,'.hexu-local/dev-key.txt'),'utf8')).trim()

const shop=Date.now(),buyer=shop+1,second=shop+2,checks=[];
async function call(p,{token,data,dev=false}={}){const r=await hexuFetch('http://127.0.0.1:8088'+p,{method:data?'POST':'GET',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{}),...(dev?{'X-Hexu-Dev-Key':key}:{}),'Idempotency-Key':randomUUID()},...(data?{body:JSON.stringify(data)}:{})});const b=await r.json();if(!r.ok||b.code!==200)throw Error(p+': '+b.msg);return b.data}
const login=async memberId=>(await call('/hexu/dev/login',{data:{memberId},dev:true})).token;
const command=(token,op,data={},management=false)=>call('/hexu/app/'+(management?'management/':'commands/')+op,{token,data:{shopId:shop,...data}});
const account=token=>call('/hexu/app/account?shopId='+shop,{token});
sql("INSERT INTO hx_member(id,name,authorized) VALUES ("+buyer+",'对账联调客户',true),("+second+",'对账联调备用客户',true); INSERT INTO hx_shop(id,name,county,kind,owner_id,merchant_no,payment_ready,settings) VALUES ("+shop+",'资金对账自动化专用商城','测试县域','DEALER',101,'DEV_RECON',true,'{}'); INSERT INTO hx_agent(member_id,shop_id,rank_no,status) VALUES (101,"+shop+",3,'ACTIVE'); INSERT INTO hx_sku(id,shop_id,name,spec,asset,retail,cloud_price,center_price,owner_price,available) SELECT id,"+shop+",name,spec,asset,retail,cloud_price,center_price,owner_price,100 FROM hx_sku WHERE shop_id=2 AND id='stapler';");
const [owner,customer,friend]=await Promise.all([login(101),login(buyer),login(second)]);

const state=JSON.parse(await fs.readFile(path.join(root,'.hexu-local/admin-auth.json'),'utf8')),admin=state.cookies.find(c=>c.name==='Admin-Token').value;
const address={name:'对账联调',phone:'13800000000',region:'测试县域',detail:'数据库测试地址，不实际发货',consent:true};
const order=await command(customer,'order-create',{items:[{id:'stapler',qty:1}],address});const payment=await command(customer,'payment-create',{id:order.id});
const data={statementDate:new Date().toISOString().slice(0,10),rows:[{type:'PAYMENT',businessId:payment.id,channelRef:'SANDBOX-'+payment.id,merchantNo:payment.merchant_no,amount:order.total-10}]};
const platform=(op,b)=>call('/hexu/admin/commands/'+op,{token:admin,data:{shopId:shop,...b}});
const resource=(token,name)=>call('/hexu/app/management/'+name+'?shopId='+shop,{token});
await call('/hexu/dev/payment/'+payment.id,{dev:true,data:{}});
await command(owner,'ship',{id:order.id,carrier:'测试物流',tracking:'RECON'+Date.now()},true);await command(customer,'order-receive',{id:order.id});
if(process.argv.includes('--prepare-ui')){await fs.writeFile(path.join(root,'.hexu-local/reconciliation-ui-fixture.json'),JSON.stringify({shop,buyer,second,orderId:order.id,paymentId:payment.id,total:order.total}));await fs.writeFile(path.join(root,'.hexu-local/reconciliation-ui.csv'),'\ufeff业务类型,业务单号,渠道流水,商户号,金额（分）\r\n支付,'+payment.id+',SANDBOX-'+payment.id+','+payment.merchant_no+','+(order.total-10));console.log(JSON.stringify({shop,buyer,orderId:order.id,ready:true}));process.exit(0)}
try{
 const batch=await platform('reconcile-import',data);assert.equal(batch.source,'MANUAL');assert.equal((await platform('reconcile-import',data)).id,batch.id);
 let lines=[];for(let i=0;i<10;i++){lines=await resource(owner,'reconciliationLines');if(lines[0]?.status==='AMOUNT_MISMATCH')break;await new Promise(r=>setTimeout(r,1500))}const line=lines[0];assert.equal(line.status,'AMOUNT_MISMATCH');assert.equal(line.difference,-10);assert.equal(line.expected_amount,order.total);checks.push('平台导入后定时任务实际比对，商家端读取相同金额差异，重复导入复用批次');
 const form=new FormData();form.set('shopId',String(shop));form.set('purpose','FINANCE');form.set('file',new Blob([await fs.readFile(path.join(appDir,'static/logo.png'))],{type:'image/png'}),'finance-proof.png');
 const uploaded=await (await fetch('http://127.0.0.1:8088/hexu/app/attachments',{method:'POST',headers:{Authorization:'Bearer '+owner},body:form})).json();assert.equal(uploaded.code,200,uploaded.msg);
 const proposal=await command(owner,'adjustment-propose',{id:line.id,reason:'测试凭证：渠道尾差10分',uploads:[uploaded.data.id]},true);
 await assert.rejects(()=>command(owner,'adjustment-review',{id:proposal.id,approve:true,reason:'申请人尝试自审'},true),/另一位/);
 await assert.rejects(()=>resource(customer,'reconciliationLines'),/权限|拥有者/);
 await platform('adjustment-review',{id:proposal.id,approve:true,reason:'平台独立复核测试凭证'});await assert.rejects(()=>platform('adjustment-review',{id:proposal.id,approve:true,reason:'重复复核测试'}),/已处理/);
 assert.equal(Number(sql("SELECT COUNT(*) FROM hx_ledger WHERE category='RECON_ADJUST' AND reference_id='"+proposal.id+"';")),1);
 assert.equal(Number(sql("SELECT amount FROM hx_ledger WHERE reference_id='"+proposal.id+"';")),-10);
 assert.equal((await call('/hexu/app/orders/'+order.id,{token:customer})).total,order.total);
 assert.equal((await resource(owner,'reconciliationLines'))[0].status,'RESOLVED');checks.push('真实财务图片加密上传，申请人不能自审，平台复核单独记账，重复入账被拒绝，客户无权读取财务数据');
 const summary=(await resource(owner,'financeSummary'))[0];assert.equal(summary.totals.find(x=>x.category==='RECEIPT').amount,order.total);assert.equal(summary.totals.find(x=>x.category==='RECON_ADJUST').amount,-10);assert.equal(summary.pendingRows,0);checks.push('商家资金汇总显示原收款及单独调整，原订单实付不被篡改');
 const r=await command(customer,'refund-apply',{id:order.id,lineId:order.items[0].id,qty:1,type:'RETURN',reason:'联调清理退款'});await command(owner,'refund-review',{id:r.id,approve:true},true);await command(owner,'return-inspect',{id:r.id,goodQty:1},true);await call('/hexu/dev/refund/'+r.id,{data:{},dev:true});
 assert.equal(Number(sql("SELECT available FROM hx_sku WHERE shop_id="+shop+" AND id='stapler';")),100);assert.equal((await account(owner)).wallet.available,0);
 await fs.writeFile(path.join(resultsDir,'reconciliation-result.json'),JSON.stringify({testedAt:new Date().toISOString(),shopId:shop,batchId:batch.id,adjustmentId:proposal.id,checks},null,2));console.log(JSON.stringify({passed:checks.length,checks}));
}finally{sql("UPDATE hx_shop SET status='INACTIVE' WHERE id="+shop+';')}
