# 07 — HostServiceImpl 四处重复「连主机→写在线状态」，错误契约自相矛盾

**Status:** resolved
**Type:** task
**来源:** 架构评审 2026-10-15（候选 7）

## 现象

`HostServiceImpl` 有四个方法，各自重复同一套四步动作
（`selectById → 判空 → credentials → ssh 操作 → updateOnlineStatus(0|1) → 组装 VO`）：

| 方法 | 行 |
|---|---|
| `testConnection` | :242-276 |
| `refreshStatus` | :279-330 |
| `checkPort` | :405-439 |
| `getHostResourceInfo` | :442-518 |

**同一个 SSH 调用，两种错误契约**：`refreshStatus` 失败写 `0`（离线），
`getHostResourceInfo` 失败返回 `null`。`refreshStatus` 与 `getHostResourceInfo` 还抓取**同一份**
`SshUtil.ResourceInfo` 再组装成不同形状。

## 根因

SSH 调用与数据库副作用（写在线状态）被焊死在同一个方法体里，
所以「什么时候把主机标离线」这句话在四处各写一遍且不一致，
且**无法按接口测试**——想验证「失败时怎么标记主机」只能真连一台主机。

附带：`HostServiceImpl:17` 导入的是宿主内部实现 `SshTunnelManager`（住在 plugin 包），
而面向插件的 SDK 契约是 `SshTunnelService` —— core 顺着内部类型穿过了自己的 seam。

## 修复建议

`HostSession` 深模块以 hostId + 操作为入口，内部承担凭据、超时、在线状态对账与统一错误契约：

```
<T> T run(hostId, HostOp<T> op)
```

四个公开方法退化为一行委托。

## 验收

- 在线状态对账规则只有一处实现，可用假 SSH 传输单测。
- 错误契约统一（消除 `null` / 写 `0` 双标）。
- `HostServiceImpl` 不再引用 `SshTunnelManager`（改用 SDK 的 `SshTunnelService`）。

## 关联

与票 03（RemoteHost）是同一组行为的两个入口，建议一并设计。

## Answer

- 新增 `HostSession`，统一封装“加载主机 → 执行操作 → 按可达性对账在线状态”的规则；`HostServiceImpl` 的测试连接、刷新状态、端口检查、资源信息四处入口均委托该模块。
- 在线状态写入规则只保留在 `HostSession`：普通操作成功写在线、`HostAccessException` 写离线；`probe` 支持显式可达性判断。
- `HostServiceImpl` 不再引用宿主内部 `SshTunnelManager`，改为依赖 `HostTunnelLifecycle` 端口；`SshTunnelManager` 实现该端口，删除主机时仍联动清理隧道。
- 新增 `HostSessionTest` 覆盖成功、可达性 false、访问异常与主机不存在；完整后端测试 832 条通过。
