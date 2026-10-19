import { beforeEach, describe, expect, it, vi } from "vitest";
import { mount } from "@vue/test-utils";
import { createPinia, setActivePinia } from "pinia";
import { nextTick, ref } from "vue";

const mockRoute = {
  path: "/extensions/app/l4d2/server-info",
  query: {},
  matched: [],
};
const mockRouter = {
  push: vi.fn().mockResolvedValue(),
};

vi.mock("vue-router", async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    useRoute: () => mockRoute,
    useRouter: () => mockRouter,
  };
});

vi.mock("@/api/plugin", () => ({
  getPluginList: vi.fn(),
  getPluginManifestByPluginId: vi.fn(),
}));

vi.mock("@/plugins/communication/pluginCommunication", () => ({
  sendPluginNavigate: vi.fn(),
}));

vi.mock("@/plugins/wujie/apps.config", () => ({
  generateWujieAppName: (gameCode) => `game-${gameCode}`,
}));

import { getPluginList, getPluginManifestByPluginId } from "@/api/plugin";
import Sidebar from "@/layouts/Sidebar.vue";

const slotStub = {
  template: "<div><slot /></div>",
};

beforeEach(() => {
  setActivePinia(createPinia());
  vi.clearAllMocks();
  mockRoute.path = "/extensions/app/l4d2/server-info";
  mockRoute.matched = [];
  getPluginList.mockResolvedValue([
    { pluginId: "plugin-l4d2", pluginName: "求生之路2", running: true },
  ]);
  getPluginManifestByPluginId.mockResolvedValue({
    gameCode: "l4d2",
    gameName: "求生之路2",
    frontend: {
      menus: [
        { path: "/dashboard", title: "仪表盘", order: 1 },
        { path: "/config", title: "配置管理", order: 2 },
        { path: "/server-info", title: "服务器信息", parent: "/config", order: 1 },
      ],
    },
  });
});

async function flushMenuLoad(expectedText = "服务器信息") {
  for (let i = 0; i < 30; i++) {
    await nextTick();
    await Promise.resolve();
    if (document.body.textContent.includes(expectedText)) return;
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
}

function mountSidebar(collapsed = false) {
  return mount(Sidebar, {
    props: { collapsed },
    global: {
      stubs: {
        "el-scrollbar": slotStub,
        "el-icon": slotStub,
        Monitor: slotStub,
        Odometer: slotStub,
        Grid: slotStub,
        Setting: slotStub,
        MagicStick: slotStub,
        Compass: slotStub,
      },
    },
  });
}

describe("Sidebar active selection", () => {
  it("marks only the current leaf as active", async () => {
    const wrapper = mountSidebar();
    await flushMenuLoad();
    const activeLeaves = wrapper.findAll(".el-menu-item.is-active");
    expect(activeLeaves).toHaveLength(1);
    expect(activeLeaves[0].text()).toContain("服务器信息");
  });

  it("retains ancestor expansion without treating it as a selected leaf", async () => {
    const wrapper = mountSidebar();
    await flushMenuLoad();

    const activeAncestorTitles = wrapper.findAll(
      ".el-sub-menu.is-active > .el-sub-menu__title"
    );
    expect(activeAncestorTitles.length).toBeGreaterThan(0);
    expect(wrapper.findAll(".el-menu-item.is-active")).toHaveLength(1);
  });

  it("keeps the unique leaf when the sidebar is collapsed", async () => {
    const wrapper = mountSidebar(true);
    await flushMenuLoad();

    const menu = wrapper.findComponent({ name: "ElMenu" });
    expect(menu.props("defaultActive")).toBe(
      "/extensions/app/l4d2/server-info"
    );
    expect(menu.classes()).toContain("el-menu--collapse");
  });

  it("does not retain a plugin leaf selection outside the plugin tree", async () => {
    mockRoute.path = "/services/instances/list";
    const wrapper = mountSidebar();
    await flushMenuLoad("求生之路2");

    expect(wrapper.findAll(".el-menu-item.is-active")).toHaveLength(1);
    expect(
      wrapper
        .findAll(".el-menu-item.is-active")
        .some((item) => item.text().includes("服务器信息"))
    ).toBe(false);
  });
});
