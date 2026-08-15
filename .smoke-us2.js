/* US2 Checkpoint 冒烟验收（V2–V6/V8 + 13 个新执行器首次真实运行）
 * 用法: node .smoke-us2.js
 */
const BASE = 'http://localhost:8081/api'
const CLIENT = 'smoke-check-001'
let failures = 0

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function api(path, opts) {
  const resp = await fetch(BASE + path, opts)
  const body = await resp.json().catch(() => ({}))
  return { httpStatus: resp.status, body }
}

function post(path, payload) {
  return api(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) })
}

function del(path) {
  return api(path, { method: 'DELETE' })
}

function get(path) {
  return api(path)
}

function check(name, ok, extra = '') {
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${extra ? '  [' + extra + ']' : ''}`)
  if (!ok) failures++
}

async function runToEnd(scenarioId, params, seed) {
  const created = await post('/runs', { scenarioId, clientId: CLIENT, params, seed })
  if (created.httpStatus !== 202 && created.httpStatus !== 200) return created
  const runId = created.body.data.runId
  let status = null
  for (let i = 0; i < 80; i++) {
    const s = await get(`/runs/${runId}?clientId=${CLIENT}`)
    status = s.body.data
    if (status.status !== 'RUNNING') break
    await sleep(300)
  }
  const result = await get(`/runs/${runId}/result?clientId=${CLIENT}`)
  return { httpStatus: 200, body: { runId, status, result: result.body.data } }
}

function buildParams(detail) {
  const params = {}
  for (const p of detail.params) {
    if (p.default !== undefined && p.default !== null) {
      params[p.key] = JSON.parse(JSON.stringify(p.default))
    } else if (p.type === 'bool') {
      params[p.key] = false
    } else if (p.type === 'dist') {
      const group = {}
      for (const f of p.fields ?? []) group[f.key] = f.default ?? 0
      params[p.key] = group
    }
  }
  return params
}

async function main() {
  // ---- 目录 ----
  const catalog = await get('/scenarios')
  check('C1 目录: 15 个场景', catalog.body.data.length === 15, `count=${catalog.body.data.length}`)

  // ---- V2: EOQ 教材算例 ----
  const eoqDetail = (await get('/scenarios/CH2-003')).body.data
  const eoqParams = { annual_demand: 10000, order_cost: 100, holding_cost: 2, lead_time: 7 }
  const eoq = await runToEnd(eoqDetail.id, eoqParams, 1)
  if (eoq.httpStatus !== 200) { check('V2 EOQ 创建运行失败', false, JSON.stringify(eoq.body).slice(0, 300)); return }
  const eoqOut = Object.fromEntries((eoq.body.result?.outputs ?? []).map((o) => [o.key, o.value]))
  check('V2 EOQ 运行 COMPLETED', eoq.body.status?.status === 'COMPLETED', eoq.body.status?.status ?? eoq.body.status)
  check('V2 EOQ=1000', Math.abs(Number(eoqOut.q_star ?? -1) - 1000) < 1e-6, `q_star=${eoqOut.q_star}`)
  const curve = eoqOut.annual_cost_curve
  const curveMin = curve && Array.isArray(curve.series?.[0]?.data)
    ? Math.min(...curve.series[0].data.filter((v) => v != null))
    : -1
  check('V2 年总成本=2000', Math.abs(curveMin - 2000) < 1e-6, `curveMin=${curveMin}`)

  // ---- V3: 可复现性（啤酒游戏 seed=42 两次一致，43 不同） ----
  const bgDetail = (await get('/scenarios/CH8-001')).body.data
  const bgParams = buildParams(bgDetail)
  const bg1 = await runToEnd(bgDetail.id, bgParams, 42)
  const bg2 = await runToEnd(bgDetail.id, bgParams, 42)
  const bg3 = await runToEnd(bgDetail.id, bgParams, 43)
  const j1 = JSON.stringify(bg1.body.result?.outputs ?? [])
  const j2 = JSON.stringify(bg2.body.result?.outputs ?? [])
  const j3 = JSON.stringify(bg3.body.result?.outputs ?? [])
  check('V3 seed=42 两次结果一致', j1 === j2)
  check('V3 seed=43 结果不同', j1 !== j3)

  // ---- V5: 分步日志 ----
  check('V5 啤酒游戏步骤数>5', (bg1.body.result?.steps ?? []).length >= 5, `steps=${(bg1.body.result?.steps ?? []).length}`)
  check('V5 步骤带中文消息与数据', (bg1.body.result.steps ?? []).every((s) => s.message && s.stepNo > 0))

  // ---- V8: 终态 DELETE → 409 ----
  const delResp = await del(`/runs/${bg1.body.runId}?clientId=${CLIENT}`)
  check('V8 终态取消返回 409', delResp.httpStatus === 409, `http=${delResp.httpStatus}`)
  const delOther = await del(`/runs/${bg1.body.runId}?clientId=someone-else`)
  check('V8 clientId 不匹配返回 403', delOther.httpStatus === 403, `http=${delOther.httpStatus}`)

  // ---- V4: 非法参数拦截 ----
  const bad = await post('/runs', { scenarioId: eoqDetail.id, clientId: CLIENT, params: { annual_demand: -5 }, seed: 1 })
  check('V4 非法参数返回 400 + 原因', bad.httpStatus === 400 && !!bad.body.message,
    `http=${bad.httpStatus} msg=${bad.body.message}`)
  const badEnum = await post('/runs', {
    scenarioId: bgDetail.id, clientId: CLIENT,
    params: { ...bgParams, total_rounds: 1 }, seed: 1,
  })
  check('V4 约束校验 400（total_rounds=1）', badEnum.httpStatus === 400, `http=${badEnum.httpStatus} msg=${badEnum.body.message}`)

  // ---- 13 个新执行器默认参数真实运行 ----
  const newExecutors = [
    'CH2-002', 'CH8-004', 'CH1-002', 'CH1-004', 'CH3-004', 'CH4-006',
    'CH5-001', 'CH6-002', 'CH7-002', 'CH9-001', 'CH10-001', 'CH11-001', 'CH11-004',
  ]
  for (const moduleId of newExecutors) {
    const detail = (await get(`/scenarios/${moduleId}`)).body.data
    const params = buildParams(detail)
    const r = await runToEnd(detail.id, params, 42)
    const status = r.body.status?.status ?? 'CREATE_FAILED'
    const outputs = r.body.result?.outputs ?? []
    const steps = r.body.result?.steps ?? []
    const declared = detail.outputs.length
    const okOut = outputs.length === declared
    check(`${moduleId} ${detail.engineKey}: COMPLETED + ${declared} 输出 + 步骤`,
      status === 'COMPLETED' && okOut && steps.length > 0,
      `status=${status} outputs=${outputs.length}/${declared} steps=${steps.length} err=${r.body.result?.errorMessage ?? ''}`)
    if (status !== 'COMPLETED' || !okOut) {
      console.log('    detail:', JSON.stringify(r.body.result ?? r.body).slice(0, 400))
    }
  }

  console.log(failures === 0 ? '\n==== 全部通过 ====' : `\n==== ${failures} 项失败 ====`)
  process.exit(failures === 0 ? 0 : 1)
}

main().catch((e) => {
  console.error('SMOKE CRASH', e)
  process.exit(2)
})
