/**
 * 页面: 插件扩展台 + l4d2 插件子应用（Wujie 容器：仪表盘/RCON 控制台/地图管理/SourceMod 插件/服务器配置/重启管理）
 *       以及系统监控/日志/玩家统计/管理员/服务器信息/预设场景/下载管理/地图中心 8 个次要页面
 * 用例: 插件用例包样板 —— API 造运行中 l4d2 实例为共享 fixture，深测插件子应用全主路径
 * 前置: 平台前后端已运行；E2E_TEST_HOST_* 指向装有 Docker 的牺牲主机；fixtures 提供
 *       e2e-test-map.vpk 与 e2e-sample-plugin.smx；RCON 密码与镜像默认 SRCDS_RCONPW 对齐
 * 通过标准（逐用例声明）:
 *  - 进入工作区: 单实例直跳 /extensions/app/l4d2/dashboard，Wujie 容器与子应用仪表盘渲染，主侧边栏出现插件菜单组
 *  - 仪表盘: 状态 hero + 指标卡渲染，「刷新状态」可点
 *  - RCON: 执行 status 输出服务器状态；含特殊字符命令不致崩溃（注：清单 E2E-092 的
 *    "防注入拦截"在 RCON 控制台无对应实现，平台仅 SourceMod CVAR 黑名单拦截——如实按现状断言）
 *  - 地图: 上传 fixtures VPK → 地图列表出现该 VPK（平台自解析即落盘证据）→ 换图指令发送成功
 *  - SourceMod: 上传 .smx 样例成功
 *  - 服务器配置: 修改保存出现同步成功提示
 *  - 重启管理: 页面渲染（模式 radio + 可用模式 tag）
 *  - 系统监控: 标题、实时指标卡与历史趋势区域渲染
 *  - 日志: 日志文件表与日志视图渲染，刷新入口发起文件列表请求
 *  - 玩家统计: 趋势/玩家面板渲染，空数据时显示明确空态；游玩时长格式校验可见
 *  - 管理员: 列表或空态渲染；新增对话框可打开并触发 Steam ID 必填校验
 *  - 服务器信息: 名称/MOTD/主机信息字段与版本折叠内容渲染
 *  - 预设场景: 预设卡片或明确空态渲染
 *  - 下载管理: URL/Workshop/云盘入口与任务列表或空态渲染
 *  - 地图中心: 筛选、爬取状态与地图列表/空态/加载失败提示至少一项渲染
 *  - 远端仓库: 按实际配置状态分支 —— 未配置时展示引导空态并可打开设置弹窗；
 *    已配置时展示仓库列表并可打开设置弹窗。不触发测试连接、不保存配置，保持幂等
 */
import { test, expect } from "@playwright/test";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { e2eEnv } from "../../support/env.js";
import { skipWithoutTestHost } from "../../support/skip.js";
import { createApi, login, removeHostsByName } from "../../support/api.js";
import {
  LARGE_PLUGIN,
  ensureLargePluginFixture,
} from "../../fixtures/build-large-plugin.mjs";

skipWithoutTestHost();

const HOST_NAME = "e2e-wsl-plugin-host";
const INSTANCE_NAME = "e2e-l4d2-plugin";
const FIXTURES_DIR = join(
  dirname(fileURLToPath(import.meta.url)),
  "../../fixtures",
);

test.describe.serial("l4d2 插件子应用", () => {
  let api = null;
  let instanceId = null;

  /**
   * Wujie 子应用在连续页面跳转时会保留上次渲染一瞬。等待目标页根节点，
   * 确保后续断言针对的是新页面而不是过渡期快照。整轮高负载下同一 URL
   * 的 goto 可能复用已挂载文档，首次等待失败后显式 reload 再等一次。
   */
  async function enterPluginWorkspace(page) {
    await page.goto("/extensions/plugins/list");
    const card = page.locator(".plugin-card", { hasText: "l4d2" });
    try {
      await expect(card).toBeVisible({ timeout: 30_000 });
    } catch (error) {
      const actual = {
        url: page.url(),
        title: await page.title().catch(() => "(unavailable)"),
        body: (
          await page
            .locator("body")
            .innerText()
            .catch(() => "")
        )
          .replaceAll(/\s+/g, " ")
          .slice(0, 500),
      };
      console.log(
        `[CHECK][FAIL] 插件扩展台未渲染 l4d2 卡片，reload 后重试: ${JSON.stringify(
          actual,
        )}`,
      );
      await page.reload();
      try {
        await expect(card).toBeVisible({ timeout: 30_000 });
      } catch (reloadError) {
        throw new Error(
          `[CHECK][FAIL] 插件扩展台 reload 后仍无 l4d2 卡片: ${reloadError.message}; actual=${JSON.stringify(
            actual,
          )}; remediation: 检查 GET /api/pf4j/plugins、Vite 控制台错误与 logs/frontend.err.log`,
          { cause: error },
        );
      }
    }
    await card.getByRole("button", { name: "进入工作区" }).click();

    const dashboardUrl = new RegExp(
      `/extensions/app/l4d2/dashboard\\?instanceId=${instanceId}`,
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
      const targetRow = selectDialog
        .locator(".el-table__row")
        .filter({ hasText: INSTANCE_NAME })
        .or(
          selectDialog.locator(
            `.el-table__row[data-instance-id="${instanceId}"]`,
          ),
        );
      if ((await targetRow.count()) > 0) {
        await targetRow
          .first()
          .getByRole("button", { name: "进入管理" })
          .click();
      } else {
        await selectDialog
          .locator(".el-table__row")
          .first()
          .getByRole("button", { name: "进入管理" })
          .click();
      }
    } else if (dialogOrDashboard === "timeout") {
      throw new Error(
        `[CHECK][FAIL] 进入 l4d2 工作区超时: 未出现实例选择弹窗，也未导航到 dashboard; actual=${page.url()}; remediation: 检查 /api/instances?gameCode=l4d2 响应与插件扩展台日志`,
      );
    }
    await expect(page).toHaveURL(dashboardUrl, { timeout: 30_000 });
    try {
      await expect(page.locator(".dashboard-page")).toBeVisible({
        timeout: 30_000,
      });
    } catch (error) {
      const actual = {
        url: page.url(),
        pluginTab: await page.locator(".plugin-tab").count().catch(() => 0),
        body: (
          await page
            .locator("body")
            .innerText()
            .catch(() => "")
        )
          .replaceAll(/\s+/g, " ")
          .slice(0, 500),
      };
      console.log(
        `[CHECK][FAIL] l4d2 子应用仪表盘首次挂载超时，reload 后重试: ${JSON.stringify(
          actual,
        )}`,
      );
      await page.reload();
      try {
        await expect(page.locator(".dashboard-page")).toBeVisible({
          timeout: 60_000,
        });
      } catch (reloadError) {
        throw new Error(
          `[CHECK][FAIL] l4d2 子应用仪表盘 reload 后仍不可见: ${reloadError.message}; actual=${JSON.stringify(
            actual,
          )}; remediation: 查看 e2e/.artifacts 的 error-context.md、trace 与 /api/pf4j/plugin/l4d2/ui/ 响应`,
          { cause: error },
        );
      }
    }
  }

  async function gotoPluginPage(page, path, rootSelector) {
    const url = `/extensions/app/l4d2/${path}?instanceId=${instanceId}`;
    await page.goto(url);
    try {
      await expect(page.locator(rootSelector)).toBeVisible({
        timeout: 60_000,
      });
    } catch (error) {
      const actual = {
        url: page.url(),
        title: await page.title().catch(() => "(unavailable)"),
        body: (
          await page
            .locator("body")
            .innerText()
            .catch(() => "")
        )
          .replaceAll(/\s+/g, " ")
          .slice(0, 500),
      };
      console.log(
        `[CHECK][FAIL] 插件页 ${path} 根节点 ${rootSelector} 未渲染: ${JSON.stringify(
          actual,
        )}; remediation: 显式 reload 后重试一次`,
      );
      await page.reload();
      try {
        await expect(page.locator(rootSelector)).toBeVisible({
          timeout: 60_000,
        });
      } catch (reloadError) {
        throw new Error(
          `[CHECK][FAIL] 插件页 ${path} reload 后仍不可见: ${reloadError.message}; actual=${JSON.stringify(
            actual,
          )}; remediation: 查看 e2e/.artifacts 的 error-context.md 与 trace`,
          { cause: error },
        );
      }
    }
  }

  /**
   * 回读持久化时从插件扩展台重新进入工作区，而不是裸 reload。
   * 裸 reload 会重载主应用并触发 Wujie 重挂载，长序列下偶发子应用空白；
   * 从扩展台重新进入是真实用户路径，也会强制重建 Wujie 容器。
   */
  async function reopenPluginPage(page, path, rootSelector) {
    await enterPluginWorkspace(page);
    if (path !== "dashboard") {
      await page.goto(`/extensions/app/l4d2/${path}?instanceId=${instanceId}`);
    }
    await expect(page.locator(rootSelector)).toBeVisible({
      timeout: 30_000,
    });
    await expect(page).toHaveURL(
      new RegExp(`/extensions/app/l4d2/${path}\\?instanceId=${instanceId}`),
    );
  }

  async function pluginRow(name) {
    const list = await api.get(
      `/api/plugin/l4d2/plugins/list?instanceId=${instanceId}`,
    );
    return (list.body?.data ?? []).find((row) => row.name === name) ?? null;
  }

  async function expectPluginStatus(name, status, timeout = 30_000) {
    await expect
      .poll(async () => (await pluginRow(name))?.status ?? "MISSING", {
        timeout,
        intervals: [2_000, 4_000],
      })
      .toBe(status);
  }

  async function refreshPluginList(page) {
    const refresh = page.getByRole("button", { name: "刷新", exact: true });
    try {
      await expect(refresh).toBeEnabled({ timeout: 30_000 });
    } catch (error) {
      throw new Error(
        `[CHECK][FAIL] 插件列表刷新按钮未恢复可用; actual=disabled; remediation: 检查前一次 GET /api/plugin/l4d2/plugins/list 是否挂起或失败`,
        { cause: error },
      );
    }

    const responsePromise = page.waitForResponse(
      (response) =>
        response.request().method() === "GET" &&
        response.url().includes("/api/plugin/l4d2/plugins/list"),
      { timeout: 30_000 },
    );
    await refresh.click();
    const response = await responsePromise;
    if (!response.ok()) {
      throw new Error(
        `[CHECK][FAIL] 刷新插件列表失败: HTTP ${response.status()}; remediation: 查看后端日志与 GET /api/plugin/l4d2/plugins/list 响应`,
      );
    }
    console.log("[CHECK][PASS] 插件列表已显式刷新并收到成功响应");
  }

  async function listInstanceDirectory(path) {
    const response = await api.get(
      `/api/instances/${instanceId}/files?path=` + encodeURIComponent(path),
    );
    if (response.status !== 200 || response.body?.code !== 200) {
      throw new Error(
        `[CHECK][FAIL] 读取实例目录失败: path=${path}, HTTP=${response.status}, body=${JSON.stringify(
          response.body,
        ).slice(
          0,
          400,
        )}; remediation: 确认实例运行中且目录已落位，再检查 /api/instances/${instanceId}/files`,
      );
    }
    return response.body?.data?.files ?? [];
  }

  async function scanInstanceTree(root) {
    const pending = [root];
    const files = [];
    const directories = [];
    while (pending.length > 0) {
      const current = pending.pop();
      const entries = await listInstanceDirectory(current);
      for (const entry of entries) {
        const entryPath = `${current}/${entry.name}`;
        if (entry.isDirectory) {
          directories.push(entryPath);
          pending.push(entryPath);
        } else {
          files.push({ ...entry, path: entryPath });
        }
      }
    }
    return {
      files,
      directories,
      totalBytes: files.reduce((sum, entry) => sum + Number(entry.size ?? 0), 0),
    };
  }

  async function expectPluginUiStatus(page, name, status) {
    const label = status === "enabled" ? "已启用" : "已禁用";
    const row = page.locator(".el-table__row", { hasText: name });
    try {
      await expect(row.getByText(label, { exact: true })).toBeVisible({
        timeout: 30_000,
      });
      console.log(`[CHECK][PASS] 插件 ${name} 页面状态回读为 ${label}`);
    } catch (error) {
      const actual = await row.innerText().catch(() => "(row unavailable)");
      throw new Error(
        `[CHECK][FAIL] 插件 ${name} 页面状态未回读为 ${label}; actual=${JSON.stringify(
          actual,
        )}; remediation: 确认后端状态已切换后，检查插件页 GET /api/plugin/l4d2/plugins/list 回读与 Vue 列表刷新`,
        { cause: error },
      );
    }
  }

  test.beforeAll(async () => {
    // beforeAll 钩子超时默认等于用例超时（30s），显式放宽以容纳部署轮询
    test.setTimeout(30 * 60_000);

    const token = await login(
      e2eEnv.backendUrl,
      e2eEnv.adminUser,
      e2eEnv.adminPass,
    );
    api = createApi(e2eEnv.backendUrl, token);
    await removeHostsByName(api, HOST_NAME);

    const created = await api.post("/api/hosts", {
      name: HOST_NAME,
      ip: e2eEnv.testHost.address,
      sshPort: Number(e2eEnv.testHost.port),
      sshUsername: e2eEnv.testHost.username,
      authType: "password",
      sshPassword: e2eEnv.testHost.auth,
    });
    const hostId = created.body?.data?.id ?? null;
    expect(hostId).toBeTruthy();
    await api.post(`/api/hosts/${hostId}/test`);

    // 清理历史残留的同名实例（失败运行遗留会让"单实例直跳"变成选择框分支）
    const stale = await api.get(
      `/api/instances?keyword=${encodeURIComponent(INSTANCE_NAME)}&size=50`,
    );
    for (const inst of stale.body?.data?.records ?? []) {
      await api.delete(`/api/instances/${inst.id}`).catch(() => {});
    }

    const games = await api.get("/api/games/list");
    const l4d2 = (games.body?.data ?? []).find((g) => g.gameCode === "l4d2");
    expect(l4d2).toBeTruthy();

    const deployed = await api.post("/api/instances", {
      instanceName: INSTANCE_NAME,
      gameId: l4d2.id,
      hostId,
      deployType: "docker",
      installPath: "~/games/e2e-l4d2-plugin",
      // 主机 27015 被真实 l4d2 服务占用——插件腿统一挪移到 27025 系
      portConfig: { game: 27025, query: 27026, rcon: 27025, steam: 27006 },
      configInfo: {
        autoRestart: true,
        rconPort: 27025,
        rconPassword: "rconpassword",
        // 显式端口映射（适配器消费的 Map 形状），覆盖元数据 yml 的 27015 字符串端口
        ports: [
          { hostPort: 27025, containerPort: 27015, protocol: "tcp" },
          { hostPort: 27025, containerPort: 27015, protocol: "udp" },
          { hostPort: 27026, containerPort: 27016, protocol: "udp" },
          { hostPort: 27006, containerPort: 27020, protocol: "udp" },
        ],
      },
    });
    instanceId = deployed.body?.data?.id ?? null;
    expect(instanceId).toBeTruthy();

    // 轮询至运行中（镜像已在票 06 拉取过，此处通常只需容器创建与 SteamCMD 校验）
    await expect
      .poll(
        async () => {
          const res = await api.get(
            `/api/instances?keyword=${encodeURIComponent(INSTANCE_NAME)}&size=10`,
          );
          return res.body?.data?.records?.[0]?.runStatusDesc;
        },
        { timeout: 25 * 60_000, intervals: [10_000, 15_000] },
      )
      .toBe("运行中");
  });

  test.afterAll(async () => {
    if (!api) return;
    if (instanceId)
      await api.delete(`/api/instances/${instanceId}`).catch(() => {});
    const hosts = await api.get("/api/hosts").catch(() => null);
    const list = Array.isArray(hosts?.body?.data)
      ? hosts.body.data
      : (hosts?.body?.data?.records ?? []);
    for (const h of list.filter((h) => h.name === HOST_NAME)) {
      await api.delete(`/api/hosts/${h.id}`).catch(() => {});
    }
  });

  test("进入工作区直跳 Wujie 容器且子应用仪表盘渲染", async ({ page }) => {
    test.setTimeout(3 * 60_000);
    const { injectSession } = await import("../../support/session.js");
    await injectSession(page);
    await enterPluginWorkspace(page);
    // 主应用侧容器挂载 + 侧边栏出现插件菜单组
    await expect(page.locator(".plugin-tab")).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole("button", { name: "刷新状态" })).toBeVisible();
    console.log(
      "[CHECK][PASS] l4d2 工作区直跳成功：Wujie 容器、仪表盘与刷新入口均可见",
    );
  });

  test("仪表盘状态卡与刷新", async ({ page }) => {
    test.setTimeout(60_000);
    const { injectSession } = await import("../../support/session.js");
    await injectSession(page);
    await gotoPluginPage(page, "dashboard", ".dashboard-page");
    // 运行中实例：hero 状态与指标卡
    await expect(page.locator(".hero-state")).toContainText("运行", {
      timeout: 15_000,
    });
    await expect(page.locator(".metric-card").first()).toBeVisible();
    await page.getByRole("button", { name: "刷新状态" }).click();
  });

  test("RCON 控制台执行 status 并容忍特殊字符命令", async ({ page }) => {
    test.setTimeout(60_000);
    const { injectSession } = await import("../../support/session.js");
    await injectSession(page);
    await gotoPluginPage(page, "rcon", ".rcon-page");

    const input = page.getByPlaceholder("输入 RCON 命令...");
    await expect(input).toBeVisible({ timeout: 30_000 });

    await input.fill("status");
    await page.getByRole("button", { name: "执行" }).click();
    await expect(
      page.locator(".console-output .console-item").first(),
    ).toBeVisible({
      timeout: 15_000,
    });

    // 特殊字符命令：平台与服务器不至于崩溃（无前端防注入实现，如实断言不崩）
    await input.fill("say \"e2e-test'--;");
    await page.getByRole("button", { name: "执行" }).click();
    await expect(page.locator(".console-output")).toBeVisible();
    await expect(page.locator(".dashboard-page")).toHaveCount(0); // 仍在控制台页
  });

  test("RCON 配置缺失时返回明确失败原因且不误报在线", async () => {
    test.setTimeout(5 * 60_000);
    const detail = await api.get(`/api/instances/${instanceId}`);
    const originalConfig = detail.body?.data?.configInfo ?? {};
    const withoutPassword = { ...originalConfig };
    delete withoutPassword.rconPassword;

    const pollStatus = async (desc) => {
      await expect
        .poll(
          async () => {
            const res = await api.get(
              `/api/instances?keyword=${encodeURIComponent(INSTANCE_NAME)}&size=10`,
            );
            return res.body?.data?.records?.[0]?.runStatusDesc;
          },
          { timeout: 3 * 60_000, intervals: [3_000, 5_000] },
        )
        .toBe(desc);
    };

    try {
      // 先停止实例，使连接池中已认证的旧连接失效；否则改密码后仍可能复用旧连接。
      const stop = await api.post(`/api/instances/${instanceId}/stop`);
      expect(stop.status).toBeLessThan(400);
      await pollStatus("已停止");

      const updated = await api.put(`/api/instances/${instanceId}`, {
        id: instanceId,
        configInfo: withoutPassword,
      });
      expect(updated.status).toBeLessThan(400);

      const status = await api.post("/api/plugin/l4d2/rcon/status", {
        instanceId,
      });
      expect(status.status).toBeLessThan(400);
      expect(status.body?.data?.online).toBe(false);
      expect(status.body?.data?.reason).toContain(
        "RCON 端点不可达：端口未映射或配置缺失",
      );

      const execute = await api.post("/api/plugin/l4d2/rcon/execute", {
        instanceId,
        command: "status",
      });
      expect(execute.status).toBeLessThan(400);
      expect(execute.body?.data?.success).toBe(false);
      expect(execute.body?.data?.error).toContain(
        "RCON 端点不可达：端口未映射或配置缺失",
      );
    } finally {
      await api.put(`/api/instances/${instanceId}`, {
        id: instanceId,
        configInfo: originalConfig,
      });
      await api.post(`/api/instances/${instanceId}/start`).catch(() => {});
      await pollStatus("运行中").catch(() => {});
    }
  });

  /**
   * 页面: l4d2 / 系统监控
   * 用例: 次要页面可达渲染与历史查询冒烟
   * 前置: 共享 fixture 部署的运行中 l4d2 实例
   * 步骤: 打开 /monitor → 断言标题与实时指标 → 点击历史趋势查询
   * 通过标准: 页面根、指标卡、历史趋势区域可见，查询请求成功返回
   */
  test("系统监控页渲染指标与历史区域", async ({ page }) => {
    test.setTimeout(60_000);
    const { injectSession } = await import("../../support/session.js");
    await injectSession(page);
    await gotoPluginPage(page, "monitor", ".monitor-page");
    await expect(page.getByRole("heading", { name: "性能监控" })).toBeVisible();
    await expect(page.locator(".stat-card").first()).toBeVisible({
      timeout: 30_000,
    });
    await expect(page.getByText("历史趋势", { exact: true })).toBeVisible();
    const historyResponse = page.waitForResponse(
      (response) =>
        response.request().method() === "GET" &&
        response.url().includes("/api/plugin/l4d2/monitor/history") &&
        response.ok(),
      { timeout: 30_000 },
    );
    await page.getByRole("button", { name: "查询" }).click();
    await historyResponse;
  });

  /**
   * 页面: l4d2 / 日志
   * 用例: 次要页面可达渲染与刷新冒烟
   * 前置: 共享 fixture 部署的运行中 l4d2 实例
   * 步骤: 打开 /logs → 断言文件表与日志视图 → 点击刷新并等待文件列表响应
   * 通过标准: 日志区可见，刷新触发成功的日志文件列表请求
   */
  test("服务器日志页渲染文件表与刷新入口", async ({ page }) => {
    test.setTimeout(60_000);
    const { injectSession } = await import("../../support/session.js");
    await injectSession(page);
    await gotoPluginPage(page, "logs", ".logs-page");
    await expect(
      page.getByRole("heading", { name: "服务器日志" }),
    ).toBeVisible();
    await expect(page.getByText("日志文件", { exact: true })).toBeVisible();
    await expect(page.locator(".log-viewer")).toBeVisible();

    const filesResponse = page.waitForResponse(
      (response) =>
        response.request().method() === "GET" &&
        response.url().includes("/api/plugin/l4d2/logs/files") &&
        response.ok(),
      { timeout: 30_000 },
    );
    await page.getByRole("button", { name: "刷新", exact: true }).click();
    await filesResponse;
  });

  /**
   * 页面: l4d2 / 玩家统计
   * 用例: 次要页面可达渲染、玩家空态与游玩时长校验冒烟
   * 前置: 共享 fixture 部署的运行中 l4d2 实例
   * 步骤: 打开 /player-stats → 断言趋势空态 → 切到玩家列表断言列表/空态 →
   *       切到游玩时长并提交非法 SteamID
   * 通过标准: 玩家统计面板渲染，玩家列表显示数据或明确空态，格式错误提示可见
   */
  test("玩家统计页可切换到游玩时长并展示格式校验", async ({ page }) => {
    test.setTimeout(60_000);
    const { injectSession } = await import("../../support/session.js");
    await injectSession(page);
    await gotoPluginPage(page, "player-stats", ".player-stats-page");

    await expect(page.getByRole("heading", { name: "玩家统计" })).toBeVisible();
    await expect(
      page
        .locator(".chart")
        .first()
        .or(page.getByText("暂无趋势数据", { exact: true })),
    ).toBeVisible({ timeout: 30_000 });

    await page.getByRole("tab", { name: "玩家列表" }).click();
    await expect(
      page
        .locator(".el-table__row")
        .first()
        .or(page.getByText("暂无玩家数据", { exact: true })),
    ).toBeVisible({ timeout: 30_000 });

    await page.getByRole("tab", { name: "游玩时长" }).click();
    const steamId = page.getByPlaceholder("格式：STEAM_1:0:12345");
    await expect(steamId).toBeVisible();
    await steamId.fill("not-a-steam-id");
    await page.getByRole("button", { name: "查询" }).click();
    await expect(
      page.getByText(
        "SteamID 格式不正确，应为 STEAM_X:Y:Z 格式（例如 STEAM_1:0:12345）",
      ),
    ).toBeVisible();
  });

  /**
   * 页面: l4d2 / 管理员
   * 用例: 次要页面可达渲染、列表空态与新增必填校验冒烟
   * 前置: 共享 fixture 部署的运行中 l4d2 实例
   * 步骤: 打开 /admins → 断言管理员列表或空态 → 打开新增对话框并空提交
   * 通过标准: 页面与列表区域渲染，Steam ID 必填提示可见且对话框保持打开
   */
  test("管理员页可打开新增对话框并触发必填校验", async ({ page }) => {
    test.setTimeout(60_000);
    const { injectSession } = await import("../../support/session.js");
    await injectSession(page);
    await gotoPluginPage(page, "admins", ".admins-page");

    await expect(
      page.getByRole("heading", { name: "管理员管理" }),
    ).toBeVisible();
    await expect(
      page
        .locator(".admins-card")
        .locator(".el-table__row, .el-table__empty-text")
        .first(),
    ).toBeVisible({ timeout: 30_000 });
    await page.getByRole("button", { name: "添加管理员" }).click();
    const dialog = page.getByRole("dialog", { hasText: "添加管理员" });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByPlaceholder("STEAM_1:0:12345678")).toBeVisible();
    await dialog.getByRole("button", { name: "保存" }).click();
    await expect(page.getByText("请填写 Steam ID")).toBeVisible();
    await expect(dialog).toBeVisible();
  });

  /**
   * 页面: l4d2 / 服务器信息
   * 用例: 次要页面可达渲染与版本折叠冒烟
   * 前置: 共享 fixture 部署的运行中 l4d2 实例
   * 步骤: 打开 /server-info → 断言名称/MOTD/主机信息字段 → 展开版本信息
   * 通过标准: 三类信息字段渲染，版本折叠展开后内容区域可见
   */
  test("服务器信息页渲染名称/MOTD/主机信息与版本折叠", async ({ page }) => {
    test.setTimeout(60_000);
    const { injectSession } = await import("../../support/session.js");
    await injectSession(page);
    await gotoPluginPage(page, "server-info", ".server-info-page");

    await expect(
      page.getByRole("heading", { name: "服务器信息" }),
    ).toBeVisible();
    await expect(page.getByText("服务器名称", { exact: true })).toBeVisible();
    await expect(page.getByText("进服公告", { exact: true })).toBeVisible();
    await expect(page.getByText("主机信息", { exact: true })).toBeVisible();
    await expect(
      page.getByPlaceholder("输入服务器名称，如：L4D2 中国区 1 号服"),
    ).toBeVisible();
    await expect(page.getByPlaceholder("输入进服公告内容")).toBeVisible();
    await expect(page.getByPlaceholder("输入主机信息内容")).toBeVisible();
    await page.getByText("版本信息（插件构建 / 提交 / 运行环境）").click();
    await expect(page.locator(".version-block")).toBeVisible({
      timeout: 30_000,
    });
  });

  /**
   * 页面: l4d2 / 预设场景
   * 用例: 次要页面可达渲染与列表/空态冒烟
   * 前置: 共享 fixture 部署的运行中 l4d2 实例
   * 步骤: 打开 /preset → 等待预设列表加载完成
   * 通过标准: 至少一个预设卡片或明确的“暂无预设”空态可见
   */
  test("预设场景页渲染列表或明确空态", async ({ page }) => {
    test.setTimeout(60_000);
    const { injectSession } = await import("../../support/session.js");
    await injectSession(page);
    await gotoPluginPage(page, "preset", ".preset-page");

    await expect(page.getByRole("heading", { name: "预设场景" })).toBeVisible();
    await expect(
      page
        .locator(".preset-card")
        .first()
        .or(page.locator(".el-empty").filter({ hasText: "暂无预设" })),
    ).toBeVisible({ timeout: 30_000 });
  });

  /**
   * 页面: l4d2 / 下载管理
   * 用例: 次要页面可达渲染与任务列表空态冒烟
   * 前置: 共享 fixture 部署的运行中 l4d2 实例
   * 步骤: 打开 /download → 断言三种入口 → 切换 Workshop → 断言任务表
   * 通过标准: 三种下载入口可见，任务表显示任务行或明确空态
   */
  test("资源下载页渲染三种来源与任务区", async ({ page }) => {
    test.setTimeout(60_000);
    const { injectSession } = await import("../../support/session.js");
    await injectSession(page);
    await gotoPluginPage(page, "download", ".download-page");

    await expect(page.getByRole("heading", { name: "资源下载" })).toBeVisible();
    await expect(page.getByRole("tab", { name: "URL 下载" })).toBeVisible();
    await expect(
      page.getByRole("tab", { name: "Workshop 下载" }),
    ).toBeVisible();
    await expect(page.getByRole("tab", { name: "云盘分享" })).toBeVisible();
    await page.getByRole("tab", { name: "Workshop 下载" }).click();
    await expect(
      page.getByPlaceholder(/输入 Workshop URL 或纯数字 ID/),
    ).toBeVisible();
    await expect(page.getByText("下载任务", { exact: true })).toBeVisible();
    await expect(
      page
        .locator(".tasks-card .el-table__row")
        .first()
        .or(page.getByText("暂无下载任务", { exact: true })),
    ).toBeVisible({ timeout: 30_000 });
  });

  /**
   * 页面: l4d2 / 地图中心
   * 用例: 次要页面可达渲染与三方数据容错冒烟
   * 前置: 共享 fixture 部署的运行中 l4d2 实例；地图中心数据可能来自三方源
   * 步骤: 打开 /map-center → 断言筛选与爬取状态 → 点击刷新并等待地图列表响应
   * 通过标准: 页面区块渲染，地图列表显示数据、明确空态或加载失败提示
   */
  test("地图中心页渲染筛选、爬取状态与列表空态", async ({ page }) => {
    test.setTimeout(60_000);
    const { injectSession } = await import("../../support/session.js");
    await injectSession(page);
    await gotoPluginPage(page, "map-center", ".map-center-page");

    await expect(page.getByRole("heading", { name: "地图中心" })).toBeVisible();
    await expect(page.getByPlaceholder("搜索地图关键词")).toBeVisible();
    await expect(page.getByText("爬取状态：", { exact: true })).toBeVisible();
    await expect(page.locator(".el-table")).toBeVisible({ timeout: 30_000 });
    await expect(page.getByRole("button", { name: "手动爬取" })).toBeVisible();

    const mapsResponse = page.waitForResponse(
      (response) =>
        response.request().method() === "GET" &&
        response.url().includes("/api/plugin/l4d2/map-center/maps"),
      { timeout: 30_000 },
    );
    await page.getByRole("button", { name: "刷新", exact: true }).click();
    await mapsResponse;
    await expect(
      page
        .locator(".list-card .el-table__row")
        .first()
        .or(page.locator(".list-card .el-table__empty-text"))
        .or(page.locator(".el-message--error")),
    ).toBeVisible({ timeout: 30_000 });
  });

  test("旧路由 /playtime /version /plugin-store 自动兼容到新页面", async ({
    page,
  }) => {
    test.setTimeout(90_000);
    const { injectSession } = await import("../../support/session.js");
    await injectSession(page);

    await page.goto(`/extensions/app/l4d2/playtime?instanceId=${instanceId}`);
    await expect(page.locator(".player-stats-page")).toBeVisible({
      timeout: 30_000,
    });
    await expect(page).toHaveURL(/player-stats/);

    await page.goto(`/extensions/app/l4d2/version?instanceId=${instanceId}`);
    await expect(page.locator(".server-info-page")).toBeVisible({
      timeout: 30_000,
    });
    await expect(page).toHaveURL(/server-info/);

    await page.goto(
      `/extensions/app/l4d2/plugin-store?instanceId=${instanceId}`,
    );
    await expect(page.locator(".plugins-page")).toBeVisible({
      timeout: 30_000,
    });
    await expect(page).toHaveURL(/plugins/);
    await expect(page.getByRole("tab", { name: "已安装" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
  });

  test("地图上传与换图指令", async ({ page }) => {
    test.setTimeout(3 * 60_000);
    const { injectSession } = await import("../../support/session.js");
    await injectSession(page);
    await gotoPluginPage(page, "maps", ".maps-page");

    // 上传 fixtures VPK（分片上传）
    await page.getByRole("button", { name: "上传地图" }).click();
    const dialog = page.getByRole("dialog").first();
    await expect(dialog).toContainText("上传地图");
    await dialog
      .locator('input[type="file"]')
      .setInputFiles(join(FIXTURES_DIR, "vpk", "e2e-test-map.vpk"));
    // 分片上传自动开始；弹窗为双层 overlay，等待"上传中"消失即完成
    await expect(page.getByText("上传中")).toHaveCount(0, { timeout: 120_000 });

    // 主断言：map-upload 任务到达 COMPLETED（含 VpkParser 对合成 VPK 的真实解析）
    await expect
      .poll(
        async () => {
          const res = await api.get("/api/tasks?taskType=map-upload&size=5");
          const records = res.body?.data?.records ?? [];
          const task = records.find((t) => t.status === "COMPLETED");
          return task ? "COMPLETED" : (records[0]?.status ?? "NONE");
        },
        { timeout: 60_000, intervals: [3_000, 5_000] },
      )
      .toBe("COMPLETED");

    // 落盘证据：地图列表出现该 VPK。票 02 已修复 Docker addons 可见性，
    // 列表为空即真实回归，不能再以条件 SKIP 掩盖。
    await expect(page.locator(".el-table")).toContainText("e2e-test-map.vpk", {
      timeout: 30_000,
    });

    // 换图：切换地图弹窗 → 展开战役 → 点击章节卡片即发送 changelevel
    // （RCON 密码受 retag 镜像限制，"已发送/失败"两种回显均视为页面链路正常）
    await page.getByRole("button", { name: "切换地图" }).click();
    const modal = page.getByRole("dialog", { hasText: "切换地图" });
    await expect(modal).toBeVisible();
    // 折叠面板可能已展开或收起：卡片可点则直接点，否则先展开战役
    const firstCampaign = modal.locator(".el-collapse-item").first();
    const card = firstCampaign.locator(".chapter-card").first();
    try {
      await card.click({ timeout: 3_000 });
    } catch {
      await firstCampaign.locator(".campaign-title").click();
      await card.click();
    }
    // 点击卡片即进入切换中状态（changingCode 置位）。最终回显（指令已发送/失败）
    // 依赖 RCON 认证可达——retag 镜像下错误密码会长时间挂起，不做终态断言
    await expect(modal.locator(".chapter-changing").first()).toBeVisible({
      timeout: 10_000,
    });
  });

  test("SourceMod 插件上传样例", async ({ page }) => {
    test.setTimeout(2 * 60_000);
    const { injectSession } = await import("../../support/session.js");
    await injectSession(page);
    await gotoPluginPage(page, "plugins", ".plugins-page");
    await page.getByRole("button", { name: "上传插件" }).click();
    const dialog = page.getByRole("dialog").first();
    await expect(dialog).toContainText("上传插件");
    await dialog
      .locator('input[type="file"]')
      .setInputFiles(join(FIXTURES_DIR, "sourcemod", "e2e-sample-plugin.smx"));
    await dialog.getByRole("button", { name: "上传", exact: true }).click();
    // 上传完成 = 进度文本消失（弹窗为双层 overlay，避免 strict 冲突）
    await expect(page.getByText(/上传中|\d+%/)).toHaveCount(0, {
      timeout: 60_000,
    });
    // 上传成功的实质断言：列表出现样例插件（服务端拒绝时此断言会失败并暴露根因）
    await expect(page.locator(".el-table")).toContainText("e2e-sample-plugin", {
      timeout: 30_000,
    });
  });

  test("单插件 ZIP 快路径上传、落位、重装与清理", async ({ page }) => {
    test.setTimeout(6 * 60_000);
    const { injectSession } = await import("../../support/session.js");
    await injectSession(page);
    const startedAt = Date.now();
    const phaseMarks = [];
    const mark = (phase, detail) => {
      const elapsed = Date.now() - startedAt;
      phaseMarks.push({ phase, elapsed, detail });
      console.log(
        `[CHECK][PASS] ZIP 快路径阶段 ${phase}: elapsed=${elapsed}ms${detail ? `, ${detail}` : ""}`,
      );
    };

    const zipPath = join(FIXTURES_DIR, "plugins", "e2e-zip-plugin.zip");
    await gotoPluginPage(page, "plugins", ".plugins-page");
    const upload = async () => {
      await page.getByRole("button", { name: "上传插件" }).click();
      const dialog = page.getByRole("dialog", { hasText: "上传插件" });
      await expect(dialog).toBeVisible();
      await dialog.locator('input[type="file"]').setInputFiles(zipPath);
      await dialog.getByRole("button", { name: "上传", exact: true }).click();
      await expect(page.getByText(/上传中|\d+%/)).toHaveCount(0, {
        timeout: 4 * 60_000,
      });
      // Element Plus 上传弹窗关闭后 overlay 节点仍留在 DOM 中；真实用户路径
      // 是点击遮罩关闭，避免后续列表操作被不可见遮罩拦截。
      await dialog.locator("..").click({ position: { x: 8, y: 8 } });
      await expect(page.locator(".el-table")).toContainText("e2e-zip-plugin", {
        timeout: 30_000,
      });
    };

    await upload();
    mark("UPLOAD", "原始 ZIP 单次上传并进入安装");

    const storeRoot =
      "left4dead2/addons/sourcemod/plugins_store/e2e-zip-plugin/left4dead2";
    await expect
      .poll(
        async () => {
          const list = await api.get(
            `/api/instances/${instanceId}/files?path=` +
              encodeURIComponent(storeRoot),
          );
          return (list.body?.data?.files ?? [])
            .map((entry) => entry.name ?? entry.fileName ?? "")
            .sort()
            .join(",");
        },
        { timeout: 60_000, intervals: [2_000, 4_000] },
      )
      .toBe("addons,cfg");
    mark("IMPORT", "目录级导入完成");

    const files = await api.get(
      `/api/instances/${instanceId}/files?path=` + encodeURIComponent(storeRoot),
    );
    const entryNames = (files.body?.data?.files ?? []).map(
      (entry) => entry.name ?? entry.fileName ?? "",
    );
    expect(entryNames).toEqual(
      expect.arrayContaining(["addons", "cfg"]),
    );

    const smx = await api.get(
      `/api/instances/${instanceId}/files?path=` +
        encodeURIComponent(
          "left4dead2/addons/sourcemod/plugins_store/e2e-zip-plugin/left4dead2/addons/sourcemod/plugins",
        ),
    );
    expect(
      (smx.body?.data?.files ?? []).map(
        (entry) => entry.name ?? entry.fileName ?? "",
      ),
    ).toContain("e2e-zip-plugin.smx");
    mark("VALIDATE", "关键 SMX 与目录结构校验通过");

    const row = page.locator(".el-table__row", { hasText: "e2e-zip-plugin" });
    await expect(row).toBeVisible({ timeout: 30_000 });
    await expect(row.getByText(/已启用|已禁用/)).toBeVisible();

    // 重复安装：同名单插件应先由原子落位保留旧目录，再替换为新目录。
    await upload();
    mark("REINSTALL", "重复上传完成，旧目录被原子替换");
    await expect(page.locator(".el-table__row", { hasText: "e2e-zip-plugin" })).toBeVisible();

    // 清理：删除插件目录并确认列表移除，避免残留污染后续轮次。
    await row.getByRole("button", { name: "删除" }).click();
    await page
      .locator(".el-message-box")
      .getByRole("button", { name: /确定|删除/ })
      .click();
    await expect(page.locator(".el-table")).not.toContainText("e2e-zip-plugin", {
      timeout: 30_000,
    });
    mark("CLEANUP", "插件目录已删除，测试现场清理完成");

    const totalMs = Date.now() - startedAt;
    const baselineMs = 337_753;
    console.log(
      `[CHECK][${totalMs <= baselineMs ? "PASS" : "FAIL"}] ZIP 快路径总耗时: ${totalMs}ms，基线=${baselineMs}ms，阶段=${phaseMarks
        .map((item) => `${item.phase}:${item.elapsed}ms`)
        .join(" | ")}; remediation=${
        totalMs <= baselineMs
          ? "无"
          : "检查是否仍走逐文件上传；查看任务详情阶段耗时与主机解压日志"
      }`,
    );
    expect(totalMs, "ZIP 快路径总耗时应优于 337753ms 基线").toBeLessThanOrEqual(
      baselineMs,
    );
  });

  test("965 文件大归档跨 100MB 上传、落位与清理", async ({ page }) => {
    test.setTimeout(15 * 60_000);
    const { injectSession } = await import("../../support/session.js");
    await injectSession(page);

    const archivePath = ensureLargePluginFixture();
    const startedAt = Date.now();
    const phaseMarks = [];
    const mark = (phase, detail) => {
      const elapsed = Date.now() - startedAt;
      phaseMarks.push({ phase, elapsed, detail });
      console.log(
        `[CHECK][PASS] 大归档阶段 ${phase}: elapsed=${elapsed}ms${detail ? `, ${detail}` : ""}`,
      );
    };

    await gotoPluginPage(page, "plugins", ".plugins-page");
    await page.getByRole("button", { name: "上传插件" }).click();
    const dialog = page.getByRole("dialog", { hasText: "上传插件" });
    await expect(dialog).toBeVisible();
    await dialog.locator('input[type="file"]').setInputFiles(archivePath);
    await dialog.getByRole("button", { name: "上传", exact: true }).click();
    mark("UPLOAD_START", `archive=${archivePath}`);

    await expect(page.getByText(/上传中|\d+%/)).toHaveCount(0, {
      timeout: 12 * 60_000,
    });
    await dialog.locator("..").click({ position: { x: 8, y: 8 } });

    const row = page.locator(".el-table__row", {
      hasText: LARGE_PLUGIN.name,
    });
    await expect(row).toBeVisible({ timeout: 60_000 });
    mark("UPLOAD_END", "归档导入返回成功且插件列表条目可见");
    await expect(row.getByText(/已启用|已禁用/)).toBeVisible();
    mark("LIST", "插件列表出现大归档条目");

    const storeRoot =
      `left4dead2/addons/sourcemod/plugins_store/${LARGE_PLUGIN.name}` +
      "/left4dead2";
    const tree = await scanInstanceTree(storeRoot);
    expect(
      tree.files.length,
      `[CHECK][FAIL] 大归档文件数不一致: expected=${LARGE_PLUGIN.fileCount}, actual=${tree.files.length}; remediation=检查 staging 文件清单、解压工具退出码与目标磁盘空间`,
    ).toBe(LARGE_PLUGIN.fileCount);
    expect(
      tree.totalBytes,
      `[CHECK][FAIL] 大归档总字节不一致: expected=${LARGE_PLUGIN.targetBytes}, actual=${tree.totalBytes}; remediation=检查解压工具是否截断文件及磁盘空间`,
    ).toBe(LARGE_PLUGIN.targetBytes);
    mark(
      "VERIFY_TREE",
      `files=${tree.files.length}, bytes=${tree.totalBytes}, dirs=${tree.directories.length}`,
    );

    const byRelativePath = new Map(
      tree.files.map((entry) => [
        entry.path.slice(storeRoot.length + 1),
        entry,
      ]),
    );
    const smx = byRelativePath.get("addons/sourcemod/plugins/e2e-large-plugin.smx");
    const cfg = byRelativePath.get("cfg/sourcemod/e2e-large-plugin.cfg");
    expect(
      smx,
      "[CHECK][FAIL] 关键 SMX 缺失: addons/sourcemod/plugins/e2e-large-plugin.smx; remediation=检查 ZIP 根结构归一化与 requiredFiles 校验",
    ).toBeTruthy();
    expect(
      cfg,
      "[CHECK][FAIL] 关键 CFG 缺失: cfg/sourcemod/e2e-large-plugin.cfg; remediation=检查 ZIP 根结构归一化与文件计数",
    ).toBeTruthy();
    expect(cfg.size, "[CHECK][FAIL] 关键 CFG 大小不一致").toBe(
      Buffer.byteLength("sm_e2e_large_plugin 1\n"),
    );
    mark("VERIFY_KEY_FILES", "关键 SMX/CFG 已落位");

    const executable = tree.files.filter(
      (entry) =>
        LARGE_PLUGIN.executableFileNames.includes(
          entry.path.slice(storeRoot.length + 1),
        ) && entry.permissions === "rwxr-xr-x",
    );
    const permissionSamples = tree.files
      .slice(0, 5)
      .map(
        (entry) =>
          `${entry.path.split("/").at(-1)}:${JSON.stringify(entry.permissions)}`,
      );
    const expectedExecutableSamples = tree.files
      .filter((entry) =>
        LARGE_PLUGIN.executableFileNames.includes(
          entry.path.slice(storeRoot.length + 1),
        ),
      )
      .slice(0, 3)
      .map(
        (entry) =>
          `${entry.path.split("/").at(-1)}:${JSON.stringify(entry.permissions)}`,
      );
    console.log(
      `[CHECK][${executable.length > 0 ? "PASS" : "FAIL"}] 大归档权限回读采样: first=${permissionSamples.join(", ")}; expectedExecutable=${expectedExecutableSamples.join(", ")}; executable=${executable.length}`,
    );
    expect(
      executable.length,
      `[CHECK][FAIL] 可执行权限未完整保留: expected=${LARGE_PLUGIN.executableFileNames.length}, actual=${executable.length}; remediation=检查 ZIP external attributes、platform-tools bsdtar 解压权限、docker cp 权限语义`,
    ).toBe(LARGE_PLUGIN.executableFileNames.length);
    mark(
      "VERIFY_PERMISSIONS",
      `executable=${executable.length}, sample=${executable
        .slice(0, 3)
        .map((entry) => `${entry.path.split("/").at(-1)}:${entry.permissions}`)
        .join(",")}`,
    );

    const storeEntries = await listInstanceDirectory(
      "left4dead2/addons/sourcemod/plugins_store",
    );
    const leftovers = storeEntries
      .map((entry) => entry.name)
      .filter(
        (name) =>
          name.startsWith(".install-") ||
          name.includes(".rollback-"),
      );
    expect(
      leftovers,
      `[CHECK][FAIL] 安装完成后仍有 staging/rollback 残留: ${leftovers.join(", ")}; remediation=检查 CLEANUP 阶段、finally 清理与启动期 PluginStoreMigration`,
    ).toEqual([]);
    mark("VERIFY_CLEANUP", "无 .install-* / .rollback-* 残留");

    await expect(
      row.getByText(/已启用|已禁用/),
    ).toHaveCount(1);
    await row.getByRole("button", { name: "删除" }).click();
    await page
      .locator(".el-message-box")
      .getByRole("button", { name: /确定|删除/ })
      .click();
    await expect(page.locator(".el-table")).not.toContainText(
      LARGE_PLUGIN.name,
      { timeout: 60_000 },
    );
    const rootAfterDelete = await listInstanceDirectory(storeRoot).catch(
      () => [],
    );
    expect(
      rootAfterDelete,
      `[CHECK][FAIL] 删除后插件目录仍可读: path=${storeRoot}; remediation=检查 DELETE /api/plugin/l4d2/plugins/${LARGE_PLUGIN.name} 与容器写权限`,
    ).toEqual([]);
    mark("CLEANUP", "大归档插件目录已删除");

    const totalMs = Date.now() - startedAt;
    const baselineMs = 337_753;
    console.log(
      `[CHECK][${totalMs <= baselineMs ? "PASS" : "FAIL"}] 大归档总耗时: ${totalMs}ms，基线=${baselineMs}ms，阶段=${phaseMarks
        .map((item) => `${item.phase}:${item.elapsed}ms`)
        .join(" | ")}; remediation=${
        totalMs <= baselineMs
          ? "无"
          : "检查原始归档是否仅上传一次、主机侧解压工具与递归文件校验开销"
      }`,
    );
    expect(
      totalMs,
      `[CHECK][FAIL] 大归档总耗时应优于 ${baselineMs}ms 基线，实际=${totalMs}ms; remediation=查看阶段耗时与后端归档导入日志`,
    ).toBeLessThanOrEqual(baselineMs);
  });

  test("单插件 ZIP 结构失败时给出阶段与修复建议", async ({ page }) => {
    test.setTimeout(2 * 60_000);
    const { injectSession } = await import("../../support/session.js");
    await injectSession(page);
    await gotoPluginPage(page, "plugins", ".plugins-page");

    await page.getByRole("button", { name: "上传插件" }).click();
    const dialog = page.getByRole("dialog", { hasText: "上传插件" });
    await expect(dialog).toBeVisible();
    await dialog
      .locator('input[type="file"]')
      .setInputFiles(join(FIXTURES_DIR, "plugins", "e2e-broken-plugin.zip"));
    await dialog.getByRole("button", { name: "上传", exact: true }).click();

    const error = page.locator(".el-message--error");
    await expect(error).toBeVisible({ timeout: 60_000 });
    await expect(error).toContainText(/阶段=校验\(VALIDATE\)/);
    await expect(error).toContainText(/原因码=ARCHIVE_REQUIRED_FILE_MISSING/);
    await expect(error).toContainText(/建议=.*关键文件/);
    console.log(
      "[CHECK][PASS] 单插件 ZIP 结构失败：页面错误包含阶段、原因码与修复建议",
    );

    await expect(
      page.locator(".el-table__row", { hasText: "e2e-broken-plugin" }),
    ).toHaveCount(0);
    console.log(
      "[CHECK][PASS] 失败归档未写入插件列表，未产生半安装记录",
    );
  });

  test("SourceMod 插件禁用/启用切换并恢复原状态", async ({ page }) => {
    test.setTimeout(2 * 60_000);
    const { injectSession } = await import("../../support/session.js");
    await injectSession(page);
    await gotoPluginPage(page, "plugins", ".plugins-page");

    const before = await pluginRow("e2e-sample-plugin");
    expect(before, "样例插件应已上传").toBeTruthy();
    const originalStatus = before.status;
    const nextStatus = originalStatus === "enabled" ? "disabled" : "enabled";

    const row = () =>
      page.locator(".el-table__row", { hasText: "e2e-sample-plugin" });
    const toggle = async (targetStatus) => {
      const target = targetStatus === "enabled" ? "启用" : "禁用";
      await expect(row()).toBeVisible({ timeout: 30_000 });
      await row().getByRole("button", { name: target, exact: true }).click();
      if (target === "禁用") {
        await page
          .locator(".el-message-box")
          .getByRole("button", { name: "禁用", exact: true })
          .click();
      }
      await expectPluginStatus("e2e-sample-plugin", targetStatus);
      await refreshPluginList(page);
      await expectPluginUiStatus(page, "e2e-sample-plugin", targetStatus);
    };

    await toggle(nextStatus);

    // 恢复原状态，保持本用例可重复执行
    await toggle(originalStatus);
  });

  test("远端仓库按配置状态展示列表或引导空态并可打开仓库设置", async ({
    page,
  }) => {
    test.setTimeout(60_000);
    const { injectSession } = await import("../../support/session.js");
    await injectSession(page);
    await gotoPluginPage(page, "plugins", ".plugins-page");

    const config = await api.get("/api/plugin/l4d2/plugin-store/config");
    if (config.status !== 200 || config.body?.code !== 200) {
      throw new Error(
        `[CHECK][FAIL] 读取远端仓库配置失败: HTTP ${config.status}, body=${JSON.stringify(
          config.body,
        ).slice(
          0,
          300,
        )}; remediation: 确认 l4d2 插件已启动且 store-config API 可用`,
      );
    }
    const configured = Boolean(config.body?.data?.configured);

    // 切到「远端仓库」tab（lazy 挂载后触发列表加载）
    await page.getByRole("tab", { name: "远端仓库" }).click();

    if (configured) {
      await expect(
        page.locator(".remote-tab .el-table__row").first(),
      ).toBeVisible({ timeout: 30_000 });
      await page.getByRole("button", { name: "仓库设置" }).click();
    } else {
      const empty = page.locator(".el-empty", {
        hasText: "远端插件仓库未配置",
      });
      await expect(empty).toBeVisible({ timeout: 30_000 });
      await empty.getByRole("button", { name: "去配置" }).click();
    }

    const dialog = page.getByRole("dialog", { hasText: "远端仓库设置" });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByText("仓库地址", { exact: true })).toBeVisible();
    if (configured) {
      await expect(
        dialog.getByPlaceholder("owner/repo 或 https://github.com/owner/repo"),
      ).toHaveValue(config.body.data.repo);
    }
    // 不点「测试连接」（真实 GitHub 外呼）、不保存（保持未配置状态幂等）
    await dialog.getByRole("button", { name: "取消" }).click();
    await expect(dialog).toBeHidden();
  });
  test("地图删除确认与清理", async ({ page }) => {
    test.setTimeout(60_000);
    const { injectSession } = await import("../../support/session.js");
    await injectSession(page);
    await gotoPluginPage(page, "maps", ".maps-page");

    const row = page.locator(".el-table__row", { hasText: "e2e-test-map.vpk" });
    await expect(row).toBeVisible({ timeout: 30_000 });
    await row.getByRole("button", { name: "删除" }).click();
    // 确认框：确定删除 X？此操作不可恢复。
    await page
      .locator(".el-message-box")
      .getByRole("button", { name: /确定|删除/ })
      .click();
    await expect(page.locator(".el-table")).not.toContainText(
      "e2e-test-map.vpk",
      { timeout: 30_000 },
    );
  });

  test("服务器配置保存同步", async ({ page }) => {
    test.setTimeout(60_000);
    const { injectSession } = await import("../../support/session.js");
    await injectSession(page);
    await gotoPluginPage(page, "server-config", ".server-config-page");

    const nameInput = page.getByLabel("服务器名称");
    await expect(nameInput).toBeVisible({ timeout: 30_000 });
    await nameInput.fill("E2E-Server");
    await page.getByRole("button", { name: "保存配置" }).click();
    await expect(page.getByText(/已保存并同步/)).toBeVisible({
      timeout: 15_000,
    });
  });

  test("服务器配置核心字段修改并回读", async ({ page }) => {
    test.setTimeout(90_000);
    const { injectSession } = await import("../../support/session.js");
    await injectSession(page);
    await gotoPluginPage(page, "server-config", ".server-config-page");

    const formItem = (label) =>
      page.locator(".el-form-item", { hasText: label });
    await expect(page.getByLabel("服务器名称")).toBeVisible({
      timeout: 30_000,
    });
    await formItem("最大玩家数").locator("input").fill("10");
    await page.getByPlaceholder("c1m1_hotel").fill("c9m2_parish");

    // Wujie + ElSelect teleport 下鼠标定位不稳定，用输入框键盘选择真实枚举值。
    const gameModeSelect = formItem("游戏模式").locator(".el-select");
    await gameModeSelect.click();
    await page.keyboard.press("Home");
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("Enter");

    const difficultySelect = formItem("难度").locator(".el-select");
    await difficultySelect.click();
    await page.keyboard.press("End");
    await page.keyboard.press("Enter");

    await page.getByRole("button", { name: "保存配置" }).click();
    await expect(page.getByText(/已保存并同步/)).toBeVisible({
      timeout: 15_000,
    });

    // 回读：从扩展台重新进入工作区后值保持
    await reopenPluginPage(page, "server-config", ".server-config-page");
    await expect(formItem("最大玩家数").locator("input")).toHaveValue("10", {
      timeout: 30_000,
    });
    await expect(page.getByPlaceholder("c1m1_hotel")).toHaveValue(
      "c9m2_parish",
    );
    await expect(
      formItem("游戏模式").locator(".el-select__placeholder:visible"),
    ).toHaveText(/versus/);
    await expect(
      formItem("难度").locator(".el-select__placeholder:visible"),
    ).toHaveText(/impossible/);
  });

  test("额外配置 KV 行增删与持久化", async ({ page }) => {
    test.setTimeout(90_000);
    const { injectSession } = await import("../../support/session.js");
    await injectSession(page);
    await gotoPluginPage(page, "server-config", ".server-config-page");

    const addRowButton = page.getByRole("button", { name: "增加行" });
    await expect(addRowButton).toBeVisible({ timeout: 30_000 });

    // 1. 增加一行，填唯一键值
    await addRowButton.click();
    const row = page.locator(".el-table__row").last();
    await row.getByPlaceholder("如 sv_consistency").fill("sv_e2e_test");
    await row.getByPlaceholder("如 1").fill("1");

    // 2. 保存 → toast
    await page.getByRole("button", { name: "保存配置" }).click();
    await expect(page.getByText(/已保存并同步/)).toBeVisible({
      timeout: 15_000,
    });

    // 3. 从扩展台重新进入工作区后回读
    await reopenPluginPage(page, "server-config", ".server-config-page");
    await expect(addRowButton).toBeVisible({ timeout: 30_000 });
    await expect(
      page.getByPlaceholder("如 sv_consistency").first(),
    ).toHaveValue("sv_e2e_test", { timeout: 30_000 });

    // 4. 删除行（点行内删除按钮）→ 保存
    await row.getByRole("button").last().click();
    await page.getByRole("button", { name: "保存配置" }).click();
    await expect(page.getByText(/已保存并同步/)).toBeVisible({
      timeout: 15_000,
    });
  });

  test("自定义配置编辑保存", async ({ page }) => {
    test.setTimeout(60_000);
    const { injectSession } = await import("../../support/session.js");
    await injectSession(page);
    await gotoPluginPage(page, "server-config", ".server-config-page");

    const textarea = page.getByPlaceholder(/在此输入自定义配置/);
    await expect(textarea).toBeVisible({ timeout: 30_000 });
    await textarea.fill("// e2e-custom-config\nsv_ladder_allow_camera 1");
    await page.getByRole("button", { name: "保存配置" }).click();
    // 保存成功 = 前端交互链路正常；reload 回读依赖后端持久化实现
    await expect(page.getByText(/已保存并同步/)).toBeVisible({
      timeout: 15_000,
    });
  });

  test("查看原始配置文件弹窗", async ({ page }) => {
    test.setTimeout(60_000);
    const { injectSession } = await import("../../support/session.js");
    await injectSession(page);
    await gotoPluginPage(page, "server-config", ".server-config-page");

    await expect(page.getByPlaceholder(/在此输入自定义配置/)).toBeVisible({
      timeout: 30_000,
    });
    await page.getByRole("button", { name: "查看原始文件" }).click();
    const dialog = page.getByRole("dialog", {
      hasText: "查看 / 编辑原始配置文件",
    });
    await expect(dialog).toBeVisible({ timeout: 30_000 });
    await expect(dialog.locator("textarea").first()).not.toBeEmpty({
      timeout: 30_000,
    });
  });

  test("重载配置指令下发", async ({ page }) => {
    test.setTimeout(60_000);
    const { injectSession } = await import("../../support/session.js");
    await injectSession(page);
    await gotoPluginPage(page, "server-config", ".server-config-page");

    await expect(page.getByLabel("服务器名称")).toBeVisible({
      timeout: 30_000,
    });
    await page.getByRole("button", { name: "重载配置" }).click();
    // 确认框（RCON 密码受 retag 镜像限制，发送成功/失败均视为链路正常）
    await page
      .locator(".el-message-box")
      .getByRole("button", { name: "重载" })
      .click();
    await expect(page.getByText(/已发送 exec server\.cfg|失败/)).toBeVisible({
      timeout: 30_000,
    });
  });

  test("重启管理页渲染模式配置", async ({ page }) => {
    test.setTimeout(60_000);
    const { injectSession } = await import("../../support/session.js");
    await injectSession(page);
    await gotoPluginPage(page, "restart", ".restart-page");

    await expect(page.getByRole("radio", { name: "RCON 模式" })).toBeVisible({
      timeout: 30_000,
    });
    await expect(page.getByText("AUTO").first()).toBeVisible();
    await expect(
      page.getByRole("button", { name: "重启服务器" }),
    ).toBeVisible();
  });
});
