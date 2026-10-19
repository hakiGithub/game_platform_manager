import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent } from 'vue'

const api = vi.hoisted(() => ({
  listBuiltin: vi.fn(),
  list: vi.fn(),
  categories: vi.fn(),
  installBuiltin: vi.fn(),
  batchInstallBuiltin: vi.fn(),
  installBuiltinStatus: vi.fn(),
  tasks: vi.fn(),
  cancelTask: vi.fn(),
  detail: vi.fn(),
  download: vi.fn(),
  getConfig: vi.fn(),
  saveConfig: vi.fn(),
  testConfig: vi.fn(),
  upload: vi.fn(),
  enableLoad: vi.fn(),
  disableUnload: vi.fn(),
  delete: vi.fn(),
  batchEnable: vi.fn(),
  batchDisable: vi.fn(),
  installPlatform: vi.fn(),
  exportAllStart: vi.fn(),
  exportAllStatus: vi.fn(),
  exportAllCancel: vi.fn(),
  exportAllDownloadUrl: vi.fn()
}))

vi.mock('@/api', () => ({
  pluginManageApi: {
    listBuiltin: api.listBuiltin,
    installBuiltin: api.installBuiltin,
    batchInstallBuiltin: api.batchInstallBuiltin,
    installBuiltinStatus: api.installBuiltinStatus,
    upload: api.upload,
    enableLoad: api.enableLoad,
    disableUnload: api.disableUnload,
    delete: api.delete,
    batchEnable: api.batchEnable,
    batchDisable: api.batchDisable,
    installPlatform: api.installPlatform,
    exportAllStart: api.exportAllStart,
    exportAllStatus: api.exportAllStatus,
    exportAllCancel: api.exportAllCancel,
    exportAllDownloadUrl: api.exportAllDownloadUrl
  },
  pluginStoreApi: {
    list: api.list,
    categories: api.categories,
    tasks: api.tasks,
    cancelTask: api.cancelTask,
    detail: api.detail,
    download: api.download,
    getConfig: api.getConfig,
    saveConfig: api.saveConfig,
    testConfig: api.testConfig
  },
  STORE_NOT_CONFIGURED_CODE: 1550,
  STORE_TASK_RUNNING_STATUSES: ['PENDING', 'RUNNING', 'DOWNLOADING', 'INSTALLING']
}))

vi.mock('@/api/request', () => ({
  ApiError: class ApiError extends Error {
    code: number
    constructor(message: string, code: number) {
      super(message)
      this.code = code
    }
  }
}))

import InstalledPluginsTab from './InstalledPluginsTab.vue'
import BuiltinMarketTab from './BuiltinMarketTab.vue'
import RemoteStoreTab from './RemoteStoreTab.vue'
import { usePluginStore } from '@/stores/plugin'

const ElPaginationStub = defineComponent({
  name: 'ElPagination',
  props: {
    currentPage: { type: Number, default: 1 },
    pageSize: { type: Number, default: 20 },
    total: { type: Number, default: 0 }
  },
  emits: ['update:currentPage', 'update:pageSize', 'current-change', 'size-change'],
  data() {
    return {
      localPage: this.currentPage as number,
      localPageSize: this.pageSize as number
    }
  },
  watch: {
    currentPage(value: number) {
      this.localPage = value
    },
    pageSize(value: number) {
      this.localPageSize = value
    }
  },
  methods: {
    next() {
      this.localPage += 1
      this.$emit('update:currentPage', this.localPage)
      this.$emit('current-change', this.localPage)
    },
    changeSize() {
      this.localPageSize = 50
      this.$emit('update:pageSize', 50)
      this.$emit('size-change', 50)
    }
  },
  template: `<div class="pagination-stub">
    <button class="next-page" @click="next">next</button>
    <button class="change-size" @click="changeSize">size</button>
  </div>`
})

const ElRadioGroupStub = defineComponent({
  name: 'ElRadioGroup',
  props: ['modelValue'],
  emits: ['update:modelValue', 'change'],
  methods: {
    select(value: string) {
      this.$emit('update:modelValue', value)
      this.$emit('change', value)
    }
  },
  template: '<div class="radio-group-stub"><slot /></div>'
})

const ElTableStub = defineComponent({
  name: 'ElTable',
  methods: {
    clearSelection() {
      this.$emit('selection-change', [])
    }
  },
  template: '<div class="table-stub"><slot /></div>'
})

function mountWithStore(component: any, props = {}) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const store = usePluginStore()
  store.setInstanceInfo({
    instanceId: 9,
    instanceName: 'test',
    gameCode: 'l4d2',
    deployType: 'docker'
  } as any)
  return mount(component, {
    props,
    global: {
      plugins: [pinia],
      mocks: {
        $router: { push: vi.fn() },
        $route: { query: {} }
      },
      provide: {
        [Symbol.for('router')]: { push: vi.fn() },
        [Symbol.for('routeLocationKey')]: { query: {} }
      },
      stubs: {
        ElPagination: ElPaginationStub,
        ElTabs: { template: '<div><slot /></div>' },
        ElTabPane: { template: '<div><slot /></div>' },
        ElTable: ElTableStub,
        ElTableColumn: { template: '<div />' },
        ElCard: { template: '<div><slot /></div>' },
        ElDrawer: { template: '<div><slot /></div>' },
        ElDialog: { template: '<div><slot /></div>' },
        ElUpload: { template: '<div><slot /></div>' },
        ElProgress: { template: '<div><slot /></div>' },
        ElResult: { template: '<div><slot /></div>' },
        ElEmpty: { template: '<div><slot /></div>' },
        ElSelect: { template: '<div><slot /></div>' },
        ElOption: { template: '<div><slot /></div>' },
        ElRadioGroup: ElRadioGroupStub,
        ElRadioButton: true,
        ElButton: { template: '<button><slot /></button>' },
        ElInput: { template: '<input />' },
        ElTag: { template: '<span><slot /></span>' },
        ElScrollbar: { template: '<div><slot /></div>' },
        ElDivider: { template: '<div />' },
        TaskProgressDialog: { template: '<div />' },
        PluginTaskStrip: { template: '<div />' },
        ElButtonGroup: { template: '<div><slot /></div>' },
        ElForm: { template: '<div><slot /></div>' },
        ElFormItem: { template: '<div><slot /></div>' }
      }
    }
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  api.listBuiltin.mockImplementation(async (_instanceId: number, params: { page?: number; size?: number }) => ({
    current: params.page || 1,
    size: params.size || 20,
    total: 1,
    pages: 1,
    records: [{ id: 'a', name: 'A', category: 'required', platform: 'all', size: 1, installed: false }]
  }))
  api.list.mockImplementation(async (params: { page?: number; size?: number }) => ({
    current: params.page || 1,
    size: params.size || 20,
    total: 1,
    pages: 1,
    records: [{ pluginId: 'a', name: 'A' }]
  }))
  api.categories.mockResolvedValue(['plugin'])
  api.tasks.mockResolvedValue([])
})

describe('插件列表分页交互', () => {
  it('已安装列表翻页和切换页大小会回传父级', async () => {
    const wrapper = mountWithStore(InstalledPluginsTab, {
      plugins: [], loading: false, total: 100, page: 1, pageSize: 20
    })
    await wrapper.find('.next-page').trigger('click')
    expect(wrapper.emitted('page-change')?.[0]).toEqual([2])
    await wrapper.find('.change-size').trigger('click')
    expect(wrapper.emitted('size-change')?.[0]).toEqual([50])
  })

  it('内置市场筛选变化回到第一页并带上筛选条件', async () => {
    const wrapper = mountWithStore(BuiltinMarketTab)
    await flushPromises()
    expect(api.listBuiltin).toHaveBeenCalled()
    await wrapper.find('.next-page').trigger('click')
    await flushPromises()
    expect(api.listBuiltin).toHaveBeenLastCalledWith(9, expect.objectContaining({ page: 2 }))
    const installedGroup = wrapper.findAllComponents(ElRadioGroupStub)[1]
    installedGroup.vm.select('uninstalled')
    await flushPromises()
    expect(api.listBuiltin).toHaveBeenLastCalledWith(9, expect.objectContaining({
      installed: 'uninstalled', page: 1, size: 20
    }))
  })

  it('远端市场页大小变化回到第一页并保留安装状态筛选', async () => {
    const wrapper = mountWithStore(RemoteStoreTab, { installedNames: [] })
    await flushPromises()
    await wrapper.find('.next-page').trigger('click')
    await flushPromises()
    expect(api.list).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2 }))
    const installedGroup = wrapper.findAllComponents(ElRadioGroupStub)[0]
    installedGroup.vm.select('installed')
    await flushPromises()
    await wrapper.find('.change-size').trigger('click')
    await flushPromises()
    expect(api.list).toHaveBeenLastCalledWith(expect.objectContaining({
      installed: 'installed', instanceId: 9, page: 1, size: 50
    }))
  })
})
