/**
 * 把智能体的执行轨迹落成 JSONL（对应 JD 里的「监控告警 / 数据闭环 / AI 错误标签体系」）。
 *
 * 抽 app.html 里真正运行的 app-core 脚本，用 Node vm 跑一批有代表性的问题，
 * 每条记录：问题 / 是否拒答 / 拒答原因 / 工具链 / 每步耗时 / 总耗时 / 答案长度 / 引用编号。
 *
 *   node export_trace.mjs
 *
 * 产物 app/trace.jsonl 可直接被 jq / pandas / Excel 读，用于：
 *   - 找出被拦截最多的问法（错题本）
 *   - 看某个工具的平均耗时（性能回归）
 *   - 对比不同版本的工具链变化（改了路由有没有跑偏）
 */
import { readFileSync, writeFileSync } from 'node:fs'
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
  getItem: k => (store.has(k) ? store.get(k) : null),
  setItem: (k, v) => store.set(k, String(v)),
  removeItem: k => store.delete(k),
}
const win = {}
const ctx = createContext({
  window: win, localStorage, performance, console, Math, Date, JSON, Map, Set,
  Array, Object, String, Number, RegExp, parseInt, parseFloat, isNaN, setTimeout, clearTimeout,
})
runInContext(m[1], ctx)
const C = win.APP_CORE
if (!C) throw new Error('核心脚本没有导出 APP_CORE')

// 覆盖：多步工作流 / 单步查询 / 规则问答 / 精确计算 / 越界拒答 / 无锚点 / 未匹配
const questions = [
  '哪些批次冷链温度超限？涉及哪些供应商？是否需要召回？',
  '最近有哪些异常和召回？分别处理到哪一步了？',
  'PB20261118001 的验收结论和检疫证号是什么？',
  '这个系统的记录核验状态有哪几类？',
  '目前有哪些供应商？',
  '加工投料的多来源规则是什么？',
  '所有批次的肉类一共有多少斤？',
  '帮我汇总一下采购、加工和销售的数量',
  '有哪些销售记录？',
  '哪些批次被召回了？',
  '有没有验收不合格的？',
  'PB20261118035 的冷链温度是多少？',
  '哪些供应商是黑名单状态？',
  '一食堂 3 号窗口卖出去多少肉？',
  '这批肉吃了安全吗？',
  '帮我预测明天的销量',
  '今天天气怎么样？',
  '我想把这个系统改成外卖平台，你觉得可行吗？',
]

const lines = []
const started = new Date().toISOString()
for (const q of questions) {
  const t0 = performance.now()
  let r
  try {
    r = C.runAgent(q)
  } catch (err) {
    r = { refused: true, reason: '运行异常：' + err.message, trace: [], tools: [] }
  }
  const totalMs = Math.round((performance.now() - t0) * 100) / 100
  lines.push(JSON.stringify({
    ts: new Date().toISOString(),
    question: q,
    refused: !!r.refused,
    refusalReason: r.reason || null,
    mode: r.mode || null,
    modeName: r.modeName || null,
    tools: r.tools || [],
    steps: (r.trace || []).map(s => ({
      no: s.no, tool: s.tool, label: s.label, ms: s.ms,
      args: s.args && typeof s.args === 'object' ? Object.keys(s.args) : [],
    })),
    totalMs,
    answerChars: (r.text || '').length,
    citations: [...new Set((r.text || '').match(/\[T\d+\]/g) || [])],
  }))
}

writeFileSync(join(here, 'trace.jsonl'), lines.join('\n') + '\n', 'utf8')

const refused = lines.map(l => JSON.parse(l)).filter(r => r.refused)
const ok = lines.length - refused.length
const totalMs = lines.reduce((s, l) => s + JSON.parse(l).totalMs, 0)
console.log(`=== 轨迹导出 ===`)
console.log(`  起始时间 ${started}`)
console.log(`  问题 ${lines.length} 条：正常作答 ${ok} / 拒答 ${refused.length}`)
console.log(`  拒答原因：${JSON.stringify(refused.reduce((o, r) => (o[r.refusalReason] = (o[r.refusalReason] || 0) + 1, o), {}))}`)
console.log(`  累计耗时 ${Math.round(totalMs * 100) / 100} ms（含拒答）`)
console.log(`  产物：${join(here, 'trace.jsonl')}`)
