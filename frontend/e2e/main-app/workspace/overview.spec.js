/**
 * 页面: 工作台（运行总览）
 * 用例: 仪表盘加载与统计卡渲染
 * 前置: 平台前后端已运行；已通过 API 注入登录态（空库即可，统计项允许为 0）
 * 步骤: 注入登录态 → 访问 /workspace/overview
 * 通过标准:
 *  - 「运营总览」标题与英雄区文案渲染
 *  - 「当前运行态势」统计条渲染（空库显示 0 属正常）
 *  - 快捷入口「主机工作台」可达可见
 */
import { test, expect } from "@playwright/test";
import { injectSession } from "../../support/session.js";

test("工作台仪表盘加载并渲染统计结构", async ({ page }) => {
  await injectSession(page);
  await page.goto("/workspace/overview");

  await expect(page.getByRole("heading", { name: "运营总览" })).toBeVisible();
  await expect(page.getByText("先看系统态势，再进入具体工作台")).toBeVisible();
  await expect(page.getByLabel("当前运行态势")).toBeVisible();
  await expect(page.getByLabel("核心指标")).toBeVisible();
  await expect(page.getByRole("button", { name: /主机工作台/ })).toBeVisible();
});
