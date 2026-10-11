// 部署扩展步骤 / 目标版本 的 E2E 夹具与前置判定（MERC-24）。
//
// 权威文本：ui-spec.md @ 62b49a9 §5 谓词 / §6.1 AC-01 两句口径与三条执行前提 /
// §6.3 两分支锚点 / §6.4 不渲染清单 / §7 X-11；design.md @ 790ccd6 §10 V-21 / V-23 / V-16。
//
// 断言纪律（本文件所有读者共用）：
//  - 只断言字段值与 locator，不匹配「拼好的句子」；用户可见词面一律取自 ui-spec。
//  - 复选框 / 目录判据按 ui-spec §6.1 前提②：作用域 = 目标控件锚点对应的 popper 内 item，
//    不限定在向导容器内、不按 class="vid" 全量扫。
import { expect } from "@playwright/test";

// 桩游戏 / 版本目录的字段值来源：backend/plugin-stub StubExtension（同源，声明序）。
export const STUB_GAME_CODE = "stub";
// 桩插件 jar 名（backend/plugins/ 下；与 scripts/deploy-plugin.sh 同路径）。
export const STUB_JAR = "plugin-stub-1.0.0-SNAPSHOT.jar";
// dnf-tw 游戏：core resources 内置（backend/core/src/main/resources/games/dnf_tw.yml）。
export const DNF_TW_GAME_CODE = "dnf_tw";
// 桩目录条目只有 docker-compose 一类可承载步骤（design §16.3 N5：集合外带步骤 ⇒ INVALID）。
export const COMPOSE_DEPLOY_TYPE = "docker-compose";

// StubExtension 声明序（三项；defaultEntry = 1.0.0-default 是第一项且 isDefault=true）。
export const STUB_VERSION_IDS = [
  "1.0.0-default",
  "2.0.0-patched",
  "2.1.0-selective",
];
export const STUB_DEFAULT_VERSION_ID = "1.0.0-default";
export const STUB_NON_DEFAULT_VERSION_IDS = STUB_VERSION_IDS.filter(
  (v) => v !== STUB_DEFAULT_VERSION_ID,
);

export function findGameByCode(games, code) {
  return (games ?? []).find((g) => g.gameCode === code) ?? null;
}

// GET /api/games/{gameId}/deploy-config/{deployType}
// 注意：deployType 是路径段，不是 query 参数（design §10 V-21 v0.3.1 订正）。
export async function readDeployConfig(api, gameId, deployType) {
  const res = await api.get(
    `/api/games/${gameId}/deploy-config/${deployType}`,
  );
  return res.body?.data ?? null;
}

// 组 1 / 2 / 4 的载体前置：桩游戏存在且其版本目录 AVAILABLE（≥2 条目）。
// 平台**不自动装载** plugins/ 下的 jar（须经 POST /pf4j/plugins/load）；本函数先显式装载再判定。
// 不满足 ⇒ 返回 reason，由调用方 test.skip（不静默降低判据）。
export async function probeStubCatalog(api, loadIfNeeded = true) {
  const res = await api.get("/api/games/list");
  const game = findGameByCode(res.body?.data, STUB_GAME_CODE);
  if (!game) {
    return {
      ok: false,
      reason:
        "SKIP (未找到桩游戏 'stub'：需投放 backend/plugin-stub 于 plugins/ 且 acceptance/games/stub.yml 于 ./games/)",
    };
  }
  let cfg = await readDeployConfig(api, game.id, COMPOSE_DEPLOY_TYPE);
  if (cfg?.versionCatalogState !== "AVAILABLE" && loadIfNeeded) {
    await api
      .post(`/api/pf4j/plugins/load?jarName=${STUB_JAR}`)
      .catch(() => null);
    for (let i = 0; i < 10; i += 1) {
      cfg = await readDeployConfig(api, game.id, COMPOSE_DEPLOY_TYPE);
      if (cfg?.versionCatalogState === "AVAILABLE") break;
      await new Promise((r) => setTimeout(r, 1000));
    }
  }
  if (cfg?.versionCatalogState !== "AVAILABLE") {
    return {
      ok: false,
      reason: `SKIP (桩游戏版本目录非 AVAILABLE：${cfg?.versionCatalogState ?? "null"})`,
    };
  }
  return { ok: true, game, cfg };
}

// 目标版本控件的稳定锚点（ui-spec §6.1 前提②）：data-ext-version-select / aria-label="目标版本"。
export function versionSelect(page) {
  return page
    .locator('[data-ext-version-select], [aria-label="目标版本"]')
    .first();
}

// 展开控件后，按锚点所属 popper 取选项集合（ui-spec §6.1 前提①+②）。
// 返回 [{ main, sub }]，main = 选项主文本（.version-option-text）、sub = versionId 副标（.version-id-sub）。
// 只读该锚点（popper-class="version-select-popper"）内的 item，不做全局 class="vid" 全扫。
export async function openAndReadOptions(page) {
  await versionSelect(page).click();
  const items = page.locator(
    ".version-select-popper .el-select-dropdown__item",
  );
  await expect(items.first()).toBeVisible({ timeout: 10_000 });
  const count = await items.count();
  const options = [];
  for (let i = 0; i < count; i += 1) {
    const item = items.nth(i);
    options.push({
      main: (await item.locator(".version-option-text").innerText()).trim(),
      sub: (await item.locator(".version-id-sub").count())
        ? (await item.locator(".version-id-sub").innerText()).trim()
        : "",
    });
  }
  return options;
}

// ui-spec §6.1 句一：非默认选项内可见的 versionId 副标集合（内容式锚点：主文本不为「默认版本」的选项行）。
export function nonDefaultSubs(options) {
  return options
    .filter((o) => o.main !== "默认版本")
    .map((o) => o.sub);
}

// §6.1 句二对象：主文本恰为「默认版本」的那一行。
export function defaultOption(options) {
  return options.find((o) => o.main === "默认版本") ?? null;
}

// 集合等式（§6.1 前提③）：去重集合相等且每元素恰出现 1 次，不是序列相等。
export function expectSetEqualityExactlyOnce(actual, expected) {
  const actualSorted = [...new Set(actual)].sort();
  const expectedSorted = [...new Set(expected)].sort();
  expect(actualSorted, `集合去重后应相等：实际 ${JSON.stringify(actual)}`).toEqual(
    expectedSorted,
  );
  // 每元素恰出现 1 次（去重会吃掉重复 ⇒ 单独核对原始长度）
  expect(
    actual.length,
    `每个元素应恰出现 1 次：实际 ${JSON.stringify(actual)}`,
  ).toBe(actualSorted.length);
}

// 走部署向导到「选择游戏」步：选主机 → 选游戏 → 选部署方式（compose 类才读目录）。
export async function gotoWizardAndSelectCompose(page, opts) {
  const { hostName, gameSearch, gameText, methodLabel } = opts;
  await page.goto("/services/instances/deploy");

  const hostCard = page.locator(".host-card", { hasText: hostName });
  await expect(hostCard.first()).toBeVisible({ timeout: 30_000 });
  await hostCard.first().click();
  await page.getByRole("button", { name: "下一步" }).click();

  // 步骤面板用 v-show：切换后「搜索游戏」框才可见
  const search = page.getByPlaceholder("搜索游戏名称或编码");
  await expect(search).toBeVisible({ timeout: 30_000 });
  await search.fill(gameSearch);
  await page.locator(".game-item", { hasText: gameText }).first().click();
  // 选中游戏后详情区才渲染部署方式 radio（textContent 含图标留白，正则容忍空白）
  await expect(page.locator(".detail-header")).toBeVisible();
  await page
    .locator(".el-radio-button")
    .filter({ hasText: new RegExp(`^\\s*${methodLabel}\\s*$`) })
    .click();
}
