/**
 * 真实 LLM 调用取证（可复现）：在真实浏览器里把软件接上 DeepSeek，
 * 跑一次复杂问题，输出脱敏证据 evaluation_llm_real.json 与截图。
 *
 *   DEEPSEEK_API_KEY=xxx node capture_llm_real.mjs
 *
 * 密钥只从环境变量读取，只注入浏览器 localStorage，**不写入任何仓库文件**。
 * 输出文件不含密钥、不含 Authorization 头。
 */
import { spawn } from 'node:child_process'
import { mkdtempSync, writeFileSync, rmSync, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const KEY = process.env.DEEPSEEK_API_KEY || ''
if (!KEY) { console.log('请先设置环境变量 DEEPSEEK_API_KEY'); process.exit(1) }

const here = dirname(fileURLToPath(import.meta.url))
const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PORT = 9227
const url = pathToFileURL(join(here, 'app.html')).href
const outJson = join(here, 'evaluation_llm_real.json')
const outPng = join(here, '..', 'backend', 'target', 'app-llm-real.png')
const sleep = ms => new Promise(r => setTimeout(r, ms))

class CDP {
  constructor(ws) {
    this.ws = ws; this.seq = 0; this.pending = new Map()
    ws.onmessage = ev => {
      const m = JSON.parse(ev.data)
      if (m.id && this.pending.has(m.id)) {
        const { resolve, reject } = this.pending.get(m.id)
        this.pending.delete(m.id)
        m.error ? reject(new Error(JSON.stringify(m.error))) : resolve(m.result)
      }
    }
  }
  static async connect(u) {
    const ws = new WebSocket(u)
    await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej })
    return new CDP(ws)
  }
  send(method, params = {}) {
    const id = ++this.seq
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject })
      this.ws.send(JSON.stringify({ id, method, params }))
    })
  }
  eval(expr) {
    return this.send('Runtime.evaluate',
      { expression: expr, returnByValue: true, awaitPromise: true })
      .then(r => r.result?.value)
  }
  close() { this.ws.close() }
}

const question = '哪些批次冷链温度超限？涉及哪些供应商？是否需要召回？'
const profile = mkdtempSync(join(tmpdir(), 'mt-llm-'))
const child = spawn(EDGE, ['--headless=new', '--disable-gpu', '--no-sandbox',
  '--hide-scrollbars', `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${profile}`, '--window-size=1440,1100', 'about:blank'],
  { stdio: 'ignore' })

let result = null
try {
  let ready = false
  for (let i = 0; i < 50; i++) {
    try { await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json(); ready = true; break }
    catch { await sleep(200) }
  }
  if (!ready) throw new Error('Edge 调试端口未就绪')

  const tab = await (await fetch(
    `http://127.0.0.1:${PORT}/json/new?${encodeURIComponent(url)}`,
    { method: 'PUT' })).json()
  const cdp = await CDP.connect(tab.webSocketDebuggerUrl)
  await cdp.send('Page.enable'); await cdp.send('Runtime.enable')
  await cdp.send('Emulation.setDeviceMetricsOverride',
    { width: 1440, height: 1100, deviceScaleFactor: 1, mobile: false })
  await sleep(1600)

  // 包一层 fetch 记录 usage；密钥只在页面内存与 localStorage，不写文件
  await cdp.eval(`(()=>{
    const orig=window.fetch.bind(window);
    window.__llmUsage=null; window.__llmHttp=null;
    window.fetch=async(...a)=>{const r=await orig(...a);window.__llmHttp=r.status;
      try{const d=await r.clone().json();window.__llmUsage=d.usage||null;
          window.__llmModel=d.model||null;}catch(e){} return r;};
    doLogin();
    C.setLLMConfig({base:'https://api.deepseek.com',key:${JSON.stringify(KEY)},
      model:'deepseek-chat',enabled:true});
    go('agent');
    document.getElementById('agentQ').value=${JSON.stringify(question)};
    window.__agentPromise=runAgentUI();
    return 'started';
  })()`)

  let done = false
  for (let i = 0; i < 90; i++) {
    await sleep(500)
    done = await cdp.eval("!!(window.__lastAgent && (window.__lastAgent.llmUsed || window.__lastAgent.llmError))")
    if (done) break
  }

  result = await cdp.eval(`(()=>{const r=window.__lastAgent||{};return {
    llmUsed:!!r.llmUsed, llmError:r.llmError||null, model:r.llmModel||window.__llmModel||null,
    traceSteps:(r.trace||[]).length, tools:(r.tools||[]),
    modeName:r.modeName||null, badge:document.getElementById('agentMode')?.textContent||'',
    finalAnswer:document.getElementById('finalAnswer')?.textContent||'',
    deterministicText:r.deterministicText||'',
    usage:window.__llmUsage, http:window.__llmHttp
  }})()`)

  const png = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true })
  writeFileSync(outPng, Buffer.from(png.data, 'base64'))
  await cdp.eval("C.clearLLMConfig(); window.fetch=undefined; 'ok'")  // 清掉进程内引用
  cdp.close()
  await fetch(`http://127.0.0.1:${PORT}/json/close/${tab.id}`).catch(() => {})
} catch (e) {
  result = { error: String(e.message || e) }
} finally {
  child.kill(); await sleep(400)
  try { rmSync(profile, { recursive: true, force: true }) } catch {}
}

const report = {
  meta: {
    capturedAt: new Date().toISOString(),
    provider: 'DeepSeek OpenAI-compatible API',
    modelRequested: 'deepseek-chat',
    question,
    note: '真实调用取证；密钥只在浏览器本机，输出文件不含密钥或 Authorization 头',
  },
  result,
}
writeFileSync(outJson, JSON.stringify(report, null, 2), 'utf8')
console.log('=== 真实 LLM 调用结果 ===')
console.log('  llmUsed:', result?.llmUsed, '| model:', result?.model, '| HTTP:', result?.http)
console.log('  轨迹', result?.traceSteps, '步 | 工具', (result?.tools || []).join(', '))
console.log('  token 用量:', JSON.stringify(result?.usage))
console.log('  徽标:', result?.badge)
console.log('  LLM 答案前 200 字:\n   ', String(result?.finalAnswer || '').slice(0, 200).replace(/\n/g, '\n    '))
console.log('  错误:', result?.error || result?.llmError || '无')
console.log('\n证据:', outJson)
console.log('截图:', outPng)
process.exit(result?.llmUsed ? 0 : 1)
