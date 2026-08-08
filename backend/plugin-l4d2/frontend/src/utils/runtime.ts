/**
 * 运行模式检测
 */

export type RuntimeMode = 'wujie' | 'dev'

/**
 * 检测当前运行模式。
 * - wujie: 运行在 Wujie 微前端环境中（被主应用加载）
 * - dev: Vite 开发模式
 */
export function detectMode(props: Record<string, any> = {}): RuntimeMode {
  // Wujie 注入的 props 最可靠（主应用会传 mode: 'wujie'）
  if (props.mode === 'wujie' || window.__POWERED_BY_WUJIE__) return 'wujie'
  return 'dev'
}
