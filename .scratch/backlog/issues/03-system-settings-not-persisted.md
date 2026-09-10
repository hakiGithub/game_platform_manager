# 03 — 系统设置假回环：GET 硬编码 / PUT 空壳，保存永不生效

**Status:** done（2026-09-10 修复，E2E 断言已转正/验证）

## 现象

系统设置页（/system/configuration）修改平台名称等配置，点"保存配置"提示"保存成功"，重新进入页面值回退为默认——**设置从未被保存过**。

## 根因

`SystemController`（backend/core/.../controller/SystemController.java）：

- `GET /api/system/settings`：直接 new 一个 `SystemSettingsVO` 返回**硬编码默认值**，不读任何存储；
- `PUT /api/system/settings`：方法体只有一行 TODO 注释 `// 实际应保存到配置文件或数据库`，直接 `Result.success()`。

前端（settings.vue）按真实接口对接，整条链路是"保存成功"的假回环。E2E 首轮发现。

## 修复建议

配置落库：新表 `sys_setting`（key-value 或单行 JSON，多方言注意 ADR-0015 建表脚本拆分），GET 启动时读库（库空回退默认值），PUT 写库。三个 tab（platform/ssh/docker）同一张表按 type 分组。

## E2E 断言转正

`frontend/e2e/main-app/system/settings.spec.js`：当前用例只断言"保存往返不报错"（文件头记录了本缺陷）；修复后补"重进页面值保持"持久化断言（用例注释里已写明）。
