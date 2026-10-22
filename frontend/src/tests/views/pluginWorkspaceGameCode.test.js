/**
 * 插件扩展台「进入工作区」的 gameCode 解析回归。
 *
 * 背景（票 07 dnf_tw 用例包暴露的产品缺陷）：
 * 扩展台原先用 pluginId 去掉 "plugin-" 前缀来猜 gameCode，
 * 于是 plugin-dnf-tw → "dnf-tw"，而实例表里的 game_code 是 "dnf_tw"，
 * 按 gameCode 查实例恒为 0 条 → 点「进入工作区」只弹"未找到实例"，
 * 已部署的 dnf_tw 实例永远进不去工作区。
 *
 * 契约：gameCode 的权威来源是插件扩展点 GameEnhancementExtension.getGameCode()，
 * 由后端 PluginStatusVO.gameCode 下发；前端只在缺失时才回退到 pluginId 猜测。
 */
import { describe, it, expect, vi, beforeEach } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import * as ElementPlusIcons from "@element-plus/icons-vue";
import PluginView from "@/views/plugin/index.vue";
import { getPluginList } from "@/api/plugin";
import { getInstanceList } from "@/api/instance";

const routerPush = vi.hoisted(() => vi.fn());

vi.mock("@/api/plugin", () => ({
  getPluginList: vi.fn(),
  startPlugin: vi.fn(),
  stopPlugin: vi.fn(),
  reloadPlugin: vi.fn(),
  unloadPlugin: vi.fn(),
}));

vi.mock("@/api/instance", () => ({
  getInstanceList: vi.fn(),
}));

vi.mock("vue-router", () => ({
  useRouter: () => ({ push: routerPush }),
}));

const DNF_TW_PLUGIN = {
  pluginId: "plugin-dnf-tw",
  // 后端下发的权威值：连字符命名（plugin-dnf-tw）与下划线编码（dnf_tw）不一致
  gameCode: "dnf_tw",
  pluginName: "DNF 台服",
  state: "STARTED",
  running: true,
};

describe("插件扩展台工作区 gameCode", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getPluginList.mockResolvedValue([DNF_TW_PLUGIN]);
    getInstanceList.mockResolvedValue({
      records: [{ id: 42, instanceName: "e2e-dnf-tw-plugin", gameCode: "dnf_tw" }],
      total: 1,
    });
  });

  async function mountView() {
    const wrapper = mount(PluginView, {
      global: {
        plugins: [ElementPlus],
        components: ElementPlusIcons,
      },
    });
    await flushPromises();
    return wrapper;
  }

  it("用后端下发的权威 gameCode 查实例，而不是从 pluginId 猜", async () => {
    const wrapper = await mountView();

    await wrapper.vm.handleEnterPlugin(DNF_TW_PLUGIN);
    await flushPromises();

    expect(getInstanceList).toHaveBeenCalledWith(
      expect.objectContaining({ gameCode: "dnf_tw" }),
    );
    // 反例守卫：插件 ID 推出来的 "dnf-tw" 一旦回流到实例查询就会恒为 0 条
    expect(getInstanceList).not.toHaveBeenCalledWith(
      expect.objectContaining({ gameCode: "dnf-tw" }),
    );
    wrapper.unmount();
  });

  it("唯一实例直接进入工作区，路由使用权威 gameCode", async () => {
    const wrapper = await mountView();

    await wrapper.vm.handleEnterPlugin(DNF_TW_PLUGIN);
    await flushPromises();

    expect(routerPush).toHaveBeenCalledWith({
      path: "/extensions/app/dnf_tw/dashboard",
      query: { instanceId: 42 },
    });
    wrapper.unmount();
  });

  it("后端未下发 gameCode 时回退到 pluginId 推断（兼容旧后端）", async () => {
    const legacy = { ...DNF_TW_PLUGIN, gameCode: undefined };
    const wrapper = await mountView();

    await wrapper.vm.handleEnterPlugin(legacy);
    await flushPromises();

    expect(getInstanceList).toHaveBeenCalledWith(
      expect.objectContaining({ gameCode: "dnf-tw" }),
    );
    wrapper.unmount();
  });
});
