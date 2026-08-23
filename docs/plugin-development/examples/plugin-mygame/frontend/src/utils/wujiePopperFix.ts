/**
 * Wujie 嵌入模式下 el-select 下拉弹层的几何修正。
 *
 * 现象：Element Plus 的 popper 定位在 Wujie 沙箱下计算错误
 * （offsetParent 跨 shadow 解析 + iframe window 尺寸不可用），
 * 下拉框会漂移到错误位置（叠进结果表格 / 视口左上角）。
 *
 * 方案：不改 popper 配置（改 strategy/modifiers 在沙箱下会破坏
 * 弹层开关），在每次点击/滚动后（popper 完成自身定位），
 * 用展开状态输入框（aria-expanded="true"）所属 .el-select 的
 * 真实 rect，把可见弹层改为 position:fixed 并贴合到正下方。
 */
export function installWujieSelectPopperFix(): () => void {
  if (!window.__POWERED_BY_WUJIE__) {
    return () => {}
  }

  const SELECT_GAP = 12

  const correct = () => {
    const inputs = Array.from(
      document.querySelectorAll<HTMLElement>('.el-select input[aria-expanded="true"]')
    )
    if (!inputs.length) return
    const poppers = Array.from(
      document.querySelectorAll<HTMLElement>('.el-select__popper')
    ).filter((p) => getComputedStyle(p).display !== 'none')
    if (!poppers.length) return

    for (const input of inputs) {
      const sel = input.closest('.el-select') as HTMLElement | null
      if (!sel) continue
      const rect = sel.getBoundingClientRect()
      const popper = poppers.length === 1 ? poppers[0] : poppers.find((p) => !p.dataset.wujieFixed)
      if (!popper) continue
      popper.dataset.wujieFixed = '1'
      popper.style.position = 'fixed'
      popper.style.inset = 'auto'
      popper.style.transform = 'none'
      popper.style.top = `${Math.round(rect.bottom + SELECT_GAP)}px`
      popper.style.left = `${Math.round(rect.left)}px`
    }
  }

  const schedule = () => {
    requestAnimationFrame(() => requestAnimationFrame(correct))
  }

  document.addEventListener('click', schedule, true)
  window.addEventListener('scroll', schedule, true)
  window.addEventListener('resize', schedule, true)

  return () => {
    document.removeEventListener('click', schedule, true)
    window.removeEventListener('scroll', schedule, true)
    window.removeEventListener('resize', schedule, true)
  }
}
