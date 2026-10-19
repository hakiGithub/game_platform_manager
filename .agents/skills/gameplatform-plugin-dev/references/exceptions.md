# 异常体系与 FAQ

> 对齐版本：v3.1.0（ADR-0001）｜ 权威源：`backend/plugin/` 源码

## 1. 异常类层级

```
RuntimeException
├── PluginException (基类，持有 pluginId)
│   ├── PluginLoadException         插件加载失败（如 DDL 执行失败）
│   ├── PluginConfigException       配置异常（如缺少必填配置）
│   └── PluginPathConflictException 控制器路径冲突（含 conflictPath + existingPluginId）
└── ExtensionStoreException (扩展资源存储基类)
    ├── DuplicateExtensionException create 时 name 已存在
    ├── OptimisticLockException     update 时版本号不匹配
    └── ExtensionNotFoundException  get/update/delete 目标不存在
```

> ⚠️ 文档历史曾误用 `PluginDataAccessException`，该类**不存在**于 backend。数据访问异常用 `ExtensionStoreException` 体系。

## 2. 使用框架异常

```java
throw new PluginConfigException(pluginId, "缺少必填配置: rcon_port");
throw new PluginLoadException(pluginId, "DDL 执行失败", cause);
throw new ExtensionNotFoundException("plugin-mygame: 玩家不存在");
```

建议在插件控制器用 `@RestControllerAdvice` + `@ExceptionHandler` 统一处理。

## 3. FAQ（基于真实异常）

| 异常 / 现象 | 触发条件 | 规避方式 |
|---|---|---|
| `PluginPathConflictException` | 两个插件注册相同 URL 路径 | 控制器路径严格按 `/api/plugin/{gameCode}/` 前缀，gameCode 全局唯一 |
| `DuplicateExtensionException` | `create` 时 name 已存在 | 创建前 `get` 判重，或用幂等 key |
| `OptimisticLockException` | `update` 时 version 不匹配（并发修改） | 重新 `get` 拿最新 version 再更新；高并发场景用状态机 |
| `ExtensionNotFoundException` | `get/update/delete` 目标缺失 | 操作前判空，或捕获后返回业务错误 |
| `PluginConfigException` | 缺少必填配置项 | `onLoad` 中校验 `PluginContext.getCustomProperties()` |
| `PluginLoadException` | 加载阶段失败（DDL、依赖缺失等） | `onLoadError` 钩子记录原因，检查依赖 gameCode 是否已加载 |
| 菜单点击白屏 | 子应用路由 path 与 `getMenus()` 声明的 path 不对齐 | `frontend/src/router/index.ts` 的 path 必须与 `getMenus()` 声明的 path 一致（ADR-0001） |
| `IllegalStateException`: 菜单 path 重复 | `getMenus()` 返回多个相同 path | 同插件内 path 必须唯一，宿主 `buildMenusFromDeclarations` 会校验 |
| `IllegalStateException`: 菜单 path 为空 | `PluginMenuDeclaration.path` 为 null 或空白 | 每个 `PluginMenuDeclaration` 必须显式声明非空 path |
| `IllegalArgumentException`（文件） | `InstanceFileService` 路径含 `..` 越界 | relativePath 仅用正斜杠相对路径，禁止 `..` |
| 任务卡死不结束 | 未检查 `isCancelled`/`isTimeout` | 循环中定期检查，命中即 return |
