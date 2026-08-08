import { createRouter, createWebHistory } from 'vue-router'
import NProgress from 'nprogress'
import { useUserStore } from '@/stores/user'

// 路由配置
const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/index.vue'),
    meta: {
      title: '登录',
      requiresAuth: false
    }
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: {
          title: '仪表盘',
          icon: 'Odometer',
          requiresAuth: true
        }
      },
      {
        path: 'host',
        name: 'Host',
        redirect: '/host/list',
        meta: {
          title: '主机管理',
          icon: 'Monitor',
          requiresAuth: true
        },
        children: [
          {
            path: 'list',
            name: 'HostList',
            component: () => import('@/views/host/index.vue'),
            meta: {
              title: '主机列表',
              requiresAuth: true
            }
          },
          {
            path: 'terminal/:id',
            name: 'HostTerminal',
            component: () => import('@/views/host/terminal.vue'),
            meta: {
              title: '主机终端',
              requiresAuth: true,
              hidden: true
            }
          }
        ]
      },
      {
        path: 'instance',
        name: 'Instance',
        redirect: '/instance/list',
        meta: {
          title: '实例管理',
          icon: 'Grid',
          requiresAuth: true
        },
        children: [
          {
            path: 'list',
            name: 'InstanceList',
            component: () => import('@/views/instance/index.vue'),
            meta: {
              title: '实例列表',
              requiresAuth: true
            }
          },
          {
            path: 'detail/:id',
            name: 'InstanceDetail',
            component: () => import('@/views/instance/detail.vue'),
            meta: {
              title: '实例详情',
              requiresAuth: true,
              hidden: true
            }
          },
          {
            path: 'deploy',
            name: 'InstanceDeploy',
            component: () => import('@/views/instance/deploy.vue'),
            meta: {
              title: '部署实例',
              requiresAuth: true,
              hidden: true
            }
          }
        ]
      },
      {
        path: 'game',
        name: 'Game',
        redirect: '/game/list',
        meta: {
          title: '游戏管理',
          icon: 'TrendCharts',
          requiresAuth: true
        },
        children: [
          {
            path: 'list',
            name: 'GameList',
            component: () => import('@/views/game/index.vue'),
            meta: {
              title: '游戏列表',
              requiresAuth: true
            }
          }
        ]
      },
      {
        path: 'task',
        name: 'Task',
        redirect: '/task/list',
        meta: {
          title: '任务中心',
          icon: 'List',
          requiresAuth: true
        },
        children: [
          {
            path: 'list',
            name: 'TaskList',
            component: () => import('@/views/task/index.vue'),
            meta: {
              title: '任务列表',
              requiresAuth: true
            }
          },
          {
            path: 'detail/:id',
            name: 'TaskDetail',
            component: () => import('@/views/task/detail.vue'),
            meta: {
              title: '任务详情',
              requiresAuth: true,
              hidden: true
            }
          }
        ]
      },
      {
        path: 'plugins',
        name: 'Plugins',
        redirect: '/plugins/list',
        meta: {
          title: '插件管理',
          icon: 'Connection',
          requiresAuth: true
        },
        children: [
          {
            path: 'list',
            name: 'PluginList',
            component: () => import('@/views/plugin/index.vue'),
            meta: {
              title: '插件列表',
              requiresAuth: true
            }
          }
        ]
      },
      {
        // Wujie 子应用加载路由
        // 支持 /plugin/:gameCode/:menuPath 形式访问插件子应用页面
        // menuPath 对应插件前端的路由 path（如 dashboard、maps、rcon）
        // menuPath 可选，缺省时使用 dashboard
        path: 'plugin/:gameCode/:menuPath(.*)?',
        name: 'PluginApp',
        component: () => import('@/plugins/components/PluginTab.vue'),
        props: route => ({
          gameCode: route.params.gameCode,
          menuPath: Array.isArray(route.params.menuPath)
            ? route.params.menuPath[0] || 'dashboard'
            : route.params.menuPath || 'dashboard',
          instanceId: Number(route.query.instanceId) || 0,
          instanceName: route.query.instanceName || '',
          hostId: Number(route.query.hostId) || 0,
          hostIp: route.query.hostIp || '',
          deployPath: route.query.deployPath || '',
          ports: route.query.ports ? JSON.parse(route.query.ports) : {}
        }),
        meta: {
          title: '插件应用',
          requiresAuth: true,
          hidden: true
        }
      },
      {
        path: 'docker',
        name: 'Docker',
        redirect: '/docker/list',
        meta: {
          title: 'Docker管理',
          icon: 'Box',
          requiresAuth: true
        },
        children: [
          {
            path: 'list',
            name: 'DockerList',
            component: () => import('@/views/docker/index.vue'),
            meta: {
              title: '容器管理',
              requiresAuth: true
            }
          },
          {
            path: 'container/:id',
            name: 'DockerContainer',
            component: () => import('@/views/docker/container.vue'),
            meta: {
              title: '容器详情',
              requiresAuth: true,
              hidden: true
            }
          }
        ]
      },
      {
        path: 'system',
        name: 'System',
        redirect: '/system/settings',
        meta: {
          title: '系统设置',
          icon: 'Setting',
          requiresAuth: true
        },
        children: [
          {
            path: 'settings',
            name: 'SystemSettings',
            component: () => import('@/views/system/settings.vue'),
            meta: {
              title: '系统配置',
              requiresAuth: true
            }
          },
          {
            path: 'logs',
            name: 'SystemLogs',
            component: () => import('@/views/system/logs.vue'),
            meta: {
              title: '系统日志',
              requiresAuth: true
            }
          }
        ]
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/error/404.vue'),
    meta: {
      title: '页面不存在',
      requiresAuth: false
    }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) {
      return savedPosition
    }
    return { top: 0 }
  }
})

// 路由守卫
router.beforeEach((to, from, next) => {
  // 开始进度条
  NProgress.start()
  
  // 设置页面标题
  document.title = to.meta.title ? `${to.meta.title} - 游戏服务器管理平台` : '游戏服务器管理平台'
  
  const userStore = useUserStore()
  const token = userStore.token
  
  // 需要认证的页面
  if (to.meta.requiresAuth) {
    if (token) {
      next()
    } else {
      next({
        path: '/login',
        query: { redirect: to.fullPath }
      })
    }
  } else {
    // 已登录用户访问登录页，重定向到首页
    if (to.path === '/login' && token) {
      next({ path: '/' })
    } else {
      next()
    }
  }
})

router.afterEach(() => {
  // 结束进度条
  NProgress.done()
})

export default router
