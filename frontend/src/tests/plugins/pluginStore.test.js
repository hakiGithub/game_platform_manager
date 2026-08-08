/**
 * pluginStore.ts 测试
 * 测试插件状态管理
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'

// Mock pluginCommunication
vi.mock('@/plugins/communication/pluginCommunication', () => ({
  fetchPluginManifest: vi.fn()
}))

// 导入被测试模块
import { usePluginStore } from '@/plugins/stores/pluginStore'
import { fetchPluginManifest } from '@/plugins/communication/pluginCommunication'

describe('pluginStore', () => {
  let pluginStore

  beforeEach(() => {
    setActivePinia(createPinia())
    pluginStore = usePluginStore()
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  describe('初始状态', () => {
    it('应该有正确的初始状态', () => {
      expect(pluginStore.currentManifest).toBeNull()
      expect(pluginStore.activeMenuId).toBeNull()
      expect(pluginStore.loading).toBe(false)
      expect(pluginStore.error).toBeNull()
      expect(pluginStore.isReady).toBe(false)
      expect(pluginStore.currentGameCode).toBeNull()
    })
  })

  describe('计算属性', () => {
    describe('menus', () => {
      it('应该返回空数组（无清单）', () => {
        expect(pluginStore.menus).toEqual([])
      })

      it('应该返回菜单列表', () => {
        pluginStore.currentManifest = {
          pluginId: 'plugin-1',
          name: 'Test Plugin',
          version: '1.0.0',
          gameCode: 'minecraft',
          entry: '/plugins/test/',
          menus: [
            { id: 'menu-1', label: '菜单1', path: '/menu1' },
            { id: 'menu-2', label: '菜单2', path: '/menu2' }
          ]
        }

        expect(pluginStore.menus).toHaveLength(2)
        expect(pluginStore.menus[0].id).toBe('menu-1')
      })
    })

    describe('flatMenus', () => {
      it('应该返回扁平化的菜单列表', () => {
        pluginStore.currentManifest = {
          pluginId: 'plugin-1',
          name: 'Test Plugin',
          version: '1.0.0',
          gameCode: 'minecraft',
          entry: '/plugins/test/',
          menus: [
            {
              id: 'menu-1',
              label: '菜单1',
              path: '/menu1',
              children: [
                { id: 'menu-1-1', label: '子菜单1', path: '/menu1/1' },
                { id: 'menu-1-2', label: '子菜单2', path: '/menu1/2' }
              ]
            },
            { id: 'menu-2', label: '菜单2', path: '/menu2' }
          ]
        }

        const flatMenus = pluginStore.flatMenus

        expect(flatMenus).toHaveLength(4)
        expect(flatMenus[0].id).toBe('menu-1')
        expect(flatMenus[0].parentLabel).toBeUndefined()
        expect(flatMenus[1].id).toBe('menu-1-1')
        expect(flatMenus[1].parentLabel).toBe('菜单1')
        expect(flatMenus[2].id).toBe('menu-1-2')
        expect(flatMenus[2].parentLabel).toBe('菜单1')
        expect(flatMenus[3].id).toBe('menu-2')
        expect(flatMenus[3].parentLabel).toBeUndefined()
      })

      it('应该返回空数组（无菜单）', () => {
        expect(pluginStore.flatMenus).toEqual([])
      })
    })

    describe('activeMenu', () => {
      it('应该返回 null（无选中菜单）', () => {
        expect(pluginStore.activeMenu).toBeNull()
      })

      it('应该返回选中的菜单项', () => {
        pluginStore.currentManifest = {
          pluginId: 'plugin-1',
          name: 'Test Plugin',
          version: '1.0.0',
          gameCode: 'minecraft',
          entry: '/plugins/test/',
          menus: [
            { id: 'menu-1', label: '菜单1', path: '/menu1' },
            { id: 'menu-2', label: '菜单2', path: '/menu2' }
          ]
        }

        pluginStore.activeMenuId = 'menu-2'

        expect(pluginStore.activeMenu).toBeDefined()
        expect(pluginStore.activeMenu.id).toBe('menu-2')
        expect(pluginStore.activeMenu.label).toBe('菜单2')
      })

      it('应该返回 null（选中的菜单不存在）', () => {
        pluginStore.currentManifest = {
          pluginId: 'plugin-1',
          name: 'Test Plugin',
          version: '1.0.0',
          gameCode: 'minecraft',
          entry: '/plugins/test/',
          menus: [{ id: 'menu-1', label: '菜单1', path: '/menu1' }]
        }

        pluginStore.activeMenuId = 'not-exist'

        expect(pluginStore.activeMenu).toBeNull()
      })
    })

    describe('hasMenus', () => {
      it('应该返回 false（无菜单）', () => {
        expect(pluginStore.hasMenus).toBe(false)
      })

      it('应该返回 true（有菜单）', () => {
        pluginStore.currentManifest = {
          pluginId: 'plugin-1',
          name: 'Test Plugin',
          version: '1.0.0',
          gameCode: 'minecraft',
          entry: '/plugins/test/',
          menus: [{ id: 'menu-1', label: '菜单1', path: '/menu1' }]
        }

        expect(pluginStore.hasMenus).toBe(true)
      })
    })
  })

  describe('Actions', () => {
    describe('loadManifest', () => {
      it('应该成功加载清单', async () => {
        const mockManifest = {
          pluginId: 'plugin-1',
          name: 'Test Plugin',
          version: '1.0.0',
          gameCode: 'minecraft',
          entry: '/plugins/test/',
          menus: [
            { id: 'menu-1', label: '菜单1', path: '/menu1' },
            { id: 'menu-2', label: '菜单2', path: '/menu2' }
          ]
        }

        fetchPluginManifest.mockResolvedValue(mockManifest)

        const result = await pluginStore.loadManifest('minecraft')

        expect(fetchPluginManifest).toHaveBeenCalledWith('minecraft')
        expect(pluginStore.currentManifest).toEqual(mockManifest)
        expect(pluginStore.currentGameCode).toBe('minecraft')
        expect(pluginStore.activeMenuId).toBe('menu-1') // 默认选中第一个
        expect(pluginStore.loading).toBe(false)
        expect(pluginStore.error).toBeNull()
        expect(result).toEqual(mockManifest)
      })

      it('应该复用已加载的清单', async () => {
        const mockManifest = {
          pluginId: 'plugin-1',
          name: 'Test Plugin',
          version: '1.0.0',
          gameCode: 'minecraft',
          entry: '/plugins/test/',
          menus: []
        }

        // 第一次加载
        fetchPluginManifest.mockResolvedValue(mockManifest)
        await pluginStore.loadManifest('minecraft')

        // 第二次加载相同 gameCode
        fetchPluginManifest.mockClear()
        const result = await pluginStore.loadManifest('minecraft')

        expect(fetchPluginManifest).not.toHaveBeenCalled()
        expect(result).toEqual(mockManifest)
      })

      it('应该处理加载失败', async () => {
        fetchPluginManifest.mockRejectedValue(new Error('Network error'))

        const result = await pluginStore.loadManifest('minecraft')

        expect(pluginStore.currentManifest).toBeNull()
        expect(pluginStore.error).toBe('Network error')
        expect(pluginStore.loading).toBe(false)
        expect(result).toBeNull()
      })

      it('应该处理非 Error 类型的错误', async () => {
        fetchPluginManifest.mockRejectedValue('Unknown error')

        const result = await pluginStore.loadManifest('minecraft')

        expect(pluginStore.error).toBe('加载插件清单失败')
        expect(result).toBeNull()
      })

      it('应该正确设置 loading 状态', async () => {
        let resolveLoad
        fetchPluginManifest.mockImplementation(() => {
          return new Promise(resolve => {
            resolveLoad = resolve
          })
        })

        const loadPromise = pluginStore.loadManifest('minecraft')

        // 检查 loading 状态
        expect(pluginStore.loading).toBe(true)

        // 完成加载
        resolveLoad({
          pluginId: 'plugin-1',
          name: 'Test',
          version: '1.0.0',
          gameCode: 'minecraft',
          entry: '/plugins/test/',
          menus: []
        })

        await loadPromise

        expect(pluginStore.loading).toBe(false)
      })

      it('加载新游戏代码时应该重新加载', async () => {
        const manifest1 = {
          pluginId: 'plugin-1',
          name: 'Plugin 1',
          version: '1.0.0',
          gameCode: 'minecraft',
          entry: '/plugins/test/',
          menus: [{ id: 'm1', label: 'M1', path: '/m1' }]
        }

        const manifest2 = {
          pluginId: 'plugin-2',
          name: 'Plugin 2',
          version: '1.0.0',
          gameCode: 'csgo',
          entry: '/plugins/test/',
          menus: [{ id: 'm2', label: 'M2', path: '/m2' }]
        }

        fetchPluginManifest.mockResolvedValueOnce(manifest1)
        await pluginStore.loadManifest('minecraft')

        fetchPluginManifest.mockResolvedValueOnce(manifest2)
        await pluginStore.loadManifest('csgo')

        expect(pluginStore.currentGameCode).toBe('csgo')
        expect(pluginStore.currentManifest.name).toBe('Plugin 2')
        expect(pluginStore.activeMenuId).toBe('m2')
      })
    })

    describe('setActiveMenu', () => {
      it('应该设置选中的菜单', () => {
        pluginStore.setActiveMenu('menu-1')

        expect(pluginStore.activeMenuId).toBe('menu-1')
      })

      it('应该清除选中的菜单', () => {
        pluginStore.activeMenuId = 'menu-1'

        pluginStore.setActiveMenu(null)

        expect(pluginStore.activeMenuId).toBeNull()
      })
    })

    describe('findMenuByPath', () => {
      beforeEach(() => {
        pluginStore.currentManifest = {
          pluginId: 'plugin-1',
          name: 'Test Plugin',
          version: '1.0.0',
          gameCode: 'minecraft',
          entry: '/plugins/test/',
          menus: [
            {
              id: 'menu-1',
              label: '菜单1',
              path: '/menu1',
              children: [
                { id: 'menu-1-1', label: '子菜单1', path: '/menu1/1' }
              ]
            },
            { id: 'menu-2', label: '菜单2', path: '/menu2' }
          ]
        }
      })

      it('应该根据路径找到菜单', () => {
        const menu = pluginStore.findMenuByPath('/menu2')

        expect(menu).toBeDefined()
        expect(menu.id).toBe('menu-2')
      })

      it('应该找到子菜单', () => {
        const menu = pluginStore.findMenuByPath('/menu1/1')

        expect(menu).toBeDefined()
        expect(menu.id).toBe('menu-1-1')
        expect(menu.parentLabel).toBe('菜单1')
      })

      it('路径不存在时应该返回 null', () => {
        const menu = pluginStore.findMenuByPath('/not-exist')

        expect(menu).toBeNull()
      })
    })

    describe('setReady', () => {
      it('应该设置就绪状态', () => {
        pluginStore.setReady(true)

        expect(pluginStore.isReady).toBe(true)

        pluginStore.setReady(false)

        expect(pluginStore.isReady).toBe(false)
      })
    })

    describe('clear', () => {
      it('应该清除所有状态', () => {
        pluginStore.currentManifest = {
          pluginId: 'plugin-1',
          name: 'Test',
          version: '1.0.0',
          gameCode: 'minecraft',
          entry: '/plugins/test/',
          menus: []
        }
        pluginStore.activeMenuId = 'menu-1'
        pluginStore.loading = true
        pluginStore.error = 'Some error'
        pluginStore.isReady = true
        pluginStore.currentGameCode = 'minecraft'

        pluginStore.clear()

        expect(pluginStore.currentManifest).toBeNull()
        expect(pluginStore.activeMenuId).toBeNull()
        expect(pluginStore.loading).toBe(false)
        expect(pluginStore.error).toBeNull()
        expect(pluginStore.isReady).toBe(false)
        expect(pluginStore.currentGameCode).toBeNull()
      })
    })

    describe('reset', () => {
      it('应该重置状态（等同于 clear）', () => {
        pluginStore.currentManifest = {
          pluginId: 'plugin-1',
          name: 'Test',
          version: '1.0.0',
          gameCode: 'minecraft',
          entry: '/plugins/test/',
          menus: []
        }
        pluginStore.activeMenuId = 'menu-1'
        pluginStore.isReady = true

        pluginStore.reset()

        expect(pluginStore.currentManifest).toBeNull()
        expect(pluginStore.activeMenuId).toBeNull()
        expect(pluginStore.isReady).toBe(false)
      })
    })
  })

  describe('边界情况', () => {
    it('清单无菜单时应该正确处理', async () => {
      const mockManifest = {
        pluginId: 'plugin-1',
        name: 'Test Plugin',
        version: '1.0.0',
        gameCode: 'minecraft',
        entry: '/plugins/test/',
        menus: []
      }

      fetchPluginManifest.mockResolvedValue(mockManifest)

      await pluginStore.loadManifest('minecraft')

      expect(pluginStore.activeMenuId).toBeNull()
      expect(pluginStore.hasMenus).toBe(false)
    })

    it('清单 menus 为 undefined 时应该正确处理', async () => {
      const mockManifest = {
        pluginId: 'plugin-1',
        name: 'Test Plugin',
        version: '1.0.0',
        gameCode: 'minecraft',
        entry: '/plugins/test/'
      }

      fetchPluginManifest.mockResolvedValue(mockManifest)

      await pluginStore.loadManifest('minecraft')

      expect(pluginStore.menus).toEqual([])
      expect(pluginStore.hasMenus).toBe(false)
    })

    it('深层嵌套菜单应该正确扁平化', () => {
      pluginStore.currentManifest = {
        pluginId: 'plugin-1',
        name: 'Test',
        version: '1.0.0',
        gameCode: 'minecraft',
        entry: '/plugins/test/',
        menus: [
          {
            id: 'level-1',
            label: 'Level 1',
            path: '/l1',
            children: [
              {
                id: 'level-2',
                label: 'Level 2',
                path: '/l1/l2',
                children: [
                  {
                    id: 'level-3',
                    label: 'Level 3',
                    path: '/l1/l2/l3'
                  }
                ]
              }
            ]
          }
        ]
      }

      const flatMenus = pluginStore.flatMenus

      expect(flatMenus).toHaveLength(3)
      expect(flatMenus[0].parentLabel).toBeUndefined()
      expect(flatMenus[1].parentLabel).toBe('Level 1')
      expect(flatMenus[2].parentLabel).toBe('Level 2')
    })
  })
})
