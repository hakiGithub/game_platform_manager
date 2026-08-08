import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { fetchPluginManifest } from '../communication/pluginCommunication'
import type { PluginManifest, PluginMenuItem } from '../types/messageTypes'

/**
 * 插件状态管理
 */
export const usePluginStore = defineStore('plugin', () => {
  // ========== 状态 ==========
  
  /** 当前实例的插件清单 */
  const currentManifest = ref<PluginManifest | null>(null)
  
  /** 当前选中的菜单 ID */
  const activeMenuId = ref<string | null>(null)
  
  /** 插件加载状态 */
  const loading = ref(false)
  
  /** 插件错误信息 */
  const error = ref<string | null>(null)
  
  /** 插件就绪状态 */
  const isReady = ref(false)
  
  /** 当前游戏代码 */
  const currentGameCode = ref<string | null>(null)
  
  // ========== 计算属性 ==========
  
  /** 插件菜单列表 */
  const menus = computed<PluginMenuItem[]>(() => {
    return currentManifest.value?.menus || []
  })
  
  /** 扁平化的菜单列表（用于面包屑等） */
  const flatMenus = computed(() => {
    const result: Array<PluginMenuItem & { parentLabel?: string }> = []
    
    function flatten(items: PluginMenuItem[], parentLabel?: string) {
      items.forEach(item => {
        result.push({ ...item, parentLabel })
        if (item.children && item.children.length > 0) {
          flatten(item.children, item.label)
        }
      })
    }
    
    flatten(menus.value)
    return result
  })
  
  /** 当前选中的菜单项 */
  const activeMenu = computed(() => {
    if (!activeMenuId.value) return null
    return flatMenus.value.find(m => m.id === activeMenuId.value) || null
  })
  
  /** 是否有插件菜单 */
  const hasMenus = computed(() => menus.value.length > 0)
  
  // ========== Actions ==========
  
  /**
   * 加载插件清单
   * @param gameCode 游戏代码
   */
  async function loadManifest(gameCode: string): Promise<PluginManifest | null> {
    if (currentGameCode.value === gameCode && currentManifest.value) {
      return currentManifest.value
    }
    
    loading.value = true
    error.value = null
    
    try {
      const manifest = await fetchPluginManifest(gameCode)
      currentManifest.value = manifest
      currentGameCode.value = gameCode
      
      // 默认选中第一个菜单
      if (manifest.menus && manifest.menus.length > 0) {
        activeMenuId.value = manifest.menus[0].id
      }
      
      return manifest
    } catch (err) {
      const raw = err instanceof Error ? err.message : '加载插件清单失败'
      // 插件未安装/未注册属于预期分支：给出明确降级空态文案，不抛 console error
      if (raw.includes('未找到游戏对应的插件')) {
        error.value = `该游戏（${gameCode}）暂无可用插件，请先在「插件管理」中确认对应插件已安装并启用。`
      } else {
        error.value = raw
      }
      currentManifest.value = null
      return null
    } finally {
      loading.value = false
    }
  }
  
  /**
   * 设置当前选中的菜单
   * @param menuId 菜单 ID
   */
  function setActiveMenu(menuId: string | null) {
    activeMenuId.value = menuId
  }
  
  /**
   * 根据路径查找菜单
   * @param path 路径
   */
  function findMenuByPath(path: string): PluginMenuItem | null {
    return flatMenus.value.find(m => m.path === path) || null
  }
  
  /**
   * 设置插件就绪状态
   * @param ready 是否就绪
   */
  function setReady(ready: boolean) {
    isReady.value = ready
  }
  
  /**
   * 清除当前插件状态
   */
  function clear() {
    currentManifest.value = null
    activeMenuId.value = null
    loading.value = false
    error.value = null
    isReady.value = false
    currentGameCode.value = null
  }
  
  /**
   * 重置状态
   */
  function reset() {
    clear()
  }
  
  return {
    // 状态
    currentManifest,
    activeMenuId,
    loading,
    error,
    isReady,
    currentGameCode,
    
    // 计算属性
    menus,
    flatMenus,
    activeMenu,
    hasMenus,
    
    // Actions
    loadManifest,
    setActiveMenu,
    findMenuByPath,
    setReady,
    clear,
    reset
  }
})
