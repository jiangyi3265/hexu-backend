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
const shop=Date.now(),buyer=shop+1,recipient=shop+2
const checks=[]
async function call(p,{token,data,dev=false}={}){const r=await hexuFetch('http://127.0.0.1:8088'+p,{method:data?'POST':'GET',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{}),...(dev?{'X-Hexu-Dev-Key':key}:{}),'Idempotency-Key':randomUUID()},...(data?{body:JSON.stringify(data)}:{})});const b=await r.json();if(!r.ok||b.code!==200)throw Error(p+': '+b.msg);return b.data}
const login=async memberId=>(await call('/hexu/dev/login',{data:{memberId},dev:true})).token
const command=(token,op,data={},management=false)=>call('/hexu/app/'+(management?'management/':'commands/')+op,{token,data:{shopId:shop,...data}})
const account=token=>call('/hexu/app/account?shopId='+shop,{token})
sql('INSERT INTO hx_member(id,name,authorized) VALUES ('+buyer+',\'批次联调用户\',true),('+recipient+',\'批次联调收赠人\',true); INSERT INTO hx_shop(id,name,county,kind,owner_id,merchant_no,payment_ready,settings) VALUES ('+shop+',\'自动化专用临时商城\',\'测试县域\',\'DEALER\',101,\'DEV_FIXTURE\',true,\'{}\'); INSERT INTO hx_agent(member_id,shop_id,rank_no,status) VALUES (101,'+shop+',3,\'ACTIVE\'); INSERT INTO hx_sku(id,shop_id,name,spec,asset,retail,cloud_price,center_price,owner_price,available) SELECT id,'+shop+',name,spec,asset,retail,cloud_price,center_price,owner_price,100 FROM hx_sku WHERE shop_id=2 AND id IN (\'stapler\',\'cup\');')
try {
 const [owner,customer,receiver]=await Promise.all([login(101),login(buyer),login(recipient)])
 await command(owner,'document-save',{kind:'points_rule',name:'批次联调',pointsPerYuan:100,expiryDays:1,purchaseRate:0,checkinPoints:5,deductionPercent:20},true)
 await command(customer,'bind');await command(receiver,'bind');
 await command(customer,'checkin')
 const earned=(await account(customer)).shopPoints;assert.equal(earned.available,5);assert.equal(earned.expiring,5);assert.equal(earned.batches.length,1)
 await command(customer,'points-transfer',{recipientId:recipient,amount:1})
 const received=(await account(receiver)).shopPoints;assert.equal(received.available,1);assert.equal(Date.parse(received.batches[0].expires_at),Date.parse(earned.batches[0].expires_at));checks.push('MySQL签到积分生成有效期批次；转赠继承原到期日')
 await command(owner,'document-save',{kind:'bundle',name:'联调套餐',type:'BUNDLE',items:[{skuId:'stapler',qty:1},{skuId:'cup',qty:1}],price:10000,quantity:5,perMember:5,stackPoints:true,startsAt:Date.now()-1000,expiresAt:Date.now()+120000},true)
 const input={items:[{id:'stapler',qty:1},{id:'cup',qty:1}],points:1,pointsScope:shop,address:{name:'联调',phone:'13800000000',region:'测试县域',detail:'自动化测试地址，不发货'}}
 const quote=await call('/hexu/app/quote',{token:customer,data:{shopId:shop,...input}});assert.equal(quote.promotionDiscount,900);assert.equal(quote.total,9999)
 const order=await command(customer,'order-create',input);assert.equal(order.total,quote.total);assert.equal(order.items.length,2);assert.equal(order.items.reduce((sum,line)=>sum+line.paid,0),9999);checks.push('MySQL组合套餐报价、分摊实付、积分抵扣与订单一致')
 sql('UPDATE hx_point_batch SET expires_at=UTC_TIMESTAMP()-INTERVAL 2 MINUTE WHERE shop_id='+shop+';')
 assert.equal((await account(customer)).shopPoints.available,0);assert.equal((await account(receiver)).shopPoints.available,0)
 await command(customer,'order-cancel',{id:order.id})
 const cancelled=await account(customer);assert.equal(cancelled.shopPoints.available,0);assert.equal(cancelled.shopPoints.expired,4)
 assert.equal((await account(customer)).shopPoints.expired,4);assert.equal((await account(receiver)).shopPoints.expired,1)
 assert.equal(Number(sql('SELECT COALESCE(SUM(amount),0) FROM hx_point_log WHERE shop_id='+shop+' AND kind=\'EXPIRE\';')),-4)
 const sku=await call('/hexu/app/products?shopId='+shop,{token:customer});assert.ok(sku.every(p=>p.stock===100&&p.locked===0));checks.push('MySQL到期只扣一次；取消订单不延长原积分有效期，库存完全释放')
 const batches=await call('/hexu/app/management/pointBatches?shopId='+shop,{token:owner});assert.equal(batches.length,2);checks.push('商家管理端读取同一数据库的积分批次与失效数量')
 await fs.writeFile(path.join(resultsDir,'expiry-marketing-result.json'),JSON.stringify({testedAt:new Date().toISOString(),shopId:shop,orderId:order.id,checks,fixture:'独立临时商城；测试结束停用；保留流水'},null,2));console.log(JSON.stringify({passed:checks.length,checks}))
} finally {sql('UPDATE hx_shop SET status=\'INACTIVE\' WHERE id='+shop+';')}
