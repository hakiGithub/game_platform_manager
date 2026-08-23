/**
 * 运行模式检测（demo）
 *
 * 两种模式（ADR-0003，v3.3.0 起 standalone 模式已废弃）：
 *   - wujie: 运行在 Wujie 微前端环境中（被主应用加载）
 *   - dev:   Vite 开发模式
 *
 * 检测顺序：
 *   1. window.__POWERED_BY_WUJIE__ 或 props.mode === 'wujie' → wujie
 *   2. 其余 → dev
 */
export type RuntimeMode = 'wujie' | 'dev'

export function detectMode(props: Record<string, any> = {}): RuntimeMode {
  if (props.mode === 'wujie' || window.__POWERED_BY_WUJIE__) return 'wujie'
  return 'dev'
}
