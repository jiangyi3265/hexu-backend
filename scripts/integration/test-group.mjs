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

const shop=Date.now(),buyer=shop+1,second=shop+2,checks=[];
async function call(p,{token,data,dev=false}={}){const r=await hexuFetch('http://127.0.0.1:8088'+p,{method:data?'POST':'GET',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{}),...(dev?{'X-Hexu-Dev-Key':key}:{}),'Idempotency-Key':randomUUID()},...(data?{body:JSON.stringify(data)}:{})});const b=await r.json();if(!r.ok||b.code!==200)throw Error(p+': '+b.msg);return b.data}
const login=async memberId=>(await call('/hexu/dev/login',{data:{memberId},dev:true})).token;
const command=(token,op,data={},management=false)=>call('/hexu/app/'+(management?'management/':'commands/')+op,{token,data:{shopId:shop,...data}});
const account=token=>call('/hexu/app/account?shopId='+shop,{token});
sql("INSERT INTO hx_member(id,name,authorized) VALUES ("+buyer+",'拼团联调客户',true),("+second+",'拼团联调好友',true); INSERT INTO hx_shop(id,name,county,kind,owner_id,merchant_no,payment_ready,settings) VALUES ("+shop+",'拼团自动化专用商城','测试县域','DEALER',101,'DEV_GROUP',true,'{}'); INSERT INTO hx_agent(member_id,shop_id,rank_no,status) VALUES (101,"+shop+",3,'ACTIVE'); INSERT INTO hx_sku(id,shop_id,name,spec,asset,retail,cloud_price,center_price,owner_price,available) SELECT id,"+shop+",name,spec,asset,retail,cloud_price,center_price,owner_price,100 FROM hx_sku WHERE shop_id=2 AND id='stapler';");
const [owner,customer,friend]=await Promise.all([login(101),login(buyer),login(second)]);
const campaign=await command(owner,'document-save',{kind:'group_campaign',name:'好友二人团',enabled:true,skuId:'stapler',price:1800,size:2,hours:1,startsAt:Date.now()-1000,expiresAt:Date.now()+86400000},true);
const address={name:'拼团联调',phone:'13800000000',region:'福建省 泉州市 安溪县',detail:'数据库测试地址，不实际发货',consent:true};await command(customer,'bind');await command(friend,'bind');await command(customer,'document-save',{kind:'address',...address,primary:true});
if(process.argv.includes('--prepare-ui')){await fs.writeFile(path.join(root,'.hexu-local/group-ui-fixture.json'),JSON.stringify({shop,buyer,second,campaignId:campaign.id}));console.log(JSON.stringify({shop,buyer,second,ready:true}));process.exit(0)}
const create=(token,groupId)=>command(token,'order-create',{items:[{id:'stapler',qty:1}],address,...(groupId?{groupId}:{groupCampaignId:campaign.id})});
const pay=async(token,order)=>{const p=await command(token,'payment-create',{id:order.id});await call('/hexu/dev/payment/'+p.id,{dev:true,data:{}})};
try{
const first=await create(customer);assert.equal(first.total,1800);assert.equal(first.freight,0);await pay(customer,first);
await assert.rejects(()=>command(owner,'ship',{id:first.id,carrier:'测试物流',tracking:'GROUP123456'},true),/未成功/);
const next=await create(friend,first.group.id);await pay(friend,next);const group=await call('/hexu/app/groups/'+first.group.id+'?shopId='+shop,{token:customer});assert.equal(group.status,'FORMED');assert.equal(group.paidCount,2);assert.equal(group.members.length,2);assert.ok(group.members.every(m=>!m.memberId&&!m.orderId));
for(const [token,order] of [[customer,first],[friend,next]]){await command(owner,'ship',{id:order.id,carrier:'测试物流',tracking:'GROUP'+Date.now()},true);await command(token,'order-receive',{id:order.id});}
assert.equal((await account(owner)).wallet.available,2400);checks.push('MySQL真实付款满2人成团；未成团禁止发货；成团后正常履约及结算');
const state=JSON.parse(await fs.readFile(path.join(root,'.hexu-local/admin-auth.json'),'utf8'));const admin=state.cookies.find(c=>c.name==='Admin-Token').value;const platform=await call('/hexu/admin/resources/groups?shopId='+shop,{token:admin});assert.equal(platform.find(g=>g.id===group.id).status,'FORMED');checks.push('商城、商家、平台三端读取同一成团状态，成员信息脱敏');
for(const [token,order] of [[customer,first],[friend,next]]){const r=await command(token,'refund-apply',{id:order.id,lineId:order.items[0].id,qty:1,type:'RETURN',reason:'联调售后'});await command(owner,'refund-review',{id:r.id,approve:true},true);await command(owner,'return-inspect',{id:r.id,goodQty:1},true);await call('/hexu/dev/refund/'+r.id,{data:{},dev:true});}
const failed=await create(customer);await pay(customer,failed);const unpaid=await create(friend,failed.group.id);sql("UPDATE hx_group_buy SET expires_at=UTC_TIMESTAMP()-INTERVAL 2 MINUTE WHERE id='"+failed.group.id+"';");
let tasks=[];for(let i=0;i<45;i++){tasks=await call('/hexu/app/management/groupRefunds?shopId='+shop,{token:owner});if(tasks.length)break;await new Promise(r=>setTimeout(r,2000));}assert.equal(tasks.length,1);assert.equal(tasks[0].refund_status,'APPROVED');assert.equal(tasks[0].amount,1800);assert.equal((await call('/hexu/app/orders/'+unpaid.id,{token:friend})).status,'CANCELLED');checks.push('定时任务实际执行：超时团生成单一退款任务，未支付订单取消释放库存');
await call('/hexu/dev/refund/'+tasks[0].refund_id,{data:{},dev:true});await call('/hexu/dev/refund/'+tasks[0].refund_id,{data:{},dev:true});assert.equal((await call('/hexu/app/orders/'+failed.id,{token:customer})).status,'REFUNDED');assert.equal((await account(owner)).wallet.available,0);assert.equal(Number(sql('SELECT available FROM hx_sku WHERE shop_id='+shop+" AND id='stapler';")),100);checks.push('失败退款回执幂等，库存100、收益归零，未调用真实渠道');
await fs.writeFile(path.join(resultsDir,'group-result.json'),JSON.stringify({testedAt:new Date().toISOString(),shopId:shop,groups:[group.id,failed.group.id],checks},null,2));console.log(JSON.stringify({passed:checks.length,checks}));
}finally{sql("UPDATE hx_shop SET status='INACTIVE' WHERE id="+shop+';')}
