import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { ref, nextTick } from 'vue'
import { usePolling, type UsePollingOptions } from './usePolling'

/**
 * usePolling 单测（ADR-0030，插件侧副本）
 *
 * 与主应用 frontend/src/tests/composables/usePolling.test.js 用例集一致 ——
 * 两侧副本靠人移植，用例应对齐；改一侧后手工同步另一侧。
 *
 * 断言的是 module 的不变量，而非实现细节：
 *   - 门控是唯一真值源（翻真起表 / 翻假停表）
 *   - 至多一个在途请求（慢接口不堆叠）
 *   - 手动 refresh 不重排节拍
 *   - 卸载后不再排下一轮
 */
describe('usePolling', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  /** 在组件上下文里挂载 composable（onBeforeUnmount 需要 setup 作用域） */
  const mountPolling = (
    fetcher: () => void | Promise<void>,
    options: UsePollingOptions
  ) => {
    let api: ReturnType<typeof usePolling> | null = null
    const wrapper = mount({
      template: '<div />',
      setup() {
        api = usePolling(fetcher, options)
      },
    })
    return {
      wrapper,
      get api() {
        return api as ReturnType<typeof usePolling>
      },
    }
  }

  /** 让已 resolve 的 promise 链跑完 */
  const flush = () => vi.advanceTimersByTimeAsync(0)

  describe('起停', () => {
    it('enabled 恒真时按 intervalMs 周期拉取', async () => {
      const fetcher = vi.fn().mockResolvedValue(undefined)
      mountPolling(fetcher, { intervalMs: 1000 })

      expect(fetcher).not.toHaveBeenCalled() // 默认 immediate:false

      await vi.advanceTimersByTimeAsync(1000)
      expect(fetcher).toHaveBeenCalledTimes(1)

      await vi.advanceTimersByTimeAsync(1000)
      expect(fetcher).toHaveBeenCalledTimes(2)

      await vi.advanceTimersByTimeAsync(3000)
      expect(fetcher).toHaveBeenCalledTimes(5)
    })

    it('immediate 为真时启动即拉一次', async () => {
      const fetcher = vi.fn().mockResolvedValue(undefined)
      mountPolling(fetcher, { intervalMs: 1000, immediate: true })

      await flush()
      expect(fetcher).toHaveBeenCalledTimes(1)

      await vi.advanceTimersByTimeAsync(1000)
      expect(fetcher).toHaveBeenCalledTimes(2)
    })

    it('isPolling 反映定时器是否在跑', async () => {
      const on = ref(true)
      const fetcher = vi.fn().mockResolvedValue(undefined)
      const { api } = mountPolling(fetcher, {
        intervalMs: 1000,
        enabled: () => on.value,
      })

      expect(api.isPolling.value).toBe(true)
      on.value = false
      await nextTick()
      expect(api.isPolling.value).toBe(false)
    })
  })

  describe('门控（enabled 是唯一真值源）', () => {
    it('翻假即停表，不再拉取', async () => {
      const on = ref(true)
      const fetcher = vi.fn().mockResolvedValue(undefined)
      mountPolling(fetcher, { intervalMs: 1000, enabled: () => on.value })

      await vi.advanceTimersByTimeAsync(1000)
      expect(fetcher).toHaveBeenCalledTimes(1)

      on.value = false
      await nextTick()

      await vi.advanceTimersByTimeAsync(5000)
      expect(fetcher).toHaveBeenCalledTimes(1) // 停表后一次都不拉
    })

    it('翻真即起表，恢复轮询', async () => {
      const on = ref(false)
      const fetcher = vi.fn().mockResolvedValue(undefined)
      mountPolling(fetcher, { intervalMs: 1000, enabled: () => on.value })

      await vi.advanceTimersByTimeAsync(5000)
      expect(fetcher).not.toHaveBeenCalled() // 初始为假，从未起表

      on.value = true
      await nextTick()

      // 起表 ≠ 拉取：首批数据等下一个 tick
      expect(fetcher).not.toHaveBeenCalled()
      await vi.advanceTimersByTimeAsync(1000)
      expect(fetcher).toHaveBeenCalledTimes(1)
    })

    it('翻真一次只起一个定时器（反复翻真不叠加）', async () => {
      const on = ref(false)
      const fetcher = vi.fn().mockResolvedValue(undefined)
      mountPolling(fetcher, { intervalMs: 1000, enabled: () => on.value })

      on.value = true
      await nextTick()
      on.value = false
      await nextTick()
      on.value = true
      await nextTick()

      await vi.advanceTimersByTimeAsync(2000)
      expect(fetcher).toHaveBeenCalledTimes(2) // 不是 4
    })

    it('拉取期间门控翻假，完成后不复活定时器', async () => {
      const on = ref(true)
      let resolveFetch!: () => void
      const fetcher = vi.fn(
        () =>
          new Promise<void>((resolve) => {
            resolveFetch = resolve
          })
      )
      mountPolling(fetcher, { intervalMs: 1000, enabled: () => on.value })

      await vi.advanceTimersByTimeAsync(1000) // 第 1 次拉取，挂在在途
      expect(fetcher).toHaveBeenCalledTimes(1)

      on.value = false // 在途期间翻假 → watch 停表
      await nextTick()

      resolveFetch() // 在途请求此刻才完成
      await flush()

      await vi.advanceTimersByTimeAsync(10000)
      expect(fetcher).toHaveBeenCalledTimes(1) // 未续排
    })
  })

  describe('在途守卫（至多一个请求）', () => {
    it('慢接口不堆叠：一次在途期间定时器推进多轮也只发一个请求', async () => {
      let resolveFetch!: () => void
      const fetcher = vi.fn(
        () =>
          new Promise<void>((resolve) => {
            resolveFetch = resolve
          })
      )
      mountPolling(fetcher, { intervalMs: 1000 })

      await vi.advanceTimersByTimeAsync(1000)
      expect(fetcher).toHaveBeenCalledTimes(1)

      // 请求耗时 5 个周期：旧 setInterval 写法这里会叠 5 个请求
      await vi.advanceTimersByTimeAsync(5000)
      expect(fetcher).toHaveBeenCalledTimes(1)

      resolveFetch()
      await flush()

      // 完成后才排下一轮 —— 速率降为「实际耗时」
      await vi.advanceTimersByTimeAsync(1000)
      expect(fetcher).toHaveBeenCalledTimes(2)
    })

    it('手动 refresh 与定时 tick 不会并发', async () => {
      let resolveFetch!: () => void
      const fetcher = vi.fn(
        () =>
          new Promise<void>((resolve) => {
            resolveFetch = resolve
          })
      )
      const { api } = mountPolling(fetcher, { intervalMs: 1000 })

      await vi.advanceTimersByTimeAsync(1000) // tick 发起第 1 个
      expect(fetcher).toHaveBeenCalledTimes(1)

      await api.refresh() // 在途，refresh 直接返回不重复发
      expect(fetcher).toHaveBeenCalledTimes(1)

      resolveFetch()
      await flush()
    })
  })

  describe('refresh', () => {
    it('立即拉一次，但不重排既有节拍', async () => {
      const fetcher = vi.fn().mockResolvedValue(undefined)
      const { api } = mountPolling(fetcher, { intervalMs: 1000 })

      await vi.advanceTimersByTimeAsync(400) // 节拍已走 400ms
      await api.refresh()
      expect(fetcher).toHaveBeenCalledTimes(1)

      // refresh 不重置节拍：再走 600ms 就应到原定的第一次 tick
      await vi.advanceTimersByTimeAsync(600)
      expect(fetcher).toHaveBeenCalledTimes(2)
    })

    it('门控为假时 refresh 不启动轮询', async () => {
      const fetcher = vi.fn().mockResolvedValue(undefined)
      const { api } = mountPolling(fetcher, {
        intervalMs: 1000,
        enabled: () => false,
      })

      await api.refresh()
      expect(fetcher).toHaveBeenCalledTimes(1) // 显式调用仍然拉

      await vi.advanceTimersByTimeAsync(5000)
      expect(fetcher).toHaveBeenCalledTimes(1) // 但不会因此起表
    })
  })

  describe('错误处理', () => {
    it('单次失败不中断轮询，默认走 console.warn', async () => {
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
      const fetcher = vi
        .fn()
        .mockRejectedValueOnce(new Error('网络抖动'))
        .mockResolvedValue(undefined)
      mountPolling(fetcher, { intervalMs: 1000 })

      await vi.advanceTimersByTimeAsync(1000)
      expect(warn).toHaveBeenCalled()

      await vi.advanceTimersByTimeAsync(1000)
      expect(fetcher).toHaveBeenCalledTimes(2) // 下一轮照常

      warn.mockRestore()
    })

    it('onError 可接管失败回调', async () => {
      const onError = vi.fn()
      const fetcher = vi.fn().mockRejectedValue(new Error('boom'))
      mountPolling(fetcher, { intervalMs: 1000, onError })

      await vi.advanceTimersByTimeAsync(1000)
      expect(onError).toHaveBeenCalledTimes(1)
      expect((onError.mock.calls[0][0] as Error).message).toBe('boom')
    })
  })

  describe('卸载清理', () => {
    it('卸载后不再排下一轮', async () => {
      const fetcher = vi.fn().mockResolvedValue(undefined)
      const { wrapper } = mountPolling(fetcher, { intervalMs: 1000 })

      await vi.advanceTimersByTimeAsync(1000)
      expect(fetcher).toHaveBeenCalledTimes(1)

      wrapper.unmount()
      await vi.advanceTimersByTimeAsync(10000)
      expect(fetcher).toHaveBeenCalledTimes(1)
    })

    it('卸载时在途请求不 abort，但完成后不续排', async () => {
      let resolveFetch!: () => void
      const fetcher = vi.fn(
        () =>
          new Promise<void>((resolve) => {
            resolveFetch = resolve
          })
      )
      const { wrapper } = mountPolling(fetcher, { intervalMs: 1000 })

      await vi.advanceTimersByTimeAsync(1000)
      wrapper.unmount()

      resolveFetch()
      await flush()

      await vi.advanceTimersByTimeAsync(10000)
      expect(fetcher).toHaveBeenCalledTimes(1)
    })
  })

  describe('参数校验', () => {
    it('intervalMs 非正数时快速失败', () => {
      expect(() => mountPolling(vi.fn(), { intervalMs: 0 })).toThrow(/intervalMs/)
      expect(() =>
        mountPolling(vi.fn(), {} as unknown as UsePollingOptions)
      ).toThrow(/intervalMs/)
      expect(() => mountPolling(vi.fn(), { intervalMs: -1 })).toThrow(/intervalMs/)
    })
  })
})
