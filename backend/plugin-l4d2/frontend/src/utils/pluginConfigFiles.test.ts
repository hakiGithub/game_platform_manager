import { describe, expect, it } from 'vitest'
import {
  canEditConfigFile,
  findFile,
  mergeConfigFileDrafts,
  normalizeConfigFiles,
  resetFileDefaults,
  tempConfigDisabledReason
} from './pluginConfigFiles'

describe('pluginConfigFiles', () => {
  it('normalizes legacy single-file payloads into a file list', () => {
    const files = normalizeConfigFiles({
      pluginName: 'legacy',
      configName: 'legacy.cfg',
      configPath: 'cfg/sourcemod/legacy.cfg',
      items: [{ key: 'legacy_key', value: '1', lineNumber: 1 }]
    })

    expect(files).toHaveLength(1)
    expect(files[0].fileId).toBe('cfg/sourcemod/legacy.cfg')
    expect(files[0].status).toBe('OK')
    expect(files[0].items[0]).toEqual({ key: 'legacy_key', value: '1', lineNumber: 1 })
  })

  it('keeps per-file drafts when switching files and merging snapshots', () => {
    const files = normalizeConfigFiles({
      pluginName: 'multi',
      items: [],
      configFiles: [
        file('a', 'a.cfg', [{ key: 'a_key', value: '1', lineNumber: 1 }]),
        file('b', 'b.cfg', [{ key: 'b_key', value: '2', lineNumber: 1 }])
      ]
    })
    findFile(files, 'a')!.items[0].value = 'edited-a'
    findFile(files, 'b')!.items[0].value = 'edited-b'

    const merged = mergeConfigFileDrafts(files, [
      file('a', 'a.cfg', [{ key: 'a_key', value: 'server-a', lineNumber: 1 }]),
      file('b', 'b.cfg', [{ key: 'b_key', value: 'server-b', lineNumber: 1 }])
    ])
    expect(findFile(merged, 'a')!.items[0].value).toBe('edited-a')
    expect(findFile(merged, 'b')!.items[0].value).toBe('edited-b')

    resetFileDefaults(findFile(merged, 'a')!)
    expect(findFile(merged, 'a')!.items[0].value).toBe('default-a')
    expect(findFile(merged, 'b')!.items[0].value).toBe('edited-b')
  })

  it('disables editing and temporary application for unreadable files', () => {
    const missing = {
      fileId: 'missing',
      configName: 'missing.cfg',
      configPath: 'cfg/sourcemod/missing.cfg',
      items: [],
      exists: false,
      status: 'MISSING' as const
    }
    const readable = file('ok', 'ok.cfg', [{ key: 'ok_key', value: '1', lineNumber: 1 }])

    expect(canEditConfigFile(missing)).toBe(false)
    expect(canEditConfigFile(readable)).toBe(true)
    expect(tempConfigDisabledReason(missing, {
      key: 'missing_key',
      value: '1',
      lineNumber: 1
    })).toContain('不存在')
  })

  it('explains empty values before RCON temporary application', () => {
    const readable = file('ok', 'ok.cfg', [{ key: 'ok_key', value: ' ', lineNumber: 1 }])

    expect(tempConfigDisabledReason(readable, readable.items[0])).toBe('当前值不能为空')
    readable.items[0].value = '2'
    expect(tempConfigDisabledReason(readable, readable.items[0])).toBe('')
  })
})

function file(id: string, name: string, items: Array<{ key: string; value: string; lineNumber: number }>) {
  return {
    fileId: id,
    configName: name,
    configPath: `cfg/sourcemod/${name}`,
    items: items.map(item => ({ ...item, defaultValue: item.key === 'a_key' ? 'default-a' : 'default-b' })),
    exists: true,
    status: 'OK' as const,
    error: undefined
  }
}
