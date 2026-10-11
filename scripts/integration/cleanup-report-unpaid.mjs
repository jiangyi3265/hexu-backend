import fs from 'node:fs/promises'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {spawnSync} from 'node:child_process'
import {randomUUID} from 'node:crypto'
import assert from 'node:assert/strict'
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


const failed=sql("SELECT s.id,o.id,o.buyer_id FROM hx_shop s JOIN hx_order o ON o.shop_id=s.id WHERE s.name='报表联调专用商城' AND o.status IN ('UNPAID','CANCELLED') AND NOT EXISTS(SELECT 1 FROM hx_report_job j WHERE j.shop_id=s.id);");for(const row of failed.split('\n').filter(Boolean)){const [shop,id,buyer]=row.split('\t');const token=await login(Number(buyer));await call('/hexu/app/commands/order-cancel',{token,data:{shopId:Number(shop),id}});sql("UPDATE hx_shop SET status='INACTIVE' WHERE id="+Number(shop)+';');}console.log('Unpaid report fixture cancelled safely');