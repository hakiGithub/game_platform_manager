# ADR-0004: 主机局域网标识（isLanHost）引入

| 字段 | 值 |
|------|----|
| 状态 | Accepted |
| 日期 | 2026-08-09 |
| 决策者 | User (grill-with-docs session) |
| 关联 | [ADR-0002](0002-main-app-plugin-scope-isolation.md)（范围隔离） |
| Supersedes | 无 |

## 背景（Context）

### 业务诉求

后续将实现补丁/资源安装能力：插件根据资源 URL 将补丁推送到目标主机或容器内指定目录。推送策略需根据主机网络位置差异化处理：

- **宿主机场景（场景一）**：SSH 探测目标主机能力（curl/wget/tar/gzip 等工具集 + /tmp 空间）→ 判断能否远程下载和解压 → 能则 SSH 执行远程下载脚本，不能则平台代劳（下载+解压+推送）
- **容器场景（场景二）**：挂载目录 → 平台解压后推送到宿主机挂载点；非挂载目录 → 平台解压后通过 `docker cp` 推送到容器内部
- **补丁格式**：压缩包需解压，非压缩包直接推送；补丁包尽量统一为 tar.gz；使用临时目录、校验、备份、回滚；批量执行时控制并发和失败重试

### 核心约束

平台跨公网推送大文件（如游戏补丁、SourceMod 包）既慢又不稳，公网主机不应让平台代劳大量推送。需要一个明确字段标记"平台与该主机之间的网络信任/带宽关系"，作为"平台代劳"的许可开关。

## 决策（Decision）

在 `host_info` 表新增 `is_lan_host` 列；类型 `BOOLEAN DEFAULT 0`；归属 `Host` 实体；作为"平台代劳下载/解压/推送补丁"的硬开关。

### 字段语义契约

```
isLanHost = true  → 平台可代劳下载/解压/推送补丁到该主机（含容器场景）
isLanHost = false → 目标主机必须能自治（curl/wget + 解压工具齐全）；
                    不能自治时直接报错，平台不跨公网代劳
```

### 字段定义

| 维度 | 取值 |
|------|------|
| 数据库列 | `is_lan_host BOOLEAN DEFAULT 0` |
| Java 字段 | `private Boolean isLanHost;` |
| 默认值 | `false` |
| 前端控件 | `el-switch`（放在"IP 地址"表单项下方） |
| 列表展示 | 仅 `isLanHost=true` 时在主机名旁显示 `el-tag type="success"` "局域网" |

### 适用范围

- **场景一（宿主机）**：`isLanHost` 作为硬开关，false 时目标不能自治则报错
- **场景二（容器）**：与场景一相同，容器场景同样适用 `isLanHost`；两条推送路径（挂载目录 / docker cp）都要经过宿主机，与宿主机共享网络位置

### 归属层

字段归属 `Host` 实体（非任务参数）。理由：
- `isLanHost` 是主机的网络位置属性，客观稳定，与"这次补丁要不要走代劳"是两回事
- 避免用户每次安装插件都要重复配置
- 与 `Host` 现有 `sshPort` / `tags` / `osType` 等主机属性一致
- 下游通过 `HostQueryService.getHostById()` 拿到 `HostVO`（含 `isLanHost`）即可消费

## 后果（Consequences）

### 正面

- 字段已就位，后续 `PatchInstallService` 可直接消费，无需再改 schema 和实体
- 前端列表展示"局域网"标签，用户可提前标注主机网络位置
- 默认 false 符合谨慎原则：新主机默认按公网处理，平台不会跨公网误推送

### 负面

- 字段引入后到 `PatchInstallService` 落地前，`isLanHost` 仅作展示用途，不产生实际行为变化
- 用户需手动标注每台主机是否为局域网，无自动探测（未来可扩展）

### 范围边界

**本次仅引入字段骨架**：`Host` 实体 + DTO/VO + DB 迁移 + schema.sql + 前端表单/列表 + 后端单测。

**本次不实施**：`PatchInstallService` 接口与实现、补丁安装 UI、探测脚本执行链路、决策树、回滚器等。这些留待后续单独的 grill + 实施周期。

## 备选方案（Alternatives）

### 备选 1：三态枚举（LAN / WAN / UNKNOWN）

**否决理由**："是否是局域网主机"是二元判断，三态化是过度设计；UNKNOWN 状态无实际消费场景，反而增加决策树分支。

### 备选 2：任务级参数（每次补丁任务独立指定）

**否决理由**：违背"主机网络位置是稳定属性"的领域语义；用户每次安装插件都要选一次，负担重且易错；与 `Host` 承载其他主机属性的模式不一致。

### 备选 3：默认 true（新主机默认按局域网处理）

**否决理由**：风险大。用户添加公网主机时若忘改默认值，平台会误以为是局域网主机，跨公网推大文件，潜在网络/性能问题。默认 false 让用户必须显式启用代劳能力，更安全。

### 备选 4：补丁服务一次成型（字段 + PatchInstallService 同步实施）

**否决理由**：单次改动过大（20-30 文件，含下载器/解压器/决策树/回滚器/并发池等新基础设施），review 困难，回归风险高；且当前无具体插件调用方，接口形状无法基于真实需求收敛。采用增量交付：先骨架后行为。

## 未来工作（Future Work）

- `PatchInstallService` 接口（plugin SDK 模块）+ 实现（core 模块）将基于此字段构建决策树
- 探测脚本（已在用户输入中提供原型）将作为 `PatchInstallService.probeCapabilities(host)` 的实现基础
- 决策树分支：
  - 目标能下载且能解压 → SSH 远程下载+解压（自治）
  - 目标能下载但不能解压 → `isLanHost=true` 时平台 SFTP 推送压缩包后远程解压或平台解压后推送散文件；`false` 时报错
  - 目标不能下载但能解压 → `isLanHost=true` 时平台下载后 SFTP 推送压缩包，目标解压；`false` 时报错
  - 目标不能下载且不能解压 → `isLanHost=true` 时平台下载+解压+推送散文件；`false` 时报错
- 容器场景决策：
  - `isLanHost=true` 且挂载目录 → 平台下载/解压 → SFTP 推到宿主机挂载点
  - `isLanHost=true` 且非挂载目录 → 平台下载/解压 → SFTP 推宿主机临时目录 → docker cp 进容器
  - `isLanHost=false` → 优先尝试容器内 curl/wget 自治（docker exec）；不能自治则报错

## 相关文档

- [ADR 索引](README.md)
- [ADR-0002 主应用与插件范围隔离规约](0002-main-app-plugin-scope-isolation.md)
- [术语表 isLanHost](glossary.md)
