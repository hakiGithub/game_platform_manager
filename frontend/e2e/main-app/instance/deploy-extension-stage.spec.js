/**
 * 页面: 实例列表 `/services/instances/list` → 部署日志弹窗（DeployProgress，mode=deploy）
 * 用例: V-23 · AC-16 界面侧——扩展阶段在部署面板「用户可辨识」
 *       （数据齐备由 V-08 判；本用例只判用户可辨识，二者不互相替代——见用例命名）
 * 前置: 平台前后端已运行；E2E_TEST_HOST_* 指向装有 Docker 的牺牲主机；
 *       桩游戏目录 AVAILABLE（plugin-stub 已装、stub.yml 已投放，见 deploy-version-catalog.spec.js）。
 * 步骤: API 造实例（configInfo.deployVersion = 桩非默认条目）→ 等扩展阶段 latch →
 *       实例列表打开部署日志弹窗 → 采样面板 DOM 与 GET /instances/{id}/deploy-progress。
 * 通过标准（逐条 = 判的是 AC-16 的哪个判据，词面归 ui-spec §6.2）:
 *  - ① 阶段带 / 「扩展」步骤点出现：latch = logs[].stage === 'EXTENSION'
 *    （DOM 侧 `.log-stage-band` 文本「部署扩展」、`.progress-step.ext` 出现；API 侧 logs 含 EXTENSION 行）
 *  - ① 「在 HEALTH_CHECK 之后仍为真」（呈现不回退，ui-spec §7 X-07 / X-08，DOM 属性）：
 *    HEALTH_CHECK 行出现后再取一次面板，`.log-stage-band` / `.progress-step.ext` 仍可见，
 *    且原五个进度点的 completed / active 判定与插入时一致。
 *    HEALTH_CHECK 在桩载体上不可达时，只把这一子判据登记为「未覆盖」，不写成等价核对。
 *  - ② 当前进行中的步骤行可读：由 stepIndex / stepTotal / stepLabel 字段来源驱动
 *    （`.log-item[data-step-index][data-step-total][data-step-event]` 的 data-* 与「序号/总数」渲染）
 *  - ③ 分工：本用例判「用户可辨识」，「数据齐备」归 V-08（用例标题已写明，二者不互相替代）
 */
import { test, expect } from "@playwright/test";
import { e2eEnv } from "../../support/env.js";
import { skipWithoutTestHost } from "../../support/skip.js";
import { assertOk, createApi, login } from "../../support/api.js";
import {
  acquireTestHost,
  deleteInstancesByName,
  releaseTestHost,
} from "../../support/test-host.js";
import { injectSession } from "../../support/session.js";
import {
  COMPOSE_DEPLOY_TYPE,
  probeStubCatalog,
} from "../../support/deploy-versions.js";

skipWithoutTestHost();

const HOST_SUFFIX = "extension";
const INSTANCE_NAME = "e2e-ext-01";
const SELECTED_VERSION = "2.1.0-selective";

/** 五个既有进度点的状态快照（label + class 断言位）。ext 点单独排除。 */
async function readStepStates(dialog) {
  const steps = dialog.locator(".progress-step");
  const count = await steps.count();
  const states = [];
  for (let i = 0; i < count; i += 1) {
    const step = steps.nth(i);
    const classes = (await step.getAttribute("class")) ?? "";
    if (!classes.split(/\s+/).includes("ext")) {
      states.push({
        label: (await step.locator(".step-label").innerText()).trim(),
        completed: classes.split(/\s+/).includes("is-completed"),
        active: classes.split(/\s+/).includes("is-active"),
      });
    }
  }
  return states;
}

test.describe.serial("部署扩展阶段呈现（V-23 AC-16 · 用户可辨识）", () => {
  let api = null;
  let host = null;
  let instanceId = null;

  test.beforeAll(async () => {
    const token = await login(
      e2eEnv.backendUrl,
      e2eEnv.adminUser,
      e2eEnv.adminPass,
    );
    api = createApi(e2eEnv.backendUrl, token);
    host = await acquireTestHost(api, HOST_SUFFIX);
    await deleteInstancesByName(api, INSTANCE_NAME);
  });

  test.afterAll(async () => {
    if (api && instanceId) {
      await api.post(`/api/instances/${instanceId}/stop`).catch(() => {});
      await api.delete(`/api/instances/${instanceId}`).catch(() => {});
    }
    if (host) await releaseTestHost(api, host);
  });

  test("AC-16 界面侧：扩展阶段在部署面板可辨识（阶段带 / 「扩展」步骤点 / 进行中步骤行）", async ({
    page,
  }) => {
    test.setTimeout(15 * 60_000);

    const probe = await probeStubCatalog(api);
    test.skip(!probe.ok, probe.reason);
    const stub = probe.game;

    // 造实例：configInfo.deployVersion 指向桩非默认条目 ⇒ 本次部署进入扩展阶段
    const deployed = await api.post("/api/instances", {
      instanceName: INSTANCE_NAME,
      gameId: stub.id,
      hostId: host.id,
      deployType: COMPOSE_DEPLOY_TYPE,
      installPath: "~/games/e2e-ext-01",
      portConfig: { game: 31099 },
      configInfo: { deployVersion: SELECTED_VERSION, autoRestart: 0 },
    });
    assertOk(deployed, "创建实例（扩展阶段载体）");
    instanceId = deployed.body?.data?.id ?? null;
    expect(
      instanceId,
      `实例创建失败: ${JSON.stringify(deployed.body)}`,
    ).toBeTruthy();

    async function readProgress() {
      const res = assertOk(
        await api.get(`/api/instances/${instanceId}/deploy-progress`),
        "读部署进度",
      );
      return res.body?.data ?? { logs: [] };
    }

    async function readLogs() {
      return (await readProgress()).logs ?? [];
    }

    // —— UI 侧：先开面板再看扩展阶段 ——
    // 部署一创建就打开列表：状态还是 installing 时行内有直连「进度」按钮；
    // 等部署结束再开，行内按钮会变成「日志」（error/running）或消失（stopped），弹窗就抓不到了。
    await injectSession(page);
    await page.goto("/services/instances/list");
    const row = page
      .locator(".el-table__row", { hasText: INSTANCE_NAME })
      .first();
    await expect(row).toBeVisible({ timeout: 30_000 });
    // 行内直连按钮：「进度」= 部署中，「日志」= 终态（error/running）。
    // 不走「更多 → 查看日志」——那个入口是跳详情页的日志 tab，不是部署进度弹窗。
    const openLogs = row.getByRole("button", { name: /^(进度|日志)$/ }).first();
    await expect(openLogs).toBeVisible({ timeout: 30_000 });
    await openLogs.click();

    const dialog = page.locator(".deploy-progress-dialog");
    await expect(dialog).toBeVisible({ timeout: 15_000 });

    // ① API 侧：latch = logs[].stage === 'EXTENSION'（任一行命中即置位）
    await expect
      .poll(
        async () => {
          try {
            const logs = await readLogs();
            return logs.some((l) => l.stage === "EXTENSION");
          } catch {
            // 部署期后端高负载可能瞬时抖动：不因单次网络错误判失败，继续轮询
            return false;
          }
        },
        { timeout: 8 * 60_000, intervals: [3_000, 5_000] },
      )
      .toBe(true);

    // ① 阶段带出现（latch）：`.log-stage-band` 文本「部署扩展」
    const band = dialog.locator(".log-stage-band");
    await expect(band).toBeVisible({ timeout: 30_000 });
    await expect(band).toHaveText("部署扩展");

    // ① 「扩展」步骤点出现（progress-steps 内插入的第 6 点，位于「配置」与「启动」之间）
    const extPoint = dialog.locator(".progress-step.ext");
    await expect(extPoint).toBeVisible();
    await expect(extPoint.locator(".step-label")).toHaveText("扩展");

    // 插入时的五个既有点状态（X-08 的对照物）
    const statesAtExtension = await readStepStates(dialog);

    // ② 当前进行中的步骤行可读：由 stepIndex / stepTotal / stepLabel 字段来源驱动
    const stepRow = dialog.locator(".log-item[data-step-event]").first();
    await expect(stepRow).toBeVisible({ timeout: 30_000 });
    const stepIndex = await stepRow.getAttribute("data-step-index");
    const stepTotal = await stepRow.getAttribute("data-step-total");
    const stepEvent = await stepRow.getAttribute("data-step-event");
    expect(stepIndex).toBeTruthy();
    expect(["START", "SUCCESS", "FAILURE"]).toContain(stepEvent);
    if (stepTotal) {
      await expect(stepRow.locator(".log-message")).toContainText(
        `${stepIndex}/${stepTotal}`,
      );
    } else {
      await expect(stepRow.locator(".log-message")).toContainText(
        `步骤 ${stepIndex}`,
      );
    }

    // ① 「在 HEALTH_CHECK 之后仍为真」——呈现不回退，判据是 DOM 属性，不是 API latch。
    // 取数时点不是终态：等 HEALTH_CHECK 行出现，或等部署先结束（本环境桩载体的补丁步骤
    // 在扩展阶段内失败 ⇒ 到不了 HEALTH_CHECK），两种情况都在那时再取一次面板。
    let healthCheckSeen = false;
    await expect
      .poll(
        async () => {
          const data = await readProgress();
          const logs = data.logs ?? [];
          if (logs.some((l) => l.stage === "HEALTH_CHECK")) return "health";
          return data.completed ? "completed" : "running";
        },
        { timeout: 6 * 60_000, intervals: [3_000, 5_000] },
      )
      .not.toBe("running");
    healthCheckSeen = (await readLogs()).some(
      (l) => l.stage === "HEALTH_CHECK",
    );
    if (healthCheckSeen) {
      // 弹窗按轮询刷新；等 HEALTH_CHECK 之后的那一帧落到 DOM 上再取。
      await expect(band).toBeVisible();
      await expect(extPoint).toBeVisible();
      expect(
        await readStepStates(dialog),
        "插入「扩展」点后，原五点的 completed / active 判定不得变化（X-08）",
      ).toEqual(statesAtExtension);
    } else {
      // 本环境桩载体的补丁步骤在扩展阶段内失败时到不了 HEALTH_CHECK；
      // 只登记哪个子判据没覆盖，不把「latch 仍为真」写成等价核对（append-only 日志上恒真）。
      test.info().annotations.push({
        type: "未覆盖子判据",
        description:
          "AC-16 ①「HEALTH_CHECK 之后阶段带 / 扩展点仍可见且原五点状态未变」：本环境未产生 HEALTH_CHECK 行，该子判据未覆盖",
      });
      // 已发生的事实仍可判：扩展阶段终态下面板两元素在（不是整条记缺口）。
      await expect(band).toBeVisible();
      await expect(extPoint).toBeVisible();
    }
  });
});
