# 缺陷/增量 Backlog（E2E 全量演练产出）

来源：`.scratch/ui-e2e-automation/` 九票全量 UI 自动化演练（整轮 PASS，2026-09-10）。
本目录收录演练发现但**未在当轮修复**的产品缺陷与覆盖增量，供 `/grill` / `/triage` 领取。

## 产品缺陷（按影响排序）

| 票 | 一句话 | 现状 |
|----|--------|------|
| [01](issues/01-backup-async-executor-stuck.md) | 备份异步执行器不推进，备份功能不可用 | 缺陷，E2E SKIP 挂起 |
| [02](issues/02-docker-addons-invisible-to-map-list.md) | Docker 实例 addons 对宿主机地图列表不可见，地图管理半失效 | 缺陷，E2E SKIP 挂起 |
| [03](issues/03-system-settings-not-persisted.md) | 系统设置假回环（GET 硬编码 / PUT 空壳） | 缺陷，E2E 断言降级 |
| [04](issues/04-db-init-race-after-tomcat-up.md) | 数据库初始化竞态：全新安装后 ~15s 内所有请求 500 | 缺陷，E2E 已绕过 |

## 覆盖增量（l4d2 与环境）

| 票 | 一句话 |
|----|--------|
| [05](issues/05-l4d2-subapp-secondary-pages-cases.md) | l4d2 子应用次要页面渲染用例补齐（约 8 条） |
| [06](issues/06-rcon-deep-ops-with-live-players.md) | RCON 深度操作用例（踢人/封禁/难度，需真实玩家在线） |
| [07](issues/07-linuxgsm-github-blocked-environment.md) | 环境阻塞：容器内 GitHub 不可达，dst/sdtd 启停断言待转正 |

## 已在演练当轮修复的（存档于票 06/09 备注，勿重复立项）

- DockerAdapter 字符串端口/卷映射 ClassCastException（docker 部署类型不可用）
- 创建备份字段错位（name/type → backupName/backupType），备份创建接口不可用
- 同 IP 主机软删后无法重新纳管（ip_address 唯一约束 + 软删除）

## 使用方式

领取任一票：`/grill .scratch/backlog/issues/<NN>-<slug>.md`。修复后按票内
"E2E 断言转正"小节把对应 SKIP 断言转回正式断言，跑
`E2E_FAST=1 npm run e2e:round` 验证整轮仍 PASS。
