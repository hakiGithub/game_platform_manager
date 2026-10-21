# 05: 通用面板的宿主能力面调研（Pterodactyl / Wings / Eggs / MCSManager / PufferPanel）

Status: ready-for-agent
Type: research
Blocked by: None

## 目的

这批是**通用面板**，不是单个游戏的插件。调研目标不是抄 UI，而是回答：一个游戏服管理平台到底该提供
哪些**与游戏无关**的宿主能力面？本平台（core/api/plugin 主应用）哪些已有、哪些缺失、哪些该由插件补。

## 范围与已核验入口

- `pterodactyl/panel`（9358 stars）+ `pterodactyl/wings`（1062）：Egg 体系、安装脚本、备份、文件管理、
  控制台 WebSocket、资源限额、子用户与权限。
- `pelican/panel`（2394）+ `pelican-eggs/eggs`（3091）：Egg 生态的去重与演进，Egg 的
  "启动参数 + 环境变量 + 配置 schema + 安装脚本"四段式契约。
- `MCSManager/MCSManager`（4995）+ `MCSManager/Daemon`：分布式守护、实例模板、计划任务、镜像。
- `pufferpanel/pufferpanel`（1754）、`easy-wi/developer`（246）：轻量面板的功能取舍（个人自用视角）。

## 调研问题

1. **Egg 契约**：Egg 的四段式（startup command / variables / install script / config parser）能否作为
   本平台 `games/{code}.yml` 的增强参考？平台现在的 `configSchema` + `customOperations` + `variables`
   缺了哪一段（例如 config parser 把配置文件键映射到 UI 表单）？
2. **文件管理**：Wings 的文件 API（列出/读/写/重命名/删除/压缩解压/上传分片/Copy）哪些平台已有，
   哪些缺（对照 `InstanceFileService`）。
3. **备份**：Wings 的备份（压缩、限额、下载、恢复、自动轮换）与平台 `InstanceBackup` 的差异。
4. **控制台/终端**：Wings 的 WS 控制台（命令注入、统计推送、日志）与平台现有的
   `/ws/instance/{id}/console`、`/ws/docker/.../attach` 的差异。
5. **子用户与权限**：这些面板的子用户/密钥/权限模型，对本平台（个人自用单租户、JWT + Spring Security）
   是否是**明确不做的**？给出判断依据。
6. **计划任务**：Wings/MCSManager 的 schedule（定时命令/备份/重启）形态，对应平台
   `ScheduledTaskHandler` 扩展点的能力是否够用。

## 输出

`.scratch/plugin-capability-mining/research/04-generic-panel-capabilities.md`，核心产物是一张
"通用宿主能力 / 上游实现 + 出处 / 平台现状（已有/缺失/部分） / 应归属（主应用 or 插件）"四列对照表。

## 验收

- [ ] 四列对照表覆盖：部署模板、文件、备份、控制台、计划任务、资源限额、权限 七个能力域。
- [ ] 每个"缺失"项都给出上游源码路径或文档链接作为出处。
- [ ] 明确写出"不做"清单（多租户 / 计费 / 子用户 API 密钥等）及理由。
