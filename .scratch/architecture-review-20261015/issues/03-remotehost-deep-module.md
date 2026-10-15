# 03 — 45 处 SSH 调用绕过 DeploymentAccess seam，直接传 7 元组

**Status:** needs-triage
**Type:** task
**来源:** 架构评审 2026-10-15（候选 2）

## 现象

上一轮评审（2026-08-13 候选 2）已建立 `DeploymentAccess` 深模块收敛「deployType 分类 + Host→SSH 凭据解析」。
但该 seam **只覆盖了 credentials/connect，没有把传输层包进去**，于是调用方从旁边绕回来：

```
45 处 sshUtil.executeCommand(host, port, user, key, pwd, cmd, timeout)
```

分布：`InstanceFileServiceImpl` 11 · `HostsFileRefresher` 8 · `DeployService` 6 · `BackupServiceImpl` 5
· `SudoAwareSshRunner` 4 · 其余 8 个文件各 1-2。

## 根因

`SshUtil` 的 interface 宽达 **7 参数 × 11 方法**，且它本身就是 seam 的所在地——
调用方直接传 7 元组，等于把「凭据如何解析」重新摊给每个调用方。
16 个文件里每一个都得自己记得 `getHost → 判空 → credentials → 传包`。

两个后果：

- 凭据解析规则（ADR 明文禁止把用户名当密码、私钥优先密码回退）在 seam 外失效。
- `DeployService:1026` 自带一条本地策略（有密码时丢弃私钥）覆盖 `DeploymentAccess` 的行为——
  同一个规则，两个模块各说各话。
- 另有第二个解凭据的模块 `SudoAwareSshRunner`（仅 5 个使用方），与 `DeploymentAccess` 平行存在。

## 修复建议

`RemoteHost` 深模块以 **hostId 为唯一入口**，承载凭据解析、提权策略、超时、重试与在线状态对账；
`SshUtil` 降为它的内部实现细节（内部 seam，供本模块自身测试注入假传输）。

```
remoteHost.exec(hostId, command)          → CommandResult
remoteHost.execPrivileged(hostId, command)
remoteHost.upload(hostId, local, remote)
```

调用方写 `remoteHost.exec(hostId, cmd)`，不再接触 7 元组。合并 `SudoAwareSshRunner` 进同一模块。

## 验收

- `SshUtil` 不再被 core 业务代码直接引用（只剩 `RemoteHost` 与其自身测试）。
- 凭据解析与提权策略各只有一处实现，有单测（注入假传输）。
- `DeployService` 的本地「丢弃私钥」策略被移除或上提为 `RemoteHost` 的显式选项。

## 关联

候选 7（HostSession）是本票在 HostService 一侧的同形问题，可一并考虑。
