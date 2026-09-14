import { describe, it, expect } from 'vitest'
import { normalizePluginName, isPluginInstalled } from './pluginMatch'

describe('normalizePluginName', () => {
  it('去 .smx 后缀并忽略大小写', () => {
    expect(normalizePluginName('Admin-Suite.smx')).toBe('adminsuite')
  })

  it('忽略下划线/连字符/空格', () => {
    expect(normalizePluginName('source_mod core')).toBe('sourcemodcore')
    expect(normalizePluginName('source-mod_core')).toBe('sourcemodcore')
  })

  it('空值安全', () => {
    expect(normalizePluginName('')).toBe('')
  })
})

describe('isPluginInstalled', () => {
  const installed = ['adminsuite.smx', 'SourceMod-Core', 'l4d2_toolkit.smx']

  it('大小写与分隔符差异视为已安装', () => {
    expect(isPluginInstalled('Admin Suite', installed)).toBe(true)
    expect(isPluginInstalled('sourcemod_core.smx', installed)).toBe(true)
  })

  it('未收录条目返回 false', () => {
    expect(isPluginInstalled('SomeOtherPlugin', installed)).toBe(false)
  })
})
