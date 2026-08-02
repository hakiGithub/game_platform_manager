# ADR-0003: 废弃 plugin-l4d2-standalone 模块

| 字段 | 值 |
|------|----|
| 状态 | Accepted |
| 日期 | 2026-08-03 |
| 决策者 | User (grill-with-docs session) |
| 关联 | [ADR-0002](0002-main-app-plugin-scope-isolation.md)（范围隔离） |
| Supersedes | 无 |

## 背景（Context）

### 模块现状

`plugin-l4d2-standalone` 是 L4D2 插件的独立运行模式，允许插件脱离主应用以独立 Spring Boot fat JAR 运行。设计初衷是为不部署主应用的用户提供"只跑 L4D2 管理后台"的轻量方案。

**模块规模**：
- 后端：28 个文件（21 个 Java 类 + 配置 + 测试 + 资源），含独立的 `L4D2StandaloneApp` 启动类、5 个 `Standalone*Controller`/`Repository`/`Service`、3 个 `Standalone*ExtensionClient`/`DdlTemplate` 适配层
- 前端：`runtime.ts` 的 `standalone` 模式分支、`InstanceSelect.vue` 实例选择页、`api/standalone.ts` API 封装、router `/ui/` 前缀分支、`main.ts` 的非 Wujie 挂载逻辑、`plugin.ts` 的 localStorage 持久化

### 废弃理由

1. **无实际使用证据**：构建脚本（`rebuild-restart.ps1` / `build-and-deploy.ps1`）**从未构建 standalone**，只构建 `plugin-l4d2-core`；无任何运行实例记录
2. **维护负担高**：standalone 模块需独立实现 `HostQueryService`/`InstanceQueryService`/`FileAccessService`/`ExtensionClient`/`DdlTemplate` 5 套宿主服务适配层，每次主应用接口变更都需同步
3. **与 ADR-0002 冲突**：standalone 模式要求主应用感知插件（`plugin.l4d2` 配置、`l4d2_*` 表），违反范围隔离规约
4. **架构演进方向不符**：项目以"主应用 + Wujie 微前端插件"为核心架构，standalone 是早期探索的备选路径，现已明确不走此方向
5. **前端复杂度**：3 种运行模式（wujie/standalone/dev）的分支逻辑遍布前端代码，废弃 standalone 后简化为 2 种（wujie/dev）

## 决策（Decision）

**物理删除 `plugin-l4d2-standalone` 后端模块和前端 standalone 模式代码。**

### 后端删除范围

- `backend/plugin-l4d2/plugin-l4d2-standalone/` 整个目录（28 个文件）
- `backend/plugin-l4d2/pom.xml` 移除 `<module>plugin-l4d2-standalone</module>` 子模块声明

### 前端删除范围

- `frontend/src/api/standalone.ts`（standalone API 封装）
- `frontend/src/pages/InstanceSelect.vue` + `InstanceSelect.test.ts`（实例选择页）
- `frontend/src/utils/runtime.ts` 移除 `standalone` 模式分支，简化为 `'wujie' | 'dev'`
- `frontend/src/router/index.ts` 移除 `/ui/` 路由前缀分支和 `/instance-select` 路由
- `frontend/src/main.ts` 移除非 Wujie 挂载的 standalone 分支（保留 dev 模式直接挂载）
- `frontend/src/stores/plugin.ts` 移除 `loadPersistedInstance`/`changeInstance`/`syncFromStandalone`/`persistInstance`/`INSTANCE_STORAGE_KEY`（localStorage 持久化是 standalone 专属）
- `frontend/src/types/index.ts` 移除 `StandaloneInstance` 接口
- `frontend/src/layouts/MainLayout.vue` 移除"切换实例"按钮和 `goToInstanceSelect` 函数
- `frontend/src/pages/Dashboard.vue` 移除实例不存在时跳转 `/instance-select` 的逻辑

### 保留范围

- `Plugins.vue` 第 435 行的 `t.includes('standalone')` 判断**保留**——这里的 `standalone` 指 L4D2 服务端部署类型（非插件运行模式），与本次废弃无关
- `frontend/src/api/index.ts` 第 67 行注释"Wujie/standalone 模式行为一致"**改为**仅描述 Wujie 模式（后续清理）

## 后果（Consequences）

### 正面

- **代码量减少**：删除 28 个后端文件 + 简化 8 个前端文件，消除 5 套宿主服务适配层的维护负担
- **架构清晰**：运行模式从 3 种简化为 2 种（Wujie + dev），前端分支逻辑大幅减少
- **符合 ADR-0002**：不再需要主应用为 standalone 模式托管 `plugin.l4d2` 配置和 `l4d2_*` 表
- **构建简化**：`plugin-l4d2` 聚合层只有一个子模块 `plugin-l4d2-core`

### 负面

- **独立运行能力丧失**：无法再以 `java -jar plugin-l4d2-standalone-*.jar` 方式独立部署 L4D2 管理后台
- **缓解**：有此需求的用户可部署完整主应用，或基于 SKILL 的 `plugin-mygame` 示例自行实现 standalone 模式

### 中性

- **git 历史可追溯**：废弃代码在 git 历史中保留，未来若需恢复可 cherry-pick
- **存量部署**：已运行的 standalone 实例不受影响（代码删除只影响新构建）

## 备选方案（Alternatives）

| 方案 | 描述 | 否决理由 |
|------|------|---------|
| 标记废弃但保留代码 | pom.xml 注释 `<module>`，README 加 DEPRECATED 头 | 仍有维护负担，构建时仍可能被误启用 |
| 移到 archive 目录 | 移到 `backend/archive/plugin-l4d2-standalone/` | 增加目录噪音，且代码脱离原上下文后参考价值降低 |
| 保留前端 standalone 代码 | 后端删除，前端保留分支 | 前端 standalone 模式无后端支持，成为死代码 |

## 迁移记录

| 日期 | 操作 | 提交 |
|------|------|------|
| 2026-08-03 | 物理删除 `plugin-l4d2-standalone/` 后端模块（28 文件） | 本提交 |
| 2026-08-03 | `plugin-l4d2/pom.xml` 移除子模块声明 | 本提交 |
| 2026-08-03 | 删除前端 standalone 专属代码（3 文件删除 + 8 文件简化） | 本提交 |
