/**
 * 本机已接入版验证：确认 app.local.html 自动带上 LLM 配置、
 * 模式徽标正确、LLM 答案里的 Markdown 加粗被正确渲染。
 *
 *   node verify_local.mjs
 *
 * 全程用 mock fetch，不调用真实模型、不产生费用；脚本不含任何密钥。
 */
import { spawn } from 'node:child_process'
import { mkdtempSync, writeFileSync, rmSync, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PORT = 9228
const local = join(here, 'app.local.html')
const sleep = ms => new Promise(r => setTimeout(r, ms))
let pass = 0, fail = 0
const ck = (n, c, d = '') => {
  if (c) { pass++; console.log('  PASS ' + n + (d ? '  ' + d : '')) }
  else { fail++; console.log('  FAIL ' + n + '  ' + d) }
}

if (!existsSync(local)) { console.log('跳过：本机已接入版 app.local.html 不存在'); process.exit(0) }
if (!existsSync(EDGE)) { console.log('跳过：本机没有 Edge'); process.exit(0) }

class CDP {
  constructor(ws) {
    this.ws = ws; this.i = 0; this.p = new Map()
    ws.onmessage = e => {
      const m = JSON.parse(e.data)
      if (m.id && this.p.has(m.id)) {
        const { resolve, reject } = this.p.get(m.id)
        this.p.delete(m.id)
        m.error ? reject(new Error(JSON.stringify(m.error))) : resolve(m.result)
      }
    }
  }
  static async connect(u) {
    const ws = new WebSocket(u)
    await new Promise((r, j) => { ws.onopen = r; ws.onerror = j })
    return new CDP(ws)
  }
  send(method, params = {}) {
    const id = ++this.i
    return new Promise((resolve, reject) => {
      this.p.set(id, { resolve, reject })
      this.ws.send(JSON.stringify({ id, method, params }))
    })
  }
  async eval(expr) {
    const r = await this.send('Runtime.evaluate',
      { expression: expr, returnByValue: true, awaitPromise: true })
    return r.result?.value
  }
}

const url = pathToFileURL(local).href
const profile = mkdtempSync(join(tmpdir(), 'mt-local-'))
const child = spawn(EDGE, ['--headless=new', '--disable-gpu', '--no-sandbox',
  `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`, 'about:blank'],
  { stdio: 'ignore' })

try {
  let ready = false
  for (let i = 0; i < 50; i++) {
    try { await (await fetch('http://127.0.0.1:' + PORT + '/json/version')).json(); ready = true; break }
    catch { await sleep(200) }
  }
  if (!ready) throw new Error('Edge 调试端口未就绪')

  const newUrl = 'http://127.0.0.1:' + PORT + '/json/new?' + encodeURIComponent(url)
  const tab = await (await fetch(newUrl, { method: 'PUT' })).json()
  const c = await CDP.connect(tab.webSocketDebuggerUrl)
  await c.send('Page.enable'); await c.send('Runtime.enable')
  await sleep(1600)

  ck('本机已接入版自动带上 LLM 配置', (await c.eval('C.llmActive()')) === true)
  console.log('  当前模型:', await c.eval('(C.getLLMConfig()||{}).model'))
  await c.eval("doLogin(); go('agent'); 'ok'")
  await sleep(200)
  ck('模式徽标为 LLM 增强',
    String(await c.eval("document.getElementById('agentMode').textContent")).includes('LLM 增强'))

  await c.eval(`window.fetch=async()=>({ok:true,json:async()=>({choices:[{message:{
    content:'**结论**：共 4 条记录 [T1]'}}]})});
    document.getElementById('agentQ').value='哪些批次冷链温度超限？涉及哪些供应商？是否需要召回？';
    runAgentUI(); 'ok'`)
  await sleep(400)
  const html = String(await c.eval("document.getElementById('finalAnswer').innerHTML"))
  ck('LLM 答案的 ** 渲染为加粗而非星号', html.includes('<strong>'))
  ck('答案仍保留工具依据标注',
    String(await c.eval("document.getElementById('finalAnswer').textContent")).includes('[T1]'))
  await c.eval("window.fetch=undefined; 'ok'")
  const png = await c.send('Page.captureScreenshot', { format: 'png' })
  writeFileSync(join(here, '..', 'backend', 'target', 'app-local-check.png'),
    Buffer.from(png.data, 'base64'))
  c.ws.close()
  await fetch('http://127.0.0.1:' + PORT + '/json/close/' + tab.id).catch(() => {})
} catch (e) {
  fail++; console.log('  FAIL 异常:', e.message)
} finally {
  child.kill(); await sleep(300)
  try { rmSync(profile, { recursive: true, force: true }) } catch {}
}
console.log('结论: PASS ' + pass + ' / FAIL ' + fail)
process.exit(fail ? 1 : 0)
