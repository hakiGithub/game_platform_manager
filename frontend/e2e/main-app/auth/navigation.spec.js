/**
 * 页面: 全局路由与守卫
 * 用例: 导航守卫链路 —— 受保护路由带 redirect / 已登录访问登录页 / token 失效 / 404
 * 前置: 平台前后端已运行；存在初始种子账号 admin/admin123
 * 步骤与通过标准（逐用例声明）:
 *  - 未登录访问 /resources/hosts/list: 重定向 /login 且携带 redirect= 参数
 *  - 已登录（API 换取真实 token 注入 localStorage）访问 /login: 重定向 /workspace/overview
 *  - 伪造 token 访问 /workspace/overview: 守卫放行后接口 401 → 自动登出，出现
 *    「登录状态已过期，请重新登录」提示并落在 /login
 *  - 访问不存在路由: 404 页（错误码 404 + 返回首页入口）
 */
import { test, expect } from "@playwright/test";
import { injectSession } from "../../support/session.js";

test("未登录访问受保护路由重定向登录页并携带 redirect 参数", async ({
  page,
}) => {
  await page.goto("/resources/hosts/list");
  // vue-router 对 query 值不做百分号编码，redirect 原样为 /resources/hosts/list
  await expect(page).toHaveURL(/\/login\?redirect=\/resources\/hosts\/list/);
  await expect(page.getByPlaceholder("请输入用户名")).toBeVisible();
});

test("已登录访问登录页重定向回工作台", async ({ page }) => {
  await injectSession(page);
  await page.goto("/login");
  await expect(page).toHaveURL(/\/workspace\/overview/, { timeout: 10_000 });
  await expect(page.getByRole("heading", { name: "运营总览" })).toBeVisible();
});

test("伪造 token 触发会话过期自动登出", async ({ page }) => {
  // 注入一个格式合法但签名无效的 token：守卫放行（非空即过），接口 401 后拦截器自动登出
  await page.addInitScript(() => {
    localStorage.setItem("token", "forged.token.value");
    localStorage.setItem("tokenType", "Bearer");
  });
  await page.goto("/workspace/overview");

  // 仪表盘并发多个接口都会 401，警告 toast 可能重叠多条，取其一断言即可
  await expect(page.locator(".el-message--warning").first()).toContainText(
    "登录状态已过期",
    { timeout: 15_000 },
  );
  await expect(page).toHaveURL(/\/login/);
});

test("访问不存在的路由显示 404 页", async ({ page }) => {
  await page.goto("/definitely-not-a-route");
  await expect(page.locator(".error-code")).toHaveText("404");
  await expect(page.getByRole("button", { name: "返回首页" })).toBeVisible();
});
