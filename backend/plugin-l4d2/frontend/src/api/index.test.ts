import { describe, it, expect, beforeEach, vi } from 'vitest'
import { pluginManageApi, pluginStoreApi, serverApi } from './index'

/**
 * 回归测试：仪表盘难度/游戏模式显示“未知”。
 * 根因链：后端返回中文（“高级”/“合作”）后，toCode 的常量表
 * （label=困难/合作模式）匹配不上 → 置空串 → Dashboard 显示未知。
 * 修复：DIFFICULTIES/GAME_MODES 增加 alias，toCode 支持 alias 与“模式”后缀宽容匹配。
 */
function stubStatusResponse(vo: Record<string, unknown>) {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ code: 200, message: 'ok', data: vo })
    })
  )
}

describe('serverApi.getStatus 难度/模式归一化', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
  })

  it('后端中文“高级”→ hard（回归：此前映射失败显示未知）', async () => {
    stubStatusResponse({ online: true, difficulty: '高级', gameMode: '合作' })
    const status = await serverApi.getStatus(64)
    expect(status.difficulty).toBe('hard')
  })

  it('后端中文“合作”→ coop（回归：此前映射失败显示未知）', async () => {
    stubStatusResponse({ online: true, difficulty: '高级', gameMode: '合作' })
    const status = await serverApi.getStatus(64)
    expect(status.gameMode).toBe('coop')
  })

  it('“拾荒”→ scavenge（后端与前端 label 用词不同）', async () => {
    stubStatusResponse({ online: true, difficulty: '普通', gameMode: '拾荒' })
    const status = await serverApi.getStatus(64)
    expect(status.gameMode).toBe('scavenge')
  })

  it('“写实模式”带后缀 → realism', async () => {
    stubStatusResponse({ online: true, difficulty: '普通', gameMode: '写实模式' })
    const status = await serverApi.getStatus(64)
    expect(status.gameMode).toBe('realism')
  })

  it('英文 code 直接匹配（coop/easy）', async () => {
    stubStatusResponse({ online: true, difficulty: 'easy', gameMode: 'coop' })
    const status = await serverApi.getStatus(64)
    expect(status.difficulty).toBe('easy')
    expect(status.gameMode).toBe('coop')
  })

  it('未识别值（未知/空串）保持空串，不得兜底默认值', async () => {
    stubStatusResponse({ online: true, difficulty: '未知', gameMode: '' })
    const status = await serverApi.getStatus(64)
    expect(status.difficulty).toBe('')
    expect(status.gameMode).toBe('')
  })
})

describe('插件列表分页 API 参数', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
  })

  it('已安装列表传递页码和页大小', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        code: 200,
        message: 'ok',
        data: { current: 2, size: 10, total: 11, pages: 2, records: [] }
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    await pluginManageApi.list(9, { keyword: 'admin', status: 'enabled', page: 2, size: 10 })

    const url = String(fetchMock.mock.calls[0][0])
    expect(url).toContain('/plugins/list')
    expect(url).toContain('instanceId=9')
    expect(url).toContain('keyword=admin')
    expect(url).toContain('status=enabled')
    expect(url).toContain('page=2')
    expect(url).toContain('size=10')
  })

  it('远端商店传递安装状态筛选与分页参数', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({
        code: 200,
        message: 'ok',
        data: { current: 1, size: 20, total: 0, pages: 0, records: [] }
      })
    })
    vi.stubGlobal('fetch', fetchMock)

    await pluginStoreApi.list({
      installed: 'uninstalled',
      instanceId: 9,
      page: 1,
      size: 20
    })

    const url = String(fetchMock.mock.calls[0][0])
    expect(url).toContain('/plugin-store/list')
    expect(url).toContain('installed=uninstalled')
    expect(url).toContain('instanceId=9')
  })
})
