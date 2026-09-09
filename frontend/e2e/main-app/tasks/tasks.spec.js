/**
 * 页面: 任务中心
 * 用例: 任务中心结构 —— 列表页骨架 / 筛选控件 / 详情页容错
 * 前置: 平台前后端已运行，已注入登录态；受管模式新库为空 → 任务列表呈空态
 * 通过标准（逐用例声明）:
 *  - 列表结构: 「任务中心」标题、任务列表/定时计划两个 tab、状态筛选存在；
 *    空库显示「暂无任务记录」，有任务则表格渲染（两种状态均算过）
 *  - 详情容错: 访问不存在的任务详情 → 「任务不存在或已被删除」+ 返回任务列表入口
 */
import { test, expect } from "@playwright/test";
import { injectSession } from "../../support/session.js";

test("任务中心列表结构渲染", async ({ page }) => {
  await injectSession(page);
  await page.goto("/services/tasks/list");

  await expect(page.getByRole("heading", { name: "任务中心" })).toBeVisible();
  await expect(page.getByRole("tab", { name: "任务列表" })).toBeVisible();
  await expect(page.getByRole("tab", { name: "定时计划" })).toBeVisible();
  // 状态筛选（下拉 placeholder=全部）
  await expect(page.getByRole("combobox").first()).toBeVisible();
  // 空库空态或已有任务表格，两者其一即可（用例与执行阶段解耦）
  await expect(
    page.getByText("暂无任务记录").or(page.locator(".el-table")).first(),
  ).toBeVisible();
});

test("访问不存在的任务详情显示容错态", async ({ page }) => {
  await injectSession(page);
  await page.goto("/services/tasks/detail/999999999");

  await expect(page.getByText("任务不存在或已被删除")).toBeVisible({
    timeout: 10_000,
  });
  await expect(
    page.getByRole("button", { name: "返回任务列表" }),
  ).toBeVisible();
});
