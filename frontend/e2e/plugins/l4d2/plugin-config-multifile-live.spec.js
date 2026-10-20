import { test, expect } from "@playwright/test";
import { injectSession } from "../../support/session.js";
import { e2eEnv } from "../../support/env.js";
import { skipWithoutLiveL4d2Instance } from "../../support/skip.js";

const INSTANCE_ID = e2eEnv.liveL4d2InstanceId;
const MULTI_FILE_PLUGIN = "1.11插件平台linux版";
const DESCRIBED_PLUGIN = "自选-多特感插件(v1.3.7)(Tordecybombo, breezy)";

skipWithoutLiveL4d2Instance();

/**
 * Page: L4D2 plugin config
 * Cases:
 * 1. A metadata plugin with multiple cfg files shows all files independently.
 * 2. A SourceMod plugin's quoted Minimum/Maximum metadata becomes a visible range
 *    and does not leak into descriptions.
 * 3. Drafts survive collapsing a file.
 * Preconditions: instance 97 is available and both named plugins are installed.
 * Steps: open the config page, select each plugin, edit and collapse a draft.
 * Pass: all three files are visible with status/path; ranges are populated without
 *       metadata leakage; per-item actions work; collapsed draft values remain.
 */
test.describe("L4D2 plugin config multi-file editing", () => {
  test("shows all cfg files, parses ranges, and keeps per-file drafts", async ({ page }) => {
    test.setTimeout(90_000);
    await injectSession(page);

    await page.goto(
      `/extensions/app/l4d2/plugin-config?instanceId=${INSTANCE_ID}`,
    );
    await expect(page.locator(".plugin-config-page")).toBeVisible({ timeout: 30_000 });

    const pluginSelect = page.locator(".header-actions .el-select").first();
    async function selectPlugin(name) {
      await pluginSelect.click();
      const option = page
        .locator(".el-select-dropdown__item")
        .filter({ hasText: name })
        .last();
      await expect(option).toBeVisible();
      const response = page.waitForResponse(
        (res) =>
          res.request().method() === "GET" &&
          res.url().includes("/plugin-config/get"),
      );
      await option.click();
      await response;
    }

    await selectPlugin(MULTI_FILE_PLUGIN);
    const fileTitles = page.locator(".config-file-title");
    await expect(fileTitles).toHaveCount(3);
    await expect(page.getByText("sm_warmode_off.cfg", { exact: true })).toBeVisible();
    await expect(page.getByText("sm_warmode_on.cfg", { exact: true })).toBeVisible();
    await expect(page.getByText("sourcemod.cfg", { exact: true })).toBeVisible();
    await expect(page.getByText("无配置项", { exact: true }).first()).toBeVisible();

    await selectPlugin(DESCRIBED_PLUGIN);
    const describedHeader = page
      .locator(".el-collapse-item__header")
      .filter({ hasText: "specialspawner.cfg" });
    await expect(describedHeader).toBeVisible();
    if (!(await describedHeader.getAttribute("aria-expanded"))?.includes("true")) {
      await describedHeader.click();
    }

    const firstValueInput = page.locator(".el-table__body .el-input__inner").first();
    await expect(firstValueInput).toBeVisible();
    await expect(page.getByText("临时设置", { exact: true }).first()).toBeVisible();

    const firstRangeCell = page.locator(".el-table__body tbody tr td:nth-child(4)").first();
    await expect(firstRangeCell).toHaveText("0 ~ 32");

    const firstDescriptionCell = page.locator(".el-table__body tbody tr td:nth-child(5)").first();
    await expect(firstDescriptionCell).not.toContainText(/Minimum:|Maximum:/);
    await expect(firstDescriptionCell).toContainText("生还者团队不超过4人时有多少个特感");
    await expect(firstDescriptionCell).not.toContainText(/鐢|鍚|缂/);

    const original = await firstValueInput.inputValue();
    const changed = original === "draft-check" ? "draft-check-2" : "draft-check";
    await firstValueInput.fill(changed);
    await describedHeader.click();
    await expect(page.locator(".el-table__body .el-input__inner").first()).toBeHidden();
    await describedHeader.click();
    await expect(firstValueInput).toHaveValue(changed);
  });
});
