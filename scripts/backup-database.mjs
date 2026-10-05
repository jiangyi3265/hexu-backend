import fs from 'node:fs/promises'
import path from 'node:path'
import {fileURLToPath} from 'node:url'
import {spawnSync} from 'node:child_process'
import {randomBytes,createCipheriv,createDecipheriv} from 'node:crypto'
import {gzipSync,gunzipSync} from 'node:zlib'
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..')
const runtime=path.join(root,'.hexu-local')
const source='hexu_integration'
const config=await fs.readFile(path.join(root,'ruoyi-admin/src/main/resources/application-druid.yml'),'utf8')
function resolveSpringValue(raw){
 const value=raw.trim().replace(/^['"]|['"]$/g,'')
 const match=value.match(/^\$\{([A-Za-z_][A-Za-z0-9_]*)(?::([^}]*))?\}$/)
 if(!match)return value
 return process.env[match[1]] ?? match[2] ?? ''
}
const user=resolveSpringValue(config.match(/^\s+username:\s*(.+)$/m)[1])
const password=resolveSpringValue(config.match(/^\s+password:\s*(.*)$/m)[1])
const configuredPort=config.match(/jdbc:mysql:\/\/[^/]+:(?:\$\{HEXU_MYSQL_PORT:(\d+)\}|(\d+))\//)
if(!configuredPort)throw Error('Database port is not configured in application-druid.yml')
const port=Number(process.env.HEXU_MYSQL_PORT ?? configuredPort[1] ?? configuredPort[2])
if(!Number.isInteger(port)||port<1||port>65535)throw Error('Invalid database port')
const mysqlDir=process.env.HEXU_MYSQL_BIN||''
function invoke(program,args,input){const r=spawnSync(path.join(mysqlDir,program+(process.platform==='win32'?'.exe':'')),['-u',user,'-h','127.0.0.1','-P',String(port),'--default-character-set=utf8mb4',...args],{input,encoding:'utf8',windowsHide:true,env:{...process.env,MYSQL_PWD:password},maxBuffer:256*1024*1024,timeout:120000});if(r.status!==0)throw Error(program+' failed: '+(r.error?.message||r.stderr));return r.stdout}
function sql(database,query){return invoke('mysql',['-N',database],"SET time_zone='+00:00';\n"+query).trim()}
function manifest(database){const tables=sql(database,'SHOW TABLES;').split(/\r?\n/).filter(Boolean);const records={};for(const table of tables){if(!/^[a-zA-Z0-9_]+$/.test(table))throw Error('Unexpected table name');const count=Number(sql(database,'SELECT COUNT(*) FROM '+table));const checksum=sql(database,'CHECKSUM TABLE '+table+' EXTENDED;').split('\t').at(-1);records[table]={count,checksum}}return records}
await fs.mkdir(runtime,{recursive:true})
const keyPath=path.join(runtime,'backup-key.bin')
const action=process.argv[2]||'backup'
if(action==='backup'){
 if(sql(source,'SELECT DATABASE();')!==source)throw Error('Source database mismatch')
 let key;try{key=await fs.readFile(keyPath)}catch(e){if(e.code!=='ENOENT')throw e;key=randomBytes(32);await fs.writeFile(keyPath,key,{flag:'wx',mode:0o600})}
 const dump=invoke('mysqldump',['--single-transaction','--skip-lock-tables','--hex-blob','--set-gtid-purged=OFF','--no-tablespaces','--skip-add-drop-table','--skip-triggers',source])
 if(/(?:^|\n)\s*(?:USE\s|CREATE\s+DATABASE|DROP\s+DATABASE)/i.test(dump))throw Error('Dump includes database switching statements')
 const data={version:1,database:source,createdAt:new Date().toISOString(),manifest:manifest(source),sql:dump}
 const iv=randomBytes(12),cipher=createCipheriv('aes-256-gcm',key,iv)
 const encrypted=Buffer.concat([cipher.update(gzipSync(Buffer.from(JSON.stringify(data)))),cipher.final()])
 const folder=path.join(runtime,'backups');await fs.mkdir(folder,{recursive:true})
 const file=path.join(folder,source+'-'+Date.now()+'.hxb')
 await fs.writeFile(file,Buffer.concat([Buffer.from('HEXUBAK1'),iv,cipher.getAuthTag(),encrypted]),{flag:'wx',mode:0o600})
 const result={at:data.createdAt,database:source,file,encrypted:true,tables:Object.keys(data.manifest).length,bytes:(await fs.stat(file)).size}
 await fs.writeFile(path.join(runtime,'last-backup.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result))
}else if(action==='restore'){
 const file=process.argv[3];if(!file)throw Error('Provide an encrypted .hxb backup')
 const target=process.argv[4]||'hexu_restore_'+Date.now();if(!/^hexu_restore_[a-zA-Z0-9_]+$/.test(target))throw Error('Restore target must be a new hexu_restore_* database')
 const exists=invoke('mysql',['-N'],"SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='"+target+"'").trim();if(exists!=='0')throw Error('Restore target already exists; refusing to overwrite')
 const bytes=await fs.readFile(file),key=await fs.readFile(keyPath);if(bytes.subarray(0,8).toString()!=='HEXUBAK1')throw Error('Unsupported backup format')
 const decipher=createDecipheriv('aes-256-gcm',key,bytes.subarray(8,20));decipher.setAuthTag(bytes.subarray(20,36));const data=JSON.parse(gunzipSync(Buffer.concat([decipher.update(bytes.subarray(36)),decipher.final()])).toString())
 if(data.database!==source||data.version!==1||/(?:^|\n)\s*(?:USE\s|CREATE\s+DATABASE|DROP\s+DATABASE)/i.test(data.sql))throw Error('Backup scope is invalid')
 invoke('mysql',[],"CREATE DATABASE "+target+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;")
 sql(target,data.sql)
 const restored=manifest(target),mismatches=Object.keys(data.manifest).filter(t=>JSON.stringify(restored[t])!==JSON.stringify(data.manifest[t]))
 const result={restoredAt:new Date().toISOString(),sourceBackupAt:data.createdAt,targetDatabase:target,tables:Object.keys(restored).length,mismatches,status:mismatches.length?'MISMATCH':'PASS',note:'逐表行数与CHECKSUM EXTENDED核对；不覆盖运行数据库，恢复库保留供复核。备份时若有并发写入，清单与事务快照可能不一致，需在暂停写入窗口重做演练。'}
 await fs.writeFile(path.join(runtime,'backup-recovery-result.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result));if(mismatches.length)process.exitCode=1
}else throw Error('Usage: node scripts/backup-database.mjs backup | restore <file.hxb> [hexu_restore_name]')
