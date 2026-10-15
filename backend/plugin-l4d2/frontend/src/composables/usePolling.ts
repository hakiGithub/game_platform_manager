/**
 * usePolling —— 轮询生命周期深模块（主应用 `frontend/src/composables/usePolling.js` 的插件侧副本）
 *
 * ⚠️ 同步关系（ADR-0030）：本文件与主应用同名文件**靠人移植**，非逐字复制
 * （主应用是 .js、本侧是 .ts）。改任一侧后需手工同步另一侧。
 *
 * 承载的不变量（调用方不必知道机制，只知道这三条）：
 *   1. 至多一个在途请求 —— 递归 setTimeout + in-flight 守卫，慢接口下请求速率自动
 *      降为「实际耗时」，绝不堆叠（旧 setInterval 写法会叠加）。
 *   2. 固定节拍 —— 每次拉取完成后才排下一次，间隔 = intervalMs。
 *   3. enabled 是唯一真值源 —— 定时器只在 enabled() 为真时存在；翻真即起表、
 *      翻假即停表。不暴露 start/stop，避免第二真值源。
 *
 * 前置假设：组件卸载即离开页面。若将来启用 keep-alive，需补 onDeactivated 停 /
 * onActivated 按 enabled 恢复 —— 当前路由层无 <keep-alive>（已核实）。
 */
import { computed, onBeforeUnmount, ref, watch, type ComputedRef } from 'vue'

export interface UsePollingOptions {
  /** 拉取间隔（毫秒） */
  intervalMs: number
  /** 门控：定时器只在返回 true 时存在。默认恒真 */
  enabled?: () => boolean
  /** 启动时是否立即拉一次。默认 false */
  immediate?: boolean
  /** 单次失败回调。默认 console.warn */
  onError?: (err: unknown) => void
}

export interface UsePollingReturn {
  /** 立即拉一次，不重排节拍 */
  refresh: () => Promise<void>
  /** 当前是否在轮询（= enabled 为真且未卸载） */
  isPolling: ComputedRef<boolean>
}

export function usePolling(
  fetcher: () => void | Promise<void>,
  options: UsePollingOptions
): UsePollingReturn {
  const {
    intervalMs,
    enabled = () => true,
    immediate = false,
    onError = (err: unknown) =>
      console.warn('[usePolling] 单次拉取失败，将在下一轮重试', err),
  } = options

  if (typeof intervalMs !== 'number' || intervalMs <= 0) {
    throw new Error('usePolling: intervalMs 必须是正数')
  }

  let timer: ReturnType<typeof setTimeout> | null = null // 递归 setTimeout 的句柄
  let inFlight = false // 在途守卫：至多一个请求
  let disposed = false // 卸载后不再排下一轮
  const active = ref(false)

  /** 停表：清掉待执行的下一轮（不影响在途请求，也不 abort —— 见 ADR-0030） */
  function stopTimer(): void {
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
    active.value = false
  }

  /** 排下一轮 */
  function schedule(): void {
    timer = setTimeout(tick, intervalMs)
  }

  /**
   * 一次拉取。手动 refresh() 与定时 tick 共用 —— 同一 fetcher 只有一处引用。
   * @param reschedule 完成后是否续排下一轮（手动 refresh 传 false）
   */
  async function run(reschedule: boolean): Promise<void> {
    if (inFlight || disposed) return
    inFlight = true
    try {
      await fetcher()
    } catch (err) {
      onError(err)
    } finally {
      inFlight = false
    }
    // 门控可能在拉取期间翻假（watch 已停表）—— 此时不得续排，否则定时器复活
    if (reschedule && !disposed && enabled()) schedule()
  }

  function tick(): void {
    timer = null
    // 门控可能已翻假：不拉、不续排，交给 watch(enabled) 重新起表
    if (!enabled()) {
      stopTimer()
      return
    }
    void run(true)
  }

  /** 起表：立即拉一次（immediate）或直接排下一轮；停表后再起表不重置节拍 */
  function startTimer(): void {
    if (disposed) return
    stopTimer()
    active.value = true
    if (immediate) {
      void run(true)
    } else {
      schedule()
    }
  }

  // enabled 翻真 ⇒ 起表；翻假 ⇒ 停表。不立刻拉取：起表 ≠ 拉取，
  // 需要立即数据的调用方显式 await refresh()。
  watch(
    () => enabled(),
    (on) => {
      if (on) startTimer()
      else stopTimer()
    },
    { immediate: true }
  )

  /** 立即拉一次，不重排节拍（节拍由既有定时器维持） */
  async function refresh(): Promise<void> {
    await run(false)
  }

  onBeforeUnmount(() => {
    disposed = true
    stopTimer()
  })

  return { refresh, isPolling: computed(() => active.value) }
}
