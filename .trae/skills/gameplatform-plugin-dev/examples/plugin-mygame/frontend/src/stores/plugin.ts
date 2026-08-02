/**
 * Pinia store（demo 简化版）
 *
 * 演示 Wujie 模式下从主应用 props 同步实例 / 认证信息。
 * 真实插件可参考 plugin-l4d2 的 stores/plugin.ts（含完整 SDK 事件总线、主题切换、localStorage 持久化等）。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { detectMode } from '@/utils/runtime'

export interface InstanceInfo {
  instanceId: number
  instanceName?: string
  hostId?: number
  gameCode?: string
}

export const usePluginStore = defineStore('plugin', () => {
  const mode = ref<'wujie' | 'standalone' | 'dev'>(detectMode())
  const instanceInfo = ref<InstanceInfo | null>(null)
  const token = ref<string>('')
  const isWujie = ref(mode.value === 'wujie')

  /**
   * 从 Wujie props 同步初始化状态（Wujie 模式启动时调用）
   * 主应用通过 Wujie props 下发 instance / auth 等信息
   */
  function syncFromWujieProps() {
    const wujie = (window as any).$wujie
    if (!wujie?.props) return

    const props = wujie.props
    // 实例信息：兼容 props.instance 与旧版 props.instanceInfo
    const info = props.instance || props.instanceInfo
    if (info) {
      instanceInfo.value = {
        instanceId: info.instanceId,
        instanceName: info.instanceName,
        hostId: info.hostId,
        gameCode: info.gameCode
      }
    }
    // 认证信息：兼容 props.auth.token 与旧版 props.token
    const auth = props.auth
    if (auth?.token) {
      token.value = auth.token
    } else if (props.token) {
      token.value = props.token
    }
  }

  return {
    mode,
    instanceInfo,
    token,
    isWujie,
    syncFromWujieProps
  }
})
