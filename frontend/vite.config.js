import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

export default defineConfig({
  plugins: [
    vue(),
    // 自动导入 Vue 相关函数
    AutoImport({
      imports: ['vue', 'vue-router', 'pinia'],
      resolvers: [ElementPlusResolver()],
      dts: 'src/auto-imports.d.ts'
    }),
    // 自动导入组件
    Components({
      // 主入口已全量加载 Element Plus 样式，避免组件级样式被懒发现后触发开发服务器整页重载。
      resolvers: [ElementPlusResolver({ importStyle: false })],
      dts: 'src/components.d.ts'
    })
  ],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  server: {
    host: '0.0.0.0',
    port: 3000,
    open: true,
    // Playwright 会把截图/trace 写到 e2e/.artifacts；这些 HTML 资源在 Vite root 下，
    // 若不忽略会触发整页 reload，打断 Wujie 子应用的长序列导航。
    watch: {
      ignored: [
        '**/e2e/.artifacts/**',
        '**/e2e/.report/**',
        '**/e2e/.tmp/**'
      ]
    },
    proxy: {
      // 本地开发默认代理本机后端；可用环境变量指向已部署环境（如 Docker 镜像的 nginx）：
      //   VITE_PROXY_TARGET=http://127.0.0.1:8081 npm run dev
      '/api': {
        // 后端默认只监听 IPv4。固定 127.0.0.1，避免 Node 双栈解析先连 ::1
        // 后回退时，在长序列/高负载下偶发 AggregateError [ECONNREFUSED]。
        target: process.env.VITE_PROXY_TARGET || 'http://127.0.0.1:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, '/api')
      },
      '/ws': {
        target: (process.env.VITE_PROXY_TARGET || 'http://127.0.0.1:8080').replace(/^http/, 'ws'),
        ws: true,
        rewrite: (path) => path.replace(/^\/ws/, '/api/ws')
      }
    }
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        manualChunks: {
          'element-plus': ['element-plus'],
          'vue-vendor': ['vue', 'vue-router', 'pinia'],
          'xterm': ['xterm', 'xterm-addon-fit', 'xterm-addon-web-links', 'xterm-addon-search']
        }
      }
    }
  },
  css: {
    preprocessorOptions: {
      scss: {
        additionalData: `@import "@/styles/variables.scss";`
      }
    }
  }
})
