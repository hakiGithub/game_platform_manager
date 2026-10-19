import { describe, it, expect, vi, beforeEach } from "vitest";
import { flushPromises, mount } from "@vue/test-utils";
import ElementPlus from "element-plus";
import * as ElementPlusIcons from "@element-plus/icons-vue";
import InstanceView from "@/views/instance/index.vue";
import { deleteInstance, getInstanceList } from "@/api/instance";
import { getHostList } from "@/api/host";

const messages = vi.hoisted(() => ({
  success: vi.fn(),
  warning: vi.fn(),
  error: vi.fn(),
}));

vi.mock("@/api/instance", () => ({
  getInstanceList: vi.fn(),
  startInstance: vi.fn(),
  stopInstance: vi.fn(),
  restartInstance: vi.fn(),
  deleteInstance: vi.fn(),
  retryDeploy: vi.fn(),
}));

vi.mock("@/api/host", () => ({
  getHostList: vi.fn(),
}));

vi.mock("vue-router", () => ({
  useRouter: () => ({ push: vi.fn() }),
}));

vi.mock("element-plus", async (importOriginal) => ({
  ...(await importOriginal()),
  ElMessage: messages,
  ElMessageBox: { confirm: vi.fn().mockResolvedValue(true) },
}));

describe("实例删除反馈", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    getHostList.mockResolvedValue({ records: [], total: 0 });
    getInstanceList.mockResolvedValue({
      records: [
        {
          id: 1,
          instanceName: "dnf-tw",
          gameName: "DNF",
          hostName: "test-host",
          hostIp: "127.0.0.1",
          port: 3000,
          status: "stopped",
          runtimeMetadata: {},
        },
      ],
      total: 1,
    });
  });

  async function mountAndConfirmDelete() {
    const wrapper = mount(InstanceView, {
      global: {
        plugins: [ElementPlus],
        stubs: { DeployProgress: true },
        components: ElementPlusIcons,
      },
    });
    await flushPromises();
    wrapper.vm.deleteTarget = wrapper.vm.tableData[0];
    wrapper.vm.deleteConfirmName = "dnf-tw";
    await wrapper.vm.confirmDelete();
    await flushPromises();
    wrapper.unmount();
  }

  it("部分成功时显示警告、残留路径，而不是纯成功", async () => {
    deleteInstance.mockResolvedValue({
      instanceId: 1,
      deleted: true,
      remoteCleanupStatus: "partial_success",
      message: "实例记录已删除，但远端资源清理未完全完成",
      residualPaths: ["/home/haku/games/dnf-tw"],
    });

    await mountAndConfirmDelete();

    expect(messages.warning).toHaveBeenCalledWith(
      "实例记录已删除，但远端资源清理未完全完成（残留路径：/home/haku/games/dnf-tw）",
    );
    expect(messages.success).not.toHaveBeenCalled();
  });

  it("远端清理完整成功时显示后端返回的成功消息", async () => {
    deleteInstance.mockResolvedValue({
      instanceId: 1,
      deleted: true,
      remoteCleanupStatus: "success",
      message: "实例及其远端资源已删除",
      residualPaths: [],
    });

    await mountAndConfirmDelete();

    expect(messages.success).toHaveBeenCalledWith("实例及其远端资源已删除");
    expect(messages.warning).not.toHaveBeenCalled();
  });
});
