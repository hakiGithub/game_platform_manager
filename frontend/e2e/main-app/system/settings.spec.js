/**
 * 页面: 系统设置
 * 用例: 系统配置修改保存并持久化
 * 前置: 平台前后端已运行，已注入登录态
 * 步骤: 修改「平台名称」→ 保存配置 → 重新进入页面
 * 通过标准: 「系统配置」页渲染；保存后提示「保存成功」；重进页面值保持
 */
import { test, expect } from "@playwright/test";
import { injectSession } from "../../support/session.js";

const PLATFORM_NAME = "E2E 测试平台";

test("平台配置修改保存并持久化", async ({ page }) => {
  await injectSession(page);
  await page.goto("/system/configuration");

  await expect(page.getByRole("heading", { name: "系统配置" })).toBeVisible();
  const nameInput = page.getByPlaceholder("请输入平台名称");
  await expect(nameInput).toBeVisible();

  await nameInput.fill(PLATFORM_NAME);
  await page.getByRole("button", { name: "保存配置" }).click();
  await expect(page.locator(".el-message--success")).toContainText("保存成功");

  // 重新进入页面验证持久化
  await page.reload();
  await expect(nameInput).toHaveValue(PLATFORM_NAME, { timeout: 10_000 });
});
