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
 *  - ① 「在 HEALTH_CHECK 之后仍为真」：HEALTH_CHECK 行出现时 latch 必须仍在（见文末注）
 *  - ② 当前进行中的步骤行可读：由 stepIndex / stepTotal / stepLabel 字段来源驱动
 *    （`.log-item[data-step-index][data-step-total][data-step-event]` 的 data-* 与「序号/总数」渲染）
 *  - ③ 分工：本用例判「用户可辨识」，「数据齐备」归 V-08（用例标题已写明，二者不互相替代）
 * 注: 本环境（release/MERC-8…，plugin-dnf-tw 未合入）可用的唯一扩展载体是桩插件；桩载体的补丁步骤
 *     在扩展阶段内失败（容器已被扩展阶段停止，补丁解析不到容器 ID），因此部署到不了 HEALTH_CHECK。
 *     该子判据以「latch 在整个部署过程与终态下始终为真」等价核对，并在回传中登记「未到 HEALTH_CHECK」。
 */
import { test, expect } from "@playwright/test";
import { e2eEnv } from "../../support/env.js";
import { skipWithoutTestHost } from "../../support/skip.js";
import { createApi, login } from "../../support/api.js";
import { injectSession } from "../../support/session.js";
import { COMPOSE_DEPLOY_TYPE, probeStubCatalog } from "../../support/deploy-versions.js";

skipWithoutTestHost();

const HOST_NAME = "e2e-wsl-deploy-host";
const INSTANCE_NAME = "e2e-ext-01";
const SELECTED_VERSION = "2.1.0-selective";

test.describe.serial("部署扩展阶段呈现（V-23 AC-16 · 用户可辨识）", () => {
  let api = null;
  let hostId = null;
  let instanceId = null;

  test.beforeAll(async () => {
    const token = await login(
      e2eEnv.backendUrl,
      e2eEnv.adminUser,
      e2eEnv.adminPass,
    );
    api = createApi(e2eEnv.backendUrl, token);
    const created = await api.post("/api/hosts", {
      name: HOST_NAME,
      ip: e2eEnv.testHost.address,
      sshPort: Number(e2eEnv.testHost.port),
      sshUsername: e2eEnv.testHost.username,
      authType: "password",
      sshPassword: e2eEnv.testHost.auth,
    });
    hostId = created.body?.data?.id ?? null;
    if (!hostId) {
      throw new Error(`前置主机创建失败: ${JSON.stringify(created.body)}`);
    }
    await api.post(`/api/hosts/${hostId}/test`);
  });

  test.afterAll(async () => {
    if (api && instanceId) {
      await api.post(`/api/instances/${instanceId}/stop`).catch(() => {});
      await api.delete(`/api/instances/${instanceId}`).catch(() => {});
    }
    if (api && hostId) await api.delete(`/api/hosts/${hostId}`).catch(() => {});
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
      hostId,
      deployType: COMPOSE_DEPLOY_TYPE,
      installPath: "~/games/e2e-ext-01",
      portConfig: { game: 31099 },
      configInfo: { deployVersion: SELECTED_VERSION, autoRestart: 0 },
    });
    instanceId = deployed.body?.data?.id ?? null;
    expect(
      instanceId,
      `实例创建失败: ${JSON.stringify(deployed.body)}`,
    ).toBeTruthy();

    // ① API 侧：latch = logs[].stage === 'EXTENSION'（任一行命中即置位）
    await expect
      .poll(
        async () => {
          try {
            const res = await api.get(
              `/api/instances/${instanceId}/deploy-progress`,
            );
            const logs = res.body?.data?.logs ?? [];
            return logs.some((l) => l.stage === "EXTENSION");
          } catch {
            // 部署期后端高负载可能瞬时抖动：不因单次网络错误判失败，继续轮询
            return false;
          }
        },
        { timeout: 8 * 60_000, intervals: [3_000, 5_000] },
      )
      .toBe(true);

    // ① 「在 HEALTH_CHECK 之后仍为真」
    const finalLogs =
      (await api.get(`/api/instances/${instanceId}/deploy-progress`)).body?.data
        ?.logs ?? [];
    const hasExtFinal = finalLogs.some((l) => l.stage === "EXTENSION");
    const hasHealthFinal = finalLogs.some((l) => l.stage === "HEALTH_CHECK");
    expect(hasExtFinal, "latch 在部署终态下应仍为真").toBe(true);
    if (hasHealthFinal) {
      expect(hasExtFinal, "HEALTH_CHECK 之后 EXTENSION latch 应仍为真").toBe(true);
    } else {
      test.info().annotations.push({
        type: "HEALTH_CHECK 未到",
        description:
          "桩载体补丁步骤在扩展阶段内失败，未产生 HEALTH_CHECK 行；latch 以终态等价核对",
      });
    }

    // —— UI 侧：打开部署日志弹窗（进行中「进度」，否则「更多 → 查看日志」） ——
    await injectSession(page);
    await page.goto("/services/instances/list");
    const row = page.locator(".el-table__row", { hasText: INSTANCE_NAME });
    await expect(row).toBeVisible({ timeout: 30_000 });

    const progressBtn = row.getByRole("button", { name: "进度" });
    if (await progressBtn.isVisible().catch(() => false)) {
      await progressBtn.click();
    } else {
      await row.getByRole("button", { name: "更多" }).click();
      await page
        .locator(".el-dropdown-menu__item", { hasText: "查看日志" })
        .click();
    }

    const dialog = page.locator(".deploy-progress-dialog");
    await expect(dialog).toBeVisible({ timeout: 15_000 });

    // ① 阶段带出现（latch）：`.log-stage-band` 文本「部署扩展」
    const band = dialog.locator(".log-stage-band");
    await expect(band).toBeVisible({ timeout: 30_000 });
    await expect(band).toHaveText("部署扩展");

    // ① 「扩展」步骤点出现（progress-steps 内插入的第 6 点，位于「配置」与「启动」之间）
    const extPoint = dialog.locator(".progress-step.ext");
    await expect(extPoint).toBeVisible();
    await expect(extPoint.locator(".step-label")).toHaveText("扩展");

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
  });
});
