import { createApp, type App as VueApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import 'element-plus/dist/index.css'
import 'element-plus/theme-chalk/dark/css-vars.css'

import App from './App.vue'
import { createPluginRouter } from './router'
import { usePluginStore } from './stores/plugin'
import { detectMode } from './utils/runtime'
import './styles/index.scss'

// Vue 应用实例
let app: VueApp<Element> | null = null
// 插件 store 实例，用于 Wujie 卸载时清理
let pluginStore: ReturnType<typeof usePluginStore> | null = null

/**
 * 渲染/挂载子应用
 * @param props Wujie 注入的 props；独立运行时为空对象
 */
function render(props: Record<string, any> = {}): void {
  // 如果已有实例，先卸载，避免重复挂载
  if (app) {
    destroyApp()
  }

  app = createApp(App)

  // 注册所有 Element Plus 图标
  for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
    app.component(key, component)
  }

  // 创建并使用 Pinia
  const pinia = createPinia()
  app.use(pinia)

  // 初始化插件 store
  pluginStore = usePluginStore()

  // Wujie 模式初始化 SDK 与 props 同步
  const mode = detectMode(props)
  if (mode === 'wujie') {
    pluginStore.initSDK()
    if (window.$wujie?.props) {
      pluginStore.syncFromWujieProps()
    }
  }

  // 创建路由实例
  const router = createPluginRouter(props)

  app.use(router)

  app.use(ElementPlus)

  // 决定挂载容器：Wujie 会传入 props.container，独立运行时使用 #app
  const container = props.container
    ? (typeof props.container === 'string'
        ? document.querySelector(props.container)
        : props.container)
    : document.getElementById('app')

  if (!container) {
    throw new Error('[L4D2 Plugin] 找不到挂载容器')
  }

  app.mount(container)
}

/**
 * 卸载并清理应用
 */
function destroyApp(): void {
  pluginStore?.destroySDK()
  pluginStore = null
  app?.unmount()
  app = null
}

/**
 * Wujie 生命周期：bootstrap
 */
const bootstrap = async (): Promise<void> => {
  console.log('[L4D2 Plugin] Wujie bootstrap')
}

/**
 * Wujie 生命周期：mount
 */
const mount = async (props: Record<string, any> = {}): Promise<void> => {
  render(props)
}

/**
 * Wujie 生命周期：unmount
 */
const unmount = async (): Promise<void> => {
  destroyApp()
}

// 导出 Wujie 生命周期对象
export { bootstrap, mount, unmount }

// 兼容旧版本 Wujie 的 window 挂载方式
if (window.__POWERED_BY_WUJIE__) {
  window.__WUJIE_MOUNT = () => mount(window.$wujie?.props || {})
  window.__WUJIE_UNMOUNT = () => unmount()
} else {
  // Vite 开发模式直接挂载
  render()
}
