# 01 — E2E 工程基建与最小打通

**What to build:** E2E 用例集的地基与第一条 tracer bullet：Playwright + Chromium 工程就位（放主前端项目之下），"受管模式" runner 一条命令完成——临时 SQLite 起后端 → 起前端 → 执行用例 → HTML 报告 → 清理（停进程、删临时库）。另支持"附着模式"（经 `E2E_BASE_URL` 指向已运行环境）。目录约定按**应用域 → 页面 → 功能**两级组织，预留**主应用**与**插件用例包**两个一级命名空间（为后续其他插件的用例接入立好位置），并建立公共 helper 层：登录态复用、平台 API 断言客户端（带 JWT）、SKIP 机制挂点。

**Blocked by:** None — can start immediately

**Status:** done

- [x] 一条命令完成"起栈 → 跑用例 → 报告 → 清理"，重复执行不留残留进程/临时库
- [x] 最小用例（打开登录页 → admin 登录成功 → 进入工作台）通过
- [x] 附着模式可用：同一最小用例指向已运行环境跑通
- [x] 目录约定含主应用与插件用例包两个命名空间，新增一条用例不需要改框架代码
- [x] 失败用例自动留存截图与 trace
- [x] 用例头注释声明前置条件、步骤、通过标准（本票以最小用例示范该格式）

> 实施备注：后端就绪标准 = 种子管理员真实登录成功（`DatabaseInitializer` 在 Tomcat 可服务后约 10-18s 才建核心表，端口通≠可开测）。发现的存量问题：`package-lock.json` 有 15 处下载地址指向不可达的内网 Nexus，已替换为等价的 npmmirror 地址（content-addressed，integrity 不变）。
