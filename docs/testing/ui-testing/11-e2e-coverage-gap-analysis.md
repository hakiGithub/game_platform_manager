# 11 - E2E 覆盖现状与缺口分析

> 更新日期：2026-10-18
> 分支：`codex/e2e-port-startup-fixes`
> 最近验证：三个历史失败 spec 附着重跑全绿；整轮受管验证结果以本轮提交前报告为准

---

## 1. 结论摘要

当前 Playwright 用例集共有 **68 项、14 个 spec 文件**（含容器认领与 Steam302 专项）。

- `npm run e2e:round` 已编排容器认领与 Steam302 专项，共 **68 项**；允许的显式 SKIP 仅限“未配置牺牲主机凭据”时整组跳过。
- 本环境配置了 WSL 牺牲主机，Steam302、LinuxGSM GitHub 连通性、L4D2 次要页面、SourceMod 启停和 RCON 缺密码分支均已纳入真实执行。
- 历史三类条件跳过已消除：SourceMod 启停改为真实状态轮询并恢复原状态；DST/SDTD LinuxGSM 就绪在 Steam302 + hosts/CA 修复链路通过后不再跳过；地图列表可见性改为强断言。

当前已补齐的高优先级链路是：

1. **Steam302 + LinuxGSM GitHub 连通性 + CA/hosts 挂载的真实部署链路**：新增专项 4 项，覆盖停止态自愈、hosts 指向 WSL LAN、容器内 GitHub HTTPS、bridge 容器 DNS/hosts 可见。
2. **RCON 失败原因和恢复路径**：新增缺密码 E2E 分支，并补 `RconProtocolTest` / `RconConnectionManagerFailureTest`；失败原因区分端口/配置缺失、认证失败、超时、连接关闭与异常响应。
3. **L4D2 子应用次要路由**：8 个次要页面与 3 个旧路由兼容均有 E2E；SourceMod 上传、精确列表查询、禁用/启用恢复已转绿。
4. **容器认领纳入统一执行入口**：已加入 `e2e:round` 的“容器认领”分组。

---

## 2. 执行基线与统计口径

### 2.1 两种执行方式

| 命令 | 用例集合 | 最近基线 | 说明 |
|------|----------|----------|------|
| `npm run e2e:round` | 七组、68 项 | 以本轮最终报告为准 | 保序整轮；仅缺主机凭据时整组 SKIP |
| `npm run e2e` | Playwright 全量发现、68 项 | 与 round 集合一致 | 容器认领和 Steam302 专项均纳入 |

### 2.2 整轮七组

| 分组 | 目录/文件 | 说明 |
|------|-----------|------|
| 冒烟与导航 | `infra`、`auth`、`workspace` | 登录、导航、工作台 |
| 无主机页面组 | `games`、`system`、`tasks`、`plugins` | 游戏/设置/任务/插件扩展台 |
| 主机纳管 | `main-app/host` | 主机、Steam302 GitHub 专项 |
| 容器认领 | `docker/adopt-container.spec.js` | 识别、预填、认领、回显、删除 |
| 实例生命周期 L4D2 | `instance/lifecycle.spec.js` | Docker 五步部署与生命周期 |
| 插件子应用 L4D2 | `plugins/l4d2` | 主路径、次要页、RCON、SourceMod、仓库 |
| DST/SDTD 演练腿 | `instance/drill-legs.spec.js` | LinuxGSM 部署到真实就绪 |

### 2.3 显式跳过与条件分支

| 用例 | 类型 | 当前行为 | 处理 |
|------|------|----------|------|
| 主机依赖整组 | 无 `E2E_TEST_HOST_*` 凭据时 SKIP | 报告明确 `SKIP (no test host)` | 本地/CI 提供 WSL 凭据即可全部执行 |
| 非本地 WSL 卫生检查 | 分支不适用 | 返回 `skipped: true`，不作为失败 | 仅 WSL 牺牲主机环境适用 |
| SourceMod 插件禁用/启用切换 | 已移除 `fixme` | 读取真实状态、切换、轮询 API、恢复原状态 | 已纳入整轮 |
| DST/SDTD LinuxGSM 就绪 | 已移除环境阻塞跳过 | 容器内 GitHub/CA/hosts 预检通过后轮询 `/app/<shortname>` | 已纳入整轮 |
| 地图列表可见性 | 已移除条件跳过 | 上传任务 COMPLETED 后强断言列表出现 VPK | 已纳入整轮 |

---

## 3. 当前已覆盖场景

### 3.1 基础设施、认证与工作台

| 场景 | 覆盖内容 |
|------|----------|
| 牺牲主机凭据契约 | `infra/host-contract.spec.js` |
| 未登录访问 | 重定向登录页，受保护路由带 `redirect` |
| 登录成功 | 种子管理员进入工作台 |
| 登录失败 | 错误密码提示、密码框清空、停留登录页 |
| 退出登录 | 确认后清理 token 并返回登录页 |
| token 失效 | 伪造 token 触发 401、自动登出 |
| 404 | 不存在路由显示 404 与返回首页 |
| 工作台 | 总览、统计结构、核心指标、快捷入口 |

### 3.2 主机、游戏、任务与系统设置

| 页面 | 已覆盖内容 |
|------|------------|
| 主机管理 | 新增连接测试门禁、连接失败门禁、详情资源概览、Web 终端挂载与输入、删除确认 |
| 游戏目录 | 元数据注册数量守护、内置游戏可见、关键词筛选/重置、YAML 导入、删除清理 |
| 任务中心 | 列表结构、任务/定时计划 tab、状态筛选、不存在任务容错 |
| 系统设置 | 平台名称保存、重新进入后持久化 |
| 插件扩展台 | L4D2 状态为已启动；按实际实例状态进入工作区或提示未找到实例 |

### 3.3 实例生命周期与部署

| 场景 | 已覆盖内容 | 缺口 |
|------|------------|------|
| L4D2 Docker 五步部署 | 主机、游戏、参数、端口检查、环境校验、提交部署、轮询运行中 | 仅 Docker |
| 实例详情 | 静态标题、动态指标区、在线玩家面板 | 未断言指标值与刷新周期 |
| 生命周期 | 停止、启动、重启、卸载 | 未覆盖失败、取消、超时分支 |
| DST/SDTD LinuxGSM | 部署、容器内 GitHub/CA/hosts 预检、`/app/<shortname>` 就绪、卸载清理 | 仍需扩展部署失败/超时分支 |
| 容器认领 | 识别、预填、认领、关联回显、记录级删除 | 已纳入整轮；依赖 WSL Docker 与 fixture 容器 |
| Steam302 GitHub | 停止态自愈、LAN hosts、容器内 DNS/HTTPS/CA、bridge 可见性 | 仍可扩展安装失败、无 sudo、镜像拉取失败分支 |

### 3.4 L4D2 插件子应用

已覆盖 6 个主路径页：

| 页面 | 已覆盖内容 |
|------|------------|
| 工作区/Wujie | 插件卡片进入工作区、单实例直跳、主容器与子应用渲染 |
| 仪表盘 | 状态 hero、指标卡、刷新按钮 |
| RCON 控制台 | 执行一次 `status`；特殊字符命令不导致页面崩溃 |
| 地图管理 | 上传 fixture VPK、上传任务 COMPLETED、换图进入 changing 状态、删除 |
| SourceMod 插件 | 上传样例 `.smx`、精确列表查询、禁用/启用切换并恢复原状态 |
| 服务器配置 | 保存同步、核心字段回读、KV 增删、自定义配置、查看原始文件、重载配置 |
| 重启管理 | 页面渲染模式 radio、可用模式、重启按钮 |
| 远端仓库 | 按实际配置状态展示列表或未配置空态、打开设置弹窗、取消不保存 |

尚未形成完整闭环的主路径：

- 换图只断言“切换中”，没有断言最终地图切换结果。
- RCON 只断言命令记录可见，没有断言输出包含服务器状态。
- SourceMod 启停已覆盖，仍可补删除/批量启用/批量禁用。
- 远端仓库已覆盖“已配置列表 / 未配置空态”双分支，不触发真实外呼；仍可补真实连接成功、鉴权失败和错误翻译。
- 服务器配置未覆盖“恢复默认”和并发保存冲突。
- 重启管理未真正执行三种重启模式。

---

## 4. 尚未覆盖的场景

### 4.1 主应用与部署通用缺口

| 域 | 未覆盖场景 | 建议层级 |
|----|------------|----------|
| 主机 | 编辑主机、私钥认证、主机离线、终端重连、批量操作 | E2E |
| 游戏 | 非法 YAML、重复 gameCode、外部目录覆盖、扫描失败 | E2E/集成 |
| 部署 | 端口冲突、环境校验失败、部署失败重试、取消部署、自动启动关闭 | E2E |
| 部署适配器 | `docker-compose`、`native`、LinuxGSM 非 Docker | 集成/E2E |
| 实例同步 | 外部停止/启动容器后的状态回写、主机离线隔离、同步禁用 | 集成/E2E |
| 任务中心 | 失败详情、取消、重试、超时、定时计划执行 | E2E |
| 插件框架 | 插件启动、停止、卸载、重载、UI 资源 404 | E2E |
| 错误处理 | 后端不可达、Docker 超时、WebSocket 断开、子应用加载失败 | E2E |
| 主题与体验 | 深浅主题在 Wujie 子应用的同步与隔离 | E2E |
| 性能 | 首页、列表、详情 metrics、子应用首次加载预算 | 性能测试 |

### 4.2 Steam302、GitHub 与证书/hosts 专项缺口

#### 实现事实

- Steam302 容器使用 `--network host`，平台从 Caddyfile 提取启用域名并写入宿主机 `/etc/hosts`。
- 默认模式写入 `127.0.0.1 域名 #S302`。
- 开启“容器共享加速”后写入宿主机 LAN IP，标记为 `#S302-LAN`；为避免 CLI 与平台双写冲突，切换时先停止容器、写 hosts、再启动容器。
- 平台强制 `Auto_Modify_Hosts=0`，hosts 的唯一写入者归平台；写入后回读校验，失败会给出 expected/actual/remediation。
- 停止 Steam302 时清除两种 `#S302` 标记行。
- 安装任务会信任宿主机 CA，并为 dockerd 写 `certs.d`。
- LinuxGSM Docker 适配器无条件挂载宿主机 CA bundle，因为 entrypoint 需要访问 GitHub 下载 `serverlist.csv`。
- `mountHostCerts=true` 时，`docker`、`docker-compose`、`linuxgsm-docker` 三类适配器会额外只读挂载 `/etc/hosts`。
- `mountHostCerts` 默认关闭；因此“已安装 Steam302”不等于“bridge/非 host 网络游戏容器已经读取到劫持条目”。

#### 当前 E2E 做了什么

`host-hygiene.js` 在 LinuxGSM 就绪轮询前执行一次容器内 GitHub 连通性预检：

```text
docker exec <container> curl -m 8 -sI https://raw.githubusercontent.com
```

不通时输出带 remediation 的 `[CHECK][FAIL]`，由调用方作为真实失败处理；不再用 SKIP 掩盖环境阻塞。专项 spec 另行验证 Steam302、DNS、TLS 与 LinuxGSM 脚本恢复。

#### 未覆盖矩阵

| 场景 | 当前状态 | 需要断言 |
|------|----------|----------|
| Steam302 未安装 | 未覆盖 | 主机详情显示未部署，安装入口可用 |
| Steam302 安装任务 | 未覆盖 | 任务成功，容器 RUNNING，CA 已信任，代理域名数 > 0 |
| 默认本机模式 | 未覆盖 | hosts 有 `127.0.0.1 ... #S302`，无 `#S302-LAN` |
| 容器共享模式 | 已覆盖 | hosts 指向 WSL LAN IP，标记 `#S302-LAN`；切换时停容器、写 hosts、再启动 |
| Caddyfile 与 hosts 一致 | 未覆盖 | 只写启用域名；通配符被丢弃；禁用服务不进入 hosts |
| 容器内 GitHub 可达 | 已覆盖 | `getent hosts`、`curl -I`、证书链和 HTTP 状态均成功 |
| CA 挂载 | 已覆盖 | 容器内 CA 与宿主机 bundle 一致，TLS 无 unknown authority |
| `/etc/hosts` 挂载 | 已覆盖 | 容器内可见平台写入的 `#S302*` 行 |
| LinuxGSM 初始化 | 已覆盖 | `/app/<shortname>` 生成，状态进入“运行中” |
| Steam302 停止 | 已覆盖 | `#S302` 与 `#S302-LAN` 全部清除，直连恢复 |
| 自愈 | 已覆盖 | 停止态启动强制拉起容器；写入后回读校验，缺失时平台补写 |
| 异常分支 | 未覆盖 | 无 Docker、无 sudo、拉镜像失败、证书超时、Caddyfile 为空 |

#### 已补 E2E 链路

已新增 `frontend/e2e/main-app/host/steam302-github.spec.js`：

1. 创建牺牲主机，进入主机详情 Steam302 面板，确认未安装态。
2. 提交安装任务，轮询任务和状态到 RUNNING；断言 `hostsEntries > 0`、`proxiedDomains > 0`、`certTrusted=true`。
3. 开启容器共享加速，断言状态目标 IP 为 LAN IP，宿主机 hosts 仅保留 `#S302-LAN`，且切换过程按“停容器→写 hosts→启动容器”完成。
4. 用 `mountHostCerts=true` 部署一个 LinuxGSM 测试实例。
5. 检查实际运行容器的 mounts，确认 CA bundle 与 `/etc/hosts:ro` 均存在。
6. 在容器内执行 hosts 查询、GitHub HTTPS 请求和 CA 校验。
7. 等待 `/app/<shortname>` 出现，再启动游戏并轮询“运行中”。
8. 停止/启动 Steam302，验证 hosts 清除、恢复和自愈。
9. 恢复 `mountHostCerts=false`、Steam302 停止，作为清理回归对照。

这条链路已同时取代 DST/SDTD “GitHub 不通即跳过”的环境阻塞分支；修复链路验证失败时为红灯，不以 SKIP 掩盖。

### 4.3 L4D2 子应用缺口

当前 router 的 8 个次要业务页已全部纳入 E2E：

| 路由 | 页面 | 根选择器 | 最低冒烟断言 |
|------|------|----------|--------------|
| `/monitor` | 系统监控 | `.monitor-page` | 标题、指标块、历史查询空态/加载失败容错 |
| `/logs` | 服务器日志 | `.logs-page` | 文件表格或空态、刷新按钮、读取失败提示 |
| `/player-stats` | 玩家统计 | `.player-stats-page` | 配置区、榜单表/空态、采样错误展示 |
| `/admins` | 管理员 | `.admins-page` | 列表/空态、新增对话框、校验与保存 |
| `/server-info` | 服务器信息 | `.server-info-page` | 信息字段、编辑保存、版本面板懒加载 |
| `/preset` | 预设场景 | `.preset-page` | 预设列表/空态、详情、应用失败容错 |
| `/download` | 资源下载 | `.download-page` | 下载表单、任务表/空态、刷新 |
| `/map-center` | 地图中心 | `.map-center-page` | 页面可达、外部源失败提示、列表/空态 |

还应补三类路由兼容：

| 旧路由 | 目标路由 | 断言 |
|--------|----------|------|
| `/playtime` | `/player-stats` | 自动重定向且选中正确菜单 |
| `/version` | `/server-info` | 自动重定向且展开版本面板 |
| `/plugin-store` | `/plugins` | 自动重定向且默认 tab 正确 |

已并入现有 spec：

- `frontend/e2e/plugins/l4d2/plugin-subapp.spec.js`（次要页面与旧路由兼容分组）

### 4.4 RCON 失败原因与失败分支

#### 当前实现中的用户可见结果

| 失败层 | 原因 | 当前结果 |
|--------|------|----------|
| resolver | 主机为空或 IP 为空 | `RCON 端点不可达：端口未映射或配置缺失` |
| resolver | `rconPassword` 缺失 | 同上 |
| resolver | Docker 端口未映射 | 同上 |
| connection | 实例不存在 | `实例不存在: <id>` |
| connection | 连接超时 | 重试后 `RCON 连接失败（重试 N 次均失败）: ...` |
| connection | 连接被拒绝 | 重试后包含 `Connection refused` |
| auth | 密码错误/未启用 RCON | `RCON 认证失败（密码错误或服务器未启用 RCON）` |
| auth | 服务端关闭连接 | `RCON 认证失败：服务器关闭连接...` |
| protocol | 非 RCON 服务或异常响应 | `无效的数据包长度`、`连接已关闭...` |
| pool | 同实例并发占用 | `实例 <id> 的 RCON 连接正忙` |
| pool | 池内 zombie connection | 自动换新连接重试一次 |
| command | 命令超时或连接断 | `RCON 通信失败: ...` |

接口表现：

- `/rcon/status` 通常返回 HTTP 200，业务字段为 `online=false` + `reason`。
- `/rcon/execute` 通常返回 HTTP 200，业务字段为 `success=false` + `error`。
- Dashboard 会展示 `serverStatus.reason`；RCON 控制台把 `result.error` 放进输出历史。
- E2E 已覆盖缺失 `rconPassword` 时的明确失败原因，并验证恢复配置后实例回到运行中；超时、拒绝、协议错误、busy、池失效仍由 Java 集成测试覆盖。

#### 当前已有自动化

| 层级 | 文件 | 已覆盖 |
|------|------|--------|
| 单测 | `RconConnectionResolverTest` | 标准键、默认端口、端口映射、缺失密码/主机 |
| 单测 | `RconConnectionManagerRetryTest` | 池内 zombie connection 换新重试、正常连接复用 |
| 插件单测 | `RconFailureDetectorTest` | 命令输出失败关键字 |
| 插件单测 | 各 service test | mock RCON 异常时的业务异常包装 |
| E2E | `plugin-subapp.spec.js` | 成功 `status`、特殊字符命令不崩溃、缺失 `rconPassword` 失败原因与恢复 |

#### 建议补充矩阵

| 优先级 | 场景 | 测试方式 | 关键断言 |
|--------|------|----------|----------|
| P0 | `rconPassword` 缺失 | E2E/API | Dashboard 和 RCON 页展示明确原因 |
| P0 | Docker 未映射 RCON 端口 | E2E/API | 不发起无意义连接，展示“端口未映射或配置缺失” |
| P0 | 实例停止/容器未运行 | E2E | 控制台失败原因可见，启动后同一实例恢复 |
| P0 | 错误密码 | E2E/API | 区分密码错误与端口不可达；修正配置后恢复 |
| P1 | 连接超时 | Java 集成 + 假 TCP server | 超时边界、重试次数、最终 reason |
| P1 | 连接拒绝 | Java 集成 | refused 原因保留 |
| P1 | 非 RCON 服务 | Java 集成 | 无效包长/协议错误可读 |
| P1 | 同实例并发 busy | Java 集成 | borrow timeout 后返回“连接正忙” |
| P1 | 池内连接失效 | Java 集成 | 首次失败自动换新连接，第二次成功 |
| P2 | 命令超长/长响应 | Java 集成 | 超时可控，不拖死连接池 |
| P2 | 审计覆盖失败 | 日志断言 | `RCON_AUDIT` 命中 caller/instanceId/success=false/reason |

已新增：

- `backend/core/src/test/java/com/gameplatform/rcon/RconProtocolTest.java`
- `backend/core/src/test/java/com/gameplatform/rcon/RconConnectionManagerFailureTest.java`
- `frontend/e2e/plugins/l4d2/plugin-subapp.spec.js`（RCON 缺密码失败分支与恢复）

不要用 retag 镜像的不确定 RCON 密码承担全部失败测试；可控假 RCON TCP server 更适合精确模拟超时、拒绝、错误密码和非协议服务。

---

## 5. 与 backlog/清单的映射

| 来源 | 状态 | 本文对应项 |
|------|------|------------|
| `.scratch/backlog/issues/05-l4d2-subapp-secondary-pages-cases.md` | 已覆盖 | §4.3 的 8 页 |
| `.scratch/backlog/issues/06-rcon-deep-ops-with-live-players.md` | 部分覆盖 | §3.4、§4.4；玩家在线操作另需环境 |
| `.scratch/backlog/issues/07-linuxgsm-github-blocked-environment.md` | 已覆盖 | §4.2；Steam302 E2E 已解除环境阻塞 |
| `.scratch/backlog/issues/02-docker-addons-invisible-to-map-list.md` | 已覆盖 | 地图列表改为强断言 |
| `.scratch/backlog/issues/01-backup-async-executor-stuck.md` | wontfix | 备份 E2E 暂不恢复 |
| `.scratch/backlog/issues/03-system-settings-not-persisted.md` | 已由当前用例触及 | 系统设置持久化已有绿灯 |
| `.scratch/backlog/issues/04-db-init-race-after-tomcat-up.md` | 已修复 | runner 以真实登录 + 游戏元数据为就绪信号 |

---

## 6. 建议实施顺序

### P0：阻塞发布可信度的缺口（本轮已处理）

1. 已新增 Steam302 专项、容器共享、CA/hosts 挂载、容器内 GitHub TLS、LinuxGSM 真正启动的 E2E。
2. 已新增 RCON 配置缺失、失败原因与恢复路径的 E2E；端口未映射/停止实例仍可继续扩展。
3. 已用可控单元/集成测试补超时、拒绝、协议错误、连接关闭等分支；busy 与池失效仍可扩展。
4. 容器认领已纳入 `e2e:round`，不再隐含遗漏。

### P1：插件与业务闭环

5. 已补 L4D2 8 个次要页面和 3 个兼容路由。
6. 地图列表已改为强断言；换图终态仍待补。
7. SourceMod 启停已解除 `fixme` 并通过真实 API 轮询验证。
8. 任务中心仍待补失败、取消、重试、超时和定时计划。
9. `docker-compose` 部署腿与部署失败分支仍待补。

### P2：体验与防御性覆盖

10. 主题同步、网络断开、WebSocket 重连、子应用加载失败。
11. 页面性能预算与 Console error 守护。
12. RCON 命令超长、长响应、审计失败日志。

---

## 7. 验收标准

### Steam302/GitHub 专项

- [x] Steam302 专项已覆盖停止态启动、RUNNING、hosts 写入校验与 CA/hosts 挂载。
- [x] 容器共享模式验证 `#S302-LAN` 指向 WSL LAN IP；默认模式仍建议独立环境复验。
- [x] `mountHostCerts=true` 的容器实际看到 CA bundle 与 `/etc/hosts` mount。
- [x] 容器内 `curl -I https://raw.githubusercontent.com` 返回 2xx/3xx 且无证书错误。
- [x] LinuxGSM `/app/<shortname>` 生成，测试腿可断言“运行中”，不再以 GitHub 不通跳过。
- [x] 停止 Steam302 后无 `#S302*` 残留，启动后可恢复，缺失标记行时平台补写。

### L4D2 插件

- [x] `/monitor`、`/logs`、`/player-stats`、`/admins`、`/server-info`、`/preset`、`/download`、`/map-center` 均有可达渲染与关键交互冒烟。
- [x] `/playtime`、`/version`、`/plugin-store` 旧路由兼容断言通过。
- [x] 新增用例已挂到 `npm run e2e:round` 插件分组。

### RCON

- [x] 缺失密码失败在 UI/API 层有明确原因；端口未映射、实例停止、错误密码仍待扩展。
- [x] 至少一条失败到修复后的恢复链路通过。
- [x] 超时、拒绝、连接关闭、非协议服务由可控测试覆盖；busy、池失效重试仍待扩展。
- [ ] 成功与失败命令均产生 `RCON_AUDIT` 记录。

---

## 8. 维护约定

- 本文只记录“当前真实自动化覆盖”，不与规划用例混算。
- 任何 `test.skip`、环境预检跳过或未纳入整轮的 spec，都必须在本文更新原因和退出条件。
- 新增 Steam302、RCON、L4D2 插件用例后，同步更新本文状态和 `07-e2e-checklist.md`。
