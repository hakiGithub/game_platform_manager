# ADR-0026: 工具容器宿主能力（platform-tools 临时容器通用化）

- 状态：Accepted
- 日期：2026-09-15
- 关联：[ADR-0006](0006-patch-install-decision-tree.md)（决策 6 格式全集增补）、[ADR-0021](0021-host-environment-tool-install.md)（platform-tools 镜像）、[ADR-0025](0025-l4d2-cloud-map-install.md)（云盘地图安装）

## Context

云盘地图包常见 rar/7z 压缩（实测天翼分享产物为 rar）。此前 rar 在直连路径不可用（PatchInstall 无该格式），只能平台中转 + 插件 Java 侧解压（sevenzipjbinding），大文件双倍带宽。`platform-tools` 镜像（ADR-0021）已预装 unrar/p7zip/bsdtar/curl/wget/rsync，PatchInstall 内部已有 `docker run --rm` 临时容器机制（用完即销毁），但它是 private 实现、不支持 rar/7z、无产物筛选。多个插件（l4d2 地图中心为先例）存在"主机侧解压压缩包"的共性需求。

## Decision

1. **PatchInstall 格式增补**：`PatchFormat` 增加 RAR、SEVEN_Z（.rar/.7z/.001 分卷不覆盖）。解压优先原生工具，缺失时 `docker run --rm <tooling-image> unrar x / 7z x`（挂载压缩包父目录与解压目录），容器用完即销毁；两路都不可用则任务失败（插件可回退中转）。

2. **includePattern 产物筛选**：`PatchInstallRequest.includePattern`（逗号分隔 glob，大小写不敏感）。提供时执行流程改为：① 容器/原生侧解压到临时目录 → ② `find -iname <pattern>` 列产物清单 → ③ 主应用按清单 basename 预判覆盖并 backup（ADR-0006 决策 7 照旧）→ ④ `find + mv` 平铺落位 targetPath → ⑤ 空清单报错"压缩包内未找到匹配文件"。不提供时保持全量解压语义。

3. **对插件的暴露形态：语义化能力，非裸容器执行**。plugin SDK 新增 `HostToolingService`，首版一个方法：
   `extractArchive(hostId, remoteArchivePath, destDir, includePattern)`——主机侧解压（原生优先、工具容器兜底），返回落位文件清单。内部实现归主应用（镜像选择、命令拼装、临时目录清理、审计日志），插件接触不到 docker 命令与镜像名。调用方审计同 RCON/CloudDrive 模式（绑定 pluginId 的工厂注入子容器）。
   未来同类需求（hash 校验、编码转换等）以新增语义方法扩展，逐个过 ADR，不开放任意命令执行。

4. **配置与销毁**：镜像沿用 `game-platform.patch.tooling-image`；临时目录在任务 finally 中清理；容器 `--rm` 即时销毁，宿主机不残留中间产物。镜像拉取失败按可重试错误处理（提示主机自 pull，ADR-0021）。

## Consequences

- 云盘地图 rar/7z 直连路径打通：大压缩包不再过平台中转，文件不落地平台磁盘。
- 插件获得安全的"借用工具镜像"能力，插件无需各自打包解压库（sevenzipjbinding 保留于现有插件为 RELAY 兜底，新插件可不再引入）。
- includePattern 的两段 find 引入一次额外遍历（解压目录内，成本可忽略）。
- `HostToolingService` 是新增宿主能力面，后续方法扩展需 ADR 记录。

## Alternatives

- **SDK 暴露裸 `runToolContainer(命令, 挂载)`**：被否——等价于把纳管主机的 root 级执行权交给插件（任意挂载读写），违背宿主能力信任模型（ADR-0002/0016 语义）。
- **插件自建 docker 客户端拉容器**：被否——Docker SPI 归主应用，插件重复实现镜像/清理/审计无收益。
- **保持 rar 平台中转**：被否——大文件双倍带宽已实测（165MB 过平台），且与直连优先的 ADR-0025 决策相悖。
