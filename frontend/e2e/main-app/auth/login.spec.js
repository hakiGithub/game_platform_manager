/**
 * 页面: 登录页
 * 用例: 登录认证链路 —— 未登录重定向 / 登录成功 / 错误密码 / 退出登录
 * 前置: 平台前后端已运行（受管模式由 runner 保证；附着模式需 E2E_BASE_URL 指向已运行环境）；
 *       存在初始种子账号 admin/admin123
 * 步骤与通过标准（逐用例声明）:
 *  - 未登录访问 /: 守卫重定向到 /login，登录表单可见
 *  - admin 登录成功: URL 进入 /workspace/overview，出现「运营总览」标题
 *  - 错误密码登录: 出现「用户名或密码错误」错误提示，停留 /login，密码框被清空
 *  - 退出登录: 头像菜单 → 退出登录 → 确认「确定要退出登录吗？」 → 返回 /login，token 被清除
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

test("错误密码登录提示错误并停留登录页", async ({ page }) => {
  await page.goto("/login");
  await page.getByPlaceholder("请输入用户名").fill(e2eEnv.adminUser);
  await page.getByPlaceholder("请输入密码").fill("wrong-password");
  await page.getByRole("button", { name: "进入控制台" }).click();

  await expect(page.locator(".el-message--error")).toContainText(
    "用户名或密码错误",
  );
  await expect(page).toHaveURL(/\/login/);
  // 登录组件在失败分支会清空密码
  await expect(page.getByPlaceholder("请输入密码")).toHaveValue("");
});

test("退出登录确认后返回登录页并清除 token", async ({ page }) => {
  await page.goto("/login");
  await page.getByPlaceholder("请输入用户名").fill(e2eEnv.adminUser);
  await page.getByPlaceholder("请输入密码").fill(e2eEnv.adminPass);
  await page.getByRole("button", { name: "进入控制台" }).click();
  await expect(page.getByRole("heading", { name: "运营总览" })).toBeVisible();

  // 用户下拉菜单（trigger=click）→ 退出登录
  await page.locator(".user-info").click();
  await page
    .locator(".el-dropdown-menu__item", { hasText: "退出登录" })
    .click();
  // 确认框
  await expect(page.locator(".el-message-box")).toContainText(
    "确定要退出登录吗？",
  );
  await page.getByRole("button", { name: "确定" }).click();

  await expect(page).toHaveURL(/\/login/, { timeout: 10_000 });
  const token = await page.evaluate(() => localStorage.getItem("token"));
  expect(token === null || token === "").toBe(true);
});
