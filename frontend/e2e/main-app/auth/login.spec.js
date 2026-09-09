/**
 * 页面: 登录页 + 工作台（运行总览）
 * 用例: 认证链路最小 tracer —— 登录重定向与登录成功进入工作台
 * 前置: 平台前后端已运行（受管模式由 runner 保证；附着模式需 E2E_BASE_URL 指向已运行环境）；
 *       存在初始种子账号 admin/admin123
 * 步骤:
 *  1) 未登录访问 / —— 路由守卫应重定向到 /login
 *  2) 输入 admin/admin123，点击「进入控制台」
 * 通过标准:
 *  - 未登录访问 / 最终 URL 落在 /login，登录表单可见
 *  - 登录成功后 URL 进入 /workspace/overview，页面出现「运营总览」标题
 */
import { test, expect } from "@playwright/test";
import { e2eEnv } from "../../support/env.js";

test("未登录访问首页重定向到登录页", async ({ page }) => {
  await page.goto("/");
  await expect(page).toHaveURL(/\/login/);
  await expect(page.getByPlaceholder("请输入用户名")).toBeVisible();
});

test("admin 登录成功进入工作台", async ({ page }) => {
  await page.goto("/login");
  await page.getByPlaceholder("请输入用户名").fill(e2eEnv.adminUser);
  await page.getByPlaceholder("请输入密码").fill(e2eEnv.adminPass);
  await page.getByRole("button", { name: "进入控制台" }).click();
  await expect(page).toHaveURL(/\/workspace\/overview/, { timeout: 15_000 });
  await expect(page.getByRole("heading", { name: "运营总览" })).toBeVisible();
});
