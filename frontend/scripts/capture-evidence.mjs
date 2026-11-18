/**
 * 单页证据截图（阶段 E 测试用例取证用）。
 *
 * 与 capture-pages.mjs 的区别：那个是批量拍演示图，这个是「一条用例一张图」，
 * 路径与输出文件名由命令行给，方便在测试执行器里按场景精确调用。
 * 公开溯源页免登录，但后台页需要 Bearer token，所以仍走 CDP 注入 localStorage。
 *
 * 用法：
 *   node scripts/capture-evidence.mjs \
 *     --path /trace/PB20261118001 \
 *     --out  D:/食堂肉类溯源系统/screenshots/02-公开溯源页/TC-32.png \
 *     --wait 3500
 *
 * 可选：--base（默认 http://127.0.0.1:5173）、--api（默认 http://127.0.0.1:8080）、
 *       --port（CDP 调试端口，默认 9224）、--width / --height
 */
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, writeFileSync, rmSync, mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'

function arg(name, fallback) {
  const i = process.argv.indexOf('--' + name)
  return i >= 0 && process.argv[i + 1] ? process.argv[i + 1] : fallback
}

const PAGE_PATH = arg('path', '/trace/PB20261118001')
const OUT = arg('out', '')
const BASE = arg('base', 'http://127.0.0.1:5173')
const API = arg('api', 'http://127.0.0.1:8080')
const PORT = Number(arg('port', '9224'))
const WAIT = Number(arg('wait', '3500'))
const WIDTH = Number(arg('width', '1440'))
const HEIGHT = Number(arg('height', '1000'))

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

class CDP {
  constructor(ws) {
    this.ws = ws
    this.seq = 0
    this.pending = new Map()
    ws.onmessage = (ev) => {
      const msg = JSON.parse(ev.data)
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id)
        this.pending.delete(msg.id)
        msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result)
      }
    }
  }
  static async connect(url) {
    const ws = new WebSocket(url)
    await new Promise((resolve, reject) => {
      ws.onopen = resolve
      ws.onerror = reject
    })
    return new CDP(ws)
  }
  send(method, params = {}) {
    const id = ++this.seq
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject })
      this.ws.send(JSON.stringify({ id, method, params }))
    })
  }
  close() { this.ws.close() }
}

async function tryLogin() {
  try {
    const res = await fetch(`${API}/api/auth/login`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: 'admin', password: 'Admin@123' }),
    })
    const body = await res.json()
    return body.code === 0 ? body.data : null
  } catch {
    // 公开溯源页不需要登录态，登录失败只影响后台页
    return null
  }
}

async function main() {
  if (!OUT) throw new Error('必须传 --out（绝对或相对输出路径）')
  if (!existsSync(EDGE)) throw new Error(`找不到 Edge：${EDGE}`)
  mkdirSync(dirname(OUT), { recursive: true })

  const auth = await tryLogin()
  const profile = mkdtempSync(join(tmpdir(), 'mt-evidence-'))
  const child = spawn(
    EDGE,
    [
      '--headless=new', '--disable-gpu', '--no-sandbox', '--hide-scrollbars',
      `--remote-debugging-port=${PORT}`, `--user-data-dir=${profile}`,
      `--window-size=${WIDTH},${HEIGHT}`, 'about:blank',
    ],
    { stdio: 'ignore' },
  )

  try {
    let version = null
    for (let i = 0; i < 40; i++) {
      try {
        version = await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json()
        break
      } catch { await sleep(250) }
    }
    if (!version) throw new Error('Edge 调试端口未就绪')

    const tab = await (
      await fetch(`http://127.0.0.1:${PORT}/json/new?${encodeURIComponent(BASE + '/login')}`, {
        method: 'PUT',
      })
    ).json()
    const cdp = await CDP.connect(tab.webSocketDebuggerUrl)
    await cdp.send('Page.enable')
    await cdp.send('Runtime.enable')
    await cdp.send('Emulation.setDeviceMetricsOverride', {
      width: WIDTH, height: HEIGHT, deviceScaleFactor: 1, mobile: false,
    })

    await cdp.send('Page.navigate', { url: BASE + '/login' })
    await sleep(1000)
    if (auth) {
      const expr = `localStorage.setItem('meat_trace_token', ${JSON.stringify(auth.token)});
                    localStorage.setItem('meat_trace_user', ${JSON.stringify(JSON.stringify(auth.user))});
                    'ok'`
      await cdp.send('Runtime.evaluate', { expression: expr })
    }

    await cdp.send('Page.navigate', { url: BASE + PAGE_PATH })
    await sleep(WAIT)
    const shot = await cdp.send('Page.captureScreenshot', {
      format: 'png', captureBeyondViewport: true,
    })
    writeFileSync(OUT, Buffer.from(shot.data, 'base64'))
    console.log(`[shot] ${OUT}  ${Math.round(shot.data.length * 0.75 / 1024)} KB  (${PAGE_PATH})`)
    cdp.close()
    await fetch(`http://127.0.0.1:${PORT}/json/close/${tab.id}`).catch(() => {})
  } finally {
    child.kill()
    await sleep(400)
    rmSync(profile, { recursive: true, force: true })
  }
}

main().catch((e) => {
  console.error('[capture-evidence] 失败：', e.message)
  process.exitCode = 1
})
