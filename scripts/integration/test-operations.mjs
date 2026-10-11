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



const fixturePath=path.join(root,'.hexu-local/operations-ui-fixture.json'),f=JSON.parse(await fs.readFile(fixturePath,'utf8'));
const owner=await login(101);
if(!process.argv.includes('--trade')&&!process.argv.includes('--finish-ui')){
 sql("INSERT INTO hx_member(id,name,authorized) VALUES ("+f.buyer+",'运营联调客户',true); INSERT INTO hx_shop(id,name,county,kind,owner_id,merchant_no,payment_ready,settings) VALUES ("+f.shop+",'运营配置联调专用商城','联调县域','DEALER',101,'DEV_OPS',true,'{}'); INSERT INTO hx_agent(member_id,shop_id,rank_no,status) VALUES (101,"+f.shop+",3,'ACTIVE'); INSERT INTO hx_binding(member_id,shop_id,agent_id) SELECT "+f.buyer+","+f.shop+",id FROM hx_agent WHERE shop_id="+f.shop+" AND member_id=101; INSERT INTO hx_sku(id,shop_id,name,spec,asset,retail,cloud_price,center_price,owner_price,available,warning_qty) VALUES ('ops-sku',"+f.shop+",'运营联调订书机','黑色 / 标准','stapler',2000,1000,800,600,10,8); INSERT INTO hx_points(shop_id,member_id,available) VALUES (0,"+f.buyer+",100);");
 await call('/hexu/app/management/document-save',{token:owner,data:{shopId:f.shop,kind:'promotion_rule',name:'运营核算折扣',type:'LIMITED',skuIds:'ops-sku',rateBps:8000,startsAt:0,expiresAt:Date.now()+86400000,stackPoints:true}});console.log(JSON.stringify({shop:f.shop,buyer:f.buyer,staffUserId:f.userId,ready:true}));
}else if(process.argv.includes('--trade')){
 const staff=await call('/hexu/app/management/operations/staff?shopId='+f.shop,{token:owner});assert.equal(staff.find(x=>x.user_id===f.userId).role_code,'WAREHOUSE');assert.equal(Number(sql('SELECT COUNT(*) FROM hx_order WHERE shop_id='+f.shop+';')),0);assert.equal(Number(sql("SELECT available FROM hx_sku WHERE shop_id="+f.shop+" AND id='ops-sku';")),10);
 const params=(await call('/hexu/app/management/documents_system_parameter?shopId='+f.shop,{token:owner})).find(x=>x.status==='ACTIVE');assert.equal(params.body.autoReceiveDays,4);assert.equal(params.body.notifyShipped,false);
 const customer=await login(f.buyer);const o=await call('/hexu/app/commands/order-create',{token:customer,data:{shopId:f.shop,items:[{id:'ops-sku',qty:3}],address:{name:'运营联调客户',phone:'13900000000',region:'联调县域',detail:'联调仓库'}}});f.orderId=o.id;const p=await call('/hexu/app/commands/payment-create',{token:customer,data:{shopId:f.shop,id:o.id}});await call('/hexu/dev/payment/'+p.id,{dev:true,data:{}});
 await call('/hexu/app/management/document-save',{token:owner,data:{...params.body,kind:'system_parameter',shopId:f.shop,autoReceiveDays:2}});await call('/hexu/app/management/ship',{token:owner,data:{shopId:f.shop,id:o.id,carrier:'顺丰',tracking:'OPS'+Date.now()}});const d=await call('/hexu/app/orders/'+o.id,{token:customer});assert.equal(JSON.parse(d.shipping_json).autoReceiveDays,4);await call('/hexu/app/commands/order-receive',{token:customer,data:{shopId:f.shop,id:o.id}});
 const data=await call('/hexu/app/bootstrap?shopId='+f.shop,{token:customer});const paid=data.notifications.find(x=>x.event_type==='ORDER_PAID'&&x.reference_id===o.id);assert.ok(paid);assert.equal(paid.title,'联调支付已确认');assert.ok(paid.body_text.includes(o.id));assert.equal(data.notifications.filter(x=>x.event_type==='ORDER_SHIPPED'&&x.reference_id===o.id).length,0);assert.equal(Number(sql("SELECT COUNT(*) FROM hx_message WHERE shop_id="+f.shop+" AND event_type='INVENTORY_LOW';")),1);f.messageId=paid.id;await fs.writeFile(fixturePath,JSON.stringify(f));console.log(JSON.stringify({orderId:o.id,amount:o.total,messageId:paid.id,readyForMessages:true}));
}else{
 assert.equal(Number(sql('SELECT COUNT(*) FROM hx_staff WHERE shop_id='+f.shop+' AND user_id='+f.userId+';')),0);assert.equal(Number(sql('SELECT is_read FROM hx_message WHERE id='+f.messageId+';')),1);const audits=await call('/hexu/app/management/audit?shopId='+f.shop,{token:owner});assert.ok(audits.some(x=>x.action==='STAFF_ASSIGN'&&x.source==='APP'&&x.request_id));assert.ok(audits.some(x=>x.action==='STAFF_ASSIGN'&&x.source==='ADMIN'));
 const customer=await login(f.buyer),d=await call('/hexu/app/orders/'+f.orderId,{token:customer});const r=await call('/hexu/app/commands/refund-apply',{token:customer,data:{shopId:f.shop,id:f.orderId,lineId:d.items[0].id,qty:3,type:'RETURN',reason:'运营功能联调结束返还测试商品'}});await call('/hexu/app/management/refund-review',{token:owner,data:{shopId:f.shop,id:r.id,approve:true}});await call('/hexu/app/management/return-inspect',{token:owner,data:{shopId:f.shop,id:r.id,goodQty:3}});await call('/hexu/dev/refund/'+r.id,{dev:true,data:{}});assert.equal(Number(sql("SELECT available FROM hx_sku WHERE shop_id="+f.shop+" AND id='ops-sku';")),10);assert.equal(Number(sql('SELECT COALESCE(SUM(available+frozen),0) FROM hx_wallet WHERE shop_id='+f.shop+';')),0);sql("UPDATE hx_shop SET status='INACTIVE' WHERE id="+f.shop+";UPDATE sys_user SET status='1' WHERE user_id="+f.userId+" AND user_name='"+f.staffLogin+"';");const result={status:'PASS',completedAt:new Date().toISOString(),shopId:f.shop,checks:['手机分配真实仓储岗位，平台撤销同商城权限并保留来源与请求审计','营销只读试算不创建订单、不扣减库存和积分','手机配置站内模板和发货通知开关，真实支付消息带正确关联单号，发货通知未生成','下单时自动收货4天，后改2天不影响旧订单；库存跨过预警阈值只提醒一次','用户消息中心按订单分类并点击标记已读；联调后全额退款还库、停用商城及测试后台账号']};await fs.writeFile(path.join(resultsDir,'operations-result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));}
