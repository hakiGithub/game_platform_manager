/**
 * 页面: 插件扩展台
 * 用例: 插件列表 —— 内置 l4d2 插件状态与进入工作区行为
 * 前置: 平台前后端已运行且内置 plugin-l4d2 处于 STARTED；已注入登录态
 * 通过标准（逐用例声明）:
 *  - 插件卡片: 「插件扩展台」标题渲染，l4d2 卡片存在且状态标签为 STARTED，
 *    「进入工作区」按钮可用
 *  - 进入工作区: 无实例时出现「未找到游戏编码 l4d2 的实例」警告；已有实例时进入
 *    dashboard 工作区并渲染仪表盘（多实例则选择第一行）
 */
import { test, expect } from "@playwright/test";
import { injectSession } from "../../support/session.js";
import { e2eEnv } from "../../support/env.js";
import { createApi, login } from "../../support/api.js";

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

test("进入工作区按 l4d2 实例状态进入工作区或提示未找到实例", async ({
  page,
}) => {
  const token = await login(
    e2eEnv.backendUrl,
    e2eEnv.adminUser,
    e2eEnv.adminPass,
  );
  const api = createApi(e2eEnv.backendUrl, token);
  const instances = await api.get("/api/instances?page=1&size=50");
  if (instances.status !== 200) {
    throw new Error(
      `[CHECK][FAIL] 读取 l4d2 实例列表失败: HTTP ${instances.status}; remediation: 确认后端已就绪且管理员登录态有效`,
    );
  }
  const l4d2Instances = (instances.body?.data?.records ?? []).filter(
    (row) => row.gameCode === "l4d2",
  );

  await injectSession(page);
  await page.goto("/extensions/plugins/list");

  const card = page.locator(".plugin-card", { hasText: "l4d2" });
  await card.getByRole("button", { name: "进入工作区" }).click();

  if (l4d2Instances.length === 0) {
    await expect(page.locator(".el-message--warning")).toContainText(
      "未找到游戏编码",
      { timeout: 10_000 },
    );
    return;
  }

  const selectDialog = page.getByRole("dialog", { hasText: "选择管理目标" });
  try {
    await selectDialog.waitFor({ state: "visible", timeout: 3_000 });
    await selectDialog
      .locator(".el-table__row")
      .first()
      .getByRole("button", { name: "进入管理" })
      .click();
  } catch {
    // 单实例直接进入工作区，不出现选择框。
  }
  await expect(page).toHaveURL(
    /\/extensions\/app\/l4d2\/dashboard\?instanceId=/,
    { timeout: 15_000 },
  );
  await expect(page.locator(".dashboard-page")).toBeVisible({
    timeout: 30_000,
  });
});
