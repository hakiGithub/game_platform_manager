/**
 * 跨插件导航实例 ID 泄漏回归测试
 *
 * Bug 场景：从插件 A 的菜单点击进入插件 B 的菜单时，
 * Sidebar 把 A 的 ?instanceId=xx 原样带到 B 的 URL（handleSelect 无条件携带 query），
 * 且 PluginTab 反查未命中时回退 props.instanceId，导致 B 子应用以 A 的实例 ID 渲染。
 *
 * 期望：
 * 1. Sidebar：跨插件（gameCode 变化）导航不携带旧 instanceId；同插件内切换保留
 * 2. PluginTab：URL 携带不属于当前 gameCode 的 instanceId 时，清除无效值并走正常选择流程
 */

import { describe, it, expect, vi, beforeEach } from "vitest";
import { mount, config } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { defineComponent, ref, nextTick } from "vue";

// ---------- 公共 mock：vue-router ----------
const mockRoute = {
  path: "/extensions/app/gameA/dashboard",
  query: { instanceId: "999" },
  params: {},
  matched: [],
};
const mockRouter = { push: vi.fn(), replace: vi.fn() };

vi.mock("vue-router", () => ({
  useRoute: () => mockRoute,
  useRouter: () => mockRouter,
}));

// ---------- 公共 mock：element-plus（部分 mock：组件走真实导出，仅消息提示打桩） ----------
vi.mock("element-plus", async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    ElMessage: { warning: vi.fn(), error: vi.fn(), success: vi.fn() },
  };
});

// ---------- Sidebar 依赖 mock ----------
vi.mock("@/api/plugin", () => ({
  getPluginList: vi.fn().mockResolvedValue([]),
  getPluginManifestByPluginId: vi.fn(),
}));

// ---------- PluginTab 依赖 mock ----------
const getInstanceListMock = vi.fn();
vi.mock("@/api/instance", () => ({
  getInstanceList: (...args) => getInstanceListMock(...args),
}));

vi.mock("@/utils/instanceStatus", () => ({
  statusType: () => "info",
}));

// PluginContainer stub：跟踪"当前挂载中"的组件 props（卸载即清空）
import { onUnmounted } from "vue";
const pluginContainerProps = ref(null);
vi.mock("@/plugins/components/PluginContainer.vue", () => ({
  default: defineComponent({
    name: "PluginContainer",
    props: [
      "src",
      "instanceId",
      "gameCode",
      "instanceName",
      "hostId",
      "hostIp",
      "deployPath",
      "ports",
    ],
    setup(props) {
      pluginContainerProps.value = props;
      onUnmounted(() => {
        if (pluginContainerProps.value === props) pluginContainerProps.value = null;
      });
      return () => null;
    },
  }),
}));

const manifestRef = ref(null);
vi.mock("@/plugins/stores/pluginStore", () => ({
  usePluginStore: () => ({
    get loading() {
      return false;
    },
    get error() {
      return null;
    },
    get menus() {
      return manifestRef.value?.menus || [];
    },
    get currentManifest() {
      return manifestRef.value;
    },
    loadManifest: vi.fn(async (gameCode) => {
      manifestRef.value = {
        pluginId: `plugin-${gameCode}`,
        name: `Plugin ${gameCode}`,
        version: "1.0.0",
        gameCode,
        entry: `/pf4j/plugin/${gameCode}/ui/index.html`,
        menus: [
          { id: "m1", label: "仪表盘", path: "/dashboard", requireInstance: true },
        ],
      };
      return manifestRef.value;
    }),
  }),
}));

import Sidebar from "@/layouts/Sidebar.vue";
import PluginTab from "@/plugins/components/PluginTab.vue";

config.global.config.warnHandler = () => {}; // 屏蔽未知组件告警噪音

// el-menu 由真实 Element Plus 组件渲染，通过 findComponent({ name: "ElMenu" }) 触发 select
// 容器类组件用透传 slot 的 stub（默认 stub 不渲染子内容，会吞掉 el-menu）
import { h } from "vue";
const slotStub = defineComponent({
  name: "SlotStub",
  setup(_, { slots }) {
    return () => h("div", slots.default?.());
  },
});

beforeEach(() => {
  setActivePinia(createPinia());
  vi.clearAllMocks();
  mockRoute.query = { instanceId: "999" };
  manifestRef.value = null;
  pluginContainerProps.value = null;
});

describe("Sidebar.handleSelect 跨插件导航", () => {
  function mountSidebar() {
    return mount(Sidebar, {
      props: { collapsed: false },
      global: {
        stubs: {
          "el-sub-menu": slotStub,
          "el-menu-item": slotStub,
          "el-scrollbar": slotStub,
          "el-icon": slotStub,
          "el-badge": slotStub,
        },
      },
    });
  }

  async function emitMenuSelect(wrapper, targetPath) {
    const menu = wrapper.findComponent({ name: "ElMenu" });
    expect(menu.exists()).toBe(true);
    menu.vm.$emit("select", targetPath);
    await nextTick();
  }

  it("跨插件（gameCode 变化）导航不应携带旧 instanceId", async () => {
    mockRoute.path = "/extensions/app/gameA/dashboard"; // 当前在插件 A
    const wrapper = mountSidebar();
    await nextTick();

    await emitMenuSelect(wrapper, "/extensions/app/gameB/dashboard");

    expect(mockRouter.push).toHaveBeenCalledTimes(1);
    const call = mockRouter.push.mock.calls[0][0];
    // 兼容字符串路径与对象两种导航形式
    const pushedPath = typeof call === "string" ? call : call.path;
    const pushedQuery = typeof call === "string" ? undefined : call.query;
    expect(pushedPath).toBe("/extensions/app/gameB/dashboard");
    // 修复后：跨插件不携带 A 的 instanceId
    expect(pushedQuery).toBeUndefined();
  });

  it("同插件内切换菜单应保留 instanceId", async () => {
    mockRoute.path = "/extensions/app/gameA/dashboard";
    const wrapper = mountSidebar();
    await nextTick();

    await emitMenuSelect(wrapper, "/extensions/app/gameA/maps");

    expect(mockRouter.push).toHaveBeenCalledTimes(1);
    const call = mockRouter.push.mock.calls[0][0];
    expect(call.path).toBe("/extensions/app/gameA/maps");
    // 同插件：保留已选实例
    expect(call.query).toEqual({ instanceId: "999" });
  });
});

describe("PluginTab 无效 instanceId 自愈", () => {
  function mountPluginTab(gameCode, instanceId) {
    mockRoute.path = `/extensions/app/${gameCode}/dashboard`;
    return mount(PluginTab, {
      props: {
        gameCode,
        menuPath: "dashboard",
        instanceId,
      },
      global: {
        stubs: {
          "el-result": true,
          "el-empty": true,
          "el-dialog": true,
          "el-alert": true,
          "el-table": true,
          "el-table-column": true,
          "el-button": true,
          "el-dropdown": true,
          "el-dropdown-menu": true,
          "el-dropdown-item": true,
          "el-icon": true,
        },
      },
    });
  }

  it("URL 携带他插件实例 ID 时应清除并重新选择（多个实例弹窗）", async () => {
    // B 插件有 2 个实例（201/202），URL 却带着 A 插件的 999
    getInstanceListMock.mockResolvedValue({
      records: [
        { id: 201, instanceName: "b-1", hostId: 1, hostIp: "1.1.1.1" },
        { id: 202, instanceName: "b-2", hostId: 1, hostIp: "1.1.1.1" },
      ],
    });

    const wrapper = mountPluginTab("gameB", 999);
    // 等待 watch immediate → loadManifest → ensureInstanceOrPrompt 完成
    await new Promise((r) => setTimeout(r, 50));

    // 修复后：清除无效 instanceId（router.replace 去掉 query）
    expect(mockRouter.replace).toHaveBeenCalled();
    const replaceQuery = mockRouter.replace.mock.calls[0][0]?.query ?? {};
    expect(replaceQuery.instanceId).toBeUndefined();

    // 修复后：多实例待选时不应挂载子应用，更不允许把 A 插件的 999 传给子应用
    expect(pluginContainerProps.value?.instanceId).not.toBe(999);
    expect(wrapper.findComponent({ name: "PluginContainer" }).exists()).toBe(false);
    wrapper.unmount();
  });

  it("URL 携带他插件实例 ID 且仅一个实例时应自动切换到本插件实例", async () => {
    getInstanceListMock.mockResolvedValue({
      records: [{ id: 201, instanceName: "b-only", hostId: 1, hostIp: "1.1.1.1" }],
    });

    const wrapper = mountPluginTab("gameB", 999);
    await new Promise((r) => setTimeout(r, 50));

    // 唯一实例：自动选中（selectInstance 写入 query）
    expect(mockRouter.replace).toHaveBeenCalledWith(
      expect.objectContaining({ query: { instanceId: 201 } })
    );
    wrapper.unmount();
  });
});
