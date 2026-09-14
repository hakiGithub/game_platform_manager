import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import ElementPlus from "element-plus";
import AdoptInstanceDialog from "@/components/docker/AdoptInstanceDialog.vue";
import { adoptContainer, getContainerDetail } from "@/api/docker";
import { getGameList } from "@/api/game";

vi.mock("@/api/docker", () => ({
  adoptContainer: vi.fn(),
  getContainerDetail: vi.fn(),
}));
vi.mock("@/api/game", () => ({
  getGameList: vi.fn(),
}));
// 保留真实 Element Plus 组件，仅替换 ElMessage 以捕获提示
vi.mock("element-plus", async (importOriginal) => ({
  ...(await importOriginal()),
  ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
}));

describe("AdoptInstanceDialog", () => {
  const games = [
    { id: 5, gameName: "L4D2", gameCode: "l4d2", supportedDeployTypes: ["docker", "docker-compose"] },
    { id: 7, gameName: "某纯Compose游戏", gameCode: "abc", supportedDeployTypes: ["docker-compose"] },
  ];

  beforeEach(() => {
    vi.clearAllMocks();
    getGameList.mockResolvedValue(games);
    getContainerDetail.mockResolvedValue({
      containerId: "27e77bb5760a",
      containerName: "l4d2",
      imageName: "laoyutang/l4d2-pure:latest",
      status: "running",
      labels: {
        "com.docker.compose.project": "game64",
        "com.docker.compose.project.working_dir": "/home/haki/games/l4d2",
        "com.docker.compose.service": "l4d2",
      },
    });
  });

  afterEach(() => {
    document.body.innerHTML = "";
  });

  async function open(overrides = {}) {
    const wrapper = mount(AdoptInstanceDialog, {
      props: {
        modelValue: true,
        hostId: 2,
        container: {
          containerId: "27e77bb5760a",
          containerName: "l4d2",
          imageName: "laoyutang/l4d2-pure:latest",
        },
        ...overrides,
      },
      global: { plugins: [ElementPlus] },
      attachTo: document.body,
    });
    // ElDialog 在挂载时（modelValue 已为 true）不会触发 open 事件，显式调用初始化逻辑
    await wrapper.vm.onOpen();
    await flushPromises();
    return wrapper;
  }

  it("打开时预填实例名=容器名，并按镜像名猜中游戏", async () => {
    const wrapper = await open();
    expect(wrapper.vm.form.instanceName).toBe("l4d2");
    expect(wrapper.vm.form.gameId).toBe(5);
    expect(wrapper.vm.guessed).toBe(true);
  });

  it("支持 docker 的游戏解析为 docker 类型，且可直接提交", async () => {
    const wrapper = await open();
    expect(wrapper.vm.resolvedDeployType).toBe("docker");
    expect(wrapper.vm.canSubmit).toBe(true);
  });

  it("仅支持 docker-compose 的游戏解析为 compose 并预填 labels", async () => {
    const wrapper = await open();
    wrapper.vm.form.gameId = 7;
    await flushPromises();
    expect(wrapper.vm.resolvedDeployType).toBe("docker-compose");
    expect(wrapper.vm.form.projectName).toBe("game64");
    expect(wrapper.vm.form.workDir).toBe("/home/haki/games/l4d2");
    expect(wrapper.vm.form.serviceName).toBe("l4d2");
  });

  it("提交调用 adoptContainer 并 emit adopted", async () => {
    adoptContainer.mockResolvedValue(100);
    const wrapper = await open();
    await wrapper.vm.onSubmit();
    expect(adoptContainer).toHaveBeenCalledWith(
      2,
      "27e77bb5760a",
      expect.objectContaining({ instanceName: "l4d2", gameId: 5, deployType: "docker" }),
    );
    expect(wrapper.emitted("adopted")).toBeTruthy();
    expect(wrapper.emitted("adopted")[0][0].instanceId).toBe(100);
  });

  it("无镜像名匹配时不预选游戏，compose 字段缺失时不可提交", async () => {
    getContainerDetail.mockResolvedValue({
      containerId: "y",
      containerName: "mystery",
      imageName: "unknown/image:latest",
      labels: {},
    });
    const wrapper = await open({
      container: { containerId: "y", containerName: "mystery", imageName: "unknown/image:latest" },
    });
    expect(wrapper.vm.form.gameId).toBeNull();
    expect(wrapper.vm.guessed).toBe(false);

    // 手动选一个仅支持 compose 的游戏且无 labels 预填 → 不可提交
    wrapper.vm.form.gameId = 7;
    await flushPromises();
    expect(wrapper.vm.canSubmit).toBe(false);
  });
});
