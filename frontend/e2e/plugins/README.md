# plugins/ — 插件用例包命名空间

每个插件的 E2E 用例一个子目录，内含该插件子应用页面的全部用例与专属 fixture。

## 为什么用例在主仓库、不在插件仓库

插件的 Java/前端源码在各自独立仓库（`D:\program\ai\plugin-7dtd`、`plugin-dst`、
`plugin-dnf-tw`），但 E2E 全部放在本仓库 `frontend/e2e/plugins/{gameCode}/`：

- E2E 测的是"插件被主应用装上之后的样子"—— Wujie 容器、宿主侧边栏菜单、
  平台 REST 通道、实例部署，这些都在主仓库。
- 放插件仓库会立刻分裂出 4 套 Playwright 工程、4 份 `support/` 与 4 个 Chromium，
  与"不要保留太重的依赖"直接冲突。
- 插件菜单/接口契约一旦漂移，主仓库这套用例会立刻红，正好起到门禁作用。

## 现有用例包

| 包 | 插件 | 菜单数 | 部署方式 | 状态 |
|----|------|--------|---------|------|
| `l4d2/` | `plugin-l4d2` | 18 | docker | 28 用例，连续两轮全绿（3.2m / 3.3m） |
| `sdtd/` | `plugin-sdtd`（`plugin-7dtd`） | 6 | linuxgsm-docker | 8 用例，连续两轮全绿（28.6s / 27.9s） |
| `dst/` | `plugin-dst` | 7 | linuxgsm-docker | 8 用例，连续两轮全绿（1.2m / 1.7m）；写盘强断言待票 10 修复 |
| `dnf_tw/` | `plugin-dnf-tw` | 10 | docker-compose | 待接入（票 07，依赖游戏自带 5 个 MySQL 库） |

## 约定

- 包内自带前置 fixture 说明与清理钩子，跑完不留实例/数据残留
  （插件的扩展资源如白名单不随实例删除，需在 `afterAll` 显式清）
- 效果断言走平台 REST API（`support/api.js`），不直连 SSH
- 主机按 IP 复用（`ensureHostByIp`），只删本次新建的记录 —— 平台对 IP 有全局唯一约束
- 包外框架代码保持零改动 —— 新增插件用例包 = 新增一个子目录
- 不新增 flaky：禁止固定 sleep，一律 `expect.poll` / Playwright auto-wait
