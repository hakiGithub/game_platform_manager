/**
 * 页面: 插件工作区 / 主应用侧边栏
 * 用例: L4D2 深链下仅一个叶子菜单呈现完整选中态，祖先只表达展开链路
 * 前置: 平台前后端已运行；存在运行中的 l4d2 插件和可访问的实例
 * 步骤:
 *  - 注入管理员会话并打开 L4D2 服务器信息深链
 *  - 等待侧边栏插件菜单树加载
 *  - 断言完整选中叶子数量为 1，祖先文字/背景/边框/阴影均无叶子选中态
 *  - 保存截图作为视觉回归证据
 * 通过标准: 活跃叶子唯一；祖先无青色文字、选中背景、底边或阴影；截图可复核。
 */
import { expect, test } from "@playwright/test";
import { injectSession } from "../../support/session.js";

test("L4D2 深链侧边栏只高亮唯一叶子", async ({ page }) => {
  await injectSession(page);
  const instancesResponse = page.waitForResponse(
    (response) =>
      response.request().method() === "GET" &&
      response.url().includes("/api/instances") &&
      response.url().includes("gameCode=l4d2"),
  );

  await page.goto("/extensions/app/l4d2/server-info");
  const response = await instancesResponse;
  expect(response.ok()).toBe(true);

  const sidebar = page.locator(".sidebar");
  await expect(sidebar.getByText("求生之路2", { exact: true })).toBeVisible();
  await expect(sidebar.getByText("服务器信息", { exact: true })).toBeVisible();

  const activeLeaves = sidebar.locator(".el-menu-item.is-active");
  await expect(activeLeaves).toHaveCount(1);
  await expect(activeLeaves.first()).toContainText("服务器信息");

  const activeAncestorTitles = sidebar.locator(
    ".el-sub-menu.is-active > .el-sub-menu__title",
  );
  await expect(activeAncestorTitles.first()).toBeVisible();

  const activeColor = await sidebar.locator(".el-menu-item.is-active").first().evaluate(
    (element) => getComputedStyle(element).color,
  );
  for (const title of await activeAncestorTitles.all()) {
    const styles = await title.evaluate((element) => {
      const style = getComputedStyle(element);
      return {
        color: style.color,
        backgroundImage: style.backgroundImage,
        borderBottomColor: style.borderBottomColor,
        boxShadow: style.boxShadow,
      };
    });
    expect(styles.color).not.toBe(activeColor);
    expect(styles.backgroundImage).toBe("none");
    expect(styles.borderBottomColor).toBe("rgba(0, 0, 0, 0)");
    expect(styles.boxShadow).toBe("none");
  }

  const inactiveSubmenuTitles = sidebar.locator(
    ".el-sub-menu:not(.is-active) > .el-sub-menu__title",
  );
  await expect(inactiveSubmenuTitles.first()).toBeVisible();
  for (const title of await inactiveSubmenuTitles.all()) {
    await expect(title).toHaveCSS("border-bottom-color", "rgba(0, 0, 0, 0)");
  }

  await page.screenshot({
    path: "e2e/.artifacts/sidebar-selection/l4d2-single-active-leaf.png",
    fullPage: true,
  });
});
