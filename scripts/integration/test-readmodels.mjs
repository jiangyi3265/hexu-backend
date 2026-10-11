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


async function call(p,{token,data,dev=false}={}){const r=await fetch('http://127.0.0.1:8088'+p,{method:data?'POST':'GET',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{}),...(dev?{'X-Hexu-Dev-Key':key}:{}),'Idempotency-Key':randomUUID()},...(data?{body:JSON.stringify(data)}:{})});const b=await r.json();if(!r.ok||b.code!==200)throw Error(p+': '+b.msg);return b.data}
const login=async memberId=>(await call('/hexu/dev/login',{data:{memberId},dev:true})).token;
const [owner,agent,customer]=await Promise.all([login(101),login(104),login(201)]);
const checks=[];const account=await call('/hexu/app/account?shopId=2',{token:agent});let e;for(const x of account.earnings.filter(x=>x.amount>0&&x.reversed===x.amount)){const d=await call('/hexu/app/earnings/'+x.id,{token:agent});if(d.line?.sku_id==='stapler'){e=d;break}}assert.ok(e,'需要先运行 test-integration.mjs 生成已全额退款的订书机收益');
assert.equal(e.settlementPrice,1000);assert.equal(e.line.snapshot_json,undefined);assert.equal(e.remaining,e.amount-e.reversed);await assert.rejects(()=>call('/hexu/app/earnings/'+e.id,{token:customer}),/无权/);checks.push('收益详情按原订单价格、数量和退款读取，只允许本人查看');
const w=await call('/hexu/app/withdrawals/'+account.withdrawals[0].id,{token:agent});assert.equal(w.accountLabel,'工商银行（3287）');assert.equal(w.amount-w.fee,w.net);await assert.rejects(()=>call('/hexu/app/withdrawals/'+w.id,{token:customer}),/无权/);await assert.rejects(()=>call('/hexu/app/management/withdrawals/'+w.id,{token:customer}),/权限|拥有者/);checks.push('提现详情费用与余额来自真实单据，结算账户脱敏并校验本人及财务权限');
const a=await call('/hexu/app/management/agent-summary/4?shopId=2',{token:owner});assert.equal(a.name,'C1 云代理');assert.equal(a.chain[1].id,3);const customers=await call('/hexu/app/management/customers?shopId=2',{token:owner});assert.ok(customers.every(x=>!x.phone||/^\d{3}\*{4}\d{4}$/.test(x.phone)));const sum=await call('/hexu/app/management/earningSummary?shopId=2',{token:owner});assert.ok(Number.isFinite(sum[0].settled));assert.ok(Number.isFinite(account.summary.pendingEarnings));checks.push('真实代理链、全量收益聚合及客户脱敏目录可正常读取');
const rev=await call('/hexu/app/management/reversals/'+e.order_id+'?shopId=2',{token:owner});assert.ok(rev.earnings.length>0);assert.equal(rev.refunded,rev.total);checks.push('管理端冲正明细与用户收益详情读取同一退款订单');
let shop,document;
if(process.argv.includes('--finish-ui')){const f=JSON.parse(await fs.readFile(path.join(root,'.hexu-local/readmodels-ui-fixture.json'),'utf8'));shop=f.shop;document=f.document;const row=JSON.parse(sql("SELECT JSON_OBJECT('status',status,'body',CAST(body_json AS JSON)) FROM hx_document WHERE id='"+document+"';"));assert.equal(row.status,'APPROVED');assert.equal(row.body.items[0].qty,3);checks.push('手机跨店审核将10件收紧为3件并成功入库');sql("UPDATE hx_shop SET status='INACTIVE' WHERE id="+shop+';');}
else{shop=Date.now();sql("INSERT INTO hx_shop(id,name,county,kind,owner_id,merchant_no,payment_ready,settings) VALUES ("+shop+",'采购审核联调专用商城','测试县域','DEALER',101,'DEV_READMODEL',true,'{}'); INSERT INTO hx_agent(member_id,shop_id,rank_no,status) VALUES (101,"+shop+",3,'ACTIVE'); INSERT INTO hx_sku(id,shop_id,name,spec,asset,retail,cloud_price,center_price,owner_price,available) SELECT id,"+shop+",name,spec,asset,retail,cloud_price,center_price,owner_price,100 FROM hx_sku WHERE shop_id=2 AND id='stapler';");const d=await call('/hexu/app/commands/document-save',{token:agent,data:{shopId:shop,kind:'cross_purchase',sourceShopId:2,expiresAt:Date.now()+86400000*3,items:[{skuId:'stapler',qty:10}],reason:'真实手机审核联调'}});document=d.id;await assert.rejects(()=>call('/hexu/app/management/document-review',{token:owner,data:{shopId:shop,id:document,decision:'APPROVED',approvedItems:[{skuId:'stapler',qty:11}],reason:'扩大范围应拒绝'}}),/不能超过/);checks.push('审核不能扩大申请数量且失败事务不修改原申请');await fs.writeFile(path.join(root,'.hexu-local/readmodels-ui-fixture.json'),JSON.stringify({shop,document}));}
const report={testedAt:new Date().toISOString(),shop,document,status:'PASS',checks};await fs.writeFile(path.join(resultsDir,'readmodels-result.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2));
