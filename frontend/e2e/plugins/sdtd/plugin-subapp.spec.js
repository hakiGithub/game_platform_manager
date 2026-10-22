/**
 * 页面: sdtd（7 Days to Die）插件子应用 —— 仪表盘/控制台/玩家管理/日志/事件记录/Modlet 管理
 * 用例: 插件用例包范式复制的第二个样板 —— 验证 l4d2 那套写法能原样套到别的插件
 * 前置: 平台前后端已运行；E2E_TEST_HOST_* 指向装有 Docker 的牺牲主机；
 *       主机可拉取 gameservermanagers/gameserver:sdtd（本机通常已存在）
 * 通过标准（逐用例声明）:
 *  - 进入工作区: 直跳 /extensions/app/sdtd/dashboard，Wujie 容器与子应用渲染，主侧边栏出现插件菜单组
 *  - 仪表盘: 四张指标卡（运行状态/在线玩家/FPS/游戏内时间）渲染，血月卡给出数据或明确空态
 *  - 控制台: 初始空态可见；发一条命令后请求返回并落到历史（实例可能未运行，
 *    只断言"请求有响应 + 输出被渲染"，不断言命令语义成功）
 *  - 玩家管理: 三个 Tab 可切换，在线玩家空态与数据源标记可见
 *  - 日志: 日志接口返回 200，视图给出日志行或明确空态
 *  - 事件记录: CSMM 上报引导提示 + 三个 Tab + 空表（牺牲主机不会有真实事件）
 *  - Modlet 管理: 已安装列表、目录弹窗、上传入口与刷新入口可见
 *  - 写操作: 白名单经 UI 新增 → 列表出现 → 移除 → 列表消失（不依赖游戏进程运行）
 */
import { test, expect } from "@playwright/test";
import { e2eEnv } from "../../support/env.js";
import { skipWithoutTestHost } from "../../support/skip.js";
import {
  createApi,
  ensureHostByIp,
  login,
  removeHostsByName,
} from "../../support/api.js";

skipWithoutTestHost();

const HOST_NAME = "e2e-sdtd-plugin-host";
const INSTANCE_NAME = "e2e-sdtd-plugin";
// 自定义容器名：默认名 sdtdserver-lgsm 会与牺牲主机上其它实例同名冲突
const CONTAINER_NAME = "e2e-sdtd-plugin-lgsm";
const E2E_STEAM_ID = "76561190000000042";

test.describe.serial("sdtd 插件子应用", () => {
  let api = null;
  let instanceId = null;
  let hostId = null;
  let createdHost = false;

  /**
   * Wujie 子应用在连续跳转时会保留上一页一瞬。等目标页根节点可见，
   * 确保后续断言打在目标页而不是过渡快照；首次等待失败后 reload 再等一次
   * （与 l4d2 包同样的处理，整轮高负载下同一 URL 可能复用已挂载文档）。
   */
  async function enterPluginWorkspace(page) {
    await page.goto("/extensions/plugins/list");
    const card = page.locator(".plugin-card", { hasText: "sdtd" });
    await expect(card).toBeVisible({ timeout: 30_000 });
    await card.getByRole("button", { name: "进入工作区" }).click();

    const dashboardUrl = new RegExp(
      `/extensions/app/sdtd/dashboard\\?instanceId=${instanceId}`,
    );
    const selectDialog = page.locator(".plugin-instance-dialog");
    const dialogOrDashboard = await Promise.race([
      selectDialog
        .waitFor({ state: "visible", timeout: 30_000 })
        .then(() => "dialog")
        .catch((error) => {
          if (error.name === "TimeoutError") return "timeout";
          throw error;
        }),
      page
        .waitForURL(dashboardUrl, { timeout: 30_000 })
        .then(() => "dashboard")
        .catch((error) => {
          if (error.name === "TimeoutError") return "timeout";
          throw error;
        }),
    ]);

    if (dialogOrDashboard === "dialog") {
      // 有多条 sdtd 实例时会出现选择弹窗；本包理应只有一条，兜底按名称挑
      const row = selectDialog
        .locator(".el-table__row")
        .filter({ hasText: INSTANCE_NAME });
      await row.first().getByRole("button", { name: "进入管理" }).click();
    } else if (dialogOrDashboard === "timeout") {
      throw new Error(
        `[CHECK][FAIL] 进入 sdtd 工作区超时: 未出现实例选择弹窗，也未导航到 dashboard; actual=${page.url()}; remediation: 检查 /api/instances?gameCode=sdtd 响应与插件扩展台日志`,
      );
    }
    await expect(page).toHaveURL(dashboardUrl, { timeout: 30_000 });
    await expect(page.locator(".plugin-tab")).toBeVisible({ timeout: 30_000 });
  }

  async function gotoPluginPage(page, path, rootSelector) {
    const url = `/extensions/app/sdtd/${path}?instanceId=${instanceId}`;
    await page.goto(url);
    try {
      await expect(page.locator(rootSelector)).toBeVisible({ timeout: 60_000 });
    } catch (error) {
      const actual = {
        url: page.url(),
        body: (
          await page
            .locator("body")
            .innerText()
            .catch(() => "")
        )
          .replaceAll(/\s+/g, " ")
          .slice(0, 500),
      };
      await page.reload();
      try {
        await expect(page.locator(rootSelector)).toBeVisible({ timeout: 60_000 });
      } catch (reloadError) {
        throw new Error(
          `[CHECK][FAIL] sdtd 子应用页面 ${path} 未渲染根节点 ${rootSelector}: ${reloadError.message}; actual=${JSON.stringify(
            actual,
          )}; remediation: 查看 e2e/.artifacts 的 error-context.md、trace 与 /api/pf4j/plugin/sdtd/ui/ 响应`,
          { cause: error },
        );
      }
    }
  }

  async function injectSession(page) {
    const { injectSession: inject } = await import("../../support/session.js");
    await inject(page);
  }

  test.beforeAll(async () => {
    // beforeAll 超时默认等于用例超时（30s），显式放宽以容纳部署轮询
    test.setTimeout(20 * 60_000);

    const token = await login(
      e2eEnv.backendUrl,
      e2eEnv.adminUser,
      e2eEnv.adminPass,
    );
    api = createApi(e2eEnv.backendUrl, token);

    // 平台对 host_info.ip_address 有全局唯一约束：同一台牺牲主机可能已被其它分组纳管
    // （或本包上一次运行的残留记录仍在）。按 IP 复用，只有本次新建的才在 afterAll 删除，
    // 否则会误删别的分组正在使用的主机。同名残留先清掉，保证名称归属稳定。
    await removeHostsByName(api, HOST_NAME);
    const ensured = await ensureHostByIp(api, {
      name: HOST_NAME,
      ip: e2eEnv.testHost.address,
      sshPort: Number(e2eEnv.testHost.port),
      sshUsername: e2eEnv.testHost.username,
      sshPassword: e2eEnv.testHost.auth,
    });
    hostId = ensured.hostId;
    createdHost = ensured.created;
    const tested = await api.post(`/api/hosts/${hostId}/test`);
    expect(tested.body?.data?.connected).not.toBe(false);

    // 清理同名残留实例：失败运行遗留会让"单实例直跳"退化成选择弹窗分支
    const stale = await api.get(
      `/api/instances?keyword=${encodeURIComponent(INSTANCE_NAME)}&size=50`,
    );
    for (const inst of stale.body?.data?.records ?? []) {
      await api.delete(`/api/instances/${inst.id}`).catch(() => {});
    }

    const games = await api.get("/api/games/list");
    const sdtd = (games.body?.data ?? []).find((g) => g.gameCode === "sdtd");
    expect(sdtd).toBeTruthy();

    // autoRestart 关闭：LinuxGSM 容器首启要下载数 GB 游戏文件，自动启动会在下载完成前
    // 抢跑。插件的所有页面在实例"已停止"时都有明确空态，因此不需要等游戏进程就绪。
    const deployed = await api.post("/api/instances", {
      instanceName: INSTANCE_NAME,
      gameId: sdtd.id,
      hostId,
      deployType: "linuxgsm-docker",
      installPath: "~/games/e2e-sdtd-plugin",
      portConfig: { game: 27000, query: 27001, rcon: 8181 },
      configInfo: {
        autoRestart: false,
        CONTAINER_NAME,
        mountHostCerts: true,
      },
    });
    instanceId = deployed.body?.data?.id ?? null;
    expect(instanceId).toBeTruthy();

    // 部署任务把状态从"安装中"推进到稳定态即可开测；容器内的 LinuxGSM auto-install
    // 会在后台继续，各页面按空态断言，不依赖它完成。
    await expect
      .poll(
        async () => {
          const res = await api.get(
            `/api/instances?keyword=${encodeURIComponent(INSTANCE_NAME)}&size=10`,
          );
          return res.body?.data?.records?.[0]?.runStatusDesc;
        },
        { timeout: 10 * 60_000, intervals: [5_000, 10_000] },
      )
      .not.toBe("安装中");
  });

  test.afterAll(async () => {
    if (!api) return;
    // 白名单是插件自管的扩展资源，不随实例删除，需显式清掉
    if (instanceId) {
      const wl = await api
        .get(`/api/plugin/sdtd/whitelist?instanceId=${instanceId}`)
        .catch(() => null);
      for (const entry of wl?.body?.data ?? []) {
        await api
          .delete(
            `/api/plugin/sdtd/whitelist?instanceId=${instanceId}&steamId=${entry.spec.steamId}`,
          )
          .catch(() => {});
      }
      await api.delete(`/api/instances/${instanceId}`).catch(() => {});
    }
    // 只删本次新建的主机记录，复用的记录留给原持有者
    if (hostId && createdHost) {
      await api.delete(`/api/hosts/${hostId}`).catch(() => {});
    }
  });

  test("进入工作区直跳 Wujie 容器且子应用仪表盘渲染", async ({ page }) => {
    test.setTimeout(3 * 60_000);
    await injectSession(page);
    await enterPluginWorkspace(page);
    await expect(page.locator(".dashboard")).toBeVisible({ timeout: 30_000 });
    // 主应用侧边栏出现该游戏的插件菜单组（ADR-0001：菜单归属主应用）。
    // 注意不是 .plugin-menu —— 那是有菜单时才渲染的独立组件，当前宿主走的是
    // 主侧边栏的分组菜单，断言要打在宿主菜单上。
    const sidebarGroup = page
      .locator(".el-menu")
      .getByRole("menuitem", { name: "七日杀" });
    await expect(sidebarGroup).toBeVisible({ timeout: 30_000 });
    for (const menu of ["仪表盘", "控制台", "玩家管理", "日志", "事件记录"]) {
      await expect(
        page.locator(".el-menu").getByRole("menuitem", { name: menu }).first(),
      ).toBeVisible();
    }
    console.log(
      "[CHECK][PASS] sdtd 工作区直跳成功：Wujie 容器、仪表盘与主侧边栏插件菜单组均可见",
    );
  });

  test("仪表盘指标卡与血月区渲染", async ({ page }) => {
    test.setTimeout(60_000);
    await injectSession(page);
    await gotoPluginPage(page, "dashboard", ".dashboard");

    const dashboardResponse = page.waitForResponse(
      (response) =>
        response.request().method() === "GET" &&
        response.url().includes("/api/plugin/sdtd/instances/") &&
        response.url().includes("/dashboard") &&
        response.ok(),
      { timeout: 30_000 },
    );
    await page.reload();
    await dashboardResponse;

    // 四张指标卡的标签是固定文案，缺一个就说明前端契约变了
    await expect(page.locator(".dashboard .stat-label")).toHaveCount(4);
    for (const label of ["运行状态", "在线玩家", "FPS", "游戏内时间"]) {
      await expect(
        page.locator(".dashboard .stat-label", { hasText: label }),
      ).toBeVisible();
    }
    // 运行状态取值随部署进度变化，但必须是有内容的文字而不是空占位
    await expect(page.locator(".dashboard .stat-value").first()).not.toHaveText(
      "",
    );
    // 血月卡：实例未运行/未解析出游戏时间时给明确空态，运行中给天数
    await expect(page.locator(".dashboard")).toContainText(
      /血月进行中|距血月还有\s*\d+\s*天|暂无数据/,
    );
  });

  test("控制台空态、命令下发与历史回读", async ({ page }) => {
    test.setTimeout(90_000);
    await injectSession(page);
    await gotoPluginPage(page, "console", ".console-page");

    await expect(page.getByText("Web 控制台", { exact: true })).toBeVisible();
    // 快速命令按钮是控制台的固定入口
    for (const cmd of ["gettime", "listplayers", "version"]) {
      await expect(
        page.locator(".quick-commands").getByRole("button", { name: cmd }),
      ).toBeVisible();
    }

    const input = page.getByPlaceholder(/输入游戏控制台命令/);
    await expect(input).toBeVisible();
    await input.fill("gettime");

    const execResponse = page.waitForResponse(
      (response) =>
        response.request().method() === "POST" &&
        response.url().includes("/api/plugin/sdtd/instances/") &&
        response.url().endsWith("/console") &&
        response.ok(),
      { timeout: 60_000 },
    );
    await page.getByRole("button", { name: "执行" }).click();
    await execResponse;

    // 实例可能未运行：只断言"命令落到历史且有输出被渲染"，不断言命令语义成功
    const line = page.locator(".console-line").first();
    await expect(line).toBeVisible({ timeout: 30_000 });
    await expect(line.locator(".console-cmd")).toContainText("gettime");
    await expect(line.locator(".console-text")).not.toHaveText("");
  });

  test("玩家管理三 Tab 与在线玩家空态", async ({ page }) => {
    test.setTimeout(60_000);
    await injectSession(page);
    await gotoPluginPage(page, "players", ".players-page");

    for (const tab of ["在线玩家", "封禁记录", "白名单"]) {
      await expect(page.getByRole("tab", { name: tab })).toBeVisible();
    }
    // 在线玩家：空表 + 数据源标记（Web API / listplayers 解析）
    await expect(page.locator(".players-page .el-table").first()).toBeVisible();
    await expect(page.locator(".source-tag")).toContainText(/Web API|listplayers/);

    await page.getByRole("tab", { name: "封禁记录" }).click();
    // Element Plus 的 tab-pane 是懒渲染的，未激活的 pane 仍在 DOM 里（aria-hidden=true）。
    // 断言必须落到具名 pane（#pane-bans），用位置选择器会命中未激活的那个。
    await expect(page.locator("#pane-bans")).toBeVisible();
    await expect(page.getByRole("tab", { name: "封禁记录" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
  });

  test("日志页渲染日志行或明确空态", async ({ page }) => {
    test.setTimeout(60_000);
    await injectSession(page);
    await gotoPluginPage(page, "logs", ".logs-page");
    await expect(page.getByText("实例日志", { exact: true })).toBeVisible();

    const logsResponse = page.waitForResponse(
      (response) =>
        response.request().method() === "GET" &&
        response.url().includes("/api/plugin/sdtd/instances/") &&
        response.url().includes("/logs?") &&
        response.ok(),
      { timeout: 30_000 },
    );
    await page.getByRole("button", { name: "刷新", exact: true }).click();
    await logsResponse;

    // 容器日志通常非空；空时必须是明确空态文案，而不是"页面空白也算过"
    await expect
      .poll(
        async () =>
          (await page.locator(".log-view .log-line").count()) > 0 ||
          (await page.getByText("暂无日志", { exact: true }).count()) > 0,
        { timeout: 30_000 },
      )
      .toBe(true);
  });

  test("事件记录页展示上报引导与三个事件 Tab", async ({ page }) => {
    test.setTimeout(60_000);
    await injectSession(page);
    await gotoPluginPage(page, "events", ".events-page");

    // 牺牲主机上没有 CSMM modlet，事件必然为空 —— 页面必须给出上报引导，不能白屏
    await expect(page.locator(".events-page")).toContainText("CSMM");
    for (const tab of ["聊天", "进出", "击杀/死亡"]) {
      await expect(page.getByRole("tab", { name: tab })).toBeVisible();
    }
    await page.getByRole("tab", { name: "进出" }).click();
    await expect(page.getByRole("tab", { name: "进出" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
  });

  test("Modlet 管理列表、目录弹窗与上传入口", async ({ page }) => {
    test.setTimeout(90_000);
    await injectSession(page);
    await gotoPluginPage(page, "mods", ".mods-page");

    await expect(page.locator(".mods-page")).toContainText("已安装 Modlet");
    await expect(
      page.getByRole("button", { name: "从目录安装" }),
    ).toBeVisible();
    // el-upload 既渲染外层 div[role=button]，又渲染内部 button，会触发 strict 冲突；
    // 断言真正的 <button>，同时确认它挂在 file input 上（上传入口可用）。
    const uploadButton = page.locator("button", { hasText: "上传 zip 安装" });
    await expect(uploadButton).toBeVisible();
    await expect(page.locator('.mods-page input[type="file"]')).toHaveCount(1);
    await expect(page.getByRole("button", { name: "刷新" })).toBeVisible();

    // 目录弹窗：走插件自带 modlet-catalog.json，不依赖外网
    const catalogResponse = page.waitForResponse(
      (response) =>
        response.request().method() === "GET" &&
        response.url().includes("/api/plugin/sdtd/mods/catalog") &&
        response.ok(),
      { timeout: 30_000 },
    );
    await page.getByRole("button", { name: "从目录安装" }).click();
    await catalogResponse;
    const dialog = page.getByRole("dialog").first();
    await expect(dialog).toContainText("从目录安装");
    await dialog.getByRole("button", { name: /关闭|取消/ }).first().click();
  });

  test("白名单新增与移除（写操作闭环）", async ({ page }) => {
    test.setTimeout(90_000);
    await injectSession(page);
    await gotoPluginPage(page, "players", ".players-page");
    await page.getByRole("tab", { name: "白名单" }).click();

    const whitelistAdd = page.waitForResponse(
      (response) =>
        response.request().method() === "POST" &&
        response.url().includes("/api/plugin/sdtd/whitelist") &&
        response.ok(),
      { timeout: 30_000 },
    );
    await page.getByRole("button", { name: "新增", exact: true }).click();
    const dialog = page.getByRole("dialog").first();
    await expect(dialog).toContainText("新增白名单");
    await dialog.getByPlaceholder("17 位 SteamID").fill(E2E_STEAM_ID);
    await dialog.locator("input").nth(1).fill("e2e-sdtd-player");
    await dialog.getByRole("button", { name: "确定", exact: true }).click();
    await whitelistAdd;

    // 平台侧回读作为落库证据
    await expect
      .poll(
        async () => {
          const res = await api.get(
            `/api/plugin/sdtd/whitelist?instanceId=${instanceId}`,
          );
          return (res.body?.data ?? []).some(
            (entry) => entry.spec.steamId === E2E_STEAM_ID,
          );
        },
        { timeout: 30_000 },
      )
      .toBe(true);
    const whitelistPane = page.locator("#pane-whitelist");
    await expect(whitelistPane).toBeVisible();
    await expect(whitelistPane).toContainText(E2E_STEAM_ID);

    // 移除：确认框 → 列表消失 → 平台回读为空
    await whitelistPane.getByRole("button", { name: "移除" }).first().click();
    const confirmBox = page.locator(".el-message-box");
    await expect(confirmBox).toBeVisible();
    const whitelistDelete = page.waitForResponse(
      (response) =>
        response.request().method() === "DELETE" &&
        response.url().includes("/api/plugin/sdtd/whitelist") &&
        response.ok(),
      { timeout: 30_000 },
    );
    await confirmBox.getByRole("button", { name: /确定|OK/ }).click();
    await whitelistDelete;
    await expect
      .poll(
        async () => {
          const res = await api.get(
            `/api/plugin/sdtd/whitelist?instanceId=${instanceId}`,
          );
          return (res.body?.data ?? []).some(
            (entry) => entry.spec.steamId === E2E_STEAM_ID,
          );
        },
        { timeout: 30_000 },
      )
      .toBe(false);
  });
});
