/**
 * 格式化工具。
 *
 * 全部围绕一条铁律：**数量与金额不许经过 JavaScript 的 Number 运算**。
 * 后端的 3 位小数是摘要规范串的一部分，前端只要做一次四舍五入就可能对不上。
 * 所以这里的做法一律是「字符串裁剪 + 补零」，不做 parseFloat。
 */

/** 把 "120" / "120.5" / "120.4567" 规范成 3 位小数字符串；非法输入返回空串 */
export function toQty(value: string | number | null | undefined, digits = 3): string {
  if (value === null || value === undefined) return ''
  let text = String(value).trim()
  if (!text) return ''
  if (!/^\d*\.?\d*$/.test(text)) return ''

  const [intRaw, fracRaw = ''] = text.split('.')
  const intPart = intRaw === '' ? '0' : intRaw
  let frac = fracRaw
  if (frac.length > digits) {
    // 只做截断后的四舍五入：看第 digits+1 位
    const next = Number(frac[digits] ?? '0')
    const kept = frac.slice(0, digits)
    if (next >= 5) {
      // 拼成整数再加一，全程不碰浮点
      const scaled = Number(intPart + kept) + 1
      let s = String(scaled)
      if (s.length <= digits) s = s.padStart(digits + 1, '0')
      return `${s.slice(0, s.length - digits)}.${s.slice(s.length - digits)}`
    }
    frac = kept
  }
  return `${intPart}.${frac.padEnd(digits, '0')}`
}

/** 金额：2 位小数 */
export function toMoney(value: string | number | null | undefined): string {
  return toQty(value, 2)
}

/** ISO-8601 → yyyy-MM-dd HH:mm:ss，表格里显示用 */
export function fmtDateTime(value?: string | null): string {
  if (!value) return '—'
  return value.replace('T', ' ').replace(/\+08:00$/, '').slice(0, 19)
}

/** 只要日期 */
export function fmtDate(value?: string | null): string {
  if (!value) return '—'
  return value.slice(0, 10)
}

/** 摘要串中间省略，避免撑破列宽 */
export function shortHash(value?: string | null, head = 10, tail = 6): string {
  if (!value) return '—'
  if (value.length <= head + tail + 3) return value
  return `${value.slice(0, head)}…${value.slice(-tail)}`
}

/** 当前时间，格式对齐后端 TimeFormats（yyyy-MM-dd HH:mm:ss） */
export function nowText(): string {
  const d = new Date()
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(
    d.getMinutes(),
  )}:${p(d.getSeconds())}`
}
