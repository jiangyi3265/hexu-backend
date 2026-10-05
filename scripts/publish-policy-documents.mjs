import {readFileSync} from 'node:fs'
import {spawnSync} from 'node:child_process'

const [user,port='3307']=process.argv.slice(2)
if(!/^[A-Za-z0-9_]+$/.test(user||'')||!/^[0-9]{1,5}$/.test(port)||!process.env.MYSQL_PWD){
 throw new Error('Provide the local database user, port and MYSQL_PWD')
}

const version='2026.10.05.2'
const operator=JSON.parse(readFileSync(new URL('../policies/operator-info.json',import.meta.url),'utf8'))
for(const field of ['name','unifiedSocialCreditCode','contactAddress','supportEmail']){
 if(typeof operator[field]!=='string'||!operator[field].trim())throw new Error(`Missing operator ${field}`)
}
const operatorDetails=`\n运营主体：${operator.name}\n统一社会信用代码：${operator.unifiedSocialCreditCode}\n联系地址：${operator.contactAddress}\n客服邮箱：${operator.supportEmail}\n小程序联系入口：我的－客服中心\n`
const documents={
 USER_AGREEMENT:'user-agreement.txt',
 PRIVACY_POLICY:'privacy-policy.txt',
 THIRD_PARTY_SHARING:'third-party-sharing.txt',
 AGENT_AGREEMENT:'agent-agreement.txt',
 PURCHASE_AGREEMENT:'purchase-agreement.txt',
 CUSTOMER_AUTHORIZATION:'customer-authorization.txt',
 WITHDRAWAL_AGREEMENT:'withdrawal-agreement.txt'
}
const quote=value=>`'${value.replaceAll('\\','\\\\').replaceAll("'","''")}'`
const statements=['START TRANSACTION']
for(const [type,file] of Object.entries(documents)){
 const template=readFileSync(new URL(`../policies/${file}`,import.meta.url),'utf8').trim()
 const [title,declaredVersion]=template.split(/\r?\n/,2)
 if(declaredVersion!==`生效版本：${version}`||template.split('{{OPERATOR_DETAILS}}').length!==2)throw new Error(`${type} policy template is invalid`)
 const content=template.replace('{{OPERATOR_DETAILS}}',operatorDetails)
 const id=`POL${version.replaceAll('.','')}${type}`
 const t=quote(type),i=quote(id)
 statements.push(`UPDATE hx_policy SET status='SUPERSEDED' WHERE policy_type=${t} AND status='PUBLISHED' AND (version LIKE '%SANDBOX%' OR title LIKE '%测试%' OR title LIKE '%非正式%' OR (shop_id=0 AND version IN ('2026.10.05','2026.10.05.1')))`)
 statements.push(`INSERT INTO hx_policy(id,shop_id,policy_type,version,title,content,status) SELECT ${i},0,${t},${quote(version)},${quote(title)},${quote(content)},'PUBLISHED' WHERE NOT EXISTS (SELECT 1 FROM hx_policy WHERE shop_id=0 AND policy_type=${t} AND status='PUBLISHED') AND NOT EXISTS (SELECT 1 FROM hx_policy WHERE id=${i})`)
 statements.push(`INSERT INTO hx_audit(shop_id,actor_id,action,reference_id,before_json,after_json,actor_kind,source,request_id,ip_masked) SELECT 0,0,'POLICY_PUBLISH',${i},NULL,JSON_OBJECT('type',${t},'version',${quote(version)}),'SYSTEM','MIGRATION','','' FROM hx_policy WHERE id=${i} AND NOT EXISTS (SELECT 1 FROM hx_audit WHERE action='POLICY_PUBLISH' AND reference_id=${i})`)
}
statements.push('COMMIT')
const result=spawnSync('mysql',['-u',user,'-h','127.0.0.1','-P',port,'--default-character-set=utf8mb4','hexu_integration'],{
 input:statements.join(';\n')+';\n',encoding:'utf8',env:process.env
})
if(result.status!==0)throw new Error(`Policy publication failed: ${result.stderr.trim()}`)
console.log(`Checked ${Object.keys(documents).length} current policy documents`)
