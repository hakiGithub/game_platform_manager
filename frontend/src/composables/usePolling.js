/**
 * usePolling —— 轮询生命周期深模块
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
 *
 * @param {() => (void | Promise<void>)} fetcher 一次拉取；可返回 Promise
 * @param {object} options
 * @param {number}   options.intervalMs          拉取间隔（毫秒）
 * @param {() => boolean} [options.enabled]      门控，默认恒真
 * @param {boolean}  [options.immediate]         启动时是否立即拉一次，默认 false
 * @param {(err: unknown) => void} [options.onError] 单次失败回调，默认 console.warn
 * @returns {{ refresh: () => Promise<void>, isPolling: import('vue').ComputedRef<boolean> }}
 */
import { computed, onBeforeUnmount, ref, watch } from "vue";

export function usePolling(fetcher, options = {}) {
  const {
    intervalMs,
    enabled = () => true,
    immediate = false,
    onError = (err) =>
      console.warn("[usePolling] 单次拉取失败，将在下一轮重试", err),
  } = options;

  if (typeof intervalMs !== "number" || intervalMs <= 0) {
    throw new Error("usePolling: intervalMs 必须是正数");
  }

  let timer = null; // 递归 setTimeout 的句柄
  let inFlight = false; // 在途守卫：至多一个请求
  let disposed = false; // 卸载后不再排下一轮
  const active = ref(false);

  /** 停表：清掉待执行的下一轮（不影响在途请求，也不 abort —— 见 ADR-0030） */
  function stopTimer() {
    if (timer !== null) {
      clearTimeout(timer);
      timer = null;
    }
    active.value = false;
  }

  /** 排下一轮 */
  function schedule() {
    timer = setTimeout(tick, intervalMs);
  }

  /**
   * 一次拉取。手动 refresh() 与定时 tick 共用 —— 同一 fetcher 只有一处引用。
   * @param {boolean} reschedule 完成后是否续排下一轮（手动 refresh 传 false）
   */
  async function run(reschedule) {
    if (inFlight || disposed) return;
    inFlight = true;
    try {
      await fetcher();
    } catch (err) {
      onError(err);
    } finally {
      inFlight = false;
    }
    // 门控可能在拉取期间翻假（watch 已停表）—— 此时不得续排，否则定时器复活
    if (reschedule && !disposed && enabled()) schedule();
  }

  function tick() {
    timer = null;
    // 门控可能已翻假：不拉、不续排，交给 watch(enabled) 重新起表
    if (!enabled()) {
      stopTimer();
      return;
    }
    void run(true);
  }

  /** 起表：立即拉一次（immediate）或直接排下一轮；停表后再起表不重置节拍 */
  function startTimer() {
    if (disposed) return;
    stopTimer();
    active.value = true;
    if (immediate) {
      void run(true);
    } else {
      schedule();
    }
  }

  // enabled 翻真 ⇒ 起表；翻假 ⇒ 停表。不立刻拉取：起表 ≠ 拉取，
  // 需要立即数据的调用方显式 await refresh()。
  watch(
    () => enabled(),
    (on) => {
      if (on) startTimer();
      else stopTimer();
    },
    { immediate: true },
  );

  /** 立即拉一次，不重排节拍（节拍由既有定时器维持） */
  async function refresh() {
    await run(false);
  }

  onBeforeUnmount(() => {
    disposed = true;
    stopTimer();
  });

  return { refresh, isPolling: computed(() => active.value) };
}
