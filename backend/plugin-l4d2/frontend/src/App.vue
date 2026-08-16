<template>
  <div class="l4d2-plugin-app">
    <el-config-provider :locale="zhCn">
      <MainLayout>
        <router-view />
      </MainLayout>
    </el-config-provider>
  </div>
</template>

<script setup lang="ts">
import { onMounted, onUnmounted, watch } from 'vue'
import { ElConfigProvider } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import MainLayout from '@/layouts/MainLayout.vue'
import { usePluginStore } from '@/stores/plugin'

const pluginStore = usePluginStore()

// 监听实例信息变化，确保 store 与 Wujie props 保持一致
watch(
  () => pluginStore.instanceInfo,
  (instanceInfo) => {
    if (instanceInfo) {
      console.log('[L4D2 Plugin] 实例信息更新:', instanceInfo)
    }
  },
  { deep: true }
)

onMounted(() => {
  // Wujie 模式下优先从宿主 props 同步实例/认证信息
  // 主应用通过 props.instance 传递实例信息，pluginSDK 默认读取 props.instanceInfo 会遗漏
  pluginStore.syncFromWujieProps()

  // 初始化插件 SDK（独立运行或 Wujie 首次挂载时都会执行）
  pluginStore.initSDK()

  // 通知主应用子应用已就绪
  pluginStore.ready()
})

onUnmounted(() => {
  pluginStore.destroySDK()
})
</script>

<style lang="scss">
.l4d2-plugin-app {
  width: 100%;
  height: 100%;
  background-color: var(--el-bg-color-page);
  color: var(--el-text-color-primary);
}
</style>
