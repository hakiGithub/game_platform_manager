import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import WujieVue from 'wujie-vue3'

import App from './App.vue'
import router from './router'

import 'element-plus/dist/index.css'
import 'nprogress/nprogress.css'
import '@/styles/index.scss'

const app = createApp(App)

// 注册所有 Element Plus 图标
for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })
// 注册无界微前端插件
app.use(WujieVue)

app.mount('#app')
