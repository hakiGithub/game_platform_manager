/**
 * 页面: 实例详情 `/services/instances/detail/{id}` → 「配置管理」tab
 * 用例: V-16 · AC-27 (b)(c) 界面半——配置管理入口「保键」且该入口必须返回成功
 * 前置: 平台前后端已运行；E2E_TEST_HOST_* 指向牺牲主机（用于建实例；本用例不依赖部署结果）。
 * 步骤: API 造实例（configInfo 含 deployVersion 与其它既有业务键）→ 实例详情 →「配置管理」→
 *       「保存配置」两次；每次读回 configInfo 逐键核对。
 * 通过标准:
 *  - AC-27 (c) / V-16: 每次「保存配置」都返回成功（`配置保存成功`），**不得**被拒绝式实现做成恒失败
 *  - AC-27 (b) 界面半: 两次保存后 `deployVersion` 精确不变，且该 map 内其它既有业务键逐键不变（BR-16）
 * 注: 权威锚点 design §14.8 / V-16；本用例只判界面入口，不替代服务层接口判据。
 */
import { test, expect } from "@playwright/test";
import { e2eEnv } from "../../support/env.js";
import { skipWithoutTestHost } from "../../support/skip.js";
import { createApi, login } from "../../support/api.js";
import { injectSession } from "../../support/session.js";

skipWithoutTestHost();

const HOST_NAME = "e2e-wsl-deploy-host";
const INSTANCE_NAME = "e2e-cfg-preserve-01";
// 既存业务键集合（deployVersion 是要保的版本键；其余是既有业务键）
const PRESERVED_KEYS = {
  autoRestart: 0,
  gameCode: "stub",
  maxPlayers: 16,
  CONTAINER_NAME: "e2e-cfg-preserve",
};
const SELECTED_VERSION = "2.0.0-patched";

test.describe.serial("配置管理保键（V-16 AC-27 (b)(c) 界面半）", () => {
  let api = null;
  let hostId = null;
  let instanceId = null;

  async function readConfigInfo() {
    const res = await api.get(
      `/api/instances?keyword=${encodeURIComponent(INSTANCE_NAME)}&size=10`,
    );
    const rec = (res.body?.data?.records ?? []).find(
      (r) => r.instanceName === INSTANCE_NAME,
    );
    return rec?.configInfo ?? null;
  }

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

  test("配置管理入口两次保存均成功且既有键逐键不变", async ({ page }) => {
    test.setTimeout(5 * 60_000);

    // 前置：用任一游戏建实例并落 configInfo（deployVersion + 既有业务键）
    const games = await api.get("/api/games/list");
    const game = (games.body?.data ?? [])[0];
    test.skip(!game, "SKIP (游戏目录为空，无法建实例)");

    const deployed = await api.post("/api/instances", {
      instanceName: INSTANCE_NAME,
      gameId: game.id,
      hostId,
      deployType: "docker-compose",
      installPath: "~/games/e2e-cfg-preserve",
      portConfig: { game: 31199 },
      configInfo: { deployVersion: SELECTED_VERSION, ...PRESERVED_KEYS },
    });
    instanceId = deployed.body?.data?.id ?? null;
    expect(instanceId, `实例创建失败: ${JSON.stringify(deployed.body)}`).toBeTruthy();

    // 落定既存键（部署可能失败，不影响配置管理入口判据）
    const before = await readConfigInfo();
    test.skip(
      !before || before.deployVersion !== SELECTED_VERSION,
      `SKIP (configInfo 未落 deployVersion：${JSON.stringify(before)})`,
    );

    await injectSession(page);
    await page.goto(`/services/instances/detail/${instanceId}`);
    await page.locator(".el-tabs__item", { hasText: "配置管理" }).click();
    await expect(page.getByRole("button", { name: "保存配置" })).toBeVisible({
      timeout: 30_000,
    });

    for (let round = 1; round <= 2; round += 1) {
      await page.getByRole("button", { name: "保存配置" }).click();
      // (c) 该入口返回成功
      await expect(page.locator(".el-message--success")).toContainText(
        "配置保存成功",
        { timeout: 20_000 },
      );
      // 保存后询问是否重启——选「稍后手动重启」，本用例不触发重启
      const later = page.getByRole("button", { name: "稍后手动重启" });
      if (await later.isVisible().catch(() => false)) {
        await later.click();
      }

      // (b) deployVersion 与其它既有业务键逐键不变
      const after = await readConfigInfo();
      expect(after, `第 ${round} 次保存后 configInfo 应仍可读`).toBeTruthy();
      expect(after.deployVersion, `第 ${round} 次保存后 deployVersion 应不变`).toBe(
        SELECTED_VERSION,
      );
      for (const [k, v] of Object.entries(PRESERVED_KEYS)) {
        expect(after[k], `第 ${round} 次保存后既有键 ${k} 应不变`).toEqual(v);
      }
    }
  });
});
