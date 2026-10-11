import fs from 'node:fs/promises'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import {hexuFetch} from './hexu-client-contract.mjs'
import {resultsDir} from './paths.mjs'
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..')
const runtime=path.join(root,'.hexu-local')
const key=(await fs.readFile(path.join(runtime,'dev-key.txt'),'utf8')).trim()
const base='http://127.0.0.1:8088'
async function call(p,{token,data,dev=false,key:idem}={}){const r=await hexuFetch(base+p,{method:data?'POST':'GET',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{}),...(dev?{'X-Hexu-Dev-Key':key}:{}),...(idem?{'Idempotency-Key':idem}:{})},...(data?{body:JSON.stringify(data)}:{})});const body=await r.json();if(!r.ok||body.code!==200)throw Error(p+': '+body.msg);return body.data}
const login=async memberId=>(await call('/hexu/dev/login',{data:{memberId},dev:true})).token
const [customer,owner,c1,receiver]=await Promise.all([login(201),login(101),login(104),login(202)])
const command=(token,op,data={},management=false,idem=randomUUID())=>call('/hexu/app/'+(management?'management/':'commands/')+op,{token,data:{shopId:2,...data},key:idem})
const account=token=>call('/hexu/app/account?shopId=2',{token})
const products=()=>call('/hexu/app/products?shopId=2',{token:customer})
const beforeStock=(await products()).find(p=>p.id==='stapler').stock
const before=await account(c1),ownerBefore=await account(owner)
const order=await command(customer,'order-create',{items:[{id:'stapler',qty:2}],address:{name:'数据库联调',phone:'13800000000',region:'福建省 泉州市 安溪县',detail:'测试地址，不实际发货'}})
assert.equal(order.total,4000)
assert.ok(Math.abs(Date.now()-Date.parse(order.created_at))<60000,'数据库创建时间应与当前时间一致')
assert.ok(Date.parse(order.expires_at)-Date.now()>14*60000&&Date.parse(order.expires_at)-Date.now()<=16*60000,'订单须在约15分钟后过期')
const payment=await command(customer,'payment-create',{id:order.id})
await call('/hexu/dev/payment/'+payment.id,{data:{},dev:true})
await call('/hexu/dev/payment/'+payment.id,{data:{},dev:true})
const merchantOrders=await call('/hexu/app/management/orders?shopId=2',{token:owner})
assert.equal(merchantOrders.find(o=>o.id===order.id).status,'PAID')
assert.equal((await call('/hexu/app/orders?shopId=2',{token:customer})).find(o=>o.id===order.id).items[0].qty,2)
await command(owner,'ship',{id:order.id,carrier:'联调物流',tracking:'TEST'+Date.now()},true)
await command(customer,'order-receive',{id:order.id})
assert.equal((await account(c1)).wallet.available-before.wallet.available,2000)
const withdrawal=await command(c1,'withdraw',{amount:1000,channel:'BANK',accountRef:'BANK104'})
assert.equal(withdrawal.fee,6)
await command(owner,'withdraw-review',{id:withdrawal.id,approve:true,reason:''},true)
await call('/hexu/dev/payout/'+withdrawal.id,{data:{success:false,reason:'联调渠道失败回退'},dev:true})
assert.equal((await account(c1)).wallet.available-before.wallet.available,2000)
for(const type of ['REFUND_ONLY','RETURN']){
 const refund=await command(customer,'refund-apply',{id:order.id,lineId:order.items[0].id,qty:1,type,reason:'联调退款核对'})
 await command(owner,'refund-review',{id:refund.id,approve:true},true)
 if(type==='RETURN')await command(owner,'return-inspect',{id:refund.id,goodQty:1},true)
 await call('/hexu/dev/refund/'+refund.id,{data:{},dev:true})
 await call('/hexu/dev/refund/'+refund.id,{data:{},dev:true})
}
assert.equal((await account(c1)).wallet.available,before.wallet.available)
assert.equal((await account(owner)).wallet.available,ownerBefore.wallet.available)
assert.equal((await products()).find(p=>p.id==='stapler').stock,beforeStock-1)
assert.equal((await call('/hexu/app/orders/'+order.id,{token:customer})).status,'REFUNDED')
const pointsBefore=(await account(customer)).platformPoints.available,receiverBefore=(await account(receiver)).platformPoints.available
const idem=randomUUID();await command(customer,'points-transfer',{shopId:0,recipientId:202,amount:1},false,idem);await command(customer,'points-transfer',{shopId:0,recipientId:202,amount:1},false,idem)
assert.equal((await account(customer)).platformPoints.available,pointsBefore-1);assert.equal((await account(receiver)).platformPoints.available,receiverBefore+1)
await command(receiver,'points-transfer',{shopId:0,recipientId:201,amount:1})
let platformVerified=false,platformNote=''
try{const browserState=JSON.parse(await fs.readFile(path.join(runtime,'admin-auth.json'),'utf8'));const token=browserState.cookies.find(c=>c.name==='Admin-Token')?.value;if(token){const rows=await call('/hexu/admin/orders?shopId=2',{token});assert.equal(rows.find(o=>o.id===order.id).status,'REFUNDED');platformVerified=true}}catch(e){if(/认证失败/.test(e.message))platformNote='平台后台会话已失效：重新正常登录并更新 .hexu-local/admin-auth.json 后才能核对平台读取';else if(e.code!=='ENOENT')throw e}
const report={at:new Date().toISOString(),database:'hexu_integration',orderId:order.id,withdrawalId:withdrawal.id,status:'PASS',platformVerified,...(platformNote?{platformNote}:{}),checks:['会员与商家端同订单','支付幂等','库存扣减','完成结算','提现审核及失败退回','退款双边冲正','仅退款不回库','合格退货回库','积分双流水及幂等'],note:'保留联调订单和审计流水；未调用真实支付、退款或物流渠道。'}
await fs.writeFile(path.join(resultsDir,'integration-result.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2))
