import { describe, expect, it } from 'vitest'
import { describeChangeMapResult } from './changeMapResult'

describe('describeChangeMapResult', () => {
  it('confirmed=true 时提示地图已切换', () => {
    expect(describeChangeMapResult({
      mapName: 'c1m1_hotel',
      accepted: true,
      confirmed: true,
      currentMap: 'c1m1_hotel',
    }, 'c1m1_hotel')).toEqual({
      level: 'success',
      message: '地图已切换：c1m1_hotel',
    })
  })

  it('accepted=true 但未确认时只提示待确认', () => {
    const notice = describeChangeMapResult({
      mapName: 'c1918_red_square_c1',
      accepted: true,
      confirmed: false,
      currentMap: 'c2m1_highway',
      error: '换图命令已发送，但未在 20 秒内确认地图切换',
    }, 'c1918_red_square_c1')

    expect(notice.level).toBe('warning')
    expect(notice.message).toContain('未在 20 秒内确认')
  })

  it('accepted=false 时拒绝成功提示并抛出服务器错误', () => {
    expect(() => describeChangeMapResult({
      mapName: 'missing',
      accepted: false,
      confirmed: false,
      error: '服务器拒绝换图: No such map',
    }, 'missing')).toThrow('No such map')
  })
})
