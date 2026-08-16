import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

/**
 * Vite 配置（demo）
 *
 * 关键点：
 *   - base: './'  使用相对路径，便于 Wujie 通过不同 URL 加载子应用资源
 *   - build.outDir: ../src/main/resources/ui  将构建产物输出到后端 JAR 的 ui 目录
 *   - server.proxy: /api → http://localhost:8080  dev 模式转发到主应用后端
 */
export default defineConfig({
  plugins: [
    vue(),
    AutoImport({
      imports: ['vue', 'vue-router', 'pinia'],
      resolvers: [ElementPlusResolver()],
      dts: 'src/auto-imports.d.ts'
    }),
    Components({
      resolvers: [ElementPlusResolver()],
      dts: 'src/components.d.ts'
    })
  ],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  base: './',
  server: {
    host: '0.0.0.0',
    port: 3100,
    open: true,
    cors: {
      origin: true,
      credentials: true,
      methods: 'GET,POST,PUT,DELETE,OPTIONS',
      allowedHeaders: 'Content-Type, Authorization'
    },
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  },
  css: {
    preprocessorOptions: {
      scss: {
        // Night Ops token 注入（ADR-0007：插件自带 token 副本，变量名无 $-- 前缀）
        additionalData: `@use "@/styles/variables.scss" as *;`
      }
    }
  },
  build: {
    // 产物输出到后端 JAR 的 ui/ 目录，打包后随 JAR 一起部署
    outDir: '../src/main/resources/ui',
    emptyOutDir: true,
    sourcemap: false
  }
})
