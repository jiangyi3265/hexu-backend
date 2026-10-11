// 刷新联调脚本使用的平台后台会话（.hexu-local/admin-auth.json）。
// 平台会话 30 分钟无操作即失效；此脚本走正常登录接口换取新会话，只保存会话令牌，不保存密码。
// 用法（后端仓库根目录）：node scripts/integration/save-admin-session.mjs
//   账号默认 admin，可用 HEXU_ADMIN_USERNAME 指定；密码在终端隐藏输入（无终端时读取 HEXU_ADMIN_PASSWORD）。
import fs from 'node:fs/promises'
import path from 'node:path'
import {fileURLToPath} from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const file = path.join(root, '.hexu-local/admin-auth.json')
const base = 'http://127.0.0.1:8088'

function askHidden(prompt) {
  const input = process.stdin
  if (!input.isTTY) throw new Error('请在终端中运行，或设置 HEXU_ADMIN_PASSWORD')
  return new Promise((resolve, reject) => {
    let value = ''
    const finish = error => {
      input.setRawMode(false); input.pause(); input.removeListener('data', onData); process.stdout.write('\n')
      error ? reject(error) : resolve(value)
    }
    const onData = chunk => {
      for (const ch of chunk) {
        if (ch === '\r' || ch === '\n') return finish()
        if (ch === '\u0003') return finish(new Error('已取消'))
        value = ch === '\u007f' || ch === '\b' ? value.slice(0, -1) : value + ch
      }
    }
    process.stdout.write(prompt)
    input.setRawMode(true); input.setEncoding('utf8'); input.resume(); input.on('data', onData)
  })
}

const username = process.env.HEXU_ADMIN_USERNAME || 'admin'
const password = process.env.HEXU_ADMIN_PASSWORD || await askHidden(`平台后台账号 ${username} 的密码：`)
const login = await (await fetch(base + '/login', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({username, password})})).json()
if (login.code !== 200 || !login.token) throw new Error('登录失败：' + login.msg)
const info = await (await fetch(base + '/getInfo', {headers: {Authorization: 'Bearer ' + login.token}})).json()
if (info.code !== 200) throw new Error('会话校验失败：' + info.msg)

let state = {}
try { state = JSON.parse(await fs.readFile(file, 'utf8')) } catch (e) { if (e.code !== 'ENOENT') throw e }
const cookie = {name: 'Admin-Token', value: login.token, domain: '127.0.0.1', path: '/', expires: -1, httpOnly: false, secure: false, sameSite: 'Lax'}
await fs.writeFile(file, JSON.stringify({cookies: [...(state.cookies || []).filter(c => c.name !== 'Admin-Token'), cookie], origins: state.origins || []}, null, 2))
console.log(`已保存 ${info.user?.userName || username} 的平台会话：${path.relative(root, file)}（30 分钟内持续使用会自动续期）`)
