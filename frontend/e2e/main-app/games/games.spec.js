/**
 * 页面: 游戏目录
 * 用例: 游戏元数据 —— 目录加载 / 关键词筛选 / YAML 导入（合成资产）/ 删除清理
 * 前置: 平台前后端已运行；启动扫描已加载内置游戏元数据（含 l4d2）；
 *       测试数据仓库提供 games/e2e-drill-game.yml（gameCode=e2edrill）
 * 通过标准（逐用例声明）:
 *  - 目录加载: 「游戏目录」标题渲染，表格含 l4d2 行
 *  - 筛选: 关键词 minecraft 应用后表格含 minecraft 且不含 l4d2，重置后恢复
 *  - YAML 导入: 选择 fixtures 的 e2e-drill-game.yml →「校验并导入」→ 提示「游戏元数据导入成功」
 *  - 删除清理: 对 e2edrill 行删除并确认「确定删除」，提示「删除成功」且行消失
 */
import { test, expect } from "@playwright/test";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { injectSession } from "../../support/session.js";

const FIXTURE_YML = join(
  dirname(fileURLToPath(import.meta.url)),
  "../../fixtures/games/e2e-drill-game.yml",
);

test.describe.serial("游戏元数据", () => {
  test("游戏目录加载且筛选可见内置游戏", async ({ page }) => {
    await injectSession(page);
    await page.goto("/services/games/list");

    await expect(page.getByRole("heading", { name: "游戏目录" })).toBeVisible();
    // 元数据 139 个游戏分页展示，l4d2 未必在第一页——用关键词筛选定位
    await page.getByPlaceholder("例如：minecraft").fill("l4d2");
    await page.getByRole("button", { name: "应用筛选" }).click();
    await expect(page.locator(".el-table")).toContainText("l4d2");
  });

  test("关键词筛选生效并可重置", async ({ page }) => {
    await injectSession(page);
    await page.goto("/services/games/list");

    await page.getByPlaceholder("例如：minecraft").fill("minecraft");
    await page.getByRole("button", { name: "应用筛选" }).click();
    const table = page.locator(".el-table");
    await expect(table).toContainText("minecraft");
    await expect(table).not.toContainText("l4d2");

    await page.getByRole("button", { name: "重置" }).click();
    // 重置后回到全量分页列表（第一页不再包含 l4d2），断言表格恢复渲染即可
    await expect(table).toContainText("游戏");
  });

  test("YAML 导入合成游戏元数据成功", async ({ page }) => {
    await injectSession(page);
    await page.goto("/services/games/list");

    await page.getByRole("button", { name: "上传 YAML" }).click();
    const dialog = page.getByRole("dialog");
    await expect(dialog).toContainText("上传游戏元数据 YAML");

    const importBtn = dialog.getByRole("button", { name: "校验并导入" });
    await expect(importBtn).toBeDisabled();
    await dialog.locator('input[type="file"]').setInputFiles(FIXTURE_YML);
    await expect(importBtn).toBeEnabled();
    await importBtn.click();

    await expect(page.locator(".el-message--success")).toContainText(
      "游戏元数据导入成功",
    );
    await expect(page.locator(".el-table")).toContainText("e2edrill");
  });

  test("删除导入的合成游戏完成清理", async ({ page }) => {
    await injectSession(page);
    await page.goto("/services/games/list");

    const row = page.locator(".el-table__row", { hasText: "e2edrill" });
    await row.getByRole("button", { name: "删除" }).click();

    const confirmBtn = page.getByRole("button", { name: "确定删除" });
    await expect(confirmBtn).toBeVisible();
    await confirmBtn.click();

    await expect(page.locator(".el-message--success")).toContainText(
      "删除成功",
    );
    await expect(page.locator(".el-table")).not.toContainText("e2edrill");
  });
});
