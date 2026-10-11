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
sql("INSERT INTO hx_member(id,name,authorized) VALUES ("+buyer+",'联动测试新代理甲',true),("+second+",'联动测试新代理乙',true); INSERT INTO hx_shop(id,name,county,kind,owner_id,merchant_no,payment_ready,settings) VALUES ("+shop+",'联动2加1自动化专用商城','测试县域','DEALER',101,'DEV_LINK',true,'{}'); INSERT INTO hx_agent(member_id,shop_id,rank_no,status) VALUES (101,"+shop+",3,'ACTIVE'); INSERT INTO hx_sku(id,shop_id,name,spec,asset,retail,cloud_price,center_price,owner_price,available) SELECT id,"+shop+",name,spec,asset,retail,cloud_price,center_price,owner_price,100 FROM hx_sku WHERE shop_id=2 AND id='stapler';");
const [owner,customer,friend]=await Promise.all([login(101),login(buyer),login(second)]);

const parentMember=shop+3;
sql("INSERT INTO hx_member(id,name,authorized) VALUES ("+parentMember+",'联动测试推荐人',true); INSERT INTO hx_agent(id,member_id,shop_id,parent_id,rank_no,approved_at) SELECT "+parentMember+","+parentMember+","+shop+",id,1,'2025-01-01' FROM hx_agent WHERE shop_id="+shop+" AND member_id=101; INSERT INTO hx_agent(id,member_id,shop_id,parent_id,rank_no,approved_at) VALUES ("+buyer+","+buyer+","+shop+","+parentMember+",1,CURRENT_TIMESTAMP),("+second+","+second+","+shop+","+parentMember+",1,CURRENT_TIMESTAMP);");
const parent=await login(parentMember);const image=new FormData();image.set('shopId',String(shop));image.set('purpose','LINK_CONFIRMATION');image.set('file',new Blob([await fs.readFile(path.join(appDir,'static/logo.png'))],{type:'image/png'}),'written-test.png');const proof=await (await fetch('http://127.0.0.1:8088/hexu/app/attachments',{method:'POST',headers:{Authorization:'Bearer '+owner},body:image})).json();assert.equal(proof.code,200,proof.msg);
const plan={kind:'link_campaign',name:'真实订单联动联调',enabled:true,writtenConfirmed:true,confirmationFile:proof.data.id,directCount:2,entryFee:0,relationshipMode:process.argv.includes('--relationship')?'EXIT_PASS_ONE':'KEEP_RELATION',passedChild:'EARLIEST',startsAt:Date.now()-60000,expiresAt:Date.now()+86400000,minOrderAmount:100,rewardCap:100,maxRewardPerOrder:1000,totalRewardBps:10000,skuIds:'stapler',directReward:50,orderReward:50,peerReward:50};
const campaign=await command(owner,'document-save',plan,true);
const status=token=>call('/hexu/app/link-campaign?shopId='+shop,{token});
const address={name:'联动联调',phone:'13800000000',region:'测试县域',detail:'数据库测试，不实际发货',consent:true};
const done=async(token)=>{const o=await command(token,'order-create',{items:[{id:'stapler',qty:1}],address});const p=await command(token,'payment-create',{id:o.id});await call('/hexu/dev/payment/'+p.id,{dev:true,data:{}});await command(owner,'ship',{id:o.id,carrier:'测试物流',tracking:'LINK'+Date.now()},true);await command(token,'order-receive',{id:o.id});return o;};
await command(parent,'link-join');const warmup=await done(parent);
if(process.argv.includes('--prepare-ui')){await fs.writeFile(path.join(root,'.hexu-local/link-ui-fixture.json'),JSON.stringify({shop,buyer,second,parentMember,campaignId:campaign.id,warmupId:warmup.id}));console.log(JSON.stringify({shop,buyer,second,parentMember,ready:true}));process.exit(0)}
const orders=[];
try{
 await command(customer,'link-join');await command(friend,'link-join');assert.equal((await status(parent)).validDirect,0);orders.push([parent,warmup]);const one=await done(customer),two=await done(friend);orders.push([customer,one],[friend,two]);const data=await status(parent);assert.equal(data.validDirect,2);assert.equal(data.participant.role,'BOSS');assert.equal(data.gross,100);assert.equal(Number(sql("SELECT rank_no FROM hx_agent WHERE id="+parentMember+';')),1);checks.push('两个审核通过的新代理完成真实商品订单，升级独立营销老板；核心云代理职级保持');
 const platformState=JSON.parse(await fs.readFile(path.join(root,'.hexu-local/admin-auth.json'),'utf8'));const admin=platformState.cookies.find(c=>c.name==='Admin-Token').value;const rows=await call('/hexu/admin/resources/linkParticipants?shopId='+shop,{token:admin});assert.equal(rows.find(p=>p.agent_id===parentMember).role,'BOSS');checks.push('代理活动中心、商家和平台共用营销身份及奖励明细');
 if(process.argv.includes('--relationship')){const documents=await call('/hexu/app/management/documents_link_relation?shopId='+shop,{token:owner});assert.equal(documents.length,1);await command(owner,'document-review',{id:documents[0].id,decision:'APPROVED',reason:'已核对营销升移和留人规则'},true);assert.equal((await status(parent)).participant.marketing_parent_id,null);assert.notEqual((await status(customer)).participant.marketing_parent_id,parentMember);assert.equal(Number(sql("SELECT parent_id FROM hx_agent WHERE id="+buyer+';')),parentMember);checks.push('真实MySQL关系审核完成升移和留人，只改营销关系，不改核心推荐链');}
 const r=await command(friend,'refund-apply',{id:two.id,lineId:two.items[0].id,qty:1,type:'RETURN',reason:'联动退款验证'});await command(owner,'refund-review',{id:r.id,approve:true},true);await command(owner,'return-inspect',{id:r.id,goodQty:1},true);await call('/hexu/dev/refund/'+r.id,{data:{},dev:true});await call('/hexu/dev/refund/'+r.id,{data:{},dev:true});orders.pop();assert.equal((await status(parent)).validDirect,1);assert.equal((await status(parent)).participant.role,'AGENT');assert.equal((await status(parent)).gross,100);checks.push('退款冲正营销收入和商城承担，老板资格回退，累计额度不重置');
 const update=await command(owner,'document-save',{...plan,id:campaign.id,enabled:false},true);assert.equal((await status(parent)).enabled,false);assert.equal((await status(parent)).gross,100);assert.ok((await status(parent)).records.length);checks.push('停用活动后仍可查询原奖励和关系历史，不允许新参与');
 for(const [token,o] of orders){const x=await command(token,'refund-apply',{id:o.id,lineId:o.items[0].id,qty:1,type:'RETURN',reason:'联调清理退款'});await command(owner,'refund-review',{id:x.id,approve:true},true);await command(owner,'return-inspect',{id:x.id,goodQty:1},true);await call('/hexu/dev/refund/'+x.id,{data:{},dev:true})}
 assert.equal(Number(sql("SELECT available FROM hx_sku WHERE shop_id="+shop+" AND id='stapler';")),100);
 await fs.writeFile(path.join(resultsDir,process.argv.includes('--relationship')?'link-relation-result.json':'link-campaign-result.json'),JSON.stringify({testedAt:new Date().toISOString(),shopId:shop,campaignId:campaign.id,checks},null,2));console.log(JSON.stringify({passed:checks.length,checks}));
}finally{sql("UPDATE hx_shop SET status='INACTIVE' WHERE id="+shop+';')}
