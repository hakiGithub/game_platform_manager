import type { ChangeMapResult } from '@/api'

export interface ChangeMapNotice {
  level: 'success' | 'warning'
  message: string
}

/**
 * 将换图结果转换成可展示提示。HTTP 业务失败由 request 层抛出；
 * 这里仍防御 accepted=false，避免旧后端或空响应被当成换图成功。
 */
export function describeChangeMapResult(
  result: ChangeMapResult | null | undefined,
  requestedMap: string
): ChangeMapNotice {
  if (!result || !result.accepted) {
    throw new Error(result?.error || `服务器未接受换图命令：${requestedMap}`)
  }

  const target = result.mapName || requestedMap
  if (result.confirmed) {
    return { level: 'success', message: `地图已切换：${target}` }
  }

  const observed = result.currentMap ? `，当前仍为 ${result.currentMap}` : ''
  return {
    level: 'warning',
    message: result.error || `换图命令已发送，但尚未确认地图切换${observed}`,
  }
}
