/**
 * 页面: 系统设置
 * 用例: 系统配置修改保存并持久化
 * 前置: 平台前后端已运行，已注入登录态
 * 步骤: 读取原值 → 修改为唯一测试值 → 保存配置 → 重新进入页面 → 恢复原值
 * 通过标准: 「系统配置」页渲染；PUT 成功且提示「保存成功」；重进页面值保持；
 *           用例结束时恢复原平台名称，保证可重复执行
 */
import { test, expect } from "@playwright/test";
import { injectSession } from "../../support/session.js";
import { e2eEnv } from "../../support/env.js";
import { createApi, login } from "../../support/api.js";

test("平台配置修改保存并持久化", async ({ page }) => {
  await injectSession(page);
  await page.goto("/system/configuration");

  await expect(page.getByRole("heading", { name: "系统配置" })).toBeVisible();
  const nameInput = page.getByPlaceholder("请输入平台名称");
  await expect(nameInput).toBeVisible();

  const originalName = await nameInput.inputValue();
  const platformName = `E2E 测试平台 ${Date.now()}`;
  const saveButton = page.getByRole("button", { name: "保存配置" });
  const token = await login(
    e2eEnv.backendUrl,
    e2eEnv.adminUser,
    e2eEnv.adminPass,
  );
  const api = createApi(e2eEnv.backendUrl, token);
  let testError = null;

  try {
    await nameInput.fill(platformName);
    const [response] = await Promise.all([
      page.waitForResponse(
        (res) =>
          res.url().includes("/api/system/settings") &&
          res.request().method() === "PUT",
      ),
      saveButton.click(),
    ]);
    const body = await response.json().catch(() => null);
    if (response.status() >= 400 || body?.code !== 200) {
      throw new Error(
        `[CHECK][FAIL] 平台配置保存失败: HTTP ${response.status()}, body=${JSON.stringify(
          body,
        ).slice(
          0,
          300,
        )}; remediation: 确认 sys_setting 表迁移已执行，并查看 logs/backend.log`,
      );
    }
    await expect(page.locator(".el-message--success")).toContainText(
      "保存成功",
    );

    await page.reload();
    await expect(nameInput).toHaveValue(platformName, { timeout: 10_000 });
  } catch (error) {
    testError = error;
  }

  const restored = await api.put("/api/system/settings", {
    type: "platform",
    platformName: originalName,
  });
  if (restored.status >= 400 || restored.body?.code !== 200) {
    throw new Error(
      `[CHECK][FAIL] 恢复原平台名称失败: HTTP ${restored.status}; remediation: 手工将平台名称恢复为 ${originalName}${
        testError ? `；原始失败: ${testError.message}` : ""
      }`,
    );
  }
  if (testError) {
    throw testError;
  }
});
