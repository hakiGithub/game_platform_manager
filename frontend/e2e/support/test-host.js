// 牺牲主机的统一夹具（MERC-24）。
//
// 建/删主机样板原先在四份 spec 里各抄一遍，且都指向同一个 IP。`host_info.ip_address`
// 有数据库级唯一约束，而删除是软删（is_deleted=1 的行仍占唯一键），后端的 `createHost`
// 只在建主机时顺手物理清理同 IP 的软删行。真正的泄漏面是「上一份 spec 删失败、留下
// is_deleted=0 的活主机」：下一份 spec 的 create 会抛「该IP地址已存在」，下游
// lifecycle.spec.js 这类原本绿的用例当场转红——正是 KPI-04 禁的形态。
//
// 唯一性为什么不落在 IP 上：ip_address 同时是 SSH 目标地址。给 127.0.0.1 加任何后缀
// 都会得到一个不可达地址，整组用例会在 POST /hosts/{id}/test 上失败。因此本夹具改为：
//   1. 主机名带每份 spec 唯一的后缀（`e2e-wsl-deploy-host-<suffix>`），便于定位残留；
//   2. 创建撞上「该IP地址已存在」时，复用该 IP 上以 `e2e-` 开头的活动主机并接管清理，
//      不把上一轮的残留继续传给后面的 spec；
//   3. 删除只删本夹具取得的那一条，失败不吞成静默成功（`assertOk` 判信封）。
import { e2eEnv } from "./env.js";
import { assertOk } from "./api.js";

const HOST_PREFIX = "e2e-wsl-deploy-host";

async function findLiveHostByIp(api, ip) {
  const res = assertOk(
    await api.get("/api/hosts?page=1&size=200"),
    "查询主机列表",
  );
  const records = res.body?.data?.records ?? [];
  return (
    records.find(
      (h) => h.ip === ip && String(h.name ?? "").startsWith("e2e-"),
    ) ?? null
  );
}

/**
 * 取得一台可用于本次 spec 的牺牲主机（已做 SSH 连通性探测）。
 *
 * @param {object} api   createApi 客户端
 * @param {string} suffix 每份 spec 唯一的后缀（进主机名）
 * @returns {Promise<{id: number, name: string, adopted: boolean}>}
 */
export async function acquireTestHost(api, suffix) {
  const name = `${HOST_PREFIX}-${suffix}`;
  const created = await api.post("/api/hosts", {
    name,
    ip: e2eEnv.testHost.address,
    sshPort: Number(e2eEnv.testHost.port),
    sshUsername: e2eEnv.testHost.username,
    authType: "password",
    sshPassword: e2eEnv.testHost.auth,
  });
  const code = created.body?.code;
  const ok = created.status < 400 && (code === 200 || code === 0);
  let host = ok ? created.body?.data : null;
  let adopted = false;

  if (!host) {
    const message = String(created.body?.message ?? "");
    if (!message.includes("该IP地址已存在")) {
      throw new Error(`前置主机创建失败: ${JSON.stringify(created.body)}`);
    }
    // 上一份 spec 删失败留下的活动主机：接管它，别让唯一键把后面所有 spec 都堵死。
    host = await findLiveHostByIp(api, e2eEnv.testHost.address);
    if (!host) {
      throw new Error(
        `主机创建报同 IP 已存在，但列表里找不到活动主机: ${message}`,
      );
    }
    adopted = true;
  }

  const hostId = host.id ?? null;
  if (!hostId) {
    throw new Error(`前置主机创建失败: ${JSON.stringify(created.body)}`);
  }
  const tested = await api.post(`/api/hosts/${hostId}/test`);
  assertOk(tested, `主机 ${hostId} 连通性探测`);
  return { id: hostId, name: host.name ?? name, adopted };
}

/** 清掉同名实例：同一名称在不同 host 上可以并存，残留会让列表行定位命中多行。 */
export async function deleteInstancesByName(api, name) {
  const res = await api.get(
    `/api/instances?keyword=${encodeURIComponent(name)}&size=50`,
  );
  for (const rec of res.body?.data?.records ?? []) {
    if (rec.instanceName === name) {
      await api.delete(`/api/instances/${rec.id}`).catch(() => null);
    }
  }
}

/** 释放本 spec 取得的主机。删除失败上抛，不让残留悄悄流到下一份 spec。 */
export async function releaseTestHost(api, host) {
  if (!api || !host?.id) return;
  const res = await api.delete(`/api/hosts/${host.id}`);
  const code = res.body?.code;
  if (res.status >= 400 || (code !== 200 && code !== 0)) {
    // 已被上一轮删掉 ⇒ 本来就干净，不算失败
    if (String(res.body?.message ?? "").includes("不存在")) return;
    assertOk(res, `删除主机 ${host.id}`);
  }
}
