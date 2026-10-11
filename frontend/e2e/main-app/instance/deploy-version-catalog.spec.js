/**
 * 页面: 部署向导 `/services/instances/deploy`（步骤 2「选择游戏」目标版本控件 + 步骤 5 摘要行）
 * 用例: V-21 · AC-01 界面侧正向（桩游戏目录 AVAILABLE）+ AC-24 / AC-02 端到端负向（dnf-tw 未填充态）
 * 前置: 平台前后端已运行；E2E_TEST_HOST_* 指向装有 Docker 的牺牲主机；
 *       组 1 正向的载体 = 桩游戏（backend/plugin-stub 已装入 plugins/、acceptance/games/stub.yml
 *       投放于 ./games/）——目录 AVAILABLE（3 条目）；组 3 载体 = 内置 dnf_tw 游戏。
 * 步骤: 见各 test 标题内的「对应」行（逐条对齐 ui-spec §5 / §6.1 / §6.4 与 design §10 V-21）。
 * 通过标准（逐条 = 该用例判的是哪条 AC 的哪个判据）:
 *  - V-21 / AC-01 句一（集合等式）: 非默认选项内可见的 versionId 副标集合 == 目录非默认条目集合，
 *    去重相等且每元素恰出现 1 次（§6.1 前提③；不是序列比对）
 *  - V-21 / AC-01 句二（默认项单独判）: 主文本「默认版本」那行的副标 = 默认条目的 versionId（支①）
 *  - V-21 / AC-01 接口侧: GET /games/{id}/deploy-config/{deployType}（deployType 为路径段）
 *    deployVersions[].versionId 集合与声明精确相等且同序、versionCatalogState == AVAILABLE
 *  - V-21 / F-05: 改选非默认后提交载荷含且只含一个 deployVersion 键，值 = 该条目 versionId
 *  - AC-24 ①③⑤⑥ / AC-02 (dnf-tw S1): 无版本控件、无「声明不合法」提示（EMPTY ≠ INVALID）、
 *    无任何示例版号 / URL / 路径（载体 = 内置 dnf_tw 游戏）
 *  - AC-24 ② / AC-02: S1 提交载荷不存在 deployVersion 键（载体 = 桩游戏 + 插件未装载 ⇒ 目录 ABSENT）
 * 注: dnf-tw 的「EMPTY 态」要求 MERC-23（plugin-dnf-tw）已合入；未合入时目录态为 ABSENT，
 *     两个 S1 形态（ABSENT / EMPTY）的界面不变量一致，实际跑到哪一支由 API 侧 versionCatalogState 决定。
 */
import { test, expect } from "@playwright/test";
import { e2eEnv } from "../../support/env.js";
import { skipWithoutTestHost } from "../../support/skip.js";
import { createApi, login } from "../../support/api.js";
import { injectSession } from "../../support/session.js";
import {
  COMPOSE_DEPLOY_TYPE,
  DNF_TW_GAME_CODE,
  STUB_DEFAULT_VERSION_ID,
  STUB_JAR,
  STUB_NON_DEFAULT_VERSION_IDS,
  STUB_VERSION_IDS,
  defaultOption,
  expectSetEqualityExactlyOnce,
  findGameByCode,
  gotoWizardAndSelectCompose,
  nonDefaultSubs,
  openAndReadOptions,
  probeStubCatalog,
  readDeployConfig,
  versionSelect,
} from "../../support/deploy-versions.js";

skipWithoutTestHost();

const HOST_NAME = "e2e-wsl-deploy-host";

// 步骤 3 的必填 compose 变量（Element Plus 给必填 form-item 加 is-required）：
// 本用例只关心 AC-24 负向判据，不关心变量语义，给空必填项填占位值使向导可继续。
async function fillEmptyRequiredInputs(page, value = "e2e") {
  const inputs = page.locator(".el-form-item.is-required").locator("input");
  const n = await inputs.count();
  for (let i = 0; i < n; i += 1) {
    const inp = inputs.nth(i);
    if ((await inp.isVisible()) && !(await inp.inputValue())) {
      await inp.fill(value);
    }
  }
}

test.describe.serial("部署目标版本（V-21 AC-01 正向 / AC-24 负向）", () => {
  let api = null;
  let hostId = null;
  const createdInstanceNames = [];

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
    if (!api) return;
    for (const name of createdInstanceNames) {
      const res = await api
        .get(`/api/instances?keyword=${encodeURIComponent(name)}&size=10`)
        .catch(() => null);
      for (const inst of res?.body?.data?.records ?? []) {
        if (inst.instanceName === name) {
          await api.delete(`/api/instances/${inst.id}`).catch(() => {});
        }
      }
    }
    if (hostId) await api.delete(`/api/hosts/${hostId}`).catch(() => {});
  });

  test("V-21 AC-01 界面侧正向（桩游戏目录 AVAILABLE）", async ({ page }) => {
    test.setTimeout(10 * 60_000);
    const probe = await probeStubCatalog(api);
    test.skip(!probe.ok, probe.reason);
    const { cfg } = probe;

    // —— 接口侧（V-21 判据块末）：集合精确相等且同序、目录可用 ——
    expect(cfg.versionCatalogState).toBe("AVAILABLE");
    expect((cfg.deployVersions ?? []).map((v) => v.versionId)).toEqual(
      STUB_VERSION_IDS,
    );

    await injectSession(page);
    await gotoWizardAndSelectCompose(page, {
      hostName: HOST_NAME,
      gameSearch: "stub",
      gameText: "验收资产",
      methodLabel: "Docker Compose",
    });

    // 控件出现（P1 谓词真）——锚点：data-ext-version-select / aria-label="目标版本"
    const select = versionSelect(page);
    await expect(select).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText("目标版本", { exact: false }).first()).toBeVisible();

    // 收起态值区：默认选中项 = defaultEntry 条目的 versionId（主文本「默认版本」+ 该条目 versionId 副标）
    const valueMain = page.locator(".version-value-main").first();
    await expect(valueMain).toHaveText("默认版本");
    await expect(page.locator(".version-value .version-id-sub").first()).toHaveText(
      STUB_DEFAULT_VERSION_ID,
    );

    // —— §6.1 前提①②③：先展开再取集合、按锚点 popper 作用域、集合等式（非序列） ——
    const options = await openAndReadOptions(page);
    // 选项数 = 条目数（默认项是合成项，恰好对应 3 个目录条目中的默认条目 → 总数 3）
    expect(options.length).toBe(STUB_VERSION_IDS.length);

    // 句一：非默认选项内可见的 versionId 副标集合 == 目录非默认条目集合
    const subs = nonDefaultSubs(options);
    expectSetEqualityExactlyOnce(subs, STUB_NON_DEFAULT_VERSION_IDS);

    // 句二：主文本「默认版本」那一行单独判（支①：桩目录存在 default=true 条目 ⇒ 副标 = 该条目 versionId）
    const def = defaultOption(options);
    expect(def, "下拉内应存在主文本为「默认版本」的选项行").not.toBeNull();
    expect(def.sub).toBe(STUB_DEFAULT_VERSION_ID);

    // 改选非默认条目（按 versionId 副标定位选项行，不按位置）
    await page
      .locator(".version-select-popper .el-select-dropdown__item")
      .filter({ hasText: "2.0.0-patched" })
      .first()
      .click();
    await expect(page.locator(".version-value .version-id-sub").first()).toHaveText(
      "2.0.0-patched",
    );

    // —— 步骤 2 → 步骤 3 ——
    await page.getByRole("button", { name: "下一步" }).click();

    // —— 走到步骤 5，核对摘要行（断言字段值，不匹配拼好的句子） ——
    const INSTANCE_NAME = "e2e-version-01";
    createdInstanceNames.push(INSTANCE_NAME);
    await page.getByPlaceholder("请输入实例名称").fill(INSTANCE_NAME);
    await page.getByPlaceholder(/请输入部署路径/).fill("~/games/e2e-version");
    await page.getByRole("button", { name: "检查端口" }).click();
    await expect(page.getByText("端口可用")).toBeVisible({ timeout: 20_000 });
    await page.getByRole("button", { name: "下一步" }).click();
    // 步骤 4 环境校验在点「下一步」时执行，通过后自动进入确认部署步
    await page.getByRole("button", { name: "下一步" }).click();

    const summary = page.locator(".version-summary").first();
    await expect(summary).toBeVisible({ timeout: 60_000 });
    await expect(summary.locator(".version-summary-main")).toHaveText("桩改造版");
    await expect(summary.locator(".version-id-sub")).toHaveText("2.0.0-patched");

    // —— F-05：提交载荷含且只含一个 deployVersion 键，值 = 所选项 versionId ——
    const [req] = await Promise.all([
      page.waitForRequest(
        (r) => r.url().includes("/api/instances") && r.method() === "POST",
      ),
      page.getByRole("button", { name: "开始部署" }).click(),
    ]);
    const body = req.postDataJSON();
    const payloadKeys = Object.keys(body.configInfo ?? {});
    expect(
      payloadKeys.filter((k) => k === "deployVersion"),
    ).toHaveLength(1);
    expect(body.configInfo.deployVersion).toBe("2.0.0-patched");
  });

  test("AC-24 ①③⑤⑥ / AC-02：dnf-tw S1 向导不渲染版本项（界面侧）", async ({ page }) => {
    test.setTimeout(5 * 60_000);
    const games = await api.get("/api/games/list");
    const dnf = findGameByCode(games.body?.data, DNF_TW_GAME_CODE);
    test.skip(!dnf, `SKIP (未找到内置游戏 '${DNF_TW_GAME_CODE}')`);
    const cfg = await readDeployConfig(api, dnf.id, COMPOSE_DEPLOY_TYPE);
    const state = cfg?.versionCatalogState;
    // S1 形态 = P1 假（ABSENT 或 EMPTY 都合法）；INVALID 不属 S1，交其他判据
    test.skip(
      state !== "ABSENT" && state !== "EMPTY",
      `SKIP (dnf-tw 目录状态非 S1：${state ?? "null"})`,
    );
    // ③ EMPTY ≠ INVALID：读到 0 条不得判成「声明不合法」（EMPTY 态需 MERC-23 合入才出现）
    if (state === "EMPTY") expect(state).not.toBe("INVALID");

    await injectSession(page);
    await gotoWizardAndSelectCompose(page, {
      hostName: HOST_NAME,
      gameSearch: DNF_TW_GAME_CODE,
      gameText: "DNF台服",
      methodLabel: "Docker Compose",
    });

    // ① 向导不出现「目标版本」控件（含标题与占位空间）
    await expect(versionSelect(page)).toHaveCount(0);
    await expect(page.locator("#ext-version-section-title")).toHaveCount(0);
    await expect(page.getByText("目标版本", { exact: true })).toHaveCount(0);

    const panelText = await page.locator(".step-panel").nth(1).innerText();
    // ③ 无「声明不合法」提示行（空目录 ≠ 声明不合法，RISK-13）
    expect(panelText).not.toContain("不合法");
    // ⑥ 界面无任何示例版号 / URL / 路径（§6.4），只核对可见文案
    expect(panelText).not.toMatch(/https?:\/\//);
    expect(panelText).not.toMatch(/STUB-|stub-version-marker/);
  });

  test("AC-24 ② / AC-02：S1 提交载荷不存在 deployVersion（桩游戏 + 插件未装载）", async ({
    page,
  }) => {
    test.setTimeout(10 * 60_000);
    const probe = await probeStubCatalog(api);
    test.skip(!probe.ok, probe.reason);
    // 卸载桩插件 ⇒ 目录 ABSENT（S1：P1 假）；界面无控件、载荷无键
    await api.delete("/api/pf4j/plugins/plugin-stub");
    await expect
      .poll(
        async () =>
          (await readDeployConfig(api, probe.game.id, COMPOSE_DEPLOY_TYPE))
            ?.versionCatalogState,
        { timeout: 30_000, intervals: [1_000, 2_000] },
      )
      .not.toBe("AVAILABLE");
    try {
      await injectSession(page);
      await gotoWizardAndSelectCompose(page, {
        hostName: HOST_NAME,
        gameSearch: "stub",
        gameText: "验收资产",
        methodLabel: "Docker Compose",
      });
      await expect(versionSelect(page)).toHaveCount(0);

      // 走完向导到步骤 5（桩游戏无必填变量、无默认端口冲突）
      await page.getByRole("button", { name: "下一步" }).click();
      const INSTANCE_NAME = "e2e-s1-payload-01";
      createdInstanceNames.push(INSTANCE_NAME);
      await page.getByPlaceholder("请输入实例名称").fill(INSTANCE_NAME);
      await page.getByPlaceholder(/请输入部署路径/).fill("~/games/e2e-s1-payload");
      await fillEmptyRequiredInputs(page);
      await page.getByRole("button", { name: "检查端口" }).click();
      await expect(page.getByText("端口可用")).toBeVisible({ timeout: 20_000 });
      await page.getByRole("button", { name: "下一步" }).click();
      await page.getByRole("button", { name: "下一步" }).click();
      // ⑤ 步骤 5 摘要行不渲染（P2 = P1 ∨ 载荷含 deployVersion，S1 两者皆假）
      await expect(page.locator(".el-descriptions").first()).toBeVisible({
        timeout: 60_000,
      });
      await expect(page.locator(".version-summary")).toHaveCount(0);

      // ② 载荷不存在 deployVersion 键
      const [req] = await Promise.all([
        page.waitForRequest(
          (r) => r.url().includes("/api/instances") && r.method() === "POST",
        ),
        page.getByRole("button", { name: "开始部署" }).click(),
      ]);
      const body = req.postDataJSON();
      expect(
        Object.prototype.hasOwnProperty.call(
          body.configInfo ?? {},
          "deployVersion",
        ),
      ).toBe(false);
    } finally {
      // 复原插件装载状态，避免影响后续用例
      await api
        .post(`/api/pf4j/plugins/load?jarName=${STUB_JAR}`)
        .catch(() => null);
    }
  });
});
