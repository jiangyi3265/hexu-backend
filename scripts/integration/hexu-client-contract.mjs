// 联调脚本的客户端协议，与小程序结算页（data/backend.js）和 G23 拣货流程保持一致：
// - 普通商城下单前读取当前购买协议，并按 /hexu/app/quote 确认应付金额（orderConsent、agreementPolicyId、agreementVersion、expectedTotal）；
// - 发货前先开始并完成拣货（hexu.fulfillment.require-pick 默认开启）。
// 调用方已显式给出的字段不覆盖。补充步骤被拒时交回原请求，由服务端返回真实业务原因。
import {randomUUID} from 'node:crypto'

const ORDER_CREATE = '/hexu/app/commands/order-create'
const SHIP = '/hexu/app/management/ship'
const WHOLESALE_QUOTE = '批发订单请使用采购结算'
const PICK_ALREADY_STARTED = '拣货已经开始或完成'

export async function hexuFetch(url, init = {}) {
  const target = new URL(url)
  if ((init.method || 'GET').toUpperCase() === 'POST') {
    if (target.pathname === ORDER_CREATE) return createOrder(target, init)
    if (target.pathname === SHIP) await pickBeforeShip(target, init)
  }
  return fetch(url, init)
}

async function send(target, path, init, body) {
  const authorization = init.headers?.Authorization
  const response = await fetch(new URL(path, target), {
    method: body === undefined ? 'GET' : 'POST',
    headers: {'Content-Type': 'application/json', 'Idempotency-Key': randomUUID(), ...(authorization ? {Authorization: authorization} : {})},
    ...(body === undefined ? {} : {body: JSON.stringify(body)})
  })
  const result = await response.clone().json().catch(() => null)
  return {response, ok: response.ok && result?.code === 200, data: result?.data, msg: result?.msg}
}

async function createOrder(target, init) {
  const body = JSON.parse(init.body || '{}')
  if (body.orderConsent === undefined) {
    const policies = await send(target, '/hexu/app/policies?shopId=' + encodeURIComponent(body.shopId), init)
    const agreement = policies.ok ? policies.data?.PURCHASE_AGREEMENT : null
    if (agreement?.id && agreement?.version) Object.assign(body, {orderConsent: true, agreementPolicyId: agreement.id, agreementVersion: agreement.version})
  }
  if (body.expectedTotal === undefined) {
    const quote = await send(target, '/hexu/app/quote', init, body)
    if (quote.ok) body.expectedTotal = quote.data.total
    // 批发商城不走零售报价，也不要求 expectedTotal；其他拒绝原因与下单校验一致，直接返回。
    else if (quote.msg !== WHOLESALE_QUOTE) return quote.response
  }
  return fetch(target, {...init, body: JSON.stringify(body)})
}

async function pickBeforeShip(target, init) {
  const {id, shopId} = JSON.parse(init.body || '{}')
  if (!id) return
  const base = '/hexu/app/management/fulfillment/pick/' + encodeURIComponent(id) + '/'
  const start = await send(target, base + 'start', init, {shopId})
  // 已开始的继续完成；已完成或不可拣货时不在此报错，交给发货接口返回原因。
  if (start.ok || start.msg === PICK_ALREADY_STARTED) await send(target, base + 'complete', init, {shopId})
}
