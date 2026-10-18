/**
 * 页面: 无 UI（API 驱动）—— BR-12 拦截发生在扩展阶段入口，界面侧可见物是部署日志词面与实例终态
 * 用例: AC-20 端到端负向两分支（① 本次所选 / ② 配置既存键）
 * 前置: 平台前后端已运行；E2E_TEST_HOST_* 指向装有 Docker 的牺牲主机；
 *       桩游戏 + plugin-stub 已就位（首次探测目录 AVAILABLE）。
 * 步骤: 用 PF4J 卸载/装载桩插件，使「目录不可读取」这一 BR-12 触发条件可控：
 *       ① 卸载插件 ⇒ 新建实例（create 载荷携带 deployVersion = 本次所选）⇒ 部署在扩展阶段入口拦截；
 *          目录卸载后始终不可读（无周期重扫），拦截发生在该次部署自己的轮次里。
 *       ② 承接同一构造：实例已因 ① 失败而进入 ERROR，配置里已有 deployVersion（由那次部署写入）——
 *          经 PUT /config 再写一次既存键（合并写，不新增语义）⇒ retry-deploy（无载荷，后端不设
 *          explicitVersionSelection ⇒ ② 支）⇒ 既存键分支拦截。
 * 通过标准（锚点 = ui-spec §6.3，核对物 = GET /instances/{id}/deploy-progress 的日志字段与词面）:
 *  - 分支 ①: 出现「部署终止：本次所选版本 〈versionId〉 不可用」；**不**出现「由既往部署写入」；
 *    恢复路径行**不**出现；实例 status = error、日志无 stage=START / HEALTH_CHECK
 *  - 分支 ②: 出现「部署终止：实例配置要求的版本 〈versionId〉 不可用」+「由既往部署写入」；
 *    恢复路径行**出现**；实例 status = error、日志无 stage=START / HEALTH_CHECK
 * 注: ① 与 ② 的区分锚点是「本次所选」vs「实例配置要求的…由既往部署写入」，恢复路径行只在 ② 出现。
 *     每次写操作按信封 code 判成功（BusinessException 是 HTTP 200 + code≠200），
 *     retry 前后各一道屏障，保证读到的日志属于 retry 这一轮，不是 create 那一轮。
 *     日志的阶段归属判 `logs[].stage` 字段，不拼 `[HEALTH_CHECK]` 一类句子。
 */
import { test, expect } from "@playwright/test";
import { e2eEnv } from "../../support/env.js";
import { skipWithoutTestHost } from "../../support/skip.js";
import { assertOk, createApi, login } from "../../support/api.js";
import { acquireTestHost, deleteInstancesByName, releaseTestHost } from "../../support/test-host.js";
import {
  COMPOSE_DEPLOY_TYPE,
  probeStubCatalog,
  readDeployConfig,
} from "../../support/deploy-versions.js";

skipWithoutTestHost();

const STUB_JAR = "plugin-stub-1.0.0-SNAPSHOT.jar";
const STUB_PLUGIN_ID = "plugin-stub";
const VERSION = "2.0.0-patched";
const HOST_SUFFIX = "br12";

test.describe.serial("BR-12 拦截两分支（AC-20 端到端）", () => {
  let api = null;
  let host = null;
  let stubGameId = null;
  const created = [];

  async function setStubLoaded(loaded) {
    if (loaded) {
      const res = await api.post(
        `/api/pf4j/plugins/load?jarName=${STUB_JAR}`,
      );
      // 插件已装载时 load 会失败（插件 ID 已存在）；这不是本用例的判据，只要目录最终可用即可。
      if (!(res.body?.code === 200 || res.body?.code === 0)) {
        console.log(`[br12] 桩插件 load 未成功（可能已装载）: ${JSON.stringify(res.body).slice(0, 200)}`);
      }
    } else {
      assertOk(
        await api.delete(`/api/pf4j/plugins/${STUB_PLUGIN_ID}`),
        "卸载桩插件",
      );
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

  async function readProgress(instanceId) {
    const res = assertOk(
      await api.get(`/api/instances/${instanceId}/deploy-progress`),
      "读部署进度",
    );
    return res.body?.data ?? { logs: [] };
  }

  /** 词面核对对象：日志 message 的拼接（只用于 §6.3 明文授权的子串判定）。 */
  function logText(logs) {
    return logs.map((l) => l.message ?? "").join("\n");
  }

  /** 阶段归属核对对象：logs[].stage 字段（不拼 [HEALTH_CHECK] 一类句子）。 */
  function stageSeen(logs, stage) {
    return logs.some((l) => l.stage === stage);
  }

  async function instanceStatus(instanceName) {
    const res = assertOk(
      await api.get(
        `/api/instances?keyword=${encodeURIComponent(instanceName)}&size=50`,
      ),
      "查实例状态",
    );
    const rec = (res.body?.data?.records ?? []).find(
      (r) => r.instanceName === instanceName,
    );
    return rec?.status ?? null;
  }

  /** 终态判据 = 票面要求的实例 ERROR（不是 stopped / running 任取其一）。 */
  async function waitInstanceError(instanceName, timeoutMs) {
    await expect
      .poll(() => instanceStatus(instanceName), {
        timeout: timeoutMs,
        intervals: [3_000, 5_000],
      })
      .toBe("error");
  }

  /** retry 屏障之一：状态先离开 error（retryDeploy 同步置 installing）。 */
  async function waitLeftError(instanceName, timeoutMs = 120_000) {
    await expect
      .poll(() => instanceStatus(instanceName), {
        timeout: timeoutMs,
        intervals: [500, 1_000],
      })
      .not.toBe("error");
  }

  /** retry 屏障之二：等 retry 那一轮的 ② 锚点行出现，再读整份日志。 */
  async function waitForLogLine(instanceId, needle, timeoutMs) {
    await expect
      .poll(
        async () => logText((await readProgress(instanceId)).logs ?? []),
        { timeout: timeoutMs, intervals: [2_000, 3_000] },
      )
      .toContain(needle);
  }

  test.beforeAll(async () => {
    const token = await login(
      e2eEnv.backendUrl,
      e2eEnv.adminUser,
      e2eEnv.adminPass,
    );
    api = createApi(e2eEnv.backendUrl, token);
    host = await acquireTestHost(api, HOST_SUFFIX);
    await deleteInstancesByName(api, "e2e-br12-a-01");
    await deleteInstancesByName(api, "e2e-br12-b-01");
    const probe = await probeStubCatalog(api);
    test.skip(!probe.ok, probe.reason);
    stubGameId = probe.game.id;
  });

  test.afterAll(async () => {
    if (!api) return;
    // 还原插件装载状态，避免影响后续用例
    await api.post(`/api/pf4j/plugins/load?jarName=${STUB_JAR}`).catch(() => {});
    for (const id of created) {
      await api.delete(`/api/instances/${id}`).catch(() => {});
    }
    // 主机删除失败要显形：host_info.ip_address 的唯一键会把残留传给后续 spec。
    if (host) await releaseTestHost(api, host);
  });

  test("AC-20 (a) 本次所选：目录不可读取 ⇒ 扩展入口拦截（① 词面）", async () => {
    test.setTimeout(10 * 60_000);
    try {
      await setStubLoaded(false);

      const res = await api.post("/api/instances", {
        instanceName: "e2e-br12-a-01",
        gameId: stubGameId,
        hostId: host.id,
        deployType: COMPOSE_DEPLOY_TYPE,
        installPath: "~/games/e2e-br12-a",
        portConfig: { game: 31201 },
        // create 载荷携带 deployVersion ⇒ 「本次所选」
        configInfo: { deployVersion: VERSION, autoRestart: 0 },
      });
      assertOk(res, "创建实例（① 支）");
      const instanceId = res.body?.data?.id ?? null;
      expect(instanceId, `实例创建失败: ${JSON.stringify(res.body)}`).toBeTruthy();
      created.push(instanceId);

      await waitInstanceError("e2e-br12-a-01", 8 * 60_000);
      const logs = (await readProgress(instanceId)).logs ?? [];
      const text = logText(logs);

      // 构造判据：目录卸载后不可读取 ⇒ 扩展入口必须拦截，且归入 ① 分支。
      // 若这里仍取到步骤集，那是后端发现，交回 Leader 转 BLOCKED —— 不用 SKIP 掩盖。
      expect(
        text,
        "目录不可读取时扩展阶段入口应拦截（BR-12）；未拦截 ⇒ 构造/后端需重新定性",
      ).toContain("本次所选版本");
      expect(text).toContain(`本次所选版本 ${VERSION} 不可用`);
      expect(text).not.toContain("由既往部署写入");
      expect(text).not.toContain("恢复路径：");
      // 未进 START / HEALTH_CHECK（按 stage 字段判，不拼句子）
      expect(stageSeen(logs, "START"), "① 支不应进入 START 阶段").toBe(false);
      expect(
        stageSeen(logs, "HEALTH_CHECK"),
        "① 支不应进入 HEALTH_CHECK 阶段",
      ).toBe(false);
    } finally {
      await setStubLoaded(true);
    }
  });

  test("AC-20 (b) 配置既存键：目录不可读取 ⇒ 扩展入口拦截（② 词面含恢复路径）", async () => {
    test.setTimeout(12 * 60_000);
    try {
      // 先造出「实例配置里已有 deployVersion 且实例处于 ERROR」的前置：
      // 目录不可读 + create 携带版本 ⇒ ① 支失败，那次部署把版本写进实例配置。
      await setStubLoaded(false);
      const res = await api.post("/api/instances", {
        instanceName: "e2e-br12-b-01",
        gameId: stubGameId,
        hostId: host.id,
        deployType: COMPOSE_DEPLOY_TYPE,
        installPath: "~/games/e2e-br12-b",
        portConfig: { game: 31202 },
        configInfo: { deployVersion: VERSION, autoRestart: 0 },
      });
      assertOk(res, "创建实例（② 支前置）");
      const instanceId = res.body?.data?.id ?? null;
      expect(instanceId, `实例创建失败: ${JSON.stringify(res.body)}`).toBeTruthy();
      created.push(instanceId);
      await waitInstanceError("e2e-br12-b-01", 8 * 60_000);

      // 落「配置既存键」：合并写，只动 deployVersion。
      assertOk(
        await api.put(`/api/instances/${instanceId}/config`, {
          deployVersion: VERSION,
        }),
        "写入实例配置既有键",
      );

      // 目录不可读取 + 既存键 ⇒ retry-deploy（无载荷）走 ② 分支。
      // retryDeploy 不设 explicitVersionSelection ⇒ ② 支；实例非 ERROR 时后端会拒绝，
      // 所以这里的成功判据必须看信封 code，不能只看 HTTP status。
      assertOk(
        await api.post(`/api/instances/${instanceId}/retry-deploy`),
        "重试部署（② 支）",
      );

      // 屏障：先后离开 error，再等 retry 这一轮自己的 ② 锚点行。
      // 不做这两步就会把 create 那一次的 ① 日志当 retry 的读（G2 必改 1）。
      await waitLeftError("e2e-br12-b-01");
      await waitForLogLine(instanceId, "实例配置要求的版本", 10 * 60_000);
      await waitInstanceError("e2e-br12-b-01", 10 * 60_000);

      const logs = (await readProgress(instanceId)).logs ?? [];
      const text = logText(logs);

      expect(text).toContain(`实例配置要求的版本 ${VERSION} 不可用`);
      expect(text).toContain("由既往部署写入");
      expect(text).toContain("恢复路径：");
      expect(text).not.toContain("本次所选版本");
      expect(stageSeen(logs, "START"), "② 支不应进入 START 阶段").toBe(false);
      expect(
        stageSeen(logs, "HEALTH_CHECK"),
        "② 支不应进入 HEALTH_CHECK 阶段",
      ).toBe(false);
    } finally {
      await setStubLoaded(true);
    }
  });
});
