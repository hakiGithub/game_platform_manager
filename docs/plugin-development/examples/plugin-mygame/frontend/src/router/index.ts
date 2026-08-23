/**
 * 路由配置（demo）
 *
 * 关键约束（ADR-0001）：
 *   - 路由 path 必须与后端 MyGameExtension.getMenus() 声明的 path 严格对齐
 *   - 否则点击主应用侧边栏菜单时会白屏
 *
 * 两种 history 模式（由 detectMode() 决定，ADR-0003 起 standalone 模式已废弃）：
 *   - wujie: createWebHashHistory()   hash 路由，避免与主应用 history 冲突
 *   - dev:   createWebHistory('/')    Vite 开发模式
 */
import { createRouter, createWebHistory, createWebHashHistory } from 'vue-router'
import type { RouteRecordRaw, Router } from 'vue-router'

export const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/dashboard'
  },
  {
    path: '/dashboard',
    name: 'Dashboard',
    component: () => import('@/pages/Dashboard.vue'),
    meta: { title: '仪表盘', icon: 'Odometer' }
  }
  // 扩展示例：
  // {
  //   path: '/notes',
  //   name: 'Notes',
  //   component: () => import('@/pages/Notes.vue'),
  //   meta: { title: '笔记管理', icon: 'Document' }
  // }
]

export function createPluginRouter(props: Record<string, any> = {}): Router {
  const isWujie = typeof window !== 'undefined' && Boolean(window.__POWERED_BY_WUJIE__)

  // Wujie: hash 路由；dev: 根路径
  const history = isWujie
    ? createWebHashHistory()
    : createWebHistory('/')

  const router = createRouter({ history, routes })

  // 主应用通过 props.route 指定初始路由（如点击菜单跳转）
  const initialRoute = props?.route
  if (initialRoute && initialRoute !== '/') {
    router.replace(initialRoute).catch(() => { /* ignore duplicate nav */ })
  }

  return router
}

const router = createPluginRouter()
export default router
