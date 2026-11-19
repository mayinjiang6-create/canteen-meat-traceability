/**
 * 核心逻辑验证：抽取 app.html 里真正运行的 <script id="app-core"> 执行，
 * 验证业务规则、状态流转与智能体工具调用轨迹。
 *
 *   node verify_app.mjs
 */
import { readFileSync } from 'node:fs'
import { createContext, runInContext } from 'node:vm'
import { performance } from 'node:perf_hooks'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const html = readFileSync(join(here, 'app.html'), 'utf8')
const m = html.match(/<script id="app-core">([\s\S]*?)<\/script>/)
if (!m) throw new Error('app.html 里找不到 app-core 脚本')

const store = new Map()
const localStorage = {
  getItem: k => store.has(k) ? store.get(k) : null,
  setItem: (k, v) => store.set(k, String(v)),
  removeItem: k => store.delete(k),
}
const win = {}
let fetchCalls = 0
const mockFetch = async (url, opts) => {
  fetchCalls++
  return { ok: true, json: async () => ({ choices: [{ message: {
    content: '[T1] 这是由 LLM 依据工具结果组织的答案（mock 验证，未调用真实模型）'
  } }] }) }
}
const ctx = createContext({ window: win, localStorage, performance, console,
  Math, Date, JSON, Map, Set, Array, Object, String, Number, RegExp,
  parseInt, parseFloat, isNaN, fetch: mockFetch, AbortController,
  setTimeout, clearTimeout })
runInContext(m[1], ctx)

const C = win.APP_CORE
if (!C) throw new Error('核心脚本没有导出 APP_CORE')
const { S, BIZ, TOOLS, runAgent } = C
let pass = 0, fail = 0
function check(name, cond, detail = '') {
  if (cond) { pass++; console.log('  PASS ' + name + (detail ? '  ' + detail : '')) }
  else { fail++; console.log('  FAIL ' + name + '  ' + detail) }
}

console.log('=== 1. 数据快照完整性 ===')
const expect = { suppliers: 26, purchases: 71, acceptances: 33, processes: 41,
  sources: 52, sales: 16, coldChain: 10, abnormals: 7, recalls: 3, events: 186 }
for (const [k, n] of Object.entries(expect))
  check(k + ' = ' + n, S[k].length === n, '实际 ' + S[k].length)

console.log('\n=== 2. 业务规则（与后端口径一致）===')
const active = S.suppliers.find(x => x.status === 'ACTIVE')
const blocked = S.suppliers.find(x => x.status !== 'ACTIVE')
check('新建采购：检疫证号必填',
  !!BIZ.addPurchase({ supplier_id: active.id, quantity: '10', quarantine_no: '' }))
if (blocked) check('新建采购：非 ACTIVE 供应商被拒',
  !!BIZ.addPurchase({ supplier_id: blocked.id, quantity: '10', quarantine_no: 'JY-X' }),
  '供应商状态 ' + blocked.status)
const before = S.purchases.length
check('新建采购：正常成功', !BIZ.addPurchase({
  supplier_id: active.id, quantity: '60.000', quarantine_no: 'JY-VERIFY-001' }),
  '新增 ' + (S.purchases.length - before) + ' 条')
const fresh = S.purchases[0]
check('新建采购：状态 PENDING_ACCEPT', fresh.status === 'PENDING_ACCEPT')
check('验收：不合格未填原因被拒', !!BIZ.accept(fresh.id, { result: 'FAIL' }))
check('验收：合格通过', !BIZ.accept(fresh.id, { result: 'PASS', temperature: '3.20', inspector: '验证员' }))
check('验收后状态 PASSED', fresh.status === 'PASSED')
check('重复验收被拒', !!BIZ.accept(fresh.id, { result: 'PASS' }))

check('加工：未验收来源被拒',
  !!BIZ.addProcess({ src1: S.purchases.find(x => x.status === 'PENDING_ACCEPT')?.id,
    qty1: '1', output_qty: '1' }))
check('加工：超量被拒',
  !!BIZ.addProcess({ src1: fresh.id, qty1: '999', output_qty: '1' }))
check('加工：产出大于投入被拒',
  !!BIZ.addProcess({ src1: fresh.id, qty1: '10', output_qty: '999' }))
check('加工：同一来源重复引用被拒',
  !!BIZ.addProcess({ src1: fresh.id, qty1: '1', src2: fresh.id, qty2: '1', output_qty: '1' }))
const pb = S.processes.length
check('加工：正常多来源投料成功',
  !BIZ.addProcess({ src1: fresh.id, qty1: '10', output_qty: '8' }),
  '新增 ' + (S.processes.length - pb) + ' 个')
const pr = S.processes[0]
check('上架 CREATED → ON_SALE', !BIZ.onsale(pr.id) && pr.status === 'ON_SALE')
check('销售：超量被拒', !!BIZ.addSale({ process_id: pr.id, quantity: '999' }))
check('销售：正常成功', !BIZ.addSale({ process_id: pr.id, quantity: '2.000' }))
const coldBefore = S.abnormals.length
check('冷链：超限登记自动开异常工单',
  !BIZ.addCold({ batch_id: fresh.id, temperature: '9.00' }) &&
  S.abnormals.length === coldBefore + 1)
check('召回：联动销售置 RECALLED',
  !BIZ.addRecall({ process_id: pr.id, reason: '验证' }) &&
  S.sales.filter(x => x.process_batch_id === pr.id).every(x => x.status === 'RECALLED'))
check('召回后禁止销售', !!BIZ.addSale({ process_id: pr.id, quantity: '1' }))

console.log('\n=== 3. 智能体：工具集与执行轨迹 ===')
check('工具集 >= 10 个', Object.keys(TOOLS).length >= 10, '实际 ' + Object.keys(TOOLS).length)
const questions = [
  '哪些批次冷链温度超限？涉及哪些供应商？是否需要召回？',
  '最近有哪些异常和召回？分别处理到哪一步了？',
  'PB20261118001 的验收结论和检疫证号是什么？',
  '这个系统的记录核验状态有哪几类？',
  '目前有哪些供应商？',
  '加工投料的多来源规则是什么？',
  '这批肉吃了安全吗？',
  '帮我预测明天的销量',
]
let workflow = 0, refused = 0
for (const q of questions) {
  const r = runAgent(q)
  if (r.mode === 'workflow') workflow++
  if (r.refused) refused++
  const ok = r.text && r.text.length > 10 && (r.refused || r.trace.length > 0)
  check('问句可处理：' + q.slice(0, 22), ok,
    (r.refused ? '拒答/' + r.reason : r.modeName + '/' + r.trace.length + '步/' + r.tools.length + '工具'))
}
check('存在多步确定性工作流', workflow >= 2, 'workflow ' + workflow + ' 个')
check('越界问题被拒答', refused >= 2, '拒答 ' + refused + ' 个')
const complex = runAgent(questions[0])
check('复杂问题轨迹 >= 4 步', complex.trace.length >= 4, '实际 ' + complex.trace.length + ' 步')
check('每步都有 thought / tool / observation',
  complex.trace.every(s => s.thought && s.tool && s.observation))
check('答案标注了工具依据', /依据：\[T\d+\]/.test(complex.text))

console.log('\n=== 4. 可插拔 LLM 生成层（mock，不调用真实模型、不产生费用）===')
check('默认离线确定性模式', C.llmActive() === false)
C.setLLMConfig({ base: 'http://mock.local/v1', key: 'sk-mock-key', model: 'mock-model', enabled: true })
check('启用后切换到 LLM 增强模式', C.llmActive() === true)
const base = runAgent('哪些批次冷链温度超限？涉及哪些供应商？是否需要召回？')
const polished = await C.polishWithLLM(base, '哪些批次冷链温度超限？涉及哪些供应商？是否需要召回？')
check('LLM 组织答案成功', polished.llmUsed === true && polished.text.includes('[T1]'),
  'fetch 调用 ' + fetchCalls + ' 次')
check('保留确定性版本可对照', polished.deterministicText === base.text)
check('轨迹与工具结果未被模型改动',
  polished.trace.length === base.trace.length && polished.tools.join() === base.tools.join())
const refusedCase = runAgent('这批肉吃了安全吗？')
const noCall = await C.polishWithLLM(refusedCase, '这批肉吃了安全吗？')
check('拒答不调用 LLM', noCall.llmUsed === false && fetchCalls === 1)
ctx.fetch = async () => { throw new Error('mock 服务不可用') }
const fallback = await C.polishWithLLM(base, '再问一次')
check('LLM 失败自动回退到确定性模板',
  fallback.llmUsed === false && fallback.text === base.text && !!fallback.llmError)
ctx.fetch = mockFetch
C.clearLLMConfig()
check('清除密钥后回到离线模式', C.llmActive() === false)

console.log('\n=== 5. 自由提问覆盖（计算类 + LLM 工具规划器，mock）===')
const calc = runAgent('所有批次的肉类一共有多少斤？')
check('计算类问题不再拒答', !calc.refused && calc.tools.includes('compute_stats'),
  calc.refused ? calc.reason : calc.tools.join(','))
const kg = S.purchases.reduce((s, x) => s + Number(x.quantity), 0)
check('答案含 kg→斤 换算且数值由代码精确计算',
  calc.text.includes('斤') && calc.text.includes((kg * 2).toFixed(3)),
  '采购总量 ' + kg.toFixed(3) + ' kg → ' + (kg * 2).toFixed(3) + ' 斤')
const calc2 = runAgent('帮我汇总一下采购、加工和销售的数量')
check('换一种说法也能命中汇总工具', !calc2.refused && calc2.tools.includes('compute_stats'))

C.setLLMConfig({ base: 'http://mock.local/v1', key: 'sk-mock', model: 'mock', enabled: true })
ctx.fetch = async (url, opts) => {
  const body = JSON.parse(opts.body)
  const isPlanner = body.messages[0].content.includes('工具规划器')
  return { ok: true, json: async () => ({ choices: [{ message: { content: isPlanner
    ? JSON.stringify({ steps: [
        { thought: '先汇总全部数量', tool: 'compute_stats', args: {} },
        { thought: '再查供应商名单', tool: 'list_suppliers', args: {} },
        { thought: '这个工具不存在', tool: 'hack_tool', args: {} }
      ] })
    : '[T1] 这是按工具结果组织的自然语言答案' } }] }) }
}
const planned = await C.planWithLLM('给我一份业务整体概览')
check('LLM 规划器产出步骤', !!planned && planned.steps.length >= 1,
  planned ? planned.name : 'null')
check('非法工具被白名单过滤',
  !!planned && planned.steps.every(s => !!TOOLS[s.tool]) && planned.steps.length === 2)
const smart = await C.runAgentSmart('给我一份业务整体概览')
check('自由提问不再落到"未匹配到可用工具"', !smart.refused,
  smart.refused ? smart.reason : smart.modeName)
check('规划方式标记为 LLM 工具规划', smart.planner === 'LLM 工具规划')
check('轨迹仍由代码执行并记录', smart.trace.length >= 2 && smart.tools.includes('compute_stats'))
check('答案仍由 LLM 组织且带依据', smart.llmUsed === true && smart.text.includes('[T1]'))
const guardCase = await C.runAgentSmart('帮我预测明天的销量')
check('越界护栏不交给 LLM 规划器',
  guardCase.refused === true && guardCase.reason === '越界拦截')
ctx.fetch = async () => { throw new Error('mock 规划器不可用') }
const fb = await C.runAgentSmart('给我一份业务整体概览')
check('规划器失败回退到友好拒答', fb.refused === true && !!fb.plannerFallback)
ctx.fetch = mockFetch
C.clearLLMConfig()
check('工具集扩为 12 个', Object.keys(TOOLS).length === 12)

console.log('\n=== 结论 ===')
console.log(`  PASS ${pass} / FAIL ${fail}`)
process.exit(fail ? 1 : 0)
