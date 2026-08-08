/**
 * 插件模块统一导出
 */

// 类型定义
export * from './types/messageTypes'

// 通信管理
export * from './communication/pluginCommunication'

// SDK
export * from './sdk/pluginSDK'

// 组件
export { default as PluginContainer } from './components/PluginContainer.vue'
export { default as PluginMenu } from './components/PluginMenu.vue'
export { default as PluginTab } from './components/PluginTab.vue'

// Store
export { usePluginStore } from './stores/pluginStore'
