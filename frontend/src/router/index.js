import { createRouter, createWebHistory } from 'vue-router'
import NProgress from 'nprogress'
import { useUserStore } from '@/stores/user'

// 路由配置：按“工作台 / 资源 / 服务 / 扩展 / 系统”组织，旧路径通过重定向保留兼容性。
const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/login/index.vue'),
    meta: { title: '登录', requiresAuth: false }
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/workspace/overview',
    children: [
      {
        path: 'workspace',
        name: 'Workspace',
        redirect: '/workspace/overview',
        meta: { title: '工作台', icon: 'Odometer', requiresAuth: true },
        children: [
          {
            path: 'overview',
            name: 'Dashboard',
            component: () => import('@/views/dashboard/index.vue'),
            meta: { title: '运行总览', navPath: '/workspace/overview', requiresAuth: true }
          }
        ]
      },
      {
        path: 'resources',
        name: 'Resources',
        meta: { title: '资源管理', requiresAuth: true },
        children: [
          {
            path: 'hosts',
            name: 'Hosts',
            redirect: '/resources/hosts/list',
            meta: { title: '主机资源', icon: 'Monitor', navPath: '/resources/hosts', requiresAuth: true },
            children: [
              {
                path: 'list',
                name: 'HostList',
                component: () => import('@/views/host/index.vue'),
                meta: { title: '主机列表', navPath: '/resources/hosts/list', requiresAuth: true }
              },
              {
                path: 'detail/:id',
                name: 'HostDetail',
                component: () => import('@/views/host/detail.vue'),
                meta: { title: '主机详情', navPath: '/resources/hosts', requiresAuth: true, hidden: true }
              },
              {
                path: 'terminal/:id',
                name: 'HostTerminal',
                component: () => import('@/views/host/terminal.vue'),
                meta: { title: '主机终端', navPath: '/resources/hosts', requiresAuth: true, hidden: true }
              }
            ]
          },
          {
            path: 'containers',
            name: 'Containers',
            redirect: '/resources/containers/list',
            meta: { title: '容器资源', icon: 'Box', navPath: '/resources/containers', requiresAuth: true },
            children: [
              {
                path: 'list',
                name: 'DockerList',
                component: () => import('@/views/docker/index.vue'),
                meta: { title: '容器列表', navPath: '/resources/containers/list', requiresAuth: true }
              },
              {
                path: 'detail/:id',
                name: 'DockerContainer',
                component: () => import('@/views/docker/container.vue'),
                meta: { title: '容器详情', navPath: '/resources/containers', requiresAuth: true, hidden: true }
              }
            ]
          }
        ]
      },
      {
        path: 'services',
        name: 'Services',
        meta: { title: '服务编排', requiresAuth: true },
        children: [
          {
            path: 'instances',
            name: 'Instances',
            redirect: '/services/instances/list',
            meta: { title: '实例服务', icon: 'Grid', navPath: '/services/instances', requiresAuth: true },
            children: [
              {
                path: 'list',
                name: 'InstanceList',
                component: () => import('@/views/instance/index.vue'),
                meta: { title: '实例列表', navPath: '/services/instances/list', requiresAuth: true }
              },
              {
                path: 'detail/:id',
                name: 'InstanceDetail',
                component: () => import('@/views/instance/detail.vue'),
                meta: { title: '实例详情', navPath: '/services/instances', requiresAuth: true, hidden: true }
              },
              {
                path: 'deploy',
                name: 'InstanceDeploy',
                component: () => import('@/views/instance/deploy.vue'),
                meta: { title: '部署实例', navPath: '/services/instances', requiresAuth: true, hidden: true }
              }
            ]
          },
          {
            path: 'games',
            name: 'Games',
            redirect: '/services/games/list',
            meta: { title: '游戏目录', icon: 'TrendCharts', navPath: '/services/games', requiresAuth: true },
            children: [
              {
                path: 'list',
                name: 'GameList',
                component: () => import('@/views/game/index.vue'),
                meta: { title: '游戏列表', navPath: '/services/games/list', requiresAuth: true }
              }
            ]
          },
          {
            path: 'tasks',
            name: 'Tasks',
            redirect: '/services/tasks/list',
            meta: { title: '执行队列', icon: 'List', navPath: '/services/tasks', requiresAuth: true },
            children: [
              {
                path: 'list',
                name: 'TaskList',
                component: () => import('@/views/task/index.vue'),
                meta: { title: '任务列表', navPath: '/services/tasks/list', requiresAuth: true }
              },
              {
                path: 'detail/:id',
                name: 'TaskDetail',
                component: () => import('@/views/task/detail.vue'),
                meta: { title: '任务详情', navPath: '/services/tasks', requiresAuth: true, hidden: true }
              }
            ]
          }
        ]
      },
      {
        path: 'extensions',
        name: 'Extensions',
        meta: { title: '扩展中心', requiresAuth: true },
        children: [
          {
            path: 'plugins',
            name: 'Plugins',
            redirect: '/extensions/plugins/list',
            meta: { title: '插件扩展', icon: 'Connection', navPath: '/extensions/plugins', requiresAuth: true },
            children: [
              {
                path: 'list',
                name: 'PluginList',
                component: () => import('@/views/plugin/index.vue'),
                meta: { title: '插件列表', navPath: '/extensions/plugins/list', requiresAuth: true }
              }
            ]
          },
          {
            path: 'app/:gameCode/:menuPath(.*)?',
            name: 'PluginApp',
            component: () => import('@/plugins/components/PluginTab.vue'),
            props: route => ({
              gameCode: route.params.gameCode,
              menuPath: Array.isArray(route.params.menuPath)
                ? route.params.menuPath[0] || 'dashboard'
                : route.params.menuPath || 'dashboard',
              // 实例状态仅经 instanceId 传递，其余信息由 PluginTab 按 gameCode 反查补全
              instanceId: Number(route.query.instanceId) || 0
            }),
            meta: { title: '插件工作区', navPath: '/extensions/plugins', requiresAuth: true, hidden: true }
          }
        ]
      },
      {
        path: 'system',
        name: 'System',
        redirect: '/system/configuration',
        meta: { title: '系统设置', icon: 'Setting', requiresAuth: true },
        children: [
          {
            path: 'configuration',
            name: 'SystemSettings',
            component: () => import('@/views/system/settings.vue'),
            meta: { title: '系统配置', navPath: '/system/configuration', requiresAuth: true }
          }
        ]
      },

      // 旧路径兼容：外部书签与现有页面内部跳转仍可平滑进入新信息架构。
      { path: 'dashboard', redirect: '/workspace/overview', meta: { requiresAuth: true, hidden: true } },
      { path: 'host', redirect: '/resources/hosts/list', meta: { requiresAuth: true, hidden: true } },
      { path: 'host/list', redirect: '/resources/hosts/list', meta: { requiresAuth: true, hidden: true } },
      { path: 'host/detail/:id', redirect: to => ({ path: `/resources/hosts/detail/${to.params.id}`, query: to.query }), meta: { requiresAuth: true, hidden: true } },
      { path: 'host/terminal/:id', redirect: to => ({ path: `/resources/hosts/terminal/${to.params.id}`, query: to.query }), meta: { requiresAuth: true, hidden: true } },
      { path: 'instance', redirect: '/services/instances/list', meta: { requiresAuth: true, hidden: true } },
      { path: 'instance/list', redirect: '/services/instances/list', meta: { requiresAuth: true, hidden: true } },
      { path: 'instance/detail/:id', redirect: to => ({ path: `/services/instances/detail/${to.params.id}`, query: to.query }), meta: { requiresAuth: true, hidden: true } },
      { path: 'instance/deploy', redirect: to => ({ path: '/services/instances/deploy', query: to.query }), meta: { requiresAuth: true, hidden: true } },
      { path: 'game', redirect: '/services/games/list', meta: { requiresAuth: true, hidden: true } },
      { path: 'game/list', redirect: '/services/games/list', meta: { requiresAuth: true, hidden: true } },
      { path: 'task', redirect: '/services/tasks/list', meta: { requiresAuth: true, hidden: true } },
      { path: 'task/list', redirect: '/services/tasks/list', meta: { requiresAuth: true, hidden: true } },
      { path: 'task/detail/:id', redirect: to => ({ path: `/services/tasks/detail/${to.params.id}`, query: to.query }), meta: { requiresAuth: true, hidden: true } },
      { path: 'docker', redirect: '/resources/containers/list', meta: { requiresAuth: true, hidden: true } },
      { path: 'docker/list', redirect: '/resources/containers/list', meta: { requiresAuth: true, hidden: true } },
      { path: 'docker/container/:id', redirect: to => ({ path: `/resources/containers/detail/${to.params.id}`, query: to.query }), meta: { requiresAuth: true, hidden: true } },
      { path: 'plugins', redirect: '/extensions/plugins/list', meta: { requiresAuth: true, hidden: true } },
      { path: 'plugins/list', redirect: '/extensions/plugins/list', meta: { requiresAuth: true, hidden: true } },
      { path: 'system/settings', redirect: '/system/configuration', meta: { requiresAuth: true, hidden: true } },
      {
        path: 'plugin/:gameCode/:menuPath(.*)?',
        redirect: to => {
          const menuPath = Array.isArray(to.params.menuPath)
            ? to.params.menuPath.join('/')
            : to.params.menuPath
          return {
            path: `/extensions/app/${to.params.gameCode}/${menuPath || 'dashboard'}`,
            query: to.query
          }
        },
        meta: { requiresAuth: true, hidden: true }
      }
    ]
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'NotFound',
    component: () => import('@/views/error/404.vue'),
    meta: { title: '页面不存在', requiresAuth: false }
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
