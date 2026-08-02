<template>
  <div class="mygame-app">
    <el-config-provider :locale="zhCn">
      <!-- demo 简化：直接渲染 router-view，不嵌套 MainLayout
           真实插件可参考 plugin-l4d2 的 layouts/MainLayout.vue（含侧边栏 + 面包屑） -->
      <router-view />
    </el-config-provider>
  </div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue'
import { ElConfigProvider } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import { usePluginStore } from '@/stores/plugin'

const pluginStore = usePluginStore()

onMounted(() => {
  // Wujie 模式下从主应用 props 同步实例 / 认证信息
  pluginStore.syncFromWujieProps()
  console.log('[MyGame] App mounted, mode=', pluginStore.mode, 'instance=', pluginStore.instanceInfo)
})
</script>

<style lang="scss">
.mygame-app {
  width: 100%;
  height: 100%;
  background-color: var(--el-bg-color);
  color: var(--el-text-color-primary);
}
</style>
