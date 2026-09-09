/**
 * 页面: 主机列表 / 主机详情 / Web 终端
 * 用例: 主机纳管全链路 —— 连接测试门禁真连牺牲主机、连接失败错误态、详情监控、Web 终端、删除主机
 * 前置: 平台前后端已运行；E2E_TEST_HOST_* 指向可 SSH 的牺牲主机（本地为 WSL 127.0.0.1:22）；
 *       用例串行执行且删除收尾自清现场
 * 通过标准（逐用例声明）:
 *  - 门禁: 未测试连接时「确定」禁用；真连成功提示「连接测试成功，可以保存」后启用；保存后列表出现该主机
 *  - 失败态: 错误端口测试提示「连接测试失败：…」且确定保持禁用
 *  - 详情: 主机资源概况（CPU/内存）渲染
 *  - 终端: xterm 挂载、键盘可输入、页面停留不崩溃
 *  - 删除: 输入主机名确认后删除成功，列表不再出现
 */
import { test, expect } from "@playwright/test";
import { e2eEnv } from "../../support/env.js";
import { skipWithoutTestHost } from "../../support/skip.js";
import { createApi, login } from "../../support/api.js";
import { injectSession } from "../../support/session.js";

skipWithoutTestHost();

const HOST_NAME = "e2e-wsl-host";

test.describe.serial("主机纳管", () => {
  let hostId = null;

  async function openAddDialog(page) {
    await injectSession(page);
    await page.goto("/resources/hosts/list");
    await page.getByRole("button", { name: "新增主机" }).click();
    await expect(page.getByRole("dialog")).toContainText("新增主机");
  }

  async function fillHostForm(page, { ip, port, name = HOST_NAME }) {
    const dialog = page.getByRole("dialog");
    await dialog.getByPlaceholder("请输入主机名称").fill(name);
    await dialog.getByPlaceholder("请输入IP地址").fill(ip);
    await dialog.locator(".el-input-number input").fill(String(port));
    await dialog
      .getByPlaceholder("请输入用户名")
      .fill(e2eEnv.testHost.username);
    await dialog.getByPlaceholder("请输入密码").fill(e2eEnv.testHost.auth);
  }

  async function getHostIdByName() {
    const token = await login(
      e2eEnv.backendUrl,
      e2eEnv.adminUser,
      e2eEnv.adminPass,
    );
    const api = createApi(e2eEnv.backendUrl, token);
    // 直连后端必须带 /api 前缀（前端靠 axios baseURL /api 补上，这里没有）
    const res = await api.get("/api/hosts");
    const data = res.body?.data;
    const list = Array.isArray(data)
      ? data
      : (data?.records ?? data?.list ?? []);
    return list.find((h) => h.name === HOST_NAME)?.id ?? null;
  }

  test("新增主机走连接测试门禁并保存成功", async ({ page }) => {
    await openAddDialog(page);
    const dialog = page.getByRole("dialog");
    await fillHostForm(page, {
      ip: e2eEnv.testHost.address,
      port: e2eEnv.testHost.port,
    });

    // 连接测试门禁：未测试前确定禁用
    const okBtn = dialog.getByRole("button", { name: "确定" });
    await expect(okBtn).toBeDisabled();

    await dialog.getByRole("button", { name: "测试连接" }).click();
    await expect(page.locator(".el-message--success")).toContainText(
      "连接测试成功，可以保存",
    );
    await expect(okBtn).toBeEnabled();
    await okBtn.click();

    await expect(page.locator(".el-table")).toContainText(HOST_NAME);
    hostId = await getHostIdByName();
    expect(hostId).toBeTruthy();
  });

  test("连接失败时提示错误且门禁不放行", async ({ page }) => {
    await openAddDialog(page);
    const dialog = page.getByRole("dialog");
    await fillHostForm(page, {
      ip: e2eEnv.testHost.address,
      port: 2299, // 无服务端口
      name: "e2e-bad-host",
    });

    await dialog.getByRole("button", { name: "测试连接" }).click();
    await expect(page.locator(".el-message--error")).toContainText(
      "连接测试失败",
    );
    await expect(dialog.getByRole("button", { name: "确定" })).toBeDisabled();
    await dialog.getByRole("button", { name: "取消" }).click();
  });

  test("主机详情渲染资源监控", async ({ page }) => {
    await injectSession(page);
    await page.goto("/resources/hosts/list");
    const row = page.locator(".el-table__row", { hasText: HOST_NAME });
    await row.getByRole("button", { name: "详情" }).click();

    await expect(page).toHaveURL(/\/resources\/hosts\/detail\/\d+/);
    const rail = page.getByLabel("主机资源概况");
    await expect(rail).toBeVisible({ timeout: 15_000 });
    await expect(rail).toContainText("CPU");
    await expect(rail).toContainText("内存");
  });

  test("Web 终端连接成功且键盘可输入", async ({ page }) => {
    expect(hostId).toBeTruthy();
    await injectSession(page);
    await page.goto(`/resources/hosts/terminal/${hostId}`);

    const term = page.locator(".xterm");
    await expect(term).toBeVisible({ timeout: 15_000 });
    await term.click();
    await page.keyboard.type("echo e2e-terminal-ok");
    await page.keyboard.press("Enter");
    // 终端页保持挂载，无崩溃跳转
    await expect(page).toHaveURL(
      new RegExp(`/resources/hosts/terminal/${hostId}`),
    );
    await expect(term).toBeVisible();
  });

  test("输入主机名确认后删除成功", async ({ page }) => {
    await injectSession(page);
    await page.goto("/resources/hosts/list");
    const row = page.locator(".el-table__row", { hasText: HOST_NAME });
    await row.getByRole("button", { name: "删除" }).click();

    const dialog = page.getByRole("dialog");
    await expect(dialog).toContainText(`确定要删除主机「${HOST_NAME}」吗？`);
    const confirmBtn = dialog.getByRole("button", { name: "确定删除" });
    await expect(confirmBtn).toBeDisabled();
    await dialog.getByPlaceholder("请输入主机名称").fill(HOST_NAME);
    await confirmBtn.click();

    await expect(page.locator(".el-message--success")).toContainText(
      "删除成功",
    );
    await expect(page.locator(".el-table")).not.toContainText(HOST_NAME);
    hostId = null;
  });
});
