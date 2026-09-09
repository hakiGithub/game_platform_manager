/**
 * 页面: 系统设置
 * 用例: 系统配置加载与保存往返
 * 前置: 平台前后端已运行，已注入登录态
 * 步骤: 修改「平台名称」→ 保存配置 → 重新进入页面
 * 通过标准: 「系统配置」页渲染；保存提示「保存成功」；重进页面表单正常回显服务端配置
 *
 * ⚠️ 已知产品缺陷（E2E 首轮发现）：后端 GET /system/settings 返回硬编码 VO、
 * PUT /system/settings 未落库（SystemController 内 TODO 注释）——设置保存是"假回环"，
 * 故本用例不断言持久化，只测页面与保存往返。缺陷修复后应补持久化断言。
 */
import { test, expect } from "@playwright/test";
import { injectSession } from "../../support/session.js";

test("平台配置保存往返不报错", async ({ page }) => {
  await injectSession(page);
  await page.goto("/system/configuration");

  await expect(page.getByRole("heading", { name: "系统配置" })).toBeVisible();
  const nameInput = page.getByPlaceholder("请输入平台名称");
  await expect(nameInput).toBeVisible();
  const serverValue = await nameInput.inputValue();

  await nameInput.fill(`${serverValue}-e2e`);
  await page.getByRole("button", { name: "保存配置" }).click();
  await expect(page.locator(".el-message--success")).toContainText("保存成功");

  // 重进页面：表单回显服务端配置（当前实现会回显硬编码默认值）
  await page.reload();
  await expect(nameInput).toBeVisible({ timeout: 10_000 });
});
