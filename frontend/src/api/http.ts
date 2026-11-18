import axios, { AxiosError, type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import type { R } from '@/types/api'

/**
 * 统一 HTTP 层。
 *
 * 三件事必须在这里做掉，不许散落到页面里：
 *  1. 拆包：后端统一返回 { code, message, data, traceId }，调用方只拿 data。
 *  2. 鉴权：401 时清 token 并跳登录（token 过期不该让用户看见一屏红字）。
 *  3. 幂等：所有 POST 自动带 X-Request-Id —— 网络抖动重放不会重复入库（BR-10）。
 */

const TOKEN_KEY = 'meat_trace_token'
const USER_KEY = 'meat_trace_user'

export const tokenStorage = {
  get: () => localStorage.getItem(TOKEN_KEY) || '',
  set: (token: string) => localStorage.setItem(TOKEN_KEY, token),
  clear: () => {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USER_KEY)
  },
}

export const userStorage = {
  get: <T>(): T | null => {
    const raw = localStorage.getItem(USER_KEY)
    if (!raw) return null
    try {
      return JSON.parse(raw) as T
    } catch {
      return null
    }
  },
  set: (user: unknown) => localStorage.setItem(USER_KEY, JSON.stringify(user)),
  clear: () => localStorage.removeItem(USER_KEY),
}

/** 业务异常：HTTP 200 但 code !== 0 */
export class BizError extends Error {
  readonly code: number
  readonly traceId?: string

  constructor(code: number, message: string, traceId?: string) {
    super(message)
    this.name = 'BizError'
    this.code = code
    this.traceId = traceId
  }
}

let onUnauthorized: (() => void) | null = null
/** 由 main.ts 注册跳转逻辑，避免 http ←→ router 循环依赖 */
export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler
}

/** RFC4122 简化版 uuid，仅用于幂等键，不要求密码学强度 */
function uuid(): string {
  const c = globalThis.crypto
  if (c && typeof c.randomUUID === 'function') return c.randomUUID()
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (ch) => {
    const r = (Math.random() * 16) | 0
    const v = ch === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

export interface RequestOptions extends AxiosRequestConfig {
  /** true 时不弹错误提示，由调用方自己处理（例如列表接口失败要显示空态而不是弹窗） */
  silent?: boolean
}

const instance = axios.create({
  baseURL: '/api',
  timeout: 15000,
  headers: { 'Content-Type': 'application/json' },
})

instance.interceptors.request.use((config) => {
  const token = tokenStorage.get()
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  const method = (config.method || 'get').toLowerCase()
  if (method === 'post' || method === 'put') {
    if (!config.headers.get('X-Request-Id')) {
      config.headers.set('X-Request-Id', uuid())
    }
  }
  return config
})

instance.interceptors.response.use(
  (response) => response,
  (error: AxiosError) => {
    if (error.response?.status === 401) {
      tokenStorage.clear()
      userStorage.clear()
      ElMessage.error('登录已过期，请重新登录')
      onUnauthorized?.()
      return Promise.reject(new BizError(40101, '登录已过期'))
    }
    if (error.code === 'ECONNABORTED') {
      return Promise.reject(new BizError(-1, '请求超时，请检查后端服务是否已启动'))
    }
    if (!error.response) {
      return Promise.reject(new BizError(-2, '无法连接后端服务（http://localhost:8080）'))
    }
    return Promise.reject(new BizError(error.response.status, `请求失败（HTTP ${error.response.status}）`))
  },
)

/**
 * 发请求并拆包。业务失败统一抛 BizError，页面用 try/catch 接。
 */
export async function request<T>(config: RequestOptions): Promise<T> {
  const { silent, ...axiosConfig } = config
  try {
    const response = await instance.request<R<T>>(axiosConfig)
    const body = response.data
    if (body.code !== 0) {
      if (!silent) {
        ElMessage.error(body.message || `操作失败（${body.code}）`)
      }
      throw new BizError(body.code, body.message || '操作失败', body.traceId)
    }
    return body.data
  } catch (err) {
    if (err instanceof BizError) throw err
    const fallback = err instanceof Error ? err.message : '未知错误'
    if (!silent) ElMessage.error(fallback)
    throw new BizError(-99, fallback)
  }
}

export const http = {
  get: <T>(url: string, params?: Record<string, unknown>, options?: RequestOptions) =>
    request<T>({ ...options, url, method: 'get', params }),
  post: <T>(url: string, data?: unknown, options?: RequestOptions) =>
    request<T>({ ...options, url, method: 'post', data }),
  put: <T>(url: string, data?: unknown, options?: RequestOptions) =>
    request<T>({ ...options, url, method: 'put', data }),
}

/**
 * 从 catch 到的错误里取人话。业务错误直接用后端 message
 * （后端的错误文案已经写成给用户看的口径，例如「可用余量不足，当前剩余 15.000 kg」）。
 */
export function errText(err: unknown, fallback = '操作失败'): string {
  if (err instanceof BizError) return err.message
  if (err instanceof Error) return err.message
  return fallback
}
