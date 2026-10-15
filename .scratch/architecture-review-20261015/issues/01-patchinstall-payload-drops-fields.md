# 01 — PatchInstall 的 headers / includePattern 跨 seam 静默丢失

**Status:** needs-triage
**Type:** task
**来源:** 架构评审 2026-10-15（候选 1）

## 现象

`PatchInstallRequest` 携带 7 个字段，但经任务中心 payload 往返后只剩 5 个。`headers` 与 `includePattern`
被静默丢弃，`PatchInstallExecutor` 读到的永远是 `null`。**不报错、不告警**，只在真实云端分享链接下表现异常。

## 根因

请求↔payload 的字段清单被手写在三处，其中两处已互相矛盾：

| 站点 | 行 | 内容 |
|---|---|---|
| `PatchInstallServiceImpl` | :47-56 | 只 `put` instanceId / url / targetPath / format / sha256 |
| `PatchInstallHandler` | :70-76 | 只 `builder` 重建这 5 个 |
| `PatchInstallExecutor` | :146 / :155 / :272 | 读 `getHeaders()` 与 `getIncludePattern()` |

两个被丢字段的用途：

- `headers` — ADR-0025「主机直连（DIRECT）」的**唯一理由**：云盘分享直链需要 Cookie/UA。
  `CloudInstallService:301-315` 构造了它，`CloudInstallService:321` 还专门分支判断
  `UNSUPPORTED_HEADER_FILE` —— **该错误路径永不触发**，直连实际在无请求头下载。
- `includePattern` — ADR-0026 的产物筛选（如压缩包只取 `*.vpk`）。

零测试覆盖：`backend/core/src/test` 中 `headers` 零命中。

## 修复建议

把请求↔payload 往返收敛为一个 module，字段清单只存在一处：

```
PatchInstallContract.toPayload(PatchInstallRequest) → Map<String,Object>
PatchInstallContract.fromPayload(TaskPayload) → PatchInstallRequest
```

Service 与 Handler 两侧都调它。补一条往返测试：断言每个非 null 字段都存活。

## 验收

- 往返测试覆盖全部 7 个字段，含 `headers` 与 `includePattern`。
- `CloudInstallService` 的 DIRECT 路径实测带上请求头；`UNSUPPORTED_HEADER_FILE` 分支可达。
- 压缩包转存安装（zip/rar → `includePattern=*.vpk`）产物筛选生效。

## 关联

候选 4（任务 payload 类型化）是本票的更彻底形态，本票可先行、且是其前置。
