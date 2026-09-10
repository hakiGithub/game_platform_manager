# 02 — Docker 实例 addons 对宿主机地图列表不可见，地图管理半失效

**Status:** done（2026-09-10 修复，E2E 断言已转正/验证）

## 现象

Docker 部署的 l4d2 实例：地图**上传成功**（map-upload 任务 COMPLETED，VPK 经 VpkParser 真实解析+裁剪），但**地图列表为空**（"暂无数据"），换图选择器的三方地图也为空——上传的地图"消失"。

## 根因

`VpkParserService`/地图列表经 SSH 读取宿主机 `installPath/left4dead2/addons`；Docker 部署的 addons 在**容器/卷内**（cm2network 镜像无宿主机映射），宿主机路径不存在：日志 WARN "addons 目录不存在或不是目录: left4dead2/addons"。

## 复现证据

- E2E：`frontend/e2e/plugins/l4d2/plugin-subapp.spec.js` 地图用例（上传 COMPLETED 后列表断言条件跳过）。
- 后端日志：`MapService - 获取地图列表` + `VpkParserService - addons 目录不存在`（票 07 整轮）。

## 修复建议

1. Docker 实例的 addons 读写统一走容器内路径解析：写入 `configInfo.containerWorkDir=/app` 的先例已在（LinuxGsmDockerAdapter），l4d2 Docker 适配器同样记录容器名/容器内 addons 路径到 runtimeMetadata，文件服务按容器路径走 `docker exec` 读写；或
2. 元数据 `docker.volumes` 声明把 addons 映射到宿主机 installPath（需要迁移存量容器数据，动用户现有实例，成本高）。

## E2E 断言转正

`plugin-subapp.spec.js` 地图用例：删除"列表可见性 SKIP"块，恢复列表包含 `e2e-test-map.vpk` 断言 + 换图到上传地图的真实验证（RCON 密码问题见票 06 备注）。
