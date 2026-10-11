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

const shop=Date.now(),buyer=shop+1,referrer=shop+2;
const checks=[];
async function call(p,{token,data,dev=false}={}){const r=await hexuFetch('http://127.0.0.1:8088'+p,{method:data?'POST':'GET',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{}),...(dev?{'X-Hexu-Dev-Key':key}:{}),'Idempotency-Key':randomUUID()},...(data?{body:JSON.stringify(data)}:{})});const b=await r.json();if(!r.ok||b.code!==200)throw Error(p+': '+b.msg);return b.data}
const login=async memberId=>(await call('/hexu/dev/login',{data:{memberId},dev:true})).token;
const command=(token,op,data={},management=false)=>call('/hexu/app/'+(management?'management/':'commands/')+op,{token,data:{shopId:shop,...data}});
const account=token=>call('/hexu/app/account?shopId='+shop,{token});
sql("INSERT INTO hx_member(id,name,authorized) VALUES ("+buyer+",'邀请联调客户',true),("+referrer+",'邀请联调代理',true); INSERT INTO hx_shop(id,name,county,kind,owner_id,merchant_no,payment_ready,settings) VALUES ("+shop+",'邀请自动化专用商城','测试县域','DEALER',101,'DEV_INVITATION',true,'{}'); INSERT INTO hx_agent(member_id,shop_id,rank_no,status) VALUES (101,"+shop+",3,'ACTIVE'); INSERT INTO hx_agent(member_id,shop_id,parent_id,rank_no,status) SELECT "+referrer+","+shop+",id,1,'ACTIVE' FROM hx_agent WHERE member_id=101 AND shop_id="+shop+"; INSERT INTO hx_sku(id,shop_id,name,spec,asset,retail,cloud_price,center_price,owner_price,available) SELECT id,"+shop+",name,spec,asset,retail,cloud_price,center_price,owner_price,100 FROM hx_sku WHERE shop_id=2 AND id='stapler';");
try{
const [owner,customer,agent]=await Promise.all([login(101),login(buyer),login(referrer)]);
await command(owner,'document-save',{kind:'invitation_campaign',name:'真实订单邀请联调',enabled:true,firstOrderReward:100,thirdOrderReward:200,rewardCap:250,minOrderAmount:100,startsAt:Date.now()-1000,expiresAt:Date.now()+3600000},true);
const invite=await command(agent,'invite');await command(customer,'bind',{invite:invite.invite});
const orders=[];
for(let n=0;n<3;n++) {const order=await command(customer,'order-create',{items:[{id:'stapler',qty:1}],address:{name:'联调客户',phone:'13800000000',region:'测试',detail:'数据库联调，不发货'}});orders.push(order);const pay=await command(customer,'payment-create',{id:order.id});await call('/hexu/dev/payment/'+pay.id,{dev:true,data:{}});const before=(await account(agent)).wallet.available;await command(owner,'ship',{id:order.id,carrier:'测试物流',tracking:'TEST'+Date.now()},true);assert.equal((await account(agent)).wallet.available,before);await command(customer,'order-receive',{id:order.id});}
let status=await call('/hexu/app/invitation-campaign?shopId='+shop,{token:agent});assert.equal(status.invited,1);assert.equal(status.qualified,1);assert.equal(status.earned,250);assert.equal((await account(agent)).wallet.available,3250);assert.equal((await account(owner)).wallet.available,950);checks.push('MySQL真实邀请绑定，首单和第三单计奖，累计封顶250分，商城承担');
const rows=await call('/hexu/app/management/earnings?shopId='+shop,{token:owner});assert.equal(rows.filter(e=>e.earning_type==='INVITE_REWARD').reduce((s,e)=>s+e.amount,0),250);assert.equal(rows.filter(e=>e.earning_type==='INVITE_COST').reduce((s,e)=>s+e.amount,0),-250);
const adminState=JSON.parse(await fs.readFile(path.join(root,'.hexu-local/admin-auth.json'),'utf8'));const admin=adminState.cookies.find(c=>c.name==='Admin-Token').value;const platform=await call('/hexu/admin/resources/earnings?shopId='+shop,{token:admin});assert.equal(platform.length,rows.length);checks.push('客户完成、代理奖励账户、商家收益及平台查询共享同一组数据库流水');
for(const order of orders){const r=await command(customer,'refund-apply',{id:order.id,lineId:order.items[0].id,qty:1,type:'RETURN',reason:'联调退款'});await command(owner,'refund-review',{id:r.id,approve:true},true);await command(owner,'return-inspect',{id:r.id,goodQty:1},true);await call('/hexu/dev/refund/'+r.id,{data:{},dev:true});await call('/hexu/dev/refund/'+r.id,{data:{},dev:true});}
status=await call('/hexu/app/invitation-campaign?shopId='+shop,{token:agent});assert.equal(status.qualified,0);assert.equal(status.earned,0);assert.equal((await account(agent)).wallet.available,0);assert.equal((await account(owner)).wallet.available,0);assert.equal(Number(sql('SELECT available FROM hx_sku WHERE shop_id='+shop+" AND id='stapler';")),100);checks.push('全部退款后奖励及承担同步归零，重复回执不重复冲正，库存恢复100');
await fs.writeFile(path.join(resultsDir,'invitation-result.json'),JSON.stringify({testedAt:new Date().toISOString(),shopId:shop,checks,orders:orders.map(o=>o.id)},null,2));console.log(JSON.stringify({passed:checks.length,checks}));
}finally{sql("UPDATE hx_shop SET status='INACTIVE' WHERE id="+shop+';')}
