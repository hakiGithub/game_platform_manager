/**
 * 页面: 插件扩展台
 * 用例: 插件列表 —— 内置 l4d2 插件状态与进入工作区行为
 * 前置: 平台前后端已运行且内置 plugin-l4d2 处于 STARTED；已注入登录态；
 *       受管模式新库无 l4d2 实例（0 实例 → 点击进入工作区出现未找到实例警告）
 * 通过标准（逐用例声明）:
 *  - 插件卡片: 「插件扩展台」标题渲染，l4d2 卡片存在且状态标签为 STARTED，
 *    「进入工作区」按钮可用
 *  - 0 实例行为: 点击进入工作区出现「未找到游戏编码 l4d2 的实例」警告
 */
import { test, expect } from "@playwright/test";
import { injectSession } from "../../support/session.js";

test("l4d2 插件卡片状态为 STARTED", async ({ page }) => {
  await injectSession(page);
  await page.goto("/extensions/plugins/list");

  await expect(page.getByRole("heading", { name: "插件扩展台" })).toBeVisible();
  const card = page.locator(".plugin-card", { hasText: "l4d2" });
  await expect(card).toBeVisible();
  // 后端 stateDesc 返回中文状态
  await expect(card).toContainText("已启动");
  const enterBtn = card.getByRole("button", { name: "进入工作区" });
  await expect(enterBtn).toBeEnabled();
});

test("零实例时进入工作区提示未找到实例", async ({ page }) => {
  await injectSession(page);
  await page.goto("/extensions/plugins/list");

  const card = page.locator(".plugin-card", { hasText: "l4d2" });
  await card.getByRole("button", { name: "进入工作区" }).click();
  await expect(page.locator(".el-message--warning")).toContainText(
    "未找到游戏编码",
    { timeout: 10_000 },
  );
});
