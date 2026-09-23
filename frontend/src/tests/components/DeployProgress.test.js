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
async function renderPayload(overrides = {}) {
  api.getDeployProgress.mockResolvedValue({
    progress: 40,
    status: "installing",
    statusText: "安装中",
    completed: false,
    logs: [],
    ...overrides,
  });

  const wrapper = mountDialog();
  await wrapper.setProps({ visible: true });
  await flushPromises();
  await wrapper.vm.$nextTick();
  return wrapper;
}

async function renderLogs(levels) {
  const logs = levels.map((level, i) => ({
    id: 1000 + i,
    level,
    message: `msg-${i}`,
    time: "12:00:00",
  }));
  return renderPayload({ logs });
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

/* ============================================================================
 * MERC-19：扩展阶段呈现（design §14.11 / §14.6、ui-spec §6.2～§6.4、F-02 / F-03）
 *
 * 载荷按 design §14.6 已定稿的字段契约**合成**（stage / stepId / stepIndex / stepTotal /
 * stepLabel / stepType / stepEvent / elapsedMs），渲染不依赖后端是否已产出这些行。
 * 合成值（含 versionId、步骤标签、耗时数字）一律是夹具，不作为 AC 证据登记：
 * AC-03 / AC-16 的机械核对与界面核对在 stage 6 按 V-08 / V-23 用真实部署过程采集。
 * ========================================================================== */

const EXT = "EXTENSION";

function log(id, level, message, extra = {}) {
  return { id, level, message, time: "14:03:40", ...extra };
}

// 阶段级行（stepId == null）：进入行 / 停实例两行
const ENTER_ROW = log(2000, "INFO", "进入部署扩展阶段", { stage: EXT });
const STOP_BEFORE_ROW = log(
  2001,
  "INFO",
  "正在停止实例，确保扩展步骤在实例未运行时执行",
  { stage: EXT },
);
const STOPPED_ROW = log(2002, "SUCCESS", "实例已停止", { stage: EXT });

// 步骤行（stepId != null）：逐步三行一组。message 留空——步骤行按字段渲染，
// 文本来源是 stepIndex / stepTotal / stepLabel / stepType / stepEvent / elapsedMs。
function stepRow(id, kind, step, extra = {}) {
  return log(id, "INFO", "", {
    stage: EXT,
    stepId: `E-${step.index}`,
    stepIndex: step.index,
    stepTotal: step.total,
    stepLabel: step.label,
    stepType: kind,
    ...extra,
  });
}

const STEP1 = { index: 1, total: 3, label: "替换二进制" };
const STEP2 = { index: 2, total: 3, label: null }; // label 缺省 ⇒ 回退「脚本执行 2」
const STEP3 = { index: 3, total: 3, label: "校验版本文件" };

// 全部步骤判成功 + 收尾失败支（阶段级行，stepId == null）
const EXTENSION_LOGS = [
  ENTER_ROW,
  STOP_BEFORE_ROW,
  STOPPED_ROW,
  stepRow(2003, "PATCH", STEP1, { stepEvent: "START" }),
  stepRow(2004, "PATCH", STEP1, { stepEvent: "SUCCESS", elapsedMs: 22100 }),
  stepRow(2005, "SCRIPT", STEP2, { stepEvent: "START" }),
  // 快速步骤：420ms < 1s ⇒ 渲染「1秒」而非「0秒」（design §14.6 单位口径 / SUG-6）
  stepRow(2006, "SCRIPT", STEP2, { stepEvent: "SUCCESS", elapsedMs: 420 }),
  stepRow(2007, "PATCH", STEP3, { stepEvent: "START" }),
  stepRow(2008, "PATCH", STEP3, { stepEvent: "SUCCESS", elapsedMs: 3720000 }),
  log(2009, "ERROR", "部署扩展阶段收尾 · 容器未能恢复到运行态 · 失败 · 原因：端口未就绪", {
    stage: EXT,
    stepId: null,
    stepEvent: "FAILURE",
    elapsedMs: 5100,
  }),
];

// 致命步骤失败（失败支后不接完成行 / 交棒行）
const FATAL_LOGS = [
  ENTER_ROW,
  STOP_BEFORE_ROW,
  STOPPED_ROW,
  stepRow(2100, "PATCH", STEP1, { stepEvent: "START" }),
  stepRow(2101, "PATCH", STEP1, { stepEvent: "SUCCESS", elapsedMs: 22000 }),
  stepRow(2102, "SCRIPT", STEP2, { stepEvent: "START" }),
  stepRow(2103, "SCRIPT", STEP2, {
    stepEvent: "FAILURE",
    level: "ERROR",
    elapsedMs: 1200,
    message: "脚本退出码 3（非 0 即判失败）",
  }),
  log(2104, "ERROR", "致命步骤失败，部署终止：不再执行后续步骤，实例不启动、不交付，状态置为异常", {
    stage: EXT,
  }),
];

// 非致命步骤失败（AC-10 界面侧）
const NONFATAL_LOGS = [
  ENTER_ROW,
  STOP_BEFORE_ROW,
  STOPPED_ROW,
  stepRow(2200, "PATCH", STEP1, { stepEvent: "START" }),
  stepRow(2201, "PATCH", STEP1, {
    stepEvent: "FAILURE",
    level: "WARN",
    elapsedMs: 900,
    message: "补丁包来源不可达",
  }),
];

// BR-12 入口拦截：进入行 + 拦截行即终止，其后无步骤行（README §14.6 stage 钉值）
const INTERCEPT_LOGS = [
  ENTER_ROW,
  log(
    2300,
    "ERROR",
    "部署终止：实例配置要求的版本 1.0.3 不可用 —— 该版本要求由既往部署写入实例配置，本次未改选",
    { stage: EXT },
  ),
];

// 无扩展声明 / 未进入扩展阶段（AC-15 / AC-24 ③ 的回归基准帧）
const NO_EXTENSION_LOGS = [
  log(100, "INFO", "[INIT] 初始化", { stage: "INIT" }),
  log(101, "SUCCESS", "[DEPLOY] 部署完成", { stage: "DEPLOY" }),
  log(102, "INFO", "[HEALTH_CHECK] 健康检查", { stage: "HEALTH_CHECK" }),
];

function stepLabels(wrapper) {
  return wrapper.findAll(".progress-step .step-label").map((l) => l.text());
}

function stepPointByLabel(wrapper, label) {
  const point = wrapper
    .findAll(".progress-step")
    .find((p) => p.find(".step-label").text() === label);
  expect(point, `未渲染出步骤点 ${label}`).toBeTruthy();
  return point;
}

function rowByText(wrapper, text) {
  const row = wrapper
    .findAll(".log-item")
    .find((r) => r.text().includes(text));
  expect(row, `未渲染出日志行 ${text}`).toBeTruthy();
  return row;
}

describe("DeployProgress 扩展阶段呈现（design §14.11 / F-02 / F-03）", () => {
  it("AC-16 ①：EXTENSION 行出现即渲染阶段带，且紧邻在「进入部署扩展阶段」之前", async () => {
    const wrapper = await renderPayload({ logs: EXTENSION_LOGS });

    const bands = wrapper.findAll(".log-stage-band");
    expect(bands).toHaveLength(1);
    expect(bands[0].text()).toContain("部署扩展");

    const children = [...wrapper.find(".logs-container").element.children];
    const bandPos = children.findIndex((el) =>
      el.classList.contains("log-stage-band"),
    );
    const enterPos = children.findIndex((el) =>
      el.textContent.includes("进入部署扩展阶段"),
    );
    expect(bandPos).toBeGreaterThanOrEqual(0);
    expect(enterPos).toBeGreaterThanOrEqual(0);
    expect(bandPos).toBe(enterPos - 1);
  });

  it("AC-16 ①：latch 在 HEALTH_CHECK / COMPLETE 之后仍为真（阶段带与「扩展」步骤点不消失）", async () => {
    const wrapper = await renderPayload({
      logs: [
        ...EXTENSION_LOGS,
        log(3000, "INFO", "[HEALTH_CHECK] 健康检查", { stage: "HEALTH_CHECK" }),
        log(3001, "SUCCESS", "[COMPLETE] 部署完成", { stage: "COMPLETE" }),
      ],
      progress: 100,
      status: "completed",
      statusText: "已完成",
      completed: true,
    });

    expect(wrapper.findAll(".log-stage-band")).toHaveLength(1);
    expect(stepLabels(wrapper)).toContain("扩展");
  });

  it("AC-15 / AC-24 ③：logs[].stage 全部非 EXTENSION 时阶段带与「扩展」步骤点都不渲染", async () => {
    const wrapper = await renderPayload({ logs: NO_EXTENSION_LOGS });

    expect(wrapper.findAll(".log-stage-band")).toHaveLength(0);
    expect(stepLabels(wrapper)).toEqual(["准备", "下载", "安装", "配置", "启动"]);
    expect(wrapper.text()).not.toContain("部署扩展");
  });

  it("AC-15 / X-08：「扩展」点插在「配置」与「启动」之间，五个既有点的判定不变", async () => {
    const off = await renderPayload({
      logs: NO_EXTENSION_LOGS,
      progress: 80,
    });
    expect(stepLabels(off)).toEqual(["准备", "下载", "安装", "配置", "启动"]);
    expect(stepPointByLabel(off, "配置").classes()).toContain("is-completed");
    expect(stepPointByLabel(off, "启动").classes()).toContain("is-active");

    const on = await renderPayload({
      logs: EXTENSION_LOGS,
      progress: 80,
      stage: "EXTENSION",
    });
    expect(stepLabels(on)).toEqual([
      "准备",
      "下载",
      "安装",
      "配置",
      "扩展",
      "启动",
    ]);
    // 插入瞬间既有点判定逐项不变（否则用户会看到进度倒退）
    expect(stepPointByLabel(on, "配置").classes()).toContain("is-completed");
    expect(stepPointByLabel(on, "启动").classes()).toContain("is-active");
    expect(stepPointByLabel(on, "准备").classes()).toContain("is-completed");
  });

  it("§14.11：「扩展」点的激活态取顶层 stage 字段，不由百分比分桶猜", async () => {
    const active = await renderPayload({
      logs: EXTENSION_LOGS,
      progress: 82,
      stage: "EXTENSION",
    });
    expect(stepPointByLabel(active, "扩展").classes()).toContain("is-active");

    const handedOff = await renderPayload({
      logs: EXTENSION_LOGS,
      progress: 85,
      stage: "HEALTH_CHECK",
    });
    expect(stepPointByLabel(handedOff, "扩展").classes()).not.toContain(
      "is-active",
    );
  });

  it("AC-16 ②：步骤行按字段可读（stepIndex / stepTotal / stepLabel 来源），阶段级行不进「步骤」形状", async () => {
    const wrapper = await renderPayload({ logs: EXTENSION_LOGS });

    const stepRows = wrapper.findAll(".log-step");
    expect(stepRows.length).toBe(6); // E-1 / E-2 / E-3 各 START + 终态

    const first = rowByText(wrapper, "步骤 1/3 替换二进制");
    expect(first.classes()).toContain("log-step");
    expect(first.attributes("data-step-index")).toBe("1");
    expect(first.attributes("data-step-total")).toBe("3");
    // ui-spec §6.2 步骤开始行逐字：「开始」前是空格
    expect(first.text()).toContain("步骤 1/3 替换二进制 · 补丁替换 开始");

    // 阶段级行（进入 / 停实例两行 / 收尾行）不进「步骤」形状
    for (const text of [
      "进入部署扩展阶段",
      "正在停止实例",
      "实例已停止",
      "部署扩展阶段收尾",
    ]) {
      expect(rowByText(wrapper, text).classes()).not.toContain("log-step");
    }
  });

  it("design §14.6 / SUG-6：elapsedMs < 1000 渲染「1秒」而非「0秒」", async () => {
    const wrapper = await renderPayload({ logs: EXTENSION_LOGS });

    const fast = rowByText(wrapper, "耗时 1秒");
    expect(fast.text()).toContain("步骤 2/3 脚本执行 2");
    expect(wrapper.text()).not.toContain("耗时 0秒");
  });

  it("§6.2：耗时格式沿用既有 N秒 / N分N秒 / N小时N分，不引入裸 ms", async () => {
    const wrapper = await renderPayload({ logs: EXTENSION_LOGS });

    expect(rowByText(wrapper, "耗时 22秒").text()).toContain(
      "步骤 1/3 替换二进制",
    );
    expect(rowByText(wrapper, "耗时 1小时2分").text()).toContain(
      "步骤 3/3 校验版本文件",
    );
    expect(wrapper.text()).not.toMatch(/\d+ms/);
  });

  it("§6.2：label 缺省回退「补丁替换 〈序号〉」/「脚本执行 〈序号〉」，界面不出现 PATCH / SCRIPT / fatal 字面量（§6.4）", async () => {
    const wrapper = await renderPayload({ logs: EXTENSION_LOGS });

    const fallback = rowByText(wrapper, "步骤 2/3 脚本执行 2");
    expect(fallback.text()).toContain("脚本执行 2 · 脚本执行 开始");

    const text = wrapper.text();
    expect(text).not.toMatch(/PATCH|SCRIPT|fatal/);
  });

  it("§6.2：阶段级行（完成行 / 交棒行）不进「步骤」形状", async () => {
    const wrapper = await renderPayload({
      logs: [
        ENTER_ROW,
        STOP_BEFORE_ROW,
        STOPPED_ROW,
        log(2400, "SUCCESS", "部署扩展阶段收尾 · 容器已恢复到运行态 · 成功 · 耗时 5秒", {
          stage: EXT,
          stepId: null,
          stepEvent: "SUCCESS",
          elapsedMs: 5000,
        }),
        log(2401, "SUCCESS", "部署扩展阶段完成 · 共 3 步 · 总耗时 30分1秒", {
          stage: EXT,
          stepId: null,
          stepEvent: "SUCCESS",
        }),
        log(2402, "INFO", "部署扩展阶段结束，进入健康检查与启动", { stage: EXT }),
      ],
    });

    expect(wrapper.findAll(".log-step")).toHaveLength(0);
    for (const text of ["部署扩展阶段收尾", "部署扩展阶段完成", "部署扩展阶段结束"]) {
      expect(rowByText(wrapper, text).classes()).not.toContain("log-step");
    }
    expect(wrapper.findAll(".log-stage-band")).toHaveLength(1);
  });

  it("§14.6：终态行 elapsedMs 缺失时不编造耗时数字", async () => {
    const wrapper = await renderPayload({
      logs: [
        ENTER_ROW,
        stepRow(2500, "PATCH", STEP1, { stepEvent: "SUCCESS" }),
      ],
    });

    const row = rowByText(wrapper, "步骤 1/3 替换二进制");
    expect(row.text()).toContain("成功");
    expect(row.text()).not.toContain("耗时");
  });

  it("§6.3：失败行原因段以「原因：」引导且置于行尾（AC-08 / AC-12 界面侧）", async () => {
    const wrapper = await renderPayload({ logs: FATAL_LOGS });

    const failed = wrapper
      .findAll(".log-item")
      .find((r) => r.text().includes("步骤 2/3") && r.text().includes("失败"));
    expect(failed).toBeTruthy();
    expect(failed.classes()).toContain("log-error");
    expect(failed.classes()).toContain("log-step");
    expect(failed.text()).toContain("原因：脚本退出码 3（非 0 即判失败）");
    expect(failed.text()).not.toContain("原因：原因：");
    expect(failed.text()).toMatch(/失败 · 耗时 1秒 · 原因：/);
  });

  it("AC-10 界面侧：非致命失败取 WARN 并行内标「失败（非致命）」", async () => {
    const wrapper = await renderPayload({ logs: NONFATAL_LOGS });

    const row = rowByText(wrapper, "失败（非致命）");
    expect(row.classes()).toContain("log-warning");
    expect(row.text()).toContain("步骤 1/3 替换二进制");
    expect(row.text()).toContain("原因：补丁包来源不可达");
  });

  it("AC-20 界面侧：BR-12 拦截行前有阶段带，拦截行不进「步骤」形状", async () => {
    const wrapper = await renderPayload({
      logs: INTERCEPT_LOGS,
      status: "failed",
      completed: true,
    });

    expect(wrapper.findAll(".log-stage-band")).toHaveLength(1);
    const row = rowByText(wrapper, "部署终止：");
    expect(row.classes()).toContain("log-error");
    expect(row.classes()).not.toContain("log-step");
    expect(wrapper.text()).not.toMatch(/PATCH|SCRIPT|fatal/);
  });

  it("AC-15 回归：既有 elapsedTime 消费点（已用时）行为不变", async () => {
    const wrapper = await renderPayload({ logs: NO_EXTENSION_LOGS, progress: 10 });
    expect(wrapper.find(".status-detail").text()).toContain("已用时 0秒");
  });
});
