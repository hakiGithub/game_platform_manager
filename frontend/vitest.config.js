import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

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
  test: {
    // 测试环境
    environment: 'happy-dom',
    // 全局变量
    globals: true,
    // 测试文件匹配模式
    include: ['src/tests/**/*.test.js'],
    // 排除目录
    exclude: ['**/node_modules/**', '**/dist/**'],
    // 测试覆盖率配置
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json', 'html'],
      reportsDirectory: './coverage',
      // 覆盖率阈值
      thresholds: {
        lines: 70,
        functions: 70,
        branches: 60,
        statements: 70
      },
      // 包含和排除的文件
      include: ['src/utils/**/*.js', 'src/stores/**/*.js', 'src/api/**/*.js', 'src/plugins/**/*.js'],
      exclude: ['src/tests/**', 'node_modules/**']
    },
    // 设置文件
    setupFiles: ['./src/tests/setup.js'],
    // 测试超时时间
    testTimeout: 10000,
    // 钩子超时时间
    hookTimeout: 10000,
    // 依赖处理
    deps: {
      // 内联处理 Element Plus
      inline: ['element-plus']
    },
    // 服务器配置
    server: {
      deps: {
        // 内联处理所有依赖
        inline: [/element-plus/]
      }
    }
  },
  css: {
    preprocessorOptions: {
      scss: {
        additionalData: `@use "@/styles/variables.scss" as *;`
      }
    }
  }
})
