/**
 * 带登录态的页面截图（答辩素材用）。
 *
 * 为什么不能直接 headless 截图：后台页面要求 Bearer token，而 token 存在 localStorage，
 * 浏览器命令行没有"预置 localStorage"的参数。这里走 CDP：
 *   登录拿 token → 起一个带调试端口的 headless Edge → 在页面里写入 localStorage → 导航 → 截图。
 *
 * 用 Node 22 内置的全局 WebSocket，不需要额外依赖。
 * 用法：node scripts/capture-pages.mjs
 */
import { spawn } from 'node:child_process'
import { existsSync, mkdirSync, writeFileSync, rmSync, mkdtempSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { dirname } from 'node:path'

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe'
const PORT = 9223
const BASE = 'http://127.0.0.1:5173'
const API = 'http://127.0.0.1:8080'
const OUT = join(dirname(fileURLToPath(import.meta.url)), '..', '..', 'screenshots')

const PAGES = [
  { path: '/trace/PB20261118001', name: 'frontend-trace-public.png' },
  { path: '/dashboard', name: 'frontend-dashboard.png' },
  { path: '/purchases', name: 'frontend-purchases.png' },
  { path: '/process', name: 'frontend-process.png' },
  { path: '/trace/PR20261118003', name: 'frontend-trace-process.png' },
]

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function login() {
  const res = await fetch(`${API}/api/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: 'admin', password: 'Admin@123' }),
  })
  const body = await res.json()
  if (body.code !== 0) throw new Error(`登录失败：${body.message}`)
  return body.data
}

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

  close() {
    this.ws.close()
  }
}

async function main() {
  if (!existsSync(EDGE)) throw new Error(`找不到 Edge：${EDGE}`)
  if (!existsSync(OUT)) mkdirSync(OUT, { recursive: true })

  const auth = await login()
  console.log(`[login] ${auth.user.displayName}，token ${auth.token.length} 字节`)

  const profile = mkdtempSync(join(tmpdir(), 'mt-edge-'))
  const child = spawn(
    EDGE,
    [
      '--headless=new',
      '--disable-gpu',
      '--no-sandbox',
      '--hide-scrollbars',
      `--remote-debugging-port=${PORT}`,
      `--user-data-dir=${profile}`,
      '--window-size=1440,900',
      'about:blank',
    ],
    { stdio: 'ignore' },
  )

  try {
    // 等调试端口就绪
    let version = null
    for (let i = 0; i < 40; i++) {
      try {
        version = await (await fetch(`http://127.0.0.1:${PORT}/json/version`)).json()
        break
      } catch {
        await sleep(250)
      }
    }
    if (!version) throw new Error('Edge 调试端口未就绪')

    for (const page of PAGES) {
      const tab = await (
        await fetch(`http://127.0.0.1:${PORT}/json/new?${encodeURIComponent(BASE + '/login')}`, {
          method: 'PUT',
        })
      ).json()
      const cdp = await CDP.connect(tab.webSocketDebuggerUrl)
      await cdp.send('Page.enable')
      await cdp.send('Runtime.enable')
      await cdp.send('Emulation.setDeviceMetricsOverride', {
        width: 1440,
        height: 900,
        deviceScaleFactor: 1,
        mobile: false,
      })

      // 先落到同源空白页，才有权限写 localStorage
      await cdp.send('Page.navigate', { url: BASE + '/login' })
      await sleep(1200)

      const expr = `localStorage.setItem('meat_trace_token', ${JSON.stringify(auth.token)});
                    localStorage.setItem('meat_trace_user', ${JSON.stringify(JSON.stringify(auth.user))});
                    'ok'`
      await cdp.send('Runtime.evaluate', { expression: expr })

      await cdp.send('Page.navigate', { url: BASE + page.path })
      await sleep(2200) // 等列表接口返回

      const shot = await cdp.send('Page.captureScreenshot', {
        format: 'png',
        captureBeyondViewport: true,
      })
      const file = join(OUT, page.name)
      writeFileSync(file, Buffer.from(shot.data, 'base64'))
      console.log(`[shot] ${page.name}  ${Math.round(shot.data.length * 0.75 / 1024)} KB`)

      cdp.close()
      await fetch(`http://127.0.0.1:${PORT}/json/close/${tab.id}`).catch(() => {})
    }
  } finally {
    child.kill()
    await sleep(500)
    rmSync(profile, { recursive: true, force: true })
  }
}

main().catch((e) => {
  console.error('[capture] 失败：', e.message)
  process.exitCode = 1
})
