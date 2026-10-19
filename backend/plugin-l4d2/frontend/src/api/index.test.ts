import { describe, it, expect, beforeEach, vi } from 'vitest'
import { pluginManageApi, pluginStoreApi, serverApi } from './index'

function stubStatusResponse(vo: Record<string, unknown>) {
  vi.stubGlobal(
    'fetch',
    vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ code: 200, message: 'ok', data: vo })
    })
  )
}

describe('serverApi.getStatus 难度/模式透传', () => {
  beforeEach(() => {
    vi.unstubAllGlobals()
  })

  it('后端规范中文标签原样返回', async () => {
    stubStatusResponse({ online: true, difficulty: '高级', gameMode: '合作' })
    const status = await serverApi.getStatus(64)
    expect(status.difficulty).toBe('高级')
    expect(status.gameMode).toBe('合作')
  })

  it('后端标签不在 UI 元数据中时也原样返回', async () => {
    stubStatusResponse({ online: true, difficulty: '普通', gameMode: '拾荒' })
    const status = await serverApi.getStatus(64)
    expect(status.difficulty).toBe('普通')
    expect(status.gameMode).toBe('拾荒')
  })

  it('未知值不被前端替换', async () => {
    stubStatusResponse({ online: true, difficulty: '未知', gameMode: '未知' })
    const status = await serverApi.getStatus(64)
    expect(status.difficulty).toBe('未知')
    expect(status.gameMode).toBe('未知')
  })

  it('空值保持空串，不得兜底默认值', async () => {
    stubStatusResponse({ online: true, difficulty: '', gameMode: '' })
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
