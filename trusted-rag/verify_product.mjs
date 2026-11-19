/**
 * 一致性验证：把 product.html 里真正运行的 <script id="rag-core"> 抽出来执行，
 * 用同一份公开评测集跑一遍，与 Python 版 evaluation.json 对比。
 *
 * 目的：证明这个离线页面不是「写死结果的静态页」，而是与 trusted_rag.py 同源。
 *
 *   node verify_product.mjs
 */
import { readFileSync } from 'node:fs'
import { createContext, runInContext } from 'node:vm'
import { performance } from 'node:perf_hooks'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const html = readFileSync(join(here, 'product.html'), 'utf8')
const m = html.match(/<script id="rag-core">([\s\S]*?)<\/script>/)
if (!m) throw new Error('product.html 里找不到 rag-core 脚本')

const win = {}
const ctx = createContext({ window: win, performance, console, Math, Map, Set, Array,
  Object, String, Number, JSON, RegExp, Date, isNaN, parseInt, parseFloat })
runInContext(m[1], ctx)

const { TrustedRAG, RAG_DATA } = win
if (!TrustedRAG || !RAG_DATA) throw new Error('核心脚本没有导出 TrustedRAG / RAG_DATA')

const rag = new TrustedRAG(RAG_DATA)
const js = rag.evaluate(RAG_DATA.evalCases)
const pyReport = JSON.parse(readFileSync(join(here, 'evaluation.json'), 'utf8'))
const py = pyReport.metrics

const keys = ['cases', 'answerCases', 'refuseCases', 'answerAccuracy',
  'refusalAccuracy', 'recallAt5', 'top1Accuracy', 'mrr']
const num = (v) => typeof v === 'number' ? Number(v.toFixed(4)) : v
let allSame = true

console.log('=== 浏览器页面 vs Python 版（同一份评测集）===')
console.log('指标'.padEnd(20) + '页面'.padEnd(12) + 'Python'.padEnd(12) + '一致')
for (const k of keys) {
  const a = num(js[k]), b = num(py[k])
  const same = a === b
  if (!same) allSame = false
  console.log(k.padEnd(20) + String(a).padEnd(12) + String(b).padEnd(12) + (same ? 'OK' : 'DIFF'))
}
const jf = js.failedCaseIds.join(',') || '无'
const pf = (pyReport.rows || []).filter(r => !r.pass).map(r => r.id).join(',') || '无'
console.log('\n页面失败用例:', jf || '无')
console.log('Python 失败用例:', pf || '无')
console.log('页面拒答原因分布:', js.rows.filter(r => r.expect === 'refuse' && !r.grounded)
  .reduce((o, r) => (o[r.mode] = (o[r.mode] || 0) + 1, o), {}))
console.log('\n结论:', allSame ? '✅ 页面与 Python 版指标完全一致' : '❌ 存在不一致，需要修正')
process.exit(allSame ? 0 : 1)
