import fs from 'node:fs/promises'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import assert from 'node:assert/strict'
import {randomUUID} from 'node:crypto'
import {hexuFetch} from './hexu-client-contract.mjs'
import {resultsDir} from './paths.mjs'
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'../..'),base='http://127.0.0.1:8088'
const key=(await fs.readFile(path.join(root,'.hexu-local/dev-key.txt'),'utf8')).trim()
async function call(url,token,data,dev=false){const response=await hexuFetch(base+url,{method:data?'POST':'GET',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{}),...(dev?{'X-Hexu-Dev-Key':key}:{}),'Idempotency-Key':randomUUID()},...(data?{body:JSON.stringify(data)}:{})});const r=await response.json();assert.equal(r.code,200,r.msg);return r.data}
const customer=(await call('/hexu/dev/login',null,{memberId:201},true)).token,owner=(await call('/hexu/dev/login',null,{memberId:101},true)).token
const command=(token,op,b,management=false)=>call('/hexu/app/'+(management?'management/':'commands/')+op,token,{shopId:2,...b})
const before=await call('/hexu/app/account?shopId=2',customer)
const stockBefore=(await call('/hexu/app/products?shopId=2',customer)).find(p=>p.id==='cup').stock
const campaign=await command(owner,'document-save',{kind:'coupon',name:'联调商品券（自动到期）',type:'PRODUCT',skuIds:'cup',discount:100,threshold:1000,quantity:1,expiresAt:Date.now()+60000},true)
const coupon=await command(customer,'coupon-claim',{id:campaign.id})
const body={shopId:2,items:[{id:'cup',qty:1}],couponId:coupon.id,points:100,pointsScope:2,address:{name:'联调收货人',detail:'本地联调，无实际发货'}}
const quote=await call('/hexu/app/quote',customer,body)
let order
try {order=await command(customer,'order-create',body);assert.equal(order.total,quote.total);assert.equal(order.discount,100);assert.equal(order.points_scope,2);assert.equal((await call('/hexu/app/account?shopId=2',customer)).shopPoints.available,before.shopPoints.available-100)}
finally {if(order)await command(customer,'order-cancel',{id:order.id})}
const after=await call('/hexu/app/account?shopId=2',customer);assert.equal(after.shopPoints.available,before.shopPoints.available);assert.equal(after.platformPoints.available,before.platformPoints.available)
assert.equal((await call('/hexu/app/products?shopId=2',customer)).find(p=>p.id==='cup').stock,stockBefore)
const report={at:new Date().toISOString(),status:'PASS',orderId:order.id,total:quote.total,checks:['指定SKU商品券','服务端试算与实际订单金额一致','商城积分仅扣商城账户','取消订单返还积分和库存'],note:'保留取消订单和1分钟有效的测试券审计记录，无真实支付。'}
await fs.writeFile(path.join(resultsDir,'checkout-result.json'),JSON.stringify(report,null,2));console.log(JSON.stringify(report,null,2))
