import { ref } from 'vue'

export const DEFAULT_PLUGIN_PAGE_SIZE = 20
export const PLUGIN_PAGE_SIZES = [10, 20, 50, 100]

export interface PluginPageResponse<T> {
  current?: number
  size?: number
  total?: number
  pages?: number
  records?: T[]
}

/**
 * 插件列表共享分页状态。
 *
 * 页码、页大小和各列表过滤条件互不共享；筛选和页大小变化统一回到第一页，
 * 后端返回的 current 用于纠正越界页。
 */
export function usePluginPagination<T>() {
  const page = ref(1)
  const pageSize = ref(DEFAULT_PLUGIN_PAGE_SIZE)
  const total = ref(0)
  const records = ref<T[]>([])

  function apply(response: PluginPageResponse<T> | null | undefined): number {
    records.value = Array.isArray(response?.records) ? response.records : []
    total.value = Number(response?.total) || 0
    const effectivePage = Number(response?.current) || 1
    page.value = effectivePage
    return effectivePage
  }

  function resetForFilter() {
    page.value = 1
  }

  function changePage(nextPage: number) {
    page.value = Math.max(1, Number(nextPage) || 1)
  }

  function changeSize(nextSize: number) {
    pageSize.value = Number(nextSize) || pageSize.value
    page.value = 1
  }

  function clear() {
    records.value = []
    total.value = 0
    page.value = 1
  }

  return {
    page,
    pageSize,
    total,
    records,
    apply,
    resetForFilter,
    changePage,
    changeSize,
    clear
  }
}
