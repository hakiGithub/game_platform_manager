/**
 * 运行模式检测（demo）
 *
 * 三种模式：
 *   - wujie:      运行在 Wujie 微前端环境中（被主应用加载）
 *   - dev:        Vite 开发模式
 *   - standalone: 独立部署模式
 *
 * 检测顺序：
 *   1. window.__POWERED_BY_WUJIE__ 或 props.mode === 'wujie' → wujie
 *   2. import.meta.env.DEV → dev
 *   3. 其余 → standalone
 */
export type RuntimeMode = 'wujie' | 'standalone' | 'dev'

export function detectMode(props: Record<string, any> = {}): RuntimeMode {
  if (props.mode === 'wujie' || window.__POWERED_BY_WUJIE__) return 'wujie'
  if (import.meta.env.DEV) return 'dev'
  return 'standalone'
}
