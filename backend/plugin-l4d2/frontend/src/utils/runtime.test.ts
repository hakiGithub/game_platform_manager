import { describe, it, expect, beforeEach } from 'vitest'
import { detectMode } from './runtime'

describe('detectMode', () => {
  beforeEach(() => {
    delete (window as any).__POWERED_BY_WUJIE__
  })

  it('returns wujie when __POWERED_BY_WUJIE__ is true', () => {
    ;(window as any).__POWERED_BY_WUJIE__ = true
    expect(detectMode()).toBe('wujie')
  })

  it('returns wujie when __POWERED_BY_WUJIE__ is false but truthy', () => {
    ;(window as any).__POWERED_BY_WUJIE__ = false
    // false is falsy, so not wujie
    expect(detectMode()).not.toBe('wujie')
  })

  it('returns dev when not in wujie (vitest runs in dev mode)', () => {
    // vitest 中 import.meta.env.DEV 为 true
    expect(detectMode()).toBe('dev')
  })
})
