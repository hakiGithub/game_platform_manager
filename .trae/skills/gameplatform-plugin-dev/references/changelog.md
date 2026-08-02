# 版本与维护约定 / Changelog

> 对齐主应用: `backend/` @ 2026-08-02
> 关联 ADR: [ADR-0001 插件菜单归属与 getMenus() 扩展点](../../../../docs/adr/0001-plugin-menu-ownership.md)

## 1. 版本与维护约定

| 项 | 规则 |
|---|---|
| 现行版本 | 本 SKILL 目录始终为最新版，顶部维护版本号与"对齐主应用版本/commit"标注 |
| 物理快照 | 每逢**主版本**变更（插件 API 破坏性改动或重大重构），将旧版另存归档保留 |
| Changelog | 本文件维护；主应用新增/变更 API 时追加条目并升版本号 |
| 升版规则 | minor 变更只更 changelog 与版本号；major 变更才产出新快照文件 |
| 权威来源 | 接口签名、路径常量、异常类均以 `backend/plugin/` 源码为准，新增即补登记 |

## 2. 历史快照

- v2.2.0 及更早版本：原 `docs/PLUGIN_DEV_GUIDE_V2.md`（如存在）
- v3.0.0 / v3.1.0：本 SKILL 目录（拆分整合后的分主题文件）

## 3. Changelog

| 版本 | 日期 | 变更 |
|---|---|---|
| 3.1.0 | 2026-08-02 | ADR-0001 菜单归属权迁移：新增 `getMenus()` 扩展点 + `PluginMenuDeclaration` 强类型声明；废弃 `getManifest()` 的 `features` 字段；宿主 `buildDefaultMenus()` 删除，新增 `buildMenusFromDeclarations()` 仅做校验与序列化（path 唯一性 / `requireInstance` 默认值补全）；SKILL 文档拆分整合：原 `docs/PLUGIN_DEV_GUIDE.md` 单一长文档拆解为 SKILL 目录下多个分主题文件（getting_started / extension_and_menus / extension_client / host_services / task_handler / frontend / exceptions / walkthrough_l4d2 / checklist / sdk_reference / gotchas / changelog） |
| 3.0.0 | 2026-08-02 | 重大重写：新增宿主服务面章（HostQueryService/InstanceQueryService/InstanceFileService/FileAccessService）；新增 PluginManifestVO 契约；修正菜单机制（features→buildDefaultMenus，纠正 v2.2.0 manifest.json 控菜单的误导）；新增前端三形态章（含 standalone 后端与 Wujie 通信）；补全生命周期钩子（onInstanceCreate/Stop/Delete）；ExtensionClient 补全 updateStatus/deleteById/getById/listAll/count/getManagedTables；修正不存在的 PluginDataAccessException；新增基于真实异常类的 FAQ；新增 plugin-l4d2 walkthrough 章；新增版本与维护约定（物理快照 V1/V2 + changelog）；新增验收标准 |
| 2.2.0 | 2026-08-02 | 任务处理器规范（ADR-009/010/014/023/025/026）；ExtensionModel 存储策略；PluginContextHolder |
