/**
 * 页面: 实例详情 `/services/instances/detail/{id}` → 「配置管理」tab
 * 用例: V-16 · AC-27 (b)(c) 界面半——配置管理入口「保键」且该入口必须返回成功
 * 前置: 平台前后端已运行；E2E_TEST_HOST_* 指向牺牲主机（用于建实例；本用例不依赖部署结果）；
 *       载体 = 桩游戏（probeStubCatalog 给出的那一个），不拿任意游戏再硬塞矛盾的 gameCode。
 * 步骤: API 在桩游戏上造实例（configInfo 含 deployVersion 与其它既有业务键）→ 实例详情 →
 *       「配置管理」→「保存配置」两次；每次读回 configInfo 逐键核对。
 * 通过标准:
 *  - AC-27 (c) / V-16: 每次「保存配置」都返回成功（`配置保存成功`），**不得**被拒绝式实现做成恒失败
 *  - AC-27 (b) 界面半: 两次保存后 `deployVersion` 精确不变，且该 map 内其它既有业务键逐键不变（BR-16）
 * 注: 权威锚点 design §14.8 / V-16；本用例只判界面入口，不替代服务层接口判据。
 */
import { test, expect } from "@playwright/test";
import { e2eEnv } from "../../support/env.js";
import { skipWithoutTestHost } from "../../support/skip.js";
import { assertOk, createApi, login } from "../../support/api.js";
import { acquireTestHost, deleteInstancesByName, releaseTestHost } from "../../support/test-host.js";
import { injectSession } from "../../support/session.js";
import { COMPOSE_DEPLOY_TYPE, probeStubCatalog } from "../../support/deploy-versions.js";

skipWithoutTestHost();

const HOST_SUFFIX = "config";
const INSTANCE_NAME = "e2e-cfg-preserve-01";
// 既存业务键集合（deployVersion 是要保的版本键；其余是既有业务键）。
// gameCode 与载体一致（桩游戏），deployType 同为 compose —— 不再往任意游戏上硬塞。
const PRESERVED_KEYS = {
  autoRestart: 0,
  maxPlayers: 16,
  CONTAINER_NAME: "e2e-cfg-preserve",
};
const SELECTED_VERSION = "2.0.0-patched";

test.describe.serial("配置管理保键（V-16 AC-27 (b)(c) 界面半）", () => {
  let api = null;
  let host = null;
  let instanceId = null;

  async function readConfigInfo() {
    const res = assertOk(
      await api.get(
        `/api/instances?keyword=${encodeURIComponent(INSTANCE_NAME)}&size=10`,
      ),
      "查实例配置",
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

  test("配置管理入口两次保存均成功且既有键逐键不变", async ({ page }) => {
    test.setTimeout(5 * 60_000);

    // 前置：钉住桩游戏（载体与 gameCode / deployType 自洽）
    const probe = await probeStubCatalog(api);
    test.skip(!probe.ok, probe.reason);
    const game = probe.game;

    const deployed = await api.post("/api/instances", {
      instanceName: INSTANCE_NAME,
      gameId: game.id,
      hostId: host.id,
      deployType: COMPOSE_DEPLOY_TYPE,
      installPath: "~/games/e2e-cfg-preserve",
      portConfig: { game: 31199 },
      configInfo: {
        deployVersion: SELECTED_VERSION,
        gameCode: game.gameCode,
        ...PRESERVED_KEYS,
      },
    });
    assertOk(deployed, "创建实例（配置保键载体）");
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

    const successToast = page.locator(".el-message--success");
    for (let round = 1; round <= 2; round += 1) {
      // Minor 9：上一轮的 toast 还在屏上时，第二轮的断言会命中残留。
      // 每轮先把计数归零，再点保存等本轮自己的 toast。
      await expect(
        successToast,
        `第 ${round} 轮开始前上一轮 toast 应已消失`,
      ).toHaveCount(0);

      await page.getByRole("button", { name: "保存配置" }).click();
      // (c) 该入口返回成功
      await expect(successToast).toContainText("配置保存成功", {
        timeout: 20_000,
      });
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
      expect(after.gameCode, `第 ${round} 次保存后既有键 gameCode 应不变`).toBe(
        game.gameCode,
      );
    }
  });
});
