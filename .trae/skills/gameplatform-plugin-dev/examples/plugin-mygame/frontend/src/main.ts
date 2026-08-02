/**
 * 入口（demo）
 *
 * 关键点：
 *   - 导出 Wujie 生命周期（bootstrap / mount / unmount），主应用通过 Wujie 加载子应用
 *   - 兼容旧版 window.__WUJIE_MOUNT 挂载方式
 *   - Vite 开发模式（dev）下直接渲染
 *   - 挂载容器：Wujie 通过 props.container 传入，dev 模式使用 #app
 */
import { createApp, type App as VueApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import 'element-plus/dist/index.css'

import App from './App.vue'
import { createPluginRouter } from './router'
import { usePluginStore } from './stores/plugin'
import { detectMode } from './utils/runtime'

let app: VueApp<Element> | null = null

function render(props: Record<string, any> = {}): void {
  if (app) {
    destroyApp()
  }

  app = createApp(App)

  // 注册所有 Element Plus 图标
  for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
    app.component(key, component)
  }

  const pinia = createPinia()
  app.use(pinia)

  // 初始化 store（detectMode 会读取 window.__POWERED_BY_WUJIE__）
  const pluginStore = usePluginStore()
  pluginStore.syncFromWujieProps()

  const router = createPluginRouter(props)
  app.use(router)
  app.use(ElementPlus)

  // 决定挂载容器
  const container = props.container
    ? (typeof props.container === 'string'
        ? document.querySelector(props.container)
        : props.container)
    : document.getElementById('app')

  if (!container) {
    throw new Error('[MyGame] 找不到挂载容器')
  }

  app.mount(container)
}

function destroyApp(): void {
  app?.unmount()
  app = null
}

// Wujie 生命周期
const bootstrap = async (): Promise<void> => {
  console.log('[MyGame] Wujie bootstrap')
}

const mount = async (props: Record<string, any> = {}): Promise<void> => {
  render(props)
}

const unmount = async (): Promise<void> => {
  destroyApp()
}

export { bootstrap, mount, unmount }

// 兼容旧版 Wujie 的 window 挂载方式
if (window.__POWERED_BY_WUJIE__) {
  window.__WUJIE_MOUNT = () => mount(window.$wujie?.props || {})
  window.__WUJIE_UNMOUNT = () => unmount()
} else {
  // Vite 开发模式直接挂载
  render()
}
