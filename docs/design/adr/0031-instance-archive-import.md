# ADR-0031: 实例归档导入（单压缩包上传 + 主机解压 + 目录级落位）

- 状态：Accepted
- 日期：2026-10-18
- 关联：[ADR-0002](0002-main-app-plugin-scope-isolation.md)、[ADR-0021](0021-host-environment-tool-install.md)、[ADR-0026](0026-host-tooling-container.md)

## Context

L4D2 插件安装当前把归档下载到平台、在平台进程内解压，再对归档内每个文件调用
`InstanceFileService.uploadLocalFile`。代表性 965 文件、约 165MB 的归档在局域网
环境下仍耗时 337753ms，瓶颈不是带宽，而是逐文件 SFTP 建连、父目录检查与 Docker
临时复制。现有 `HostToolingService.extractArchive` 只接受主机绝对路径，不能表达
“把归档导入某个实例的目标目录”，也没有 staging、原子落位与失败回滚语义。

## Decision

1. **新增实例级语义接口 `InstanceArchiveImportService`**。请求只包含实例 ID、
   本地单插件 ZIP、staging 与目标实例相对路径、上限和关键文件；不暴露 Docker
   命令、镜像名、容器 ID 或任意主机命令。

2. **固定六阶段进度**：上传 0–15、解压 15–40、导入 40–65、校验 65–80、
   原子落位 80–95、清理 95–100。每阶段边界检查取消；取消后不写元数据。

3. **原始压缩包只上传一次**。主应用把 ZIP 推到主机临时目录，解压优先主机原生
   工具，缺失时复用 `platform-tools` 工具容器。插件不自行解压。

4. **staging 与最终目录位于同一文件系统**。目标在可确认 bind mount 时直接写
   宿主挂载源下的 staging。命名卷的宿主 `Mountpoint` 通常只允许 Docker root
   访问，因此不要求 SSH 用户穿透宿主卷目录；命名卷或无法确认挂载时统一使用
   一次目录级 `docker cp` 导入容器 staging，再在容器内 `mv` 落位。

5. **先校验、后落位**。解压后校验单插件根结构、路径穿越、符号链接、硬链接、
   条目数、总字节数和关键文件。旧目标目录先改名为回滚目录，新目录原子落位；
   随后执行可选的提交动作（如写入 `plugin.yaml`）。提交成功才删除备份；动作
   失败时恢复旧目录，避免文件目录与元数据不一致。

6. **结构化错误**。`InstanceArchiveImportException` 携带阶段、路径、命令类别、
   退出码和建议，消息可直接展示给用户。

## Consequences

- 单插件 ZIP 从 N 次文件上传降为一次原始归档上传加一次目录级导入。
- 平台不再需要把 165MB 归档在进程内解压；插件侧逐文件路径保留为兼容回退。
- Docker 挂载识别失败时仍有一次 `docker cp` 兜底，但不会退化为逐文件复制。
- staging 与回滚目录会短时占用额外磁盘；启动清理逻辑回收遗留 `.install-*`。
- 首版只覆盖单插件 ZIP；多插件归档、VPK、SMX 与不安全结构继续走旧路径。

## Alternatives

- **继续逐文件上传**：被否，实测瓶颈来自文件数与 Docker 复制次数，不是带宽。
- **插件直接执行 Docker 命令**：被否，违反 ADR-0002/0026 的宿主能力信任边界。
- **扩展 `HostToolingService.extractArchive` 接受实例路径**：被否，该接口面向
  主机文件工具；实例路由、回滚和进度属于新的实例生命周期语义。
- **平台解压后打包目录再上传**：被否，仍需平台承载大归档，且没有减少主机侧
  的目录落位工作。
