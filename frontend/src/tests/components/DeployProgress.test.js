import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { mount, flushPromises } from "@vue/test-utils";
import * as ElementPlusIcons from "@element-plus/icons-vue";
import DeployProgress from "@/components/DeployProgress.vue";

// 后端 DeployService.appendLog 写入的 level 取值集合是 INFO / WARN / ERROR / SUCCESS（大写），
// 见 backend/core/src/main/java/com/gameplatform/service/DeployService.java:565 及其调用点。
// 本组用例锁定组件侧的归一化映射（design.md §14.9 / F-01、V-09）。
const api = vi.hoisted(() => ({
  getDeployProgress: vi.fn(),
  getInstanceLogs: vi.fn(),
}));

vi.mock("@/api/instance", () => ({
  getDeployProgress: api.getDeployProgress,
  getInstanceLogs: api.getInstanceLogs,
}));

const BACKEND_LEVEL_CASES = [
  { level: "INFO", cls: "log-info", icon: "InfoFilled" },
  { level: "SUCCESS", cls: "log-success", icon: "CircleCheck" },
  { level: "WARN", cls: "log-warning", icon: "Warning" },
  { level: "ERROR", cls: "log-error", icon: "CircleClose" },
];

let wrappers = [];

function mountDialog() {
  const wrapper = mount(DeployProgress, {
    props: { visible: false, instanceId: 1, mode: "deploy" },
    attachTo: document.body,
    global: {
      components: ElementPlusIcons,
    },
  });
  wrappers.push(wrapper);
  return wrapper;
}

// 组件只在 visible 由 false → true 时拉取进度，故先挂载再翻转可见性
async function renderLogs(levels) {
  const logs = levels.map((level, i) => ({
    id: 1000 + i,
    level,
    message: `msg-${i}`,
    time: "12:00:00",
  }));

  api.getDeployProgress.mockResolvedValue({
    progress: 40,
    status: "installing",
    statusText: "安装中",
    completed: false,
    logs,
  });

  const wrapper = mountDialog();
  await wrapper.setProps({ visible: true });
  await flushPromises();
  await wrapper.vm.$nextTick();
  return wrapper;
}

function rowByMessage(wrapper, message) {
  const row = wrapper
    .findAll(".log-item")
    .find((r) => r.text().includes(message));
  expect(row, `未渲染出日志行 ${message}`).toBeTruthy();
  return row;
}

beforeEach(() => {
  api.getDeployProgress.mockReset();
  api.getInstanceLogs.mockReset();
});

afterEach(() => {
  wrappers.forEach((w) => w.unmount());
  wrappers = [];
});

describe("DeployProgress level 归一化（design §14.9 / F-01）", () => {
  it("后端大写 level 各自命中独立 class 与图标", async () => {
    const wrapper = await renderLogs(BACKEND_LEVEL_CASES.map((c) => c.level));

    BACKEND_LEVEL_CASES.forEach(({ level, cls, icon }, i) => {
      const row = rowByMessage(wrapper, `msg-${i}`);
      expect(row.classes(), `${level} 的 class`).toContain(cls);
      expect(
        row.findComponent({ name: icon }).exists(),
        `${level} 的图标应为 ${icon}`,
      ).toBe(true);
    });
  });

  it("V-09：四类的 class 与图标两两不同（不再同色同图标）", async () => {
    const wrapper = await renderLogs(BACKEND_LEVEL_CASES.map((c) => c.level));

    const levelClassOf = (row) =>
      row.classes().find((c) => c.startsWith("log-") && c !== "log-item");

    const classes = BACKEND_LEVEL_CASES.map((_, i) =>
      levelClassOf(rowByMessage(wrapper, `msg-${i}`)),
    );
    const icons = BACKEND_LEVEL_CASES.map(
      ({ icon }, i) => rowByMessage(wrapper, `msg-${i}`).findComponent({ name: icon }).exists(),
    );

    expect(new Set(classes).size).toBe(4);
    expect(icons.every(Boolean)).toBe(true);
    expect(
      new Set(BACKEND_LEVEL_CASES.map((c) => c.icon)).size,
    ).toBe(4);
  });

  it("致命失败行取失败样式（AC-08 界面侧）", async () => {
    const wrapper = await renderLogs(["ERROR"]);
    const row = rowByMessage(wrapper, "msg-0");
    expect(row.classes()).toContain("log-error");
    expect(row.classes()).not.toContain("log-info");
    expect(row.findComponent({ name: "CircleClose" }).exists()).toBe(true);
  });

  it("WARN 不与 INFO 同色同图标（AC-10 界面侧）", async () => {
    const wrapper = await renderLogs(["WARN", "INFO"]);
    const warn = rowByMessage(wrapper, "msg-0");
    const info = rowByMessage(wrapper, "msg-1");

    expect(warn.classes()).toContain("log-warning");
    expect(info.classes()).toContain("log-info");
    expect(warn.classes()).not.toEqual(info.classes());
    expect(warn.findComponent({ name: "Warning" }).exists()).toBe(true);
    expect(warn.findComponent({ name: "InfoFilled" }).exists()).toBe(false);
  });

  it("小写 warning / warn 别名同样落到 warning 分支", async () => {
    const wrapper = await renderLogs(["warning", "warn"]);
    for (const msg of ["msg-0", "msg-1"]) {
      const row = rowByMessage(wrapper, msg);
      expect(row.classes()).toContain("log-warning");
      expect(row.findComponent({ name: "Warning" }).exists()).toBe(true);
    }
  });

  it("大小写混写的后端取值也能命中", async () => {
    const wrapper = await renderLogs(["Success", "warn", "Error"]);
    expect(rowByMessage(wrapper, "msg-0").classes()).toContain("log-success");
    expect(rowByMessage(wrapper, "msg-1").classes()).toContain("log-warning");
    expect(rowByMessage(wrapper, "msg-2").classes()).toContain("log-error");
  });

  it("未知 / 空 level 不抛异常，落默认样式", async () => {
    const wrapper = await renderLogs(["TRACE", "", null, undefined, "verbose"]);

    for (let i = 0; i < 5; i++) {
      const row = rowByMessage(wrapper, `msg-${i}`);
      expect(row.classes()).toContain("log-info");
      expect(row.findComponent({ name: "InfoFilled" }).exists()).toBe(true);
    }
  });
});
