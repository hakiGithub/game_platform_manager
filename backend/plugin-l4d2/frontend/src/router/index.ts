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
  },
  {
    path: '/maps',
    name: 'Maps',
    component: () => import('@/pages/Maps.vue'),
    meta: { title: '地图管理', icon: 'Map' }
  },
  {
    path: '/map-center',
    name: 'MapCenter',
    component: () => import('@/pages/MapCenter.vue'),
    meta: { title: '地图中心', icon: 'MapLocation' }
  },
  {
    path: '/plugins',
    name: 'Plugins',
    component: () => import('@/pages/Plugins.vue'),
    meta: { title: '插件管理', icon: 'Box' }
  },
  {
    path: '/rcon',
    name: 'Rcon',
    component: () => import('@/pages/Rcon.vue'),
    meta: { title: '控制台', icon: 'Monitor' }
  },
  {
    path: '/monitor',
    name: 'Monitor',
    component: () => import('@/pages/Monitor.vue'),
    meta: { title: '系统监控', icon: 'Monitor' }
  },
  {
    path: '/player-stats',
    name: 'PlayerStats',
    component: () => import('@/pages/PlayerStats.vue'),
    meta: { title: '玩家统计', icon: 'User' }
  },
  {
    // 菜单已并入 玩家统计（Tab），保留路由兼容遗留链接
    path: '/playtime',
    redirect: '/player-stats',
  },
  {
    path: '/admins',
    name: 'Admins',
    component: () => import('@/pages/Admins.vue'),
    meta: { title: '管理员', icon: 'User' }
  },
  {
    path: '/server-info',
    name: 'ServerInfo',
    component: () => import('@/pages/ServerInfo.vue'),
    meta: { title: '服务器信息', icon: 'InfoFilled' }
  },
  {
    path: '/server-config',
    name: 'ServerConfig',
    component: () => import('@/pages/ServerConfig.vue'),
    meta: { title: '服务器配置', icon: 'Setting' }
  },
  {
    path: '/restart',
    name: 'Restart',
    component: () => import('@/pages/Restart.vue'),
    meta: { title: '重启管理', icon: 'RefreshRight' }
  },
  {
    // 菜单已并入 服务器信息（折叠面板），保留路由兼容遗留链接
    path: '/version',
    redirect: '/server-info',
  },
  {
    path: '/logs',
    name: 'Logs',
    component: () => import('@/pages/Logs.vue'),
    meta: { title: '日志', icon: 'Document' }
  },
  {
    path: '/backup',
    name: 'Backup',
    component: () => import('@/pages/Backup.vue'),
    meta: { title: '备份还原', icon: 'FolderOpened' }
  },
  {
    path: '/plugin-store',
    name: 'PluginStore',
    component: () => import('@/pages/PluginStore.vue'),
    meta: { title: '插件商店', icon: 'ShoppingBag' }
  },
  {
    path: '/plugin-config',
    name: 'PluginConfig',
    component: () => import('@/pages/PluginConfig.vue'),
    meta: { title: '插件配置', icon: 'Setting' }
  },
  {
    path: '/preset',
    name: 'Preset',
    component: () => import('@/pages/Preset.vue'),
    meta: { title: '预设场景', icon: 'MagicStick' }
  },
  {
    path: '/download',
    name: 'Download',
    component: () => import('@/pages/Download.vue'),
    meta: { title: '下载管理', icon: 'Download' }
  }
]

/**
 * 创建路由实例
 * @param props Wujie 传入的 props；其中可能包含 route 指定初始路由
 */
export function createPluginRouter(props: Record<string, any> = {}): Router {
  const isWujie = typeof window !== 'undefined' && Boolean(window.__POWERED_BY_WUJIE__)

  // Wujie: hash 路由；dev: 根路径
  const history = isWujie
    ? createWebHashHistory()
    : createWebHistory('/')

  const router = createRouter({
    history,
    routes
  })

  // 如果主应用通过 props.route 指定了初始路由，则进行替换
  const initialRoute = props?.route
  if (initialRoute && initialRoute !== '/') {
    router.replace(initialRoute).catch(() => {
      // 忽略重复导航错误
    })
  }

  return router
}

// 默认导出一个独立运行时的路由实例，保持非 Wujie 场景兼容性
const router = createPluginRouter()
export default router
