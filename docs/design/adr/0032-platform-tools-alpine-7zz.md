# ADR-0032: platform-tools Alpine 镜像与 7zz 解压实现修订

- 状态：Accepted
- 日期：2026-10-19
- 部分 Supersedes：[ADR-0006](0006-patch-install-decision-tree.md)、[ADR-0021](0021-host-environment-tool-install.md)、[ADR-0025](0025-l4d2-cloud-map-install.md)、[ADR-0026](0026-host-tooling-container.md)

## Context

旧 ADR 记录 `platform-tools` 预装 `unrar`，并以 `unrar x` 解压 RAR。Alpine 软件源
不提供 `unrar` 包；实际镜像改为预装 7zip 与 bsdtar，并由 `7zz` 读取 RAR。若直接
改写已冻结的 ADR，会破坏本目录的历史追溯约定。

## Decision

1. `platform-tools` 继续基于 Alpine，预装 `7zz`/`7z`、bsdtar、curl、wget、rsync。
2. RAR 使用 `7zz x`，7z 使用 `7z x`，其余归档使用 bsdtar。
3. 工具容器以 SSH 用户的 UID/GID 运行，避免解压产物归 root 所有。
4. ADR-0006、0021、0025、0026 保持原样；本 ADR 仅取代其中关于工具镜像包名、
   RAR 命令和容器用户的事实描述。

## Consequences

- 镜像不依赖 Alpine 不存在的 `unrar` 包，构建可重复。
- 解压产物可由 SSH 用户清理和移动，满足实例内原子落位的权限要求。
- 阅读旧 ADR 的实现细节时需同时参考本 ADR。

## Alternatives

- **继续安装 unrar**：Alpine 默认源无该包，需要引入额外仓库或自编译，增加供应链面。
- **直接修改旧 ADR**：违反 ADR 冻结约定，丢失历史决策边界。
