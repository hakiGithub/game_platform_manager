/**
 * 页面: 主机详情 / Steam302 加速面板 + 容器网络链路
 * 用例: Steam302 容器共享、hosts 二态、CA/挂载、容器内 GitHub HTTPS 与状态恢复
 * 前置:
 *  - 平台前后端已运行；E2E_TEST_HOST_* 指向 WSL 牺牲主机
 *  - 本用例复用已安装的 Steam302，不重复安装；beforeAll 保存 containerShare，afterAll 恢复
 * 通过标准（逐用例声明）:
 *  - 状态: phase=RUNNING、hostsEntries>0、proxiedDomains>0、certTrusted=true
 *  - 默认态: 关闭共享后 hosts 出现 #S302 且无 #S302-LAN
 *  - 共享态: 平台停容器写入 hosts 后重启，hosts 出现 #S302-LAN，目标 IP 等于 WSL eth0 IP
 *  - 容器链路: bridge 容器挂载宿主 hosts + CA 后可解析 GitHub 且 HTTPS 返回 2xx/3xx
 *  - 自愈: 删除平台标记后 status 自动补写，宿主 hosts 恢复非空
 */
import { test, expect } from "@playwright/test";
import { e2eEnv } from "../../support/env.js";
import { skipWithoutTestHost } from "../../support/skip.js";
import { createApi, ensureHostByIp, login } from "../../support/api.js";
import {
  execWslBash,
  getWslLanIp,
  inspectSteam302HostState,
} from "../../support/host-hygiene.js";

skipWithoutTestHost();

const HOST_NAME = "e2e-wsl-steam302-host";

test.describe.serial("Steam302 GitHub 专项", () => {
  let api = null;
  let hostId = null;
  let originalShare = null;
  let createdHost = false;

  async function getConfigValue(key) {
    const res = await api.get(`/api/hosts/${hostId}/steam302/config`);
    expect(res.status).toBeLessThan(400);
    return res.body?.data?.[key] ?? null;
  }

  function check(label, ok, actual, remediation) {
    console.log(
      `[CHECK][${ok ? "PASS" : "FAIL"}] ${label}: ${actual ?? "(empty)"}`,
    );
    if (!ok && remediation) {
      console.log(`  remediation: ${remediation}`);
    }
  }

  test.beforeAll(async () => {
    test.setTimeout(3 * 60_000);
    const lanIp = getWslLanIp();
    if (!lanIp) {
      throw new Error(
        "[CHECK][FAIL] 无法获取 WSL eth0 IP; remediation: 确认 wsl.exe 可执行并能访问默认发行版",
      );
    }

    const token = await login(
      e2eEnv.backendUrl,
      e2eEnv.adminUser,
      e2eEnv.adminPass,
    );
    api = createApi(e2eEnv.backendUrl, token);

    // 平台对 IP 有唯一约束：持久库或整轮中已有同 IP 主机时复用，
    // 仅在确实不存在时创建，并只清理本轮创建的记录。
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
      `[CHECK][${createdHost ? "PASS" : "INFO"}] Steam302 专项主机${createdHost ? "已创建" : "复用既有记录"}: id=${hostId}, ip=${lanIp}`,
    );
    const tested = await api.post(`/api/hosts/${hostId}/test`);
    if (tested.body?.data?.connected === false) {
      throw new Error(
        `[CHECK][FAIL] Steam302 专项主机 SSH 连接失败: ${tested.body?.data?.message ?? "unknown"}; remediation: 确认 WSL sshd 与 E2E_TEST_HOST_* 凭据有效`,
      );
    }

    const status = await api.get(`/api/hosts/${hostId}/steam302/status`);
    const data = status.body?.data;
    if (status.status >= 400 || !data || data.phase !== "RUNNING") {
      throw new Error(
        `[CHECK][FAIL] Steam302 未运行: HTTP ${status.status}, body=${JSON.stringify(
          status.body,
        ).slice(0, 300)}; remediation: 先在 WSL 主机安装并启动 Steam302`,
      );
    }
    originalShare = Boolean(data.containerShare);
    const autoModifyHosts = await getConfigValue("Auto_Modify_Hosts");
    if (autoModifyHosts === null) {
      throw new Error(
        "[CHECK][FAIL] S302.ini 缺少 Auto_Modify_Hosts; remediation: 确认 Steam302 安装完整且 /opt/steam302/S302.ini 可读",
      );
    }
  });

  test.afterAll(async () => {
    if (api && hostId && originalShare !== null) {
      await api
        .put(`/api/hosts/${hostId}/steam302/container-share`, {
          enabled: originalShare,
        })
        .catch(() => {});
    }
    if (api && hostId) {
      if (createdHost) {
        await api.delete(`/api/hosts/${hostId}`).catch(() => {});
      }
    }
  });

  test("状态字段完整且安装基础链路有效", async () => {
    test.setTimeout(60_000);
    const res = await api.get(`/api/hosts/${hostId}/steam302/status`);
    expect(res.status).toBeLessThan(400);
    const data = res.body?.data ?? {};
    expect(data.phase).toBe("RUNNING");
    expect(data.hostsEntries).toBeGreaterThan(0);
    expect(data.proxiedDomains).toBeGreaterThan(0);
    expect(data.certTrusted).toBe(true);
    expect(data.containerStatus).toBeTruthy();
    const autoModifyHosts = await getConfigValue("Auto_Modify_Hosts");
    check(
      "Auto_Modify_Hosts 已关闭（平台独占 hosts）",
      autoModifyHosts === "0",
      `Auto_Modify_Hosts=${autoModifyHosts}`,
      "开启容器共享或启动 Steam302 会触发平台自愈；若仍为 1，检查 S302.ini 是否可写",
    );
    expect(autoModifyHosts).toBe("0");
  });

  test("默认模式写入 #S302，LAN 模式写入 #S302-LAN 且指向 WSL IP", async () => {
    test.setTimeout(2 * 60_000);
    const lanIp = getWslLanIp();
    expect(lanIp).toBeTruthy();

    const disabled = await api.put(
      `/api/hosts/${hostId}/steam302/container-share`,
      { enabled: false },
    );
    expect(disabled.status).toBeLessThan(400);
    await expect
      .poll(
        async () => inspectSteam302HostState({ address: lanIp, expectLan: false }),
        { timeout: 60_000, intervals: [2_000, 4_000] },
      )
      .toMatchObject({ ok: true, mode: "local" });

    const enabled = await api.put(
      `/api/hosts/${hostId}/steam302/container-share`,
      { enabled: true },
    );
    expect(
      enabled.status,
      `[CHECK][FAIL] 开启容器共享失败: HTTP ${enabled.status} ${JSON.stringify(
        enabled.body,
      ).slice(0, 300)}; remediation: 查看后端日志中 Steam302 hosts 写入/重启阶段`,
    ).toBeLessThan(400);
    await expect
      .poll(
        async () => {
          const res = await api.get(`/api/hosts/${hostId}/steam302/status`);
          return res.body?.data?.phase ?? "UNKNOWN";
        },
        { timeout: 60_000, intervals: [2_000, 4_000] },
      )
      .toBe("RUNNING");
    await expect
      .poll(
        async () =>
          inspectSteam302HostState({
            address: lanIp,
            expectLan: true,
            expectedLanIp: lanIp,
          }),
        { timeout: 60_000, intervals: [2_000, 4_000] },
      )
      .toMatchObject({ ok: true, mode: "lan", lanIp });

    const status = await api.get(`/api/hosts/${hostId}/steam302/status`);
    expect(status.body?.data?.containerShare).toBe(true);
    expect(status.body?.data?.targetIp).toBe(lanIp);

    const state = inspectSteam302HostState({
      address: lanIp,
      expectLan: true,
      expectedLanIp: lanIp,
    });
    check(
      "共享态 hosts 仅保留 #S302-LAN 且指向 WSL LAN IP",
      state.ok && state.mode === "lan" && state.lanIp === lanIp,
      `mode=${state.mode}, targetIp=${state.lanIp}, entries=${state.hostLines.length}`,
      "检查 Auto_Modify_Hosts=0 是否生效；若工具仍覆盖 hosts，重启 Steam302 后再执行容器共享切换",
    );
    expect(state.ok, state.reason).toBe(true);
  });

  test("bridge 容器挂载宿主 hosts + CA 后可解析 GitHub 并完成 HTTPS 请求", async () => {
    test.setTimeout(2 * 60_000);
    const hostState = inspectSteam302HostState({
      address: getWslLanIp(),
      expectLan: true,
      expectedLanIp: getWslLanIp(),
    });
    expect(hostState.ok, hostState.reason).toBe(true);

    // Steam302 自身不挂载 /etc/hosts（见 ADR-0019 修订）；真实消费者是
    // LinuxGSM bridge 容器，因此用临时容器复现 mountHostCerts 的挂载组合。
    const fixture = "e2e-steam302-consumer";
    const fixtureImage = "gameservermanagers/gameserver:dst";
    execWslBash(`docker rm -f ${fixture} >/dev/null 2>&1 || true`);
    const run = execWslBash(
      `docker run -d --name ${fixture} --entrypoint sh -v /etc/hosts:/etc/hosts:ro -v /etc/ssl/certs/ca-certificates.crt:/etc/ssl/certs/ca-certificates.crt:ro ${fixtureImage} -lc "while true; do sleep 3600; done"`,
    );
    expect(
      run.ok,
      `[CHECK][FAIL] 创建 bridge 消费者 fixture 失败: ${run.stderr || run.stdout}; remediation: 确认 ${fixtureImage} 镜像存在（LinuxGSM 镜像自带 curl/getent）且 Docker 可用`,
    ).toBe(true);

    const dns = execWslBash(
      `docker exec ${fixture} sh -lc "getent hosts raw.githubusercontent.com | head -1"`,
    );
    check(
      "bridge 消费者容器内 GitHub DNS 命中宿主 hosts",
      dns.ok && dns.stdout.includes("raw.githubusercontent.com"),
      dns.stdout || dns.stderr,
      "确认宿主机 #S302-LAN 条目存在，且 fixture 挂载了 /etc/hosts:ro",
    );
    expect(dns.ok).toBe(true);
    expect(dns.stdout).toMatch(/raw\.githubusercontent\.com/);

    const curl = execWslBash(
      `docker exec ${fixture} sh -lc "curl -m 10 -sS -I -o /dev/null -w '%{http_code}' https://raw.githubusercontent.com"`,
    );
    const httpCode = curl.stdout.trim();
    const proxyUpstreamFailure = curl.ok && /^50[234]$/.test(httpCode);
    check(
      "bridge 消费者容器内 GitHub HTTPS 返回 2xx/3xx",
      curl.ok && /^[23]\d\d$/.test(httpCode),
      `http=${httpCode || "none"}${curl.stderr ? `, stderr=${curl.stderr}` : ""}`,
      proxyUpstreamFailure
        ? "Steam302 已收到请求，但其上游 GitHub 连接失败；检查主机外网连通性并查看 docker exec steam302 tail -80 /data/caddy.log"
        : "检查宿主 CA bundle、Caddy 代理和 hosts 映射；失败时查看 docker logs steam302",
    );
    expect(curl.ok, curl.stderr || curl.stdout).toBe(true);
    expect(httpCode).toMatch(/^[23]\d\d$/);
    execWslBash(`docker rm -f ${fixture} >/dev/null 2>&1 || true`);
  });

  test("删除平台 hosts 标记后 status 可自愈补写", async () => {
    test.setTimeout(2 * 60_000);
    const password = String(e2eEnv.testHost.auth).replace(/'/g, "'\\''");
    const cleaned = execWslBash(
      `printf '%s\\n' '${password}' | sudo -S -p '' sh -c "sed -i '/#S302-LAN/d; /#S302/d' /etc/hosts" && echo cleaned`,
    );
    expect(
      cleaned.ok,
      `[CHECK][FAIL] 清理 /etc/hosts 标记失败: ${cleaned.stderr || cleaned.stdout}; remediation: 确认 E2E_TEST_HOST_AUTH 可 sudo 提权，且 SSH 主机确实是当前 WSL`,
    ).toBe(true);
    expect(cleaned.stdout).toContain("cleaned");

    const status = await api.get(`/api/hosts/${hostId}/steam302/status`);
    expect(status.status).toBeLessThan(400);
    await expect
      .poll(
        async () => {
          const res = await api.get(`/api/hosts/${hostId}/steam302/status`);
          return res.body?.data?.hostsEntries ?? -1;
        },
        { timeout: 60_000, intervals: [2_000, 4_000] },
      )
      .toBeGreaterThan(0);
  });
});
