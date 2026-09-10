# 04 — 数据库初始化竞态：Tomcat 可服务后 ~15s 内所有请求 500

**Status:** needs-triage

## 现象

全新 SQLite 库启动时：Tomcat 先就绪（Started 日志），`DatabaseInitializer`（CommandLineRunner）在 **10~18 秒后**才执行核心建表（sys_user 等）。窗口期内任何 `/api` 请求 500（"no such table: sys_user"）——**全新部署后立刻登录必踩**。

## 根因

CommandLineRunner 在应用 Started 之后才运行，且被插件加载（PluginAutoLoader 逐插件初始化）拖延；期间 Web 层已可服务。

## 复现证据

- E2E 票 01：runner 就绪探测曾用"端口可通"判就绪 → 用例在窗口期登录 500。
- 日志：`Started GamePlatformApplication`（01:14:13）→ `no such table: sys_user`（01:14:21）→ `DatabaseInitializer 建表完成`（01:14:24）。

## 修复建议

把建表/种子/迁移提前到 **Web 服务端口绑定之前**：如 `DataSource` 就绪后立即执行（`BeanFactoryPostProcessor`/`DataSourceInitializer` 或 `ApplicationRunner` 前置到 listen 之前），或至少让 `/actuator/health` readiness 在初始化完成前返回 503。

## E2E 现状

runner 已改用"种子管理员真实登录成功"作为就绪标准绕过（`e2e/runner.mjs` waitForBackendLogin），产品修复后此绕过仍兼容、无需回改。
