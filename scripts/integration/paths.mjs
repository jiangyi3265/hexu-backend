// 联调脚本放在后端仓库 scripts/integration/；后端内路径以仓库根目录为准（各脚本的 root）。
// 小程序仓库和联调结果目录在本仓库之外，按约定的同级目录查找，也可以用环境变量指定。
import fs from 'node:fs'
import path from 'node:path'
import {fileURLToPath} from 'node:url'

const backend = fileURLToPath(new URL('../../', import.meta.url))
const workspace = path.resolve(backend, '..')
const sibling = names => names.map(name => path.join(workspace, name)).find(dir => fs.existsSync(dir))

// 小程序仓库：HEXU_APP_DIR，或与后端仓库同级的常见目录名。
export const appDir = process.env.HEXU_APP_DIR
  ? path.resolve(process.env.HEXU_APP_DIR)
  : sibling(['分销批发中台小程序', 'hexu-app', 'fenxiao-app']) || path.join(workspace, 'hexu-app')

// 联调结果 JSON：HEXU_RESULTS_DIR，或工作区 docs/（与联调说明放在一起）；都没有时写入 .hexu-local/results。
export const resultsDir = process.env.HEXU_RESULTS_DIR
  ? path.resolve(process.env.HEXU_RESULTS_DIR)
  : sibling(['docs']) || path.join(backend, '.hexu-local', 'results')
fs.mkdirSync(resultsDir, {recursive: true})
