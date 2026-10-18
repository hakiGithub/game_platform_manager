/**
 * 页面: 无 UI（API 驱动的参数化演练腿——部署向导与生命周期 UI 已由 l4d2 腿覆盖）
 * 用例: dst / sdtd 演练腿 —— 同一套参数化用例在不同演练游戏上执行 部署→停止→启动→卸载
 * 前置: 平台前后端已运行；E2E_TEST_HOST_* 指向装有 Docker 的牺牲主机；
 *       网络可拉取 gameservermanagers/gameserver:dst / :sdtd 镜像（数 GB，首启含 LinuxGSM 初始化）
 * 通过标准（逐游戏声明）:
 *  - 部署至运行中（上限 30 分钟）；停止至已停止（5 分钟）；启动回运行中（5 分钟）；
 *    API 卸载后列表不再出现（2 分钟）
 * 端口挪移：牺牲主机上跑着真实游戏服务（l4d2/dst 等），演练端口整体 +100 避让
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
import {
  cleanupStaleE2EContainers,
  getWslLanIp,
  inspectSteam302HostState,
  waitForLinuxGsmReady,
} from "../../support/host-hygiene.js";

skipWithoutTestHost();

const HOST_NAME = "e2e-wsl-drill-host";
// 演练游戏与挪移后的端口（避让真实服务）
// needsToken: DST 服务器无 cluster token 会启动即退（token 需 Steam 账号生成，
// 无法自动化注入）——该腿只断言"部署完成（离开安装中）"，不做启停断言
const LEGS = [
  {
    code: "dst",
    instance: "e2e-dst-01",
    containerName: "e2e-dst-lgsm",
    ports: { game: 11099, query: 28016 },
    needsToken: true,
  },
  {
    code: "sdtd",
    instance: "e2e-sdtd-01",
    containerName: "e2e-sdtd-lgsm",
    ports: { game: 27000, query: 27001, rcon: 8181 },
    needsToken: false,
  },
];

test.describe.serial("dst / sdtd 演练腿", () => {
  let api = null;
  let hostId = null;
  let originalContainerShare = null;
  let createdHost = false;

  test.beforeAll(async () => {
    cleanupStaleE2EContainers();
    const lanIp = getWslLanIp();
    if (!lanIp) {
      throw new Error(
        "[CHECK][FAIL] 无法获取 WSL eth0 IP；remediation: 确认 wsl.exe 可执行并能访问默认 WSL 发行版",
      );
    }
    console.log(
      `[CHECK][INFO] LinuxGSM 演练腿前置：WSL_LAN=${lanIp}`,
    );
    const token = await login(
      e2eEnv.backendUrl,
      e2eEnv.adminUser,
      e2eEnv.adminPass,
    );
    api = createApi(e2eEnv.backendUrl, token);
    await removeHostsByName(api, HOST_NAME);
    const ensured = await ensureHostByIp(api, {
      name: HOST_NAME,
      ip: lanIp,
      sshPort: Number(e2eEnv.testHost.port),
      sshUsername: e2eEnv.testHost.username,
      sshPassword: e2eEnv.testHost.auth,
    });
    hostId = ensured.hostId;
    createdHost = ensured.created;
    console.log(
      `[CHECK][${createdHost ? "PASS" : "INFO"}] 演练腿主机${createdHost ? "已创建" : "复用既有记录"}: id=${hostId}, ip=${lanIp}`,
    );
    const tested = await api.post(`/api/hosts/${hostId}/test`);
    if (tested.body?.data?.connected === false) {
      throw new Error(
        `[CHECK][FAIL] WSL LAN SSH 连接失败: ${tested.body?.data?.message ?? "unknown"}; remediation: 确认 sshd 监听 WSL LAN IP ${lanIp}:${e2eEnv.testHost.port}，且凭据 E2E_TEST_HOST_* 有效`,
      );
    }

    const status = await api.get(`/api/hosts/${hostId}/steam302/status`);
    const steam302 = status.body?.data;
    if (status.status >= 400 || !steam302 || steam302.phase === "NOT_INSTALLED") {
      throw new Error(
        `[CHECK][FAIL] Steam302 状态不可用: HTTP ${status.status}, body=${JSON.stringify(
          status.body,
        ).slice(0, 300)}; remediation: 先在 WSL 主机安装并启动 Steam302，再重跑 LinuxGSM 演练腿`,
      );
    }
    originalContainerShare = Boolean(steam302.containerShare);
    const share = await api.put(
      `/api/hosts/${hostId}/steam302/container-share`,
      { enabled: true },
    );
    if (share.status >= 400) {
      throw new Error(
        `[CHECK][FAIL] 开启容器共享加速失败: HTTP ${share.status}, body=${JSON.stringify(
          share.body,
        ).slice(0, 300)}; remediation: 检查 Steam302 flag 写入权限与 Caddyfile 是否含启用域名`,
      );
    }
    const verified = inspectSteam302HostState({
      address: lanIp,
      expectLan: true,
      expectedLanIp: lanIp,
    });
    if (!verified.ok) {
      throw new Error(
        `[CHECK][FAIL] 容器共享加速状态与 hosts 不一致: ${verified.reason}; remediation: 检查 Steam302 是否被 Auto_Modify_Hosts 重新覆盖为 127.0.0.1，确认 #S302-LAN 指向 ${lanIp}`,
      );
    }
  });

  test.afterAll(async () => {
    if (!api) return;
    // 残留实例兜底清理
    const res = await api.get("/api/instances?size=50").catch(() => null);
    const records = res?.body?.data?.records ?? [];
    for (const inst of records.filter((r) =>
      LEGS.some((l) => l.instance === r.instanceName),
    )) {
      await api.delete(`/api/instances/${inst.id}`).catch(() => {});
    }
    if (hostId && originalContainerShare !== null) {
      await api
        .put(`/api/hosts/${hostId}/steam302/container-share`, {
          enabled: originalContainerShare,
        })
        .catch(() => {});
    }
    if (hostId && createdHost) {
      await api.delete(`/api/hosts/${hostId}`).catch(() => {});
    }
  });

  for (const leg of LEGS) {
    test.describe(`演练腿 ${leg.code}`, () => {
      let instanceId = null;

      async function pollStatus(desc, timeoutMs) {
        await expect
          .poll(
            async () => {
              const res = await api.get(
                `/api/instances?keyword=${encodeURIComponent(leg.instance)}&size=10`,
              );
              return res.body?.data?.records?.[0]?.runStatusDesc;
            },
            { timeout: timeoutMs, intervals: [10_000, 15_000] },
          )
          .toBe(desc);
      }

      async function currentStatus() {
        const res = await api.get(
          `/api/instances?keyword=${encodeURIComponent(leg.instance)}&size=10`,
        );
        return res.body?.data?.records?.[0]?.runStatusDesc;
      }

      test(`${leg.code} 部署完成（首启自动安装转入后台）`, async () => {
        test.setTimeout(30 * 60_000);
        const games = await api.get("/api/games/list");
        const game = (games.body?.data ?? []).find(
          (g) => g.gameCode === leg.code,
        );
        if (!game) {
          throw new Error(
            `${leg.code} 未在游戏目录中找到: HTTP ${games.status}, 列表大小=${
              (games.body?.data ?? []).length
            }, body=${JSON.stringify(games.body).slice(0, 300)}`,
          );
        }

        const deployed = await api.post("/api/instances", {
          instanceName: leg.instance,
          gameId: game.id,
          hostId,
          deployType: "linuxgsm-docker",
          installPath: `~/games/e2e-${leg.code}`,
          portConfig: leg.ports,
          // CONTAINER_NAME 注入自定义值：默认名与宿主机上真实容器同名会冲突。
          // autoStart 关闭：容器首启的 LinuxGSM 自动安装耗时分钟级，自动启动会在
          // 安装完成前抢跑 start 导致异常——改为部署后轮询重试启动（见下）。
          configInfo: {
            autoRestart: false,
            CONTAINER_NAME: leg.containerName,
            mountHostCerts: true,
          },
        });
        instanceId = deployed.body?.data?.id ?? null;
        if (!instanceId) {
          throw new Error(
            `${leg.code} 实例创建失败: HTTP ${deployed.status} ${JSON.stringify(
              deployed.body,
            )}`,
          );
        }

        // 部署任务完成后实例进入稳定态（自动启动关闭 → 已停止）
        const deadline = Date.now() + 10 * 60_000;
        let status = "安装中";
        while (Date.now() < deadline && status === "安装中") {
          await new Promise((r) => setTimeout(r, 10_000));
          status = await currentStatus();
        }
        expect(status).not.toBe("安装中");
      });

      test(`${leg.code} 轮询重试启动至 LinuxGSM 就绪`, async () => {
        test.setTimeout(35 * 60_000);
        expect(instanceId).toBeTruthy();

        // 容器首启的自动安装（GitHub serverlist + SteamCMD 游戏下载）耗时分钟级，
        // start 的初始化检查在就绪前会失败——等待就绪后再启动。
        // 预检会逐项打印 Steam302、hosts、CA、DNS、GitHub 的实际值；
        // 失败时不能静默当作“环境阻塞”，必须把 remediation 带到用例结果里。
        const ready = waitForLinuxGsmReady(
          leg.containerName,
          `${leg.code}server`,
          30 * 60_000,
          { address: getWslLanIp(), allowLoopback: false },
        );
        if (!ready.ok) {
          throw new Error(
            `[CHECK][FAIL] LinuxGSM 联网/挂载预检未通过: ${ready.reason}; remediation: 逐项查看上方 [CHECK][FAIL] 的 hint/remediation，修复 Steam302 hosts、mountHostCerts、CA 或容器网络后重跑`,
          );
        }
        const r = await api.post(`/api/instances/${instanceId}/start`);
        expect(r.status).toBeLessThan(400);

        if (leg.needsToken) {
          // DST：无 cluster token，srcds 进程启动后即退出 → 到达已停止即符合预期
          const deadline = Date.now() + 5 * 60_000;
          let status = "启动中";
          while (
            Date.now() < deadline &&
            ["启动中", "运行中"].includes(status)
          ) {
            await new Promise((r2) => setTimeout(r2, 10_000));
            status = await currentStatus();
          }
          expect(["已停止", "运行中"]).toContain(status);
        } else {
          await pollStatus("运行中", 5 * 60_000);
        }
      });

      test(`${leg.code} 卸载清理`, async () => {
        test.setTimeout(2 * 60_000);
        expect(instanceId).toBeTruthy();
        const del = await api.delete(`/api/instances/${instanceId}`);
        expect(del.status).toBeLessThan(400);
        await expect
          .poll(
            async () => {
              const res = await api.get(
                `/api/instances?keyword=${encodeURIComponent(leg.instance)}&size=10`,
              );
              return (res.body?.data?.records ?? []).length;
            },
            { timeout: 2 * 60_000, intervals: [5_000, 10_000] },
          )
          .toBe(0);
        instanceId = null;
      });
    });
  }
});
