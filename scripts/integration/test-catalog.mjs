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

if(!process.argv.includes('--finish-ui')){
const shop=Date.now(),buyer=shop+1;
sql("INSERT INTO hx_member(id,name,authorized) VALUES ("+buyer+",'商品联调客户',true); INSERT INTO hx_shop(id,name,county,kind,owner_id,merchant_no,payment_ready,settings) VALUES ("+shop+",'商品目录联调专用商城','测试县域','DEALER',101,'DEV_CATALOG',true,'{}'); INSERT INTO hx_agent(member_id,shop_id,rank_no,status) VALUES (101,"+shop+",3,'ACTIVE');");
const owner=await login(101),customer=await login(buyer);
await call('/hexu/app/commands/document-save',{token:customer,data:{shopId:shop,kind:'address',name:'商品联调',phone:'13800000000',region:'新疆维吾尔自治区 乌鲁木齐市 天山区',detail:'数据库测试地址，不实际发货',primary:true,consent:true}});
await fs.writeFile(path.join(root,'.hexu-local/catalog-ui-fixture.json'),JSON.stringify({shop,buyer}));console.log(JSON.stringify({shop,buyer,ready:true}));
}else{
const f=JSON.parse(await fs.readFile(path.join(root,'.hexu-local/catalog-ui-fixture.json'),'utf8')),owner=await login(101),customer=await login(f.buyer),checks=[];
const list=await call('/hexu/app/products?shopId='+f.shop,{token:customer});assert.equal(list.length,2);assert.ok(list.every(p=>p.product_group==='qa-series'&&p.category==='办公文具'));assert.ok(list.every(p=>!('cloud_price' in p)));checks.push('手机新增商品、分类、图片和SKU组经平台审核后出现在真实商品接口，客户只看零售价');
const d=await call('/hexu/app/orders/'+f.orderId,{token:customer});assert.equal(d.items[0].sku_id,'qa-blue');assert.equal(d.items[0].qty,3);assert.equal(d.freight,2200);assert.equal(d.total,14200);checks.push('用户选蓝色SKU购买3件，800克每件合并重量续费并加配送地区附加费，报价与订单实付一致');
const payment=await call('/hexu/app/commands/payment-create',{token:customer,data:{shopId:f.shop,id:d.id}});await call('/hexu/dev/payment/'+payment.id,{dev:true,data:{}});await call('/hexu/app/management/ship',{token:owner,data:{shopId:f.shop,id:d.id,carrier:'测试物流',tracking:'CATALOG'+Date.now()}});await call('/hexu/app/commands/order-receive',{token:customer,data:{shopId:f.shop,id:d.id}});
const r=await call('/hexu/app/commands/refund-apply',{token:customer,data:{shopId:f.shop,id:d.id,lineId:d.items[0].id,qty:3,type:'RETURN',reason:'商品数据库联调结束退货'}});await call('/hexu/app/management/refund-review',{token:owner,data:{shopId:f.shop,id:r.id,approve:true}});await call('/hexu/app/management/return-inspect',{token:owner,data:{shopId:f.shop,id:r.id,goodQty:3}});await call('/hexu/dev/refund/'+r.id,{dev:true,data:{}});
assert.equal(Number(sql("SELECT available FROM hx_sku WHERE shop_id="+f.shop+" AND id='qa-blue';")),20);assert.equal((await call('/hexu/app/account?shopId='+f.shop,{token:owner})).wallet.available,0);checks.push('支付、发货、结算、原渠道退款和商品回库完整贯通，代理收益冲正归零');
sql("UPDATE hx_shop SET status='INACTIVE' WHERE id="+f.shop+';');await fs.writeFile(path.join(resultsDir,'catalog-result.json'),JSON.stringify({testedAt:new Date().toISOString(),status:'PASS',shopId:f.shop,orderId:d.id,checks},null,2));console.log(JSON.stringify({passed:checks.length,checks}));
}
