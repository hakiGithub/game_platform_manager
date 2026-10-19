---
name: gameplatform-e2e
description: GamePlatform 平台 E2E 测试与 Docker 部署验证技能。执行/编写/调试 frontend/e2e 下的 Playwright 用例（受管/附着模式、WSL 牺牲主机、插件挂载部署），排查测试与环境故障。当用户要求跑 E2E、写 UI 用例、部署镜像到 WSL/主机验证、或 E2E 用例失败需要排查时使用。
---

# GamePlatform E2E 测试与部署验证

## 适用场景

- 运行/调试 `frontend/e2e/` 下的 Playwright 用例
- 为新功能编写 E2E 用例（含 l4d2 插件子应用）
- 部署平台镜像到 WSL/远程主机并验证
- 排查 E2E 失败（登录/上传/导航/状态断言类）

## 执行方式（一条命令）

```bash
cd frontend
E2E_FAST=1 npm run e2e:round    # 整轮：六组保序 + 通过判定（除显式 SKIP 外全绿 = PASS）
npm run e2e                     # 受管模式：临时 SQLite 起栈 → 跑用例 → 报告 → 清理
npm run e2e -- --attach         # 附着模式：对已运行环境跑用例
npm run e2e -- e2e/main-app/host --reporter=list   # 只跑某个目录/用例
npm run e2e:report              # HTML 报告（截图/trace）
```

首次：`npm install && npx playwright install chromium`。

## 环境契约（frontend/e2e/support/env.js 是唯一来源）

- 凭据放 `frontend/e2e/.env.local`（gitignored）：`E2E_TEST_HOST_ADDRESS/PORT/USERNAME/AUTH`
- 牺牲主机 = WSL：地址用 **192.168.111.253**（eth0 IP）或 127.0.0.1（仅 Windows 后端直连时）；
  **容器内 127.0.0.1 是容器自己**——后端跑在 WSL Docker 里时绝不能用
- WSL IP 重启可能变化；`wsl hostname -I` 查询
- `--db mysql`：目标机叠加 MySQL 8.4 服务（数据落命名卷），backend 自动切换数据源
- `E2E_FAST=1`：跳过 mvn 编译（需 backend/core/target/cp.txt 已存在）

## 部署脚本（scripts/deploy-image.sh）

- 受管构建：同步源码上下文（tar 管道）→ 目标机 `docker build` 版本化镜像（时间戳 tag + latest）→
  同步插件 jar → `docker-compose.deploy.yml` up → 保留最近 `--keep`（默认 2）版 → 五项验证
- **就绪判定 = 种子管理员真实登录成功**。`DatabaseInitializer` 是启动后置 Runner，
  端口可通后仍有 ~10-18s 窗口期（核心表未建，登录 500）
- compose 形态自适应：`docker compose` v2 优先，回退 `docker-compose` v1（独立二进制）
- v1 已知坑：对已有容器 in-place 重建撞 `ContainerConfig` KeyError → 一律 down → up

## 编写用例约定

- 目录：`e2e/main-app/<页面>/<功能>.spec.js`；插件用例包放 `e2e/plugins/<pluginId>/`
- 文件头注释：页面/用例/前置/步骤/通过标准（逐用例声明）
- 登录态：`support/session.js` 的 `injectSession(page)`（API 换 JWT 写 localStorage）
- 效果断言走平台 API：`support/api.js`（**直连后端路径必须带 /api 前缀**）
- 主机类用例顶部调 `skipWithoutTestHost()`（无凭据自动 SKIP）
- 断言禁 sleep，用 auto-wait / `expect.poll`；等待后端数据用 API 轮询

## 踩坑清单（每条都真实耗过排查时间）

1. **后端就绪 ≠ 端口可通**：`DatabaseInitializer` 是 CommandLineRunner，端口绑定后
   ~10-18s 才建核心表；窗口期登录 400"用户名或密码错误"（非 500，易误判为凭据错误）
2. **容器内 127.0.0.1 不是宿主机**：后端容器连 WSL 主机须用 WSL eth0 IP
3. **Element Plus 弹层是双层的**：上传/对话框断言用"进度文本消失"或 `.first()`，避免 strict 冲突
4. **上传成功 ≠ 生效**：进度消失只代表前端流程结束，必须补"列表/详情出现目标行"的实质断言
5. **插件上传后默认已启用**（el-table 行文本），但 Plugins.vue 注释说商店安装需手动启用——
   初始状态不要硬编码，读行文本自适应
6. **el-collapse 可能收起**：点章节卡片前先试 3s 短超时点击，失败再点标题展开
7. **vue-router query 不做百分号编码**：`redirect=/a/b` 原样出现在 URL
8. **Wujie 子应用内容在 shadow DOM**：Playwright 定位器自动穿透，正常写即可；
   刷新后定位保持（Wujie 不重挂载）
9. **faker 数据写库**：开发库（backend/data）是持久库，测试残留实例会累积——
   fixture beforeAll 先清理同名实例，afterAll 删除自建资源
10. **元数据严格绑定**：games/*.yml 的键必须是 `GameYamlConfig` 已声明字段
    （如 docker.workingDir，不是 imageRepo），未声明键导致整个游戏解析失败且仅 WARN

## 已知产品限制（写用例时绕开，勿当 Bug 反复报）

- 备份还原功能已临时下线（实例详情页无此 tab，backlog #01 wontfix）
- RCON 控制台无防注入拦截（清单 E2E-092 无对应实现）
- retag 镜像无法注入 SRCDS_RCONPW，RCON 写操作终态回显非确定——断言止步于"切换中"状态
- 系统设置若未接存储（backlog #03 修复前），持久化断言需降级

## 排查纪律（diagnosing-bugs 精简版）

1. 先建反馈环：一条能稳定变红/变绿的命令（探针脚本放 `e2e/.tmp/`，gitignored）
2. 失败先读 `e2e/.artifacts/<用例>/error-context.md`（含页面快照与失败断言）
3. 后端真实状态查 `logs/backend.log`（注意：每次 start-all 全量覆盖）
4. 状态类断言失败 → 先查 DB 实际值（WSL python3 + sqlite3 / mysql client），再怀疑前端
