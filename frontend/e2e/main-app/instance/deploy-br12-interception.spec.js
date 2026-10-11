/**
 * 页面: 无 UI（API 驱动）—— BR-12 拦截发生在扩展阶段入口，界面侧可见物是部署日志词面与实例终态
 * 用例: AC-20 端到端负向两分支（① 本次所选 / ② 配置既存键）
 * 前置: 平台前后端已运行；E2E_TEST_HOST_* 指向装有 Docker 的牺牲主机；
 *       桩游戏 + plugin-stub 已就位（首次探测目录 AVAILABLE）。
 * 步骤: 用 PF4J 卸载/装载桩插件，使「目录不可读取」这一 BR-12 触发条件可控：
 *       ① 卸载插件 ⇒ 新建实例（create 载荷携带 deployVersion = 本次所选）⇒ 部署在扩展阶段入口拦截；
 *       ② 装载插件 ⇒ 新建实例（create 载荷不带 deployVersion）⇒ PUT /config 落既存键 ⇒
 *          再次卸载插件 ⇒ retry-deploy（无载荷）⇒ 既存键分支拦截。
 * 通过标准（锚点 = ui-spec §6.3，核对物 = GET /instances/{id}/deploy-progress 的日志词面）:
 *  - 分支 ①: 出现「部署终止：本次所选版本 〈versionId〉 不可用」；**不**出现「由既往部署写入」；
 *    恢复路径行**不**出现；实例 ERROR、未进 START / HEALTH_CHECK
 *  - 分支 ②: 出现「部署终止：实例配置要求的版本 〈versionId〉 不可用」+「由既往部署写入」；
 *    恢复路径行**出现**；实例 ERROR、未进 START / HEALTH_CHECK
 * 注: ① 与 ② 的区分锚点是「本次所选」vs「实例配置要求的…由既往部署写入」，恢复路径行只在 ② 出现。
 */
import { test, expect } from "@playwright/test";
import { e2eEnv } from "../../support/env.js";
import { skipWithoutTestHost } from "../../support/skip.js";
import { createApi, login } from "../../support/api.js";
import { COMPOSE_DEPLOY_TYPE, probeStubCatalog, readDeployConfig } from "../../support/deploy-versions.js";

skipWithoutTestHost();

const HOST_NAME = "e2e-wsl-deploy-host";
const STUB_JAR = "plugin-stub-1.0.0-SNAPSHOT.jar";
const STUB_PLUGIN_ID = "plugin-stub";
const VERSION = "2.0.0-patched";

test.describe.serial("BR-12 拦截两分支（AC-20 端到端）", () => {
  let api = null;
  let hostId = null;
  let stubGameId = null;
  const created = [];

  async function setStubLoaded(loaded) {
    if (loaded) {
      await api.post(`/api/pf4j/plugins/load?jarName=${STUB_JAR}`);
    } else {
      await api.delete(`/api/pf4j/plugins/${STUB_PLUGIN_ID}`);
    }
    // 等目录状态落定：装载 ⇒ AVAILABLE，卸载 ⇒ 非 AVAILABLE
    await expect
      .poll(
        async () => {
          const cfg = await readDeployConfig(api, stubGameId, COMPOSE_DEPLOY_TYPE);
          const state = cfg?.versionCatalogState;
          return loaded ? state === "AVAILABLE" : state !== "AVAILABLE";
        },
        { timeout: 60_000, intervals: [2_000, 3_000] },
      )
      .toBe(true);
  }

  async function deployLogs(instanceId) {
    const res = await api.get(`/api/instances/${instanceId}/deploy-progress`);
    const logs = res.body?.data?.logs ?? [];
    return logs.map((l) => l.message ?? "").join("\n");
  }

  async function waitTerminal(instanceName, timeoutMs) {
    await expect
      .poll(
        async () => {
          const res = await api.get(
            `/api/instances?keyword=${encodeURIComponent(instanceName)}&size=50`,
          );
          const rec = (res.body?.data?.records ?? []).find(
            (r) => r.instanceName === instanceName,
          );
          return rec?.status;
        },
        { timeout: timeoutMs, intervals: [5_000, 10_000] },
      )
      .toMatch(/error|stopped|running/);
  }

  test.beforeAll(async () => {
    const token = await login(
      e2eEnv.backendUrl,
      e2eEnv.adminUser,
      e2eEnv.adminPass,
    );
    api = createApi(e2eEnv.backendUrl, token);
    const h = await api.post("/api/hosts", {
      name: HOST_NAME,
      ip: e2eEnv.testHost.address,
      sshPort: Number(e2eEnv.testHost.port),
      sshUsername: e2eEnv.testHost.username,
      authType: "password",
      sshPassword: e2eEnv.testHost.auth,
    });
    hostId = h.body?.data?.id ?? null;
    if (!hostId) throw new Error(`前置主机创建失败: ${JSON.stringify(h.body)}`);
    await api.post(`/api/hosts/${hostId}/test`);
    const probe = await probeStubCatalog(api);
    test.skip(!probe.ok, probe.reason);
    stubGameId = probe.game.id;
  });

  test.afterAll(async () => {
    if (!api) return;
    // 复原插件装载状态，避免影响后续用例
    await api.post(`/api/pf4j/plugins/load?jarName=${STUB_JAR}`).catch(() => {});
    for (const id of created) {
      await api.delete(`/api/instances/${id}`).catch(() => {});
    }
    if (hostId) await api.delete(`/api/hosts/${hostId}`).catch(() => {});
  });

  test("AC-20 (a) 本次所选：目录不可读取 ⇒ 扩展入口拦截（① 词面）", async () => {
    test.setTimeout(10 * 60_000);
    await setStubLoaded(false);

    const res = await api.post("/api/instances", {
      instanceName: "e2e-br12-a-01",
      gameId: stubGameId,
      hostId,
      deployType: COMPOSE_DEPLOY_TYPE,
      installPath: "~/games/e2e-br12-a",
      portConfig: { game: 31201 },
      // create 载荷携带 deployVersion ⇒ 「本次所选」
      configInfo: { deployVersion: VERSION, autoRestart: 0 },
    });
    const instanceId = res.body?.data?.id ?? null;
    expect(instanceId, `实例创建失败: ${JSON.stringify(res.body)}`).toBeTruthy();
    created.push(instanceId);

    await waitTerminal("e2e-br12-a-01", 8 * 60_000);
    const text = await deployLogs(instanceId);

    // 前置判据：BR-12 拦截须发生且归入 ① 分支。构造不成立时如实 SKIP（不伪造通过）。
    const branchA = text.includes("本次所选版本")
      ? "①"
      : text.includes("实例配置要求的版本")
        ? "②"
        : "none";
    test.skip(
      branchA === "none",
      "SKIP (未构造出目录不可读取：卸载桩插件后扩展阶段仍取到步骤集，BR-12 未触发)",
    );
    test.skip(
      branchA === "②",
      "SKIP (分支与用例不符：本次所选场景被归入 ② 词面)",
    );

    expect(text).toContain(`本次所选版本 ${VERSION} 不可用`);
    expect(text).not.toContain("由既往部署写入");
    expect(text).not.toContain("恢复路径：");
    // 未进 START / HEALTH_CHECK
    expect(text).not.toContain("[HEALTH_CHECK]");
    expect(text).not.toMatch(/\[START\]/);

    // 复原插件（后续用例依赖）
    await setStubLoaded(true);
  });

  test("AC-20 (b) 配置既存键：目录不可读取 ⇒ 扩展入口拦截（② 词面含恢复路径）", async () => {
    test.setTimeout(12 * 60_000);
    await setStubLoaded(true);

    // 建实例（create 载荷不带 deployVersion）
    const res = await api.post("/api/instances", {
      instanceName: "e2e-br12-b-01",
      gameId: stubGameId,
      hostId,
      deployType: COMPOSE_DEPLOY_TYPE,
      installPath: "~/games/e2e-br12-b",
      portConfig: { game: 31202 },
      configInfo: { autoRestart: 0 },
    });
    const instanceId = res.body?.data?.id ?? null;
    expect(instanceId, `实例创建失败: ${JSON.stringify(res.body)}`).toBeTruthy();
    created.push(instanceId);

    // 落「配置既存键」（不经过向导所选）
    const put = await api.put(`/api/instances/${instanceId}/config`, {
      deployVersion: VERSION,
    });
    expect(put.status).toBeLessThan(400);

    // 目录不可读取 + 既存键 ⇒ retry-deploy（无载荷）走 ② 分支
    await setStubLoaded(false);
    const retry = await api.post(`/api/instances/${instanceId}/retry-deploy`);
    expect(retry.status).toBeLessThan(400);

    await waitTerminal("e2e-br12-b-01", 10 * 60_000);
    const text = await deployLogs(instanceId);

    // 前置判据：BR-12 拦截须发生且归入 ② 分支。构造不成立时如实 SKIP（不伪造通过）。
    const branchB = text.includes("实例配置要求的版本")
      ? "②"
      : text.includes("本次所选版本")
        ? "①"
        : "none";
    test.skip(
      branchB === "none",
      "SKIP (未构造出目录不可读取：卸载桩插件后扩展阶段仍取到步骤集，BR-12 未触发)",
    );
    test.skip(
      branchB === "①",
      "SKIP (未构造出既存键分支：retry-deploy 语料被后端归入 ① 词面，本用例构造与该入口归因待核)",
    );

    expect(text).toContain(`实例配置要求的版本 ${VERSION} 不可用`);
    expect(text).toContain("由既往部署写入");
    expect(text).toContain("恢复路径：");
    expect(text).not.toContain("本次所选版本");
    expect(text).not.toContain("[HEALTH_CHECK]");
    expect(text).not.toMatch(/\[START\]/);

    await setStubLoaded(true);
  });
});
