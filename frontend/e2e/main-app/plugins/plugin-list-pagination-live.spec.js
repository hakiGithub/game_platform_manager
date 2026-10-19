import { test, expect } from "@playwright/test";
import { injectSession } from "../../support/session.js";

const INSTANCE_ID = 97;

/**
 * 页面：主应用 / L4D2 插件管理
 * 用例：三个插件列表展示分页，翻页与筛选使用服务端查询
 * 前置：实例 97 在线，已安装插件不少于 10 条，内置未安装插件超过一页
 * 步骤：切换页大小并翻页；在内置列表第二页应用安装状态筛选；切到远端仓库
 * 通过标准：请求携带正确 page/size，筛选后回到第 1 页，三个列表都有分页控件
 */
test("插件管理三个列表分页可见且服务端筛选重置页码", async ({ page }) => {
  test.setTimeout(90_000);
  await injectSession(page);

  async function selectPageSize(pagination, size) {
    await pagination.locator(".el-pagination__sizes .el-select").click();
    const option = page
      .locator(".el-select-dropdown__item")
      .filter({ hasText: new RegExp(`^${size}\\s*条/页$`) })
      .last();
    await expect(option).toBeVisible();
    await option.click();
  }

  async function expectCurrentPage(pagination, number) {
    await expect(
      pagination.getByRole("listitem", { name: `第 ${number} 页` }),
    ).toHaveClass(/is-active/);
  }

  const url =
    `/extensions/app/l4d2/plugins?instanceId=${INSTANCE_ID}`;
  const installedList = page.waitForResponse(
    (res) =>
      res.request().method() === "GET" &&
      res.url().includes("/api/plugin/l4d2/plugins/list"),
  );
  await page.goto(url);
  await expect(page.locator(".plugins-page")).toBeVisible({ timeout: 30_000 });
  await installedList;

  const installedPagination = page
    .locator(".installed-tab .el-pagination")
    .last();
  await expect(installedPagination).toBeVisible();
  await expect(installedPagination).toContainText("共 15 条");

  const resized = page.waitForResponse(
    (res) =>
      res.request().method() === "GET" &&
      res.url().includes("/api/plugin/l4d2/plugins/list") &&
      res.url().includes("page=1") &&
      res.url().includes("size=10"),
  );
  await selectPageSize(installedPagination, 10);
  await resized;
  await expectCurrentPage(installedPagination, 1);

  await page
    .locator(".installed-tab tbody tr")
    .first()
    .locator(".el-checkbox")
    .click();
  await expect(
    page.locator(".installed-tab").getByRole("button", { name: "批量启用" }),
  ).toBeEnabled();

  const secondPage = page.waitForResponse(
    (res) =>
      res.request().method() === "GET" &&
      res.url().includes("/api/plugin/l4d2/plugins/list") &&
      res.url().includes("page=2"),
  );
  await installedPagination.locator(".btn-next").click();
  await secondPage;
  await expectCurrentPage(installedPagination, 2);
  await expect(
    page.locator(".installed-tab").getByRole("button", { name: "批量启用" }),
  ).toBeDisabled();

  const builtinFirst = page.waitForResponse(
    (res) =>
      res.request().method() === "GET" &&
      res.url().includes("/api/plugin/l4d2/plugins/builtin/list") &&
      res.url().includes("page=1"),
  );
  await page.getByRole("tab", { name: "内置插件" }).click();
  await builtinFirst;
  const builtinPagination = page
    .locator(".builtin-tab .el-pagination")
    .last();
  await expect(builtinPagination).toBeVisible();

  const builtinResized = page.waitForResponse(
    (res) =>
      res.request().method() === "GET" &&
      res.url().includes("/api/plugin/l4d2/plugins/builtin/list") &&
      res.url().includes("page=1") &&
      res.url().includes("size=10"),
  );
  await selectPageSize(builtinPagination, 10);
  await builtinResized;

  const builtinSecondPage = page.waitForResponse(
    (res) =>
      res.request().method() === "GET" &&
      res.url().includes("/api/plugin/l4d2/plugins/builtin/list") &&
      res.url().includes("page=2") &&
      res.url().includes("size=10"),
  );
  await builtinPagination.locator(".btn-next").click();
  await builtinSecondPage;
  await expectCurrentPage(builtinPagination, 2);

  const filterResponse = page.waitForResponse(
    (res) =>
      res.request().method() === "GET" &&
      res.url().includes("/api/plugin/l4d2/plugins/builtin/list") &&
      res.url().includes("installed=uninstalled") &&
      res.url().includes("page=1"),
  );
  await page
    .locator(".builtin-tab .el-radio-button")
    .filter({ hasText: "未安装" })
    .click();
  await filterResponse;
  await expectCurrentPage(builtinPagination, 1);

  await page.getByRole("tab", { name: "远端仓库" }).click();
  const remotePagination = page
    .locator(".remote-tab .el-pagination")
    .last();
  await expect(remotePagination).toBeVisible({ timeout: 30_000 });
});
