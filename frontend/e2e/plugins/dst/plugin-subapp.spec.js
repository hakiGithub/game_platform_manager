/**
 * 页面: dst（Don't Starve Together）插件子应用 —— 控制台/房间设置/玩家管理/玩家统计/
 *       Mod 管理/备份管理/定时任务
 * 用例: 插件用例包范式的第三个样板（7 个菜单，含嵌套 Tab 与多层表单）
 * 前置: 平台前后端已运行；E2E_TEST_HOST_* 指向装有 Docker 的牺牲主机；
 *       主机可拉取 gameservermanagers/gameserver:dst
 * 通过标准（逐用例声明）:
 *  - 进入工作区: 直跳 /extensions/app/dst/console，Wujie 容器与主侧边栏菜单组渲染
 *  - 控制台: 状态卡 + 六个操作按钮 + 广播/命令输入 + 日志区（日志或"暂无日志"）
 *  - 房间设置: 四个 Tab（令牌/集群/分片/世界生成），集群设置渲染出 INI 表单
 *  - 玩家管理: 四个 Tab（在线/管理员/白名单/黑名单），名单编辑器渲染标题与添加框
 *  - 玩家统计: 四个 Tab + 时间范围切换 + 甘特图空态文案
 *  - Mod 管理: Master/Caves 分片 Tab + 列表区渲染
 *  - 备份管理: 备份表 + 回档快照表两个区域
 *  - 定时任务: 各计划卡片 + 保存/触发/历史入口
 *
 * 已知阻塞（票 10）：平台给 linuxgsm-docker 实例的 containerWorkDir=/app，
 * 而 DST 插件的数据根约定是 /data，两者不一致 → 插件读写落在错误目录。
 * 因此本包**不断言"改的值回读一致"**，只断言页面契约与接口响应；
 * 票 10 修好后应把这些断言升级成强断言（见文件末尾 TODO）。
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

const HOST_NAME = "e2e-dst-plugin-host";
const INSTANCE_NAME = "e2e-dst-plugin";
const CONTAINER_NAME = "e2e-dst-plugin-lgsm";

test.describe.serial("dst 插件子应用", () => {
  let api = null;
  let instanceId = null;
  let hostId = null;
  let createdHost = false;
  /** 部署后的实例状态文案（决定启停类断言是否 SKIP） */
  let runStatusDesc = "";

  /**
   * 从插件扩展台进入 dst 工作区。
   *
   * 注意：主应用的「进入工作区」把落地页硬编码成 `/dashboard`，而 dst 插件没有
   * 这个路由（第一个菜单是 `/console`），也没有 catch-all 兜底 —— 见票 11。
   * 因此这里进入后**显式 goto 到 /console**，并在票 11 修好后删掉这段绕行
   * （届时断言应改回"落地页 == /console"）。
   */
  async function enterPluginWorkspace(page) {
    await page.goto("/extensions/plugins/list");
    const card = page.locator(".plugin-card", { hasText: "dst" });
    await expect(card).toBeVisible({ timeout: 30_000 });
    await card.getByRole("button", { name: "进入工作区" }).click();

    const selectDialog = page.locator(".plugin-instance-dialog");
    const dialogOrPage = await Promise.race([
      selectDialog
        .waitFor({ state: "visible", timeout: 30_000 })
        .then(() => "dialog")
        .catch((error) => {
          if (error.name === "TimeoutError") return "timeout";
          throw error;
        }),
      page
        .waitForURL(
          new RegExp(`/extensions/app/dst/.+\\?instanceId=${instanceId}`),
          { timeout: 30_000 },
        )
        .then(() => "page")
        .catch((error) => {
          if (error.name === "TimeoutError") return "timeout";
          throw error;
        }),
    ]);

    if (dialogOrPage === "dialog") {
      const row = selectDialog
        .locator(".el-table__row")
        .filter({ hasText: INSTANCE_NAME });
      await row.first().getByRole("button", { name: "进入管理" }).click();
    } else if (dialogOrPage === "timeout") {
      throw new Error(
        `[CHECK][FAIL] 进入 dst 工作区超时: 未出现实例选择弹窗，也未离开插件扩展台; actual=${page.url()}; remediation: 检查 /api/instances?gameCode=dst 响应与插件扩展台日志`,
      );
    }

    // 票 11 绕行：主应用落地页写死 dashboard，dst 没有该路由
    const consoleUrl = `/extensions/app/dst/console?instanceId=${instanceId}`;
    if (!page.url().includes("/extensions/app/dst/console")) {
      await page.goto(consoleUrl);
    }
    await expect(page).toHaveURL(consoleUrl, { timeout: 30_000 });
    await expect(page.locator(".plugin-tab")).toBeVisible({ timeout: 30_000 });
  }

  async function gotoPluginPage(page, path, rootSelector) {
    const url = `/extensions/app/dst/${path}?instanceId=${instanceId}`;
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
          `[CHECK][FAIL] dst 子应用页面 ${path} 未渲染根节点 ${rootSelector}: ${reloadError.message}; actual=${JSON.stringify(
            actual,
          )}; remediation: 查看 e2e/.artifacts 的 error-context.md、trace 与 /api/pf4j/plugin/dst/ui/ 响应`,
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
    test.setTimeout(20 * 60_000);

    const token = await login(
      e2eEnv.backendUrl,
      e2eEnv.adminUser,
      e2eEnv.adminPass,
    );
    api = createApi(e2eEnv.backendUrl, token);

    // 平台对 host_info.ip_address 有全局唯一约束 → 按 IP 复用（见 l4d2/sdtd 包同款注释）
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

    const stale = await api.get(
      `/api/instances?keyword=${encodeURIComponent(INSTANCE_NAME)}&size=50`,
    );
    for (const inst of stale.body?.data?.records ?? []) {
      await api.delete(`/api/instances/${inst.id}`).catch(() => {});
    }

    const games = await api.get("/api/games/list");
    const dst = (games.body?.data ?? []).find((g) => g.gameCode === "dst");
    expect(dst).toBeTruthy();

    // autoRestart 关闭：无 cluster token 时服务器启动即退，自动启动只会制造噪音
    const deployed = await api.post("/api/instances", {
      instanceName: INSTANCE_NAME,
      gameId: dst.id,
      hostId,
      deployType: "linuxgsm-docker",
      installPath: "~/games/e2e-dst-plugin",
      portConfig: { game: 11099, query: 28016 },
      configInfo: {
        autoRestart: false,
        CONTAINER_NAME,
        mountHostCerts: true,
      },
    });
    instanceId = deployed.body?.data?.id ?? null;
    expect(instanceId).toBeTruthy();

    // 只要求离开"安装中"：容器起来了就够了，游戏进程起不起来由 token 决定
    await expect
      .poll(
        async () => {
          const res = await api.get(
            `/api/instances?keyword=${encodeURIComponent(INSTANCE_NAME)}&size=10`,
          );
          const record = res.body?.data?.records?.[0];
          runStatusDesc = record?.runStatusDesc ?? "";
          return runStatusDesc;
        },
        { timeout: 10 * 60_000, intervals: [5_000, 10_000] },
      )
      .not.toBe("安装中");
    console.log(
      `[CHECK][INFO] dst 实例稳定态: ${runStatusDesc}（无 Klei token 时启动即退属预期）`,
    );
  });

  test.afterAll(async () => {
    if (!api) return;
    if (instanceId)
      await api.delete(`/api/instances/${instanceId}`).catch(() => {});
    if (hostId && createdHost) {
      await api.delete(`/api/hosts/${hostId}`).catch(() => {});
    }
  });

  test("进入工作区直跳 Wujie 容器且子应用控制台渲染", async ({ page }) => {
    test.setTimeout(3 * 60_000);
    await injectSession(page);
    await enterPluginWorkspace(page);
    await expect(page.locator(".dst-console")).toBeVisible({ timeout: 30_000 });

    // 主侧边栏出现该游戏的 7 个菜单（ADR-0001：菜单归属主应用）
    const sidebar = page.locator(".el-menu");
    await expect(
      sidebar.getByRole("menuitem", { name: "饥荒联机版" }).first(),
    ).toBeVisible({ timeout: 30_000 });
    for (const menu of [
      "服务器控制台",
      "房间设置",
      "玩家管理",
      "玩家统计",
      "Mod 管理",
      "备份管理",
      "定时任务",
    ]) {
      await expect(
        sidebar.getByRole("menuitem", { name: menu }).first(),
      ).toBeVisible();
    }
    console.log(
      "[CHECK][PASS] dst 工作区直跳成功：Wujie 容器、控制台与 7 个侧边栏菜单均可见",
    );
  });

  test("控制台状态卡、操作按钮与日志区", async ({ page }) => {
    test.setTimeout(90_000);
    await injectSession(page);
    await gotoPluginPage(page, "console", ".dst-console");

    await expect(page.getByText("服务器状态", { exact: true })).toBeVisible();
    for (const button of [
      "启动",
      "优雅关服（存档）",
      "强制停止",
      "重启",
      "游戏更新",
      "刷新状态",
    ]) {
      await expect(
        page.getByRole("button", { name: button, exact: true }),
      ).toBeVisible();
    }

    // 状态查询：接口必须返回，且页面显示一个非空状态文案（运行中/已停止/异常…）
    const statusResponse = page.waitForResponse(
      (response) =>
        response.request().method() === "POST" &&
        response.url().includes("/api/plugin/dst/console/status") &&
        response.ok(),
      { timeout: 30_000 },
    );
    await page.getByRole("button", { name: "刷新状态", exact: true }).click();
    await statusResponse;
    await expect(page.locator(".card-header .el-tag").first()).not.toHaveText("");

    // 广播与命令输入框
    await expect(page.getByPlaceholder("全服广播内容")).toBeVisible();
    await expect(
      page.getByPlaceholder(/控制台 Lua 命令/),
    ).toBeVisible();
    await expect(page.getByRole("button", { name: "广播", exact: true })).toBeVisible();
    await expect(
      page.getByRole("button", { name: "发送（无输出）" }),
    ).toBeVisible();
    await expect(page.getByRole("button", { name: "查询（带输出）" })).toBeVisible();

    // 日志区：有内容或明确"暂无日志"
    const logResponse = page.waitForResponse(
      (response) =>
        response.request().method() === "GET" &&
        response.url().includes("/api/plugin/dst/console/logs") &&
        response.ok(),
      { timeout: 30_000 },
    );
    await page.getByRole("button", { name: "刷新日志" }).click();
    await logResponse;
    await expect(page.locator(".server-log")).not.toHaveText("");
  });

  test("房间设置四 Tab 与集群 INI 表单渲染", async ({ page }) => {
    test.setTimeout(90_000);
    await injectSession(page);
    await gotoPluginPage(page, "settings", ".dst-settings");

    for (const tab of [
      "服务器令牌",
      "集群设置（cluster.ini）",
      "分片设置（server.ini）",
      "世界生成",
    ]) {
      await expect(page.getByRole("tab", { name: tab })).toBeVisible();
    }
    // 令牌页：未设置时给红色 tag + 输入框 + 保存按钮
    await expect(page.locator(".dst-settings")).toContainText(
      /已设置（|未设置/,
    );

    // 集群设置：INI 编辑器必须真的渲染出节与键（不是空白表单）
    await page.getByRole("tab", { name: "集群设置（cluster.ini）" }).click();
    const clusterPane = page.locator("#pane-cluster");
    await expect(clusterPane).toBeVisible({ timeout: 30_000 });

    /*
     * Element Plus 的 tab-pane 是懒挂载：切 Tab 才挂 IniEditor 并触发 onMounted→load。
     * 若在该次请求之后再建 waitForResponse，就会一直等一个已经发出的请求（首版 bug）。
     * 这里显式点编辑器自带的「重新加载」，把请求时序握在自己手里。
     */
    const iniResponse = page.waitForResponse(
      (response) =>
        response.request().method() === "GET" &&
        response.url().includes("/api/plugin/dst/settings/ini/cluster") &&
        response.ok(),
      { timeout: 30_000 },
    );
    await clusterPane.getByRole("button", { name: "重新加载" }).click();
    await iniResponse;

    // 至少渲染出真实 section 标题（GAMEPLAY / NETWORK / MISC / SHARD 之一），
    // 而不是只剩卡片 header 里的「节-键编辑」字样
    await expect
      .poll(
        async () => clusterPane.locator(".section-title").count(),
        { timeout: 30_000 },
      )
      .toBeGreaterThan(0);
    await expect(clusterPane).toContainText(/GAMEPLAY|NETWORK|MISC|SHARD/);
  });

  test("玩家管理四 Tab 与名单编辑器", async ({ page }) => {
    test.setTimeout(90_000);
    await injectSession(page);
    await gotoPluginPage(page, "players", ".dst-players");

    for (const tab of ["在线玩家", "管理员名单", "白名单", "黑名单"]) {
      await expect(page.getByRole("tab", { name: tab })).toBeVisible();
    }
    // 在线玩家：未运行时是"点击查询在线玩家获取"提示，不是白屏
    await expect(page.getByRole("button", { name: "查询在线玩家" })).toBeVisible();
    await expect(page.locator(".online-output")).toContainText(/点击|需服务器运行中/);

    // 管理员名单：NameListEditor 渲染标题/添加框/保存按钮
    await page.getByRole("tab", { name: "管理员名单" }).click();
    const adminPane = page.locator("#pane-admin");
    await expect(adminPane).toBeVisible();
    await expect(adminPane).toContainText("adminlist.txt");
    await expect(adminPane.getByPlaceholder("KU_xxxxxxxx")).toBeVisible();
    await expect(
      adminPane.getByRole("button", { name: "添加", exact: true }),
    ).toBeVisible();
    await expect(
      adminPane.getByRole("button", { name: "保存", exact: true }),
    ).toBeVisible();

    await page.getByRole("tab", { name: "白名单" }).click();
    await expect(page.locator("#pane-whitelist")).toContainText("whitelist.txt");
  });

  test("玩家统计四 Tab、时间范围与甘特空态", async ({ page }) => {
    test.setTimeout(90_000);
    await injectSession(page);
    await gotoPluginPage(page, "player-stats", ".dst-player-stats");

    // 时间范围是 el-radio-group / el-radio-button（渲染成 role=radio 的 label，不是 button）
    for (const label of ["最近 24 小时", "最近 3 天", "最近 7 天", "最近 30 天"]) {
      await expect(page.getByRole("radio", { name: label })).toBeVisible();
    }
    for (const tab of ["在线甘特图", "在线统计", "聊天记录", "历史玩家", "采样配置"]) {
      await expect(page.getByRole("tab", { name: tab })).toBeVisible();
    }

    // 甘特图：无采样数据时必须给明确文案
    const statsResponse = page.waitForResponse(
      (response) =>
        response.request().method() === "GET" &&
        response.url().includes("/api/plugin/dst/players/") &&
        response.ok(),
      { timeout: 30_000 },
    );
    await page.getByRole("button", { name: "刷新", exact: true }).click();
    await statsResponse;
    await expect(page.locator("#pane-gantt")).toContainText(
      /没有玩家在线记录|采样/,
      { timeout: 30_000 },
    );
  });

  test("Mod 管理分片 Tab 与列表区", async ({ page }) => {
    test.setTimeout(90_000);
    await injectSession(page);
    await gotoPluginPage(page, "mods", ".dst-mods");

    await expect(page.getByRole("tab", { name: "地面 Master" })).toBeVisible();
    await expect(page.getByRole("tab", { name: "洞穴 Caves" })).toBeVisible();

    const modsResponse = page.waitForResponse(
      (response) =>
        response.request().method() === "GET" &&
        response.url().includes("/api/plugin/dst/mods?") &&
        response.ok(),
      { timeout: 30_000 },
    );
    await page.reload();
    await modsResponse;
    // modoverrides 为空时必须给明确空态/提示，不允许整块空白
    await expect(page.locator(".dst-mods .el-card").last()).not.toHaveText("");
  });

  test("备份管理备份表与回档快照两区域", async ({ page }) => {
    test.setTimeout(90_000);
    await injectSession(page);
    await gotoPluginPage(page, "backup", ".dst-backup");

    await expect(page.getByText("备份文件", { exact: true })).toBeVisible();
    await expect(
      page.getByText("回档快照（DST 原生 session）", { exact: true }),
    ).toBeVisible();
    await expect(page.getByRole("button", { name: "立即备份" })).toBeVisible();

    const backupsResponse = page.waitForResponse(
      (response) =>
        response.request().method() === "GET" &&
        response.url().includes("/api/plugin/dst/backup/list") &&
        response.ok(),
      { timeout: 30_000 },
    );
    await page.getByRole("button", { name: "刷新", exact: true }).first().click();
    await backupsResponse;
    // 空时必须给出提示（Element Plus 表格空文案或 .hint）
    await expect(page.locator(".dst-backup")).toContainText(/暂无|没有|备份/);
  });

  test("定时任务页渲染各计划卡片与操作入口", async ({ page }) => {
    test.setTimeout(90_000);
    await injectSession(page);
    await gotoPluginPage(page, "schedules", ".dst-schedules");

    const schedulesResponse = page.waitForResponse(
      (response) =>
        response.request().method() === "GET" &&
        /\/api\/plugin\/dst\/schedules\/\d+/.test(response.url()) &&
        response.ok(),
      { timeout: 30_000 },
    );
    await page.reload();
    await schedulesResponse;

    await expect(page.locator(".dst-schedules")).toContainText("定时备份");
    // 每张卡片都应有保存/触发/历史三个入口（card-actions 组件）
    await expect(
      page.getByRole("button", { name: "保存", exact: true }).first(),
    ).toBeVisible();
    await expect(
      page.getByRole("button", { name: "手动触发" }).first(),
    ).toBeVisible();
  });

  /*
   * TODO（票 10 修复后升级）：
   * 1. 房间设置改一个 cluster.ini 键 → 经 InstanceFileService 回读确认落盘，
   *    并断言未在表单出现的键原样保留（mergeAndRender 语义）。
   * 2. 玩家管理加一个 KU_ id → 保存 → 回读确认。
   * 3. 无 token 时可断言"启动 → 状态回到已停止"，作为 token 缺失的显式证据
   *    （目前 runStatusDesc 已在 beforeAll 记录，供后续断言使用）。
   */
});
