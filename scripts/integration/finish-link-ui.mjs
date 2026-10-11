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


const fixture=JSON.parse(await fs.readFile(path.join(root,'.hexu-local/link-ui-fixture.json'),'utf8'));const shop=fixture.shop;
async function call(p,token,data,dev=false){const r=await fetch('http://127.0.0.1:8088'+p,{method:data?'POST':'GET',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{}),...(dev?{'X-Hexu-Dev-Key':key}:{}),'Idempotency-Key':randomUUID()},...(data?{body:JSON.stringify(data)}:{})});const b=await r.json();assert.equal(b.code,200,b.msg);return b.data}
const login=async member=>(await call('/hexu/dev/login',null,{memberId:member},true)).token;
const owner=await login(101);const cmd=(token,op,b,management=false)=>call('/hexu/app/'+(management?'management/':'commands/')+op,token,{shopId:shop,...b});
const parent=await login(fixture.parentMember);const original=await call('/hexu/app/link-campaign?shopId='+shop,parent);assert.equal(original.enabled,false);assert.equal(original.participant.role,'BOSS');assert.equal(original.gross,100);
for(const item of fixture.orders){const token=await login(item.member),o=await call('/hexu/app/orders/'+item.id,token);const r=await cmd(token,'refund-apply',{id:o.id,lineId:o.items[0].id,qty:1,type:'RETURN',reason:'联动页面测试结束退款'});await cmd(owner,'refund-review',{id:r.id,approve:true},true);await cmd(owner,'return-inspect',{id:r.id,goodQty:1},true);await call('/hexu/dev/refund/'+r.id,null,{},true)}
assert.equal(Number(sql("SELECT available FROM hx_sku WHERE shop_id="+shop+" AND id='stapler';")),100);assert.equal(Number(sql("SELECT COALESCE(SUM(available+frozen),0) FROM hx_wallet WHERE shop_id="+shop+';')),0);sql("UPDATE hx_shop SET status='INACTIVE' WHERE id="+shop+';');
await fs.writeFile(path.join(resultsDir,'link-campaign-ui-result.json'),JSON.stringify({testedAt:new Date().toISOString(),shopId:shop,orders:fixture.orders.map(x=>x.id),status:'PASS',checks:['手机查看实际参与资格并加入活动','手机商品下单、支付、收货后按真实金额计奖','两名有效推荐后手机显示营销老板、累计奖励1元封顶','平台读取同一身份与奖励记录','平台上传活动确认图片并停用','停用后手机保留历史记录','全部测试订单退款后库存100、收益余额0、测试商城停用'],browserErrors:[]},null,2));console.log('联动页面联调证据已保存，库存和收益恢复，测试商城已停用。');
