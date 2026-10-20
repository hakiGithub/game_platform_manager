import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import Logs from './Logs.vue'

const listFiles = vi.fn()
const readContent = vi.fn()
const searchContent = vi.fn()

vi.mock('@/api', () => ({
  logsApi: {
    listFiles: (...args: unknown[]) => listFiles(...args),
    readContent: (...args: unknown[]) => readContent(...args),
    searchContent: (...args: unknown[]) => searchContent(...args)
  }
}))

vi.mock('@/stores/plugin', () => ({
  usePluginStore: () => ({ instanceInfo: { instanceId: 97 } })
}))

vi.mock('element-plus', () => ({
  ElMessage: { warning: vi.fn(), error: vi.fn() }
}))

vi.mock('@/components/LogViewer.vue', () => ({
  default: {
    name: 'LogViewer',
    props: ['logs'],
    template: '<div class="log-viewer">{{ logs.join("\\n") }}</div>'
  }
}))

const tableStub = {
  props: ['data'],
  template: '<div><button v-for="row in data" :key="row.name" class="file-row" @click="$emit(\'row-click\', row)">{{ row.name }}</button></div>'
}

const inputStub = {
  props: ['modelValue'],
  emits: ['update:modelValue'],
  template: '<input class="search-input" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
}

function mountLogs() {
  return mount(Logs, {
    global: {
      directives: {
        loading: {}
      },
      stubs: {
        'el-row': { template: '<div><slot /></div>' },
        'el-col': { template: '<div><slot /></div>' },
        'el-card': { template: '<section><slot name="header" /><slot /></section>' },
        'el-table': tableStub,
        'el-table-column': { template: '<div />' },
        'el-input': inputStub,
        'el-button': {
          emits: ['click'],
          template: '<button @click="$emit(\'click\')"><slot /></button>'
        },
        'el-button-group': { template: '<div><slot /></div>' },
        'el-tag': { template: '<span><slot /></span>' }
      }
    }
  })
}

describe('Logs 文件选择', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    listFiles.mockResolvedValue([
      { name: 'errors_20261018.log', path: '', size: 1, lastModified: 1, isErrorLog: true },
      { name: 'L261020.log', path: '', size: 1, lastModified: 2, isErrorLog: false }
    ])
    readContent.mockResolvedValue('second file line')
    searchContent.mockResolvedValue('matched line')
  })

  it('点击第二行后标题和内容切换到该文件', async () => {
    const wrapper = mountLogs()
    await flushPromises()

    await wrapper.findAll('.file-row')[1].trigger('click')
    await flushPromises()

    expect(wrapper.text()).toContain('L261020.log')
    expect(wrapper.text()).toContain('second file line')
    expect(readContent).toHaveBeenCalledWith(97, 'L261020.log')
  })

  it('搜索输入调用远端 grep 接口', async () => {
    const wrapper = mountLogs()
    await flushPromises()
    await wrapper.findAll('.file-row')[1].trigger('click')
    await flushPromises()

    await wrapper.find('.search-input').setValue('error | quote')
    await wrapper.findAll('button').find(button => button.text() === '搜索')!.trigger('click')
    await flushPromises()

    expect(searchContent).toHaveBeenCalledWith(97, 'L261020.log', 'error | quote')
    expect(wrapper.text()).toContain('matched line')
  })

  it('搜索后的轮询继续携带关键字', async () => {
    vi.useFakeTimers()
    try {
      const wrapper = mountLogs()
      await flushPromises()
      await wrapper.findAll('.file-row')[1].trigger('click')
      await flushPromises()

      await wrapper.find('.search-input').setValue('timeout')
      await wrapper.findAll('button').find(button => button.text() === '搜索')!.trigger('click')
      await flushPromises()
      expect(searchContent).toHaveBeenCalledTimes(1)

      await vi.advanceTimersByTimeAsync(2000)
      await flushPromises()

      expect(searchContent).toHaveBeenCalledTimes(2)
      expect(searchContent).toHaveBeenLastCalledWith(97, 'L261020.log', 'timeout')
      expect(readContent).toHaveBeenCalledTimes(1)
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })
})
