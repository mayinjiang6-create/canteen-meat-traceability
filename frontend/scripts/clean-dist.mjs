/**
 * 构建前清理 dist。
 *
 * 为什么不用 Vite 自带的 emptyOutDir：
 * Windows + 中文路径下，Vite 内部用 fs.rmSync({recursive:true}) 清空 dist 会偶发
 * EPERM（资源被索引/杀软短暂占用），导致「第一次能构建、第二次必失败」。
 * 这里改成逐文件 unlink，单个文件被占用就跳过、不阻塞构建，
 * 配合 vite.config.ts 的 emptyOutDir:false，构建稳定性不受影响。
 */
import { existsSync, readdirSync, statSync, unlinkSync, rmdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const dist = join(root, 'dist')

function purge(dir) {
  if (!existsSync(dir)) return 0
  let removed = 0
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    try {
      const stat = statSync(full)
      if (stat.isDirectory()) {
        removed += purge(full)
        rmdirSync(full)
      } else {
        unlinkSync(full)
        removed += 1
      }
    } catch {
      // 被占用 / 只读：留给 Vite 覆盖，不因一个文件让整个构建失败
    }
  }
  return removed
}

const count = purge(dist)
console.log(`[clean-dist] removed ${count} stale file(s)`)
