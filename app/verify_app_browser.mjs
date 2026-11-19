/**
 * 浏览器交互验证：真实启动 Edge (CDP)，模拟从登录到智能体运行的完整路径。
 *
 *   node verify_app_browser.mjs
 *
 * 产出一个截图用于目视复核。不改动任何产品文件。
 */
import { spawn } from 'node:child_process'
import { mkdtempSync, writeFileSync, rmSync, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PORT = 9226
const url = pathToFileURL(join(here, 'app.html')).href
const shot = join(here, '..', 'backend', 'target', 'app-preview.png')
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
  async eval(expr) {
    const r = await this.send('Runtime.evaluate',
      { expression: expr, returnByValue: true, awaitPromise: true })
    return r.result?.value
  }
  close() { this.ws.close() }
}

if (!existsSync(EDGE)) { console.log('跳过：本机没有 Edge'); process.exit(0) }
const profile = mkdtempSync(join(tmpdir(), 'mt-app-'))
const child = spawn(EDGE, ['--headless=new', '--disable-gpu', '--no-sandbox',
  '--hide-scrollbars', `--remote-debugging-port=${PORT}`,
  `--user-data-dir=${profile}`, '--window-size=1440,1000', 'about:blank'],
  { stdio: 'ignore' })

let pass = 0, fail = 0
const check = (name, cond, detail = '') => {
  if (cond) { pass++; console.log('  PASS ' + name + (detail ? '  ' + detail : '')) }
  else { fail++; console.log('  FAIL ' + name + '  ' + detail) }
}
try {
  let version = null
  for (let i = 0; i < 50; i++) {
    try { version = await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json(); break }
    catch { await sleep(200) }
  }
  if (!version) throw new Error('Edge 调试端口未就绪')

  const tab = await (await fetch(
    `http://127.0.0.1:${PORT}/json/new?${encodeURIComponent(url)}`,
    { method: 'PUT' })).json()
  const cdp = await CDP.connect(tab.webSocketDebuggerUrl)
  await cdp.send('Page.enable'); await cdp.send('Runtime.enable')
  await cdp.send('Emulation.setDeviceMetricsOverride',
    { width: 1440, height: 1000, deviceScaleFactor: 1, mobile: false })
  await sleep(1800)

  check('页面加载出登录框', await cdp.eval("!!document.getElementById('login')"))
  await cdp.eval("doLogin(); 'ok'")
  await sleep(300)
  check('登录后主界面显示',
    await cdp.eval("document.getElementById('shell').style.display") === 'block')
  check('侧边栏含 10 个业务模块',
    (await cdp.eval("document.querySelectorAll('#nav .nav').length")) === 10)

  await cdp.eval("go('purchases'); 'ok'")
  await sleep(200)
  const rows = await cdp.eval("document.querySelectorAll('#view table tr').length")
  check('采购页渲染真实表格', rows > 20, rows + ' 行')

  await cdp.eval(`go('agent'); document.getElementById('agentQ').value=
    '哪些批次冷链温度超限？涉及哪些供应商？是否需要召回？'; runAgentUI(); 'ok'`)
  await sleep(400)
  const steps = await cdp.eval("document.querySelectorAll('.step').length")
  check('智能体渲染 4 步执行轨迹', steps >= 4, steps + ' 步')
  const final = await cdp.eval("document.querySelector('.final')?.textContent || ''")
  check('最终答案含工具依据', final.includes('依据：'), final.slice(0, 60).replace(/\n/g, ' '))
  const stat = await cdp.eval("document.querySelector('.stat')?.textContent || ''")
  check('显示工具调用统计', stat.includes('确定性工作流'), stat.slice(0, 50))

  const png = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true })
  writeFileSync(shot, Buffer.from(png.data, 'base64'))
  console.log('\n  截图已保存:', shot)

  await cdp.eval(`go('suppliers'); document.getElementById('agentQ')?.value; 'ok'`)
  await sleep(150)
  check('可切换到其他业务模块',
    (await cdp.eval("document.getElementById('crumb').textContent")) === '供应商管理')

  await cdp.eval("go('llm'); 'ok'")
  await sleep(150)
  check('LLM 接入设置页可渲染',
    await cdp.eval("!!document.getElementById('llmBase') && !!document.getElementById('llmKey')"))

  // 用 mock fetch 验证浏览器里 LLM 增强路径确实走通（不调用真实模型、不产生费用）
  await cdp.eval(`
    window.fetch=async()=>({ok:true,json:async()=>({choices:[{message:{
      content:'[T1] LLM 依据工具结果组织的答案（浏览器 mock 验证）'}}]})});
    C.setLLMConfig({base:'http://mock.local/v1',key:'sk-mock',model:'mock-model',enabled:true});
    go('agent');
    document.getElementById('agentQ').value='哪些批次冷链温度超限？涉及哪些供应商？是否需要召回？';
    runAgentUI(); 'ok'`)
  await sleep(600)
  const llmAnswer = await cdp.eval("document.querySelector('#finalAnswer')?.textContent || ''")
  check('浏览器里 LLM 增强模式生效', llmAnswer.includes('LLM 依据工具结果组织'),
    llmAnswer.slice(0, 50))
  check('模式徽标切换为 LLM 增强',
    String(await cdp.eval("document.getElementById('agentMode')?.textContent || ''"))
      .includes('LLM 增强'))
  await cdp.eval("C.clearLLMConfig(); window.fetch=undefined; 'ok'")
  check('清除后恢复离线模式', (await cdp.eval('C.llmActive()')) === false)

  cdp.close()
  await fetch(`http://127.0.0.1:${PORT}/json/close/${tab.id}`).catch(() => {})
} catch (e) {
  fail++; console.log('  FAIL 浏览器验证异常:', e.message)
} finally {
  child.kill(); await sleep(400)
  try { rmSync(profile, { recursive: true, force: true }) } catch {}
}
console.log(`\n结论: PASS ${pass} / FAIL ${fail}`)
process.exit(fail ? 1 : 0)
