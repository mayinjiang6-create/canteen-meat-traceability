import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * 开发期把 /api 反代到后端 8080，浏览器视角同源，不需要 CORS。
 * 后端另有一份 CorsConfig 兜底（打包后分离部署 / 直接用 IP 访问时用得上）。
 */
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    host: '127.0.0.1',
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    // 中文路径下 Vite 清空 dist 会偶发 EPERM，改由 scripts/clean-dist.mjs 逐文件清理
    emptyOutDir: false,
    // Element Plus 全量引入约 900 kB（gzip 后约 200 kB），属于 UI 库正常体积，单独缓存即可
    chunkSizeWarningLimit: 1000,
    // 按依赖拆成 vendor 块：① 首屏并行下载更快；② 业务代码改动不会让 UI 库缓存失效。
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (id.includes('node_modules/element-plus') || id.includes('node_modules/@element-plus')) {
            return 'element-plus'
          }
          if (
            id.includes('node_modules/vue') ||
            id.includes('node_modules/@vue') ||
            id.includes('node_modules/pinia')
          ) {
            return 'vue-vendor'
          }
          if (id.includes('node_modules/axios') || id.includes('node_modules/follow-redirects')) {
            return 'http-vendor'
          }
          return undefined
        },
      },
    },
  },
})
