# 部署任务状态机功能 UI 自动化测试用例

> **测试对象**：本次改动涉及的"部署任务自动触发 + 实例状态机 + 双模式日志查看 + 重试部署 + 端口检测"功能
> **文档版本**：v1.0
> **最后更新**：2026-07-16
> **关联代码**：
> - 后端：`DeployService.java`、`InstanceServiceImpl.java`、`InstanceController.java`、`DeployRecoveryListener.java`、`HostController.java`
> - 前端：`instance/index.vue`、`instance/deploy.vue`、`components/DeployProgress.vue`、`api/instance.js`

---

## 一、测试环境准备信息

执行测试前，必须准备并核对以下信息，缺失任何一项均会导致测试用例无法执行。

### 1.1 服务地址

| 项目 | 值（示例） | 说明 |
|------|-----------|------|
| 后端 API 地址 | `http://localhost:8080/api` | Spring Boot 服务，context-path 为 `/api` |
| 前端访问地址 | `http://localhost:3000` | Vite 开发服务器，可能自动切换到 3001 |
| 数据库路径 | `d:/program/ai/game_platform_manger/backend/data/game_platform.db` | SQLite，需可读写 |

### 1.2 测试账号

| 字段 | 值 |
|------|----|
| 用户名 | `admin` |
| 密码 | `admin123` |
| 备注 | 首次登录后获取 JWT Token，后续请求需携带 `Authorization: Bearer {token}` |

### 1.3 测试主机（必须真实可连接）

| 字段 | 要求 |
|------|------|
| 主机 ID | 已纳管的主机记录（如 id=1） |
| 操作系统 | Linux（推荐 Ubuntu 22.04） |
| SSH 连接 | 密码或密钥认证均需可用 |
| Docker | 已安装并运行（`docker info` 可成功执行） |
| 磁盘空间 | ≥ 5GB（用于拉取镜像） |
| 端口可用性 | 27015、27016、27020 端口未被占用 |

> ⚠️ **关键**：测试主机的 SSH 凭证必须正确解密可用，否则环境校验阶段会失败（非功能 Bug，是测试数据问题）。

### 1.4 测试游戏元数据

| 字段 | 值 |
|------|----|
| 游戏 ID | 5（求生之路2） |
| gameCode | `l4d2` |
| 部署类型 | `docker` |
| 镜像 | `cm2network/left4dead2` |
| 默认端口 | 27015 |

### 1.5 测试工具

| 工具 | 用途 | 推荐版本 |
|------|------|---------|
| Playwright | UI 自动化测试框架 | ≥ 1.40 |
| Node.js | 运行测试脚本 | ≥ 18 |
| curl / Invoke-RestMethod | API 辅助验证 | - |
| SQLite DB Browser | 数据库状态核对 | - |
| 浏览器 | 手动验证 / headed 模式 | Chrome ≥ 120 |

### 1.6 测试前自行重启前后端服务（重要）

> ⚠️ **测试人员必须自行重启前后端服务后再进行测试**，以确保测试的是最新代码。
> 已提供一键编译并重启脚本，无需手动编译和启动。

#### 1.6.1 一键重启前后端（推荐）

在项目根目录执行：

```powershell
cd d:\program\ai\game_platform_manger
.\scripts\restart-all.ps1
```

脚本会自动完成：编译后端 → 停止旧进程 → 启动后端 → 停止旧前端 → 启动前端。
启动完成后会显示访问地址。

#### 1.6.2 仅重启后端

```powershell
cd d:\program\ai\game_platform_manger\backend
.\scripts\rebuild-restart.ps1
```

参数说明：
- `-SkipCompile`：跳过编译，仅重启（用于快速重启）
- `-DbPath <路径>`：自定义数据库路径

示例：
```powershell
.\scripts\rebuild-restart.ps1 -SkipCompile          # 跳过编译快速重启
.\scripts\rebuild-restart.ps1 -DbPath "D:\my.db"    # 指定数据库路径
```

#### 1.6.3 仅重启前端

```powershell
cd d:\program\ai\game_platform_manger\frontend
.\scripts\rebuild-restart.ps1
```

参数说明：
- `-Port <端口号>`：指定端口（默认 3000）

示例：
```powershell
.\scripts\rebuild-restart.ps1 -Port 3001
```

#### 1.6.4 验证服务启动成功

重启脚本执行完成后，检查输出：

| 服务 | 验证方式 | 预期结果 |
|------|---------|---------|
| 后端 | 脚本输出 | 显示"后端启动成功" + 访问地址 http://localhost:8080 |
| 后端 | 命令验证 | `netstat -ano \| findstr :8080` 显示 LISTENING |
| 后端 | API 验证 | `POST http://localhost:8080/api/auth/login` 返回 200 |
| 前端 | 脚本输出 | 显示"前端启动成功" + 访问地址 http://localhost:3000 |
| 前端 | 命令验证 | `netstat -ano \| findstr :3000` 显示 LISTENING |
| 前端 | 浏览器 | 访问 http://localhost:3000 显示登录页 |

#### 1.6.5 测试数据准备

服务启动成功后，按以下顺序准备测试数据：

1. **确认主机在线**：登录前端 → 主机管理 → 确认测试主机 `onlineStatus=1`

2. **清理脏数据**：执行测试前，删除所有 `runStatus=5`（部署中）的实例，避免启动恢复机制干扰
   ```sql
   UPDATE game_instance SET is_deleted=1 WHERE run_status=5;
   ```

3. **准备测试实例命名规则**：`auto-test-{功能}-{时间戳}`，便于测试后清理

#### 1.6.6 日志位置说明

测试过程中如需查看日志，日志文件位置如下：

| 日志类型 | 路径 |
|---------|------|
| 后端应用日志 | `backend/logs/application.log` |
| 后端启动日志 | `backend/logs/startup.log` |
| 前端应用日志 | `frontend/logs/frontend.log` |
| 前端启动日志 | `frontend/logs/startup.log` |
| 统一重启日志 | `logs/restart-all.log` |

#### 1.6.7 常见问题排查

| 问题 | 原因 | 解决方案 |
|------|------|---------|
| 后端启动失败：端口被占用 | 旧进程未停止 | 重新执行重启脚本，或手动 `Stop-Process -Id (Get-NetTCPConnection -LocalPort 8080).OwningProcess -Force` |
| 后端启动失败：编译错误 | 代码有语法错误 | 检查 `backend/logs/startup.log` 中的编译输出 |
| 后端启动失败：数据库错误 | TRAE 沙箱限制 | 确认数据库路径在项目目录下（脚本已默认处理） |
| 前端启动失败：端口被占用 | 旧进程未停止 | 重新执行重启脚本，或指定其他端口 `-Port 3001` |
| 前端启动失败：node_modules 不存在 | 未安装依赖 | 脚本会自动执行 `npm install`，如失败请手动安装 |
| 健康检查超时 | 启动较慢 | 增加等待时间，或查看日志确认启动进度 |

### 1.7 验证点通用检查项

每个用例除特定验证点外，均需检查：
- [ ] 页面无 JavaScript 控制台错误
- [ ] 网络请求返回 200 状态码（除特定错误场景）
- [ ] UI 元素文案与设计一致
- [ ] 操作后有合理的加载状态反馈

---

## 二、测试用例

### TC-01: 创建实例自动触发部署任务

**优先级**：P0
**功能点**：部署任务自动触发 + 状态机初始化
**前置条件**：
1. 已登录系统，跳转到实例部署页 `/instance/deploy`
2. 测试主机在线且 Docker 可用
3. 端口 27015 未被占用

**操作步骤**：

| 步骤 | 操作 | 预期结果 |
|------|------|---------|
| 1 | 选择游戏"求生之路2" | 加载该游戏的配置表单 |
| 2 | 选择测试主机 | 主机下拉框显示已纳管主机 |
| 3 | 填写实例名称 `auto-test-create-001` | 输入框正常接收输入 |
| 4 | 选择部署类型 `docker` | 显示 Docker 配置项 |
| 5 | 填写端口配置 27015→27015 | 端口映射显示正确 |
| 6 | 点击"端口检测"按钮（如存在） | 返回端口可用 |
| 7 | 点击"开始部署"按钮 | 按钮变为 loading 状态 |
| 8 | 等待页面跳转 | 跳转到 `/instance/list` 实例列表页 |

**验证点**：

- [ ] **V1.1**：点击"开始部署"后，POST `/api/instances` 请求返回 `code=200`
- [ ] **V1.2**：响应体 `data.runStatus` 等于 `5`（部署中）
- [ ] **V1.3**：响应体 `data.status` 等于 `"deploying"`
- [ ] **V1.4**：响应体 `data.runStatusDesc` 等于 `"部署中"`
- [ ] **V1.5**：响应体 `data.deployTaskId` 不为空，且等于实例 ID 字符串
- [ ] **V1.6**：跳转到实例列表后，新实例状态显示为"部署中"（橙色 tag）
- [ ] **V1.7**：状态列图标为 Loading 旋转动画
- [ ] **V1.8**：操作列显示"查看日志"按钮（带 Loading 图标）
- [ ] **V1.9**：列表页 5 秒后自动刷新一次（autoRefresh 机制）

**后置清理**：记录创建的实例 ID，测试完成后调用 `DELETE /api/instances/{id}` 删除

---

### TC-02: 实例列表状态机显示验证

**优先级**：P0
**功能点**：状态机 `runStatus` → `status` 字符串映射 + UI 显示
**前置条件**：已存在不同状态的实例（可通过 API 创建）

**操作步骤**：

| 步骤 | 操作 | 预期结果 |
|------|------|---------|
| 1 | 访问实例列表页 `/instance/list` | 正常加载列表 |
| 2 | 观察各实例的状态列 | 不同状态显示不同颜色和文案 |

**验证点（状态映射矩阵）**：

| runStatus | status 字符串 | 状态文案 | Tag 类型 | 图标 | 颜色 |
|-----------|--------------|---------|---------|------|------|
| 0 | `stopped` | 已停止 | info | InfoFilled | stopped 色 |
| 1 | `running` | 运行中 | success | VideoPlay | running 色 |
| 2 | `error` | 异常 | danger | CircleClose | error 色 |
| 3 | `stopping` | 停止中 | warning | Loading | deploying 色 |
| 5 | `deploying` | 部署中 | warning | Loading（旋转） | deploying 色 |
| 6 | `starting` | 启动中 | warning | Loading（旋转） | deploying 色 |

- [ ] **V2.1**：上表所有状态映射正确
- [ ] **V2.2**：`deploying`/`starting`/`stopping` 状态的图标有旋转动画
- [ ] **V2.3**：`error` 状态的行有红色背景（`row-error` class）
- [ ] **V2.4**：`stopped` 状态的行有灰色背景（`row-stopped` class）
- [ ] **V2.5**：状态筛选下拉框包含"部署中"选项（value=`deploying`）

---

### TC-03: 查看部署进度日志（deploy 模式）

**优先级**：P0
**功能点**：DeployProgress 组件 deploy 模式 + deploy-progress 端点轮询
**前置条件**：
1. 存在一个 `status=deploying` 的实例（参考 TC-01 创建）
2. 部署任务正在执行中

**操作步骤**：

| 步骤 | 操作 | 预期结果 |
|------|------|---------|
| 1 | 在实例列表找到 deploying 状态的实例 | 行操作列显示"查看日志"按钮 |
| 2 | 点击"查看日志"按钮 | 弹出日志查看对话框 |
| 3 | 观察对话框内容 | 显示进度条、状态、日志列表 |
| 4 | 等待 3 秒 | 日志列表自动刷新，新增日志条目 |
| 5 | 观察进度条 | 进度条根据后端返回的 progress 更新 |
| 6 | 关闭对话框 | 对话框关闭，轮询停止 |

**验证点**：

- [ ] **V3.1**：点击"查看日志"后，前端调用 `GET /api/instances/{id}/deploy-progress`
- [ ] **V3.2**：对话框标题显示实例信息
- [ ] **V3.3**：进度条显示百分比（0-100）
- [ ] **V3.4**：状态标签显示当前阶段（如"环境校验中"、"下载镜像中"）
- [ ] **V3.5**：日志列表按时间倒序显示，每条包含 level、message、stage、time
- [ ] **V3.6**：ERROR 级别日志显示红色，INFO 级别默认色
- [ ] **V3.7**：日志容器自动滚动到底部（最新日志可见）
- [ ] **V3.8**：每 2 秒自动轮询一次进度接口
- [ ] **V3.9**：`completed=true` 时停止轮询
- [ ] **V3.10**：成功完成时显示绿色"已完成"状态，失败显示红色"失败"状态
- [ ] **V3.11**：耗时显示从打开对话框开始累计（XX秒/XX分XX秒）
- [ ] **V3.12**：关闭对话框后，网络面板确认轮询请求停止

---

### TC-04: 部署失败查看错误日志

**优先级**：P0
**功能点**：错误状态日志查看 + 状态机 error 流转
**前置条件**：
1. 创建一个会失败的实例（如使用不存在的镜像，或断开测试主机 SSH）
2. 实例状态已变为 `error`

**操作步骤**：

| 步骤 | 操作 | 预期结果 |
|------|------|---------|
| 1 | 在实例列表找到 error 状态的实例 | 行显示红色背景 |
| 2 | 观察操作列 | 显示"重试"和"查看日志"按钮 |
| 3 | 点击"查看日志"按钮 | 弹出日志对话框 |
| 4 | 查看日志内容 | 显示失败阶段的错误日志 |

**验证点**：

- [ ] **V4.1**：error 状态实例的操作列显示"重试"按钮（warning 类型）
- [ ] **V4.2**：error 状态实例的操作列显示"查看日志"按钮
- [ ] **V4.3**：日志对话框状态显示"失败"（红色）
- [ ] **V4.4**：进度条显示失败时的进度百分比（非 100%）
- [ ] **V4.5**：日志列表包含 `[ERROR]` 级别条目，红色显示
- [ ] **V4.6**：错误日志的 `stage` 字段标明失败阶段（如 `ENV_CHECK`、`DEPLOY`、`HEALTH_CHECK`）
- [ ] **V4.7**：`error` 字段显示错误摘要信息
- [ ] **V4.8**：`completed=true`，`success=false`
- [ ] **V4.9**：数据库 `game_instance.run_status=2`，`status` 映射为 `"error"`

---

### TC-05: 重试部署功能

**优先级**：P0
**功能点**：retry-deploy 端点 + 状态机 error→deploying 流转
**前置条件**：
1. 存在一个 `status=error` 的实例（参考 TC-04）
2. 测试主机已恢复正常（SSH 可连接、Docker 可用）

**操作步骤**：

| 步骤 | 操作 | 预期结果 |
|------|------|---------|
| 1 | 在实例列表找到 error 状态的实例 | 显示"重试"按钮 |
| 2 | 点击"重试"按钮 | 弹出确认对话框 |
| 3 | 确认对话框文案 | 显示"确定要重新部署实例「xxx」吗？" |
| 4 | 点击"确定" | 请求发出，显示成功提示 |
| 5 | 观察实例状态 | 状态变回"部署中" |
| 6 | 等待自动刷新 | 列表 5 秒后刷新 |

**验证点**：

- [ ] **V5.1**：点击"重试"弹出 `ElMessageBox.confirm` 确认框
- [ ] **V5.2**：确认后调用 `POST /api/instances/{id}/retry-deploy`
- [ ] **V5.3**：API 返回 `code=200`
- [ ] **V5.4**：显示 `ElMessage.success("已重新触发部署")`
- [ ] **V5.5**：实例状态从 `error` 变为 `deploying`（数据库 runStatus 2→5）
- [ ] **V5.6**：操作列按钮变化：从"重试+查看日志"变为"查看日志"（带 Loading）
- [ ] **V5.7**：自动刷新定时器启动（5 秒间隔）
- [ ] **V5.8**：点击"取消"不发起请求，状态不变

**异常场景**：
- [ ] **V5.9**：对非 error 状态的实例调用 retry-deploy API，返回 400 错误（业务校验）
- [ ] **V5.10**：网络异常时显示 `ElMessage.error("重试部署失败: xxx")`

---

### TC-13: 部署失败全流程验证

**优先级**：P0
**功能点**：从创建实例到部署失败的完整状态机流转 + 日志追溯
**前置条件**：
1. 后端服务正常运行
2. 测试主机在线但缺少有效 Docker 镜像配置（或配置错误镜像）

**操作步骤**：

| 步骤 | 操作 | 预期结果 |
|------|------|---------|
| 1 | 调用 `POST /api/instances` 创建实例 | API 返回 200，runStatus=5，status=deploying |
| 2 | 立即调用 `GET /api/instances/{id}` | 实例状态为 deploying |
| 3 | 调用 `GET /api/instances/{id}/deploy-progress` | 返回部署进度和日志 |
| 4 | 等待 5~10 秒（部署执行完成） | 部署因环境原因失败 |
| 5 | 再次调用 `GET /api/instances/{id}` | runStatus=2，status=error |
| 6 | 再次调用 `GET /api/instances/{id}/deploy-progress` | status=failed，completed=true，success=false，日志包含 ERROR 条目 |
| 7 | 进入前端实例列表页 | 该实例显示为"异常"（红色标签） |
| 8 | 点击"查看日志" | 弹出"部署进度"对话框，显示失败日志 |

**验证点**：

- [ ] **V13.1**：创建实例 API 返回 200，deployTaskId 不为空
- [ ] **V13.2**：创建后数据库 run_status=5
- [ ] **V13.3**：部署进度端点返回 status=failed 或 in_progress
- [ ] **V13.4**：部署失败后实例状态自动变为 error（runStatus=2）
- [ ] **V13.5**：部署进度 completed=true，success=false
- [ ] **V13.6**：日志列表包含 ERROR 级别条目（红色显示）
- [ ] **V13.7**：日志内容可追溯至具体失败原因（如"未指定Docker镜像"、"环境校验失败"等）
- [ ] **V13.8**：前端列表页该实例状态为"异常"，显示"重试"按钮
- [ ] **V13.9**：前端点击"查看日志"进入 deploy 模式（而非 runtime 模式）
- [ ] **V13.10**：状态机完整流转：creating → deploying(5) → error(2)

**异常场景**：
- [ ] **V13.11**：部署过程中后端崩溃，重启后 run_status 应从 5 恢复为 2（依赖 TC-08）
- [ ] **V13.12**：对 error 状态实例调用 retry-deploy，应重新进入 deploying(5)

---

### TC-06: 查看运行时日志（runtime 模式）

**优先级**：P0
**功能点**：DeployProgress 组件 runtime 模式 + Docker 容器实时日志
**前置条件**：
1. 存在一个 `status=running` 的实例
2. 该实例为 Docker 部署，容器正在运行

**操作步骤**：

| 步骤 | 操作 | 预期结果 |
|------|------|---------|
| 1 | 在实例列表找到 running 状态的实例 | 操作列显示"停止"+"查看日志" |
| 2 | 点击"查看日志"按钮 | 弹出日志对话框 |
| 3 | 观察对话框 | 显示容器实时日志 |
| 4 | 等待 5 秒 | 日志持续更新 |

**验证点**：

- [ ] **V6.1**：running 状态点击"查看日志"，`mode` 参数为 `"runtime"`
- [ ] **V6.2**：前端调用 `GET /api/instances/{id}/logs`（而非 deploy-progress）
- [ ] **V6.3**：对话框不显示进度条（`v-if="mode === 'deploy'"` 隐藏）
- [ ] **V6.4**：日志内容来自 Docker 容器（`docker logs` 命令输出）
- [ ] **V6.5**：日志按 5 行 hash 去重，避免重复追加（`appendRuntimeLogs` 逻辑）
- [ ] **V6.6**：日志持续滚动更新，最新日志在底部
- [ ] **V6.7**：后端通过 SSH 执行 `docker logs {containerName} --tail 100` 获取日志
- [ ] **V6.8**：关闭对话框后轮询停止

**异常场景**：
- [ ] **V6.9**：容器不存在时，返回空日志或错误提示
- [ ] **V6.10**：SSH 连接失败时，显示错误信息

---

### TC-07: 部署成功状态流转（健康检查通过）

**优先级**：P1
**功能点**：健康检查重试机制 + 状态机 deploying→starting→running 流转
**前置条件**：
1. 测试主机 Docker 正常
2. 镜像 `cm2network/left4dead2` 已拉取或可拉取
3. 端口 27015 可用

**操作步骤**：

| 步骤 | 操作 | 预期结果 |
|------|------|---------|
| 1 | 创建实例并自动部署 | 状态为 deploying |
| 2 | 观察状态变化 | deploying → starting → running |
| 3 | 查看部署进度日志 | 日志显示健康检查通过 |

**验证点**：

- [ ] **V7.1**：部署阶段完成后，状态从 `deploying`(5) 变为 `starting`(6)
- [ ] **V7.2**：日志显示 `[START] 启动实例` 条目，进度 95%
- [ ] **V7.3**：容器启动后等待 5 秒，开始健康检查
- [ ] **V7.4**：健康检查最多重试 3 次，间隔 5 秒
- [ ] **V7.5**：健康检查通过后，状态变为 `running`(1)，进度 98%
- [ ] **V7.6**：日志显示 `实例已启动并健康`
- [ ] **V7.7**：最终进度 100%，状态 `completed`，`success=true`
- [ ] **V7.8**：数据库 `run_status=1`，`status="running"`
- [ ] **V7.9**：实例列表显示"运行中"（绿色 tag）

**异常场景**：
- [ ] **V7.10**：健康检查 3 次均失败，状态变为 `error`(2)
- [ ] **V7.11**：日志显示 `健康检查 3 次重试均失败`
- [ ] **V7.12**：抛出 `DeployException("健康检查失败：3 次重试均未通过")`

---

### TC-08: 启动恢复机制验证

**优先级**：P1
**功能点**：DeployRecoveryListener 启动时标记中断部署
**前置条件**：
1. 创建一个 deploying 状态的实例
2. 部署过程中强制停止后端服务

**操作步骤**：

| 步骤 | 操作 | 预期结果 |
|------|------|---------|
| 1 | 创建实例触发部署 | 状态为 deploying |
| 2 | 强制停止后端进程 | 服务中断 |
| 3 | 确认数据库 `run_status=5` | 部署中状态保留 |
| 4 | 重新启动后端服务 | 触发 ApplicationReadyEvent |
| 5 | 查询该实例状态 | 已变为 error |

**验证点**：

- [ ] **V8.1**：后端启动后，`DeployRecoveryListener` 执行
- [ ] **V8.2**：查询所有 `run_status=5` 的实例
- [ ] **V8.3**：将这些实例的 `run_status` 更新为 `2`（error）
- [ ] **V8.4**：实例列表显示该实例为"异常"状态
- [ ] **V8.5**：可点击"重试"按钮重新部署
- [ ] **V8.6**：后端日志输出恢复记录（如有）

---

### TC-09: 部署提交后页面跳转

**优先级**：P1
**功能点**：deploy.vue handleDeploy 跳转逻辑
**前置条件**：已登录，在部署页 `/instance/deploy`

**操作步骤**：

| 步骤 | 操作 | 预期结果 |
|------|------|---------|
| 1 | 填写完整部署表单 | 表单校验通过 |
| 2 | 点击"开始部署" | 按钮 loading |
| 3 | 等待 API 响应 | 跳转到 `/instance/list` |

**验证点**：

- [ ] **V9.1**：无论 API 返回是否包含 `deployTaskId`，均跳转到实例列表
- [ ] **V9.2**：跳转后列表首位显示刚创建的实例
- [ ] **V9.3**：新实例状态显示"部署中"
- [ ] **V9.4**：不显示独立的进度对话框（复用列表的"查看日志"入口）

**异常场景**：
- [ ] **V9.5**：API 返回 400（参数错误）时，不跳转，显示错误提示
- [ ] **V9.6**：API 返回 500 时，不跳转，显示错误提示

---

### TC-10: 端口检测功能（check-port）

**优先级**：P1
**功能点**：HostController check-port 端点
**前置条件**：测试主机在线，已知端口 22（被占用）、25565（可用）

**操作步骤**：

| 步骤 | 操作 | 预期结果 |
|------|------|---------|
| 1 | 调用 `GET /api/hosts/{id}/check-port?port=22` | 返回端口被占用 |
| 2 | 调用 `GET /api/hosts/{id}/check-port?port=25565` | 返回端口可用 |
| 3 | 在部署页填写端口后点击检测 | 显示检测结果 |

**验证点**：

- [ ] **V10.1**：后端通过 SSH 执行 `ss -tulnp | grep :22` 或 `netstat -tulnp | grep :22`
- [ ] **V10.2**：端口被占用时，返回 `available=false`
- [ ] **V10.3**：端口可用时，返回 `available=true`
- [ ] **V10.4**：主机不存在时，返回 404 错误
- [ ] **V10.5**：SSH 连接失败时，返回 500 错误
- [ ] **V10.6**：前端部署页端口检测按钮反馈正确

---

### TC-11: 列表自动刷新机制

**优先级**：P2
**功能点**：startAutoRefresh / stopAutoRefresh
**前置条件**：实例列表存在 deploying/starting/stopping 状态的实例

**操作步骤**：

| 步骤 | 操作 | 预期结果 |
|------|------|---------|
| 1 | 进入实例列表页 | 存在活跃状态实例 |
| 2 | 等待 5 秒 | 列表自动刷新 |
| 3 | 所有实例变为稳定状态（running/stopped/error） | 自动刷新停止 |
| 4 | 离开页面 | 定时器清理 |

**验证点**：

- [ ] **V11.1**：存在 deploying/starting/stopping 状态时，5 秒间隔刷新
- [ ] **V11.2**：网络面板可见 `GET /api/instances` 请求每 5 秒一次
- [ ] **V11.3**：所有实例变为稳定状态后，刷新停止
- [ ] **V11.4**：`onBeforeUnmount` 钩子触发 `stopAutoRefresh`，无内存泄漏
- [ ] **V11.5**：手动点击搜索/重置后，立即触发一次 fetchData

---

### TC-12: 日志对话框去重与追加逻辑

**优先级**：P2
**功能点**：appendRuntimeLogs hash 去重
**前置条件**：存在 running 状态的 Docker 实例

**操作步骤**：

| 步骤 | 操作 | 预期结果 |
|------|------|---------|
| 1 | 打开运行时日志对话框 | 显示初始日志 |
| 2 | 等待多次轮询 | 日志持续追加 |
| 3 | 观察日志列表 | 无重复条目 |

**验证点**：

- [ ] **V12.1**：每次轮询取最后 N 行，与现有日志最后 5 行 hash 比对
- [ ] **V12.2**：重复内容不追加，仅追加新增部分
- [ ] **V12.3**：日志条目数稳定增长，不爆炸式增长
- [ ] **V12.4**：日志容器自动滚动到底部

---

## 三、自动化测试脚本示例（Playwright）

以下为 TC-01 的 Playwright 脚本示例，其他用例可参照编写。

```javascript
// tests/deploy-task.spec.js
const { test, expect } = require('@playwright/test');

const BASE_URL = 'http://localhost:3000';
const API_URL = 'http://localhost:8080/api';

test.describe('部署任务状态机功能', () => {
  test.beforeEach(async ({ page, context }) => {
    // 登录并存储 token
    await page.goto(`${BASE_URL}/login`);
    await page.fill('[data-test=username]', 'admin');
    await page.fill('[data-test=password]', 'admin123');
    await page.click('[data-test=login-btn]');
    await page.waitForURL('**/dashboard');
  });

  test('TC-01: 创建实例自动触发部署', async ({ page }) => {
    // 监听 API 请求
    const createRequest = page.waitForResponse(resp =>
      resp.url().includes('/api/instances') && resp.request().method() === 'POST'
    );

    await page.goto(`${BASE_URL}/instance/deploy`);

    // 选择游戏
    await page.click('[data-test=game-select]');
    await page.click('text=求生之路2');

    // 选择主机
    await page.click('[data-test=host-select]');
    await page.click('text=测试主机');

    // 填写实例名称
    const instanceName = `auto-test-create-${Date.now()}`;
    await page.fill('[data-test=instance-name]', instanceName);

    // 选择部署类型
    await page.click('[data-test=deploy-type-docker]');

    // 填写端口
    await page.fill('[data-test=port-host]', '27015');
    await page.fill('[data-test=port-container]', '27015');

    // 点击开始部署
    await page.click('[data-test=deploy-submit]');

    // 验证 API 响应
    const response = await createRequest;
    const body = await response.json();
    expect(body.code).toBe(200);
    expect(body.data.runStatus).toBe(5);
    expect(body.data.status).toBe('deploying');
    expect(body.data.deployTaskId).toBeTruthy();

    // 验证跳转到列表页
    await page.waitForURL('**/instance/list');

    // 验证列表显示部署中状态
    const statusTag = page.locator('tr').filter({ hasText: instanceName }).locator('.el-tag');
    await expect(statusTag).toHaveText('部署中');
    await expect(statusTag).toHaveClass(/el-tag--warning/);
  });

  test('TC-03: 查看部署进度日志', async ({ page }) => {
    // 前置：已存在 deploying 实例
    await page.goto(`${BASE_URL}/instance/list`);

    // 找到 deploying 状态的行
    const deployRow = page.locator('tr').filter({
      has: page.locator('.el-tag', { hasText: '部署中' })
    }).first();

    // 点击查看日志
    await deployRow.locator('button', { hasText: '查看日志' }).click();

    // 验证对话框打开
    const dialog = page.locator('.el-dialog').last();
    await expect(dialog).toBeVisible();

    // 验证进度条存在
    await expect(dialog.locator('.el-progress-bar')).toBeVisible();

    // 验证日志列表存在
    await expect(dialog.locator('.log-list')).toBeVisible();

    // 等待轮询请求
    await page.waitForResponse(resp =>
      resp.url().includes('/deploy-progress') && resp.status() === 200
    );

    // 关闭对话框
    await dialog.locator('.el-dialog__close').click();
    await expect(dialog).not.toBeVisible();
  });

  test('TC-05: 重试部署', async ({ page }) => {
    await page.goto(`${BASE_URL}/instance/list`);

    // 找到 error 状态的行
    const errorRow = page.locator('tr').filter({
      has: page.locator('.el-tag', { hasText: '异常' })
    }).first();

    // 点击重试
    await errorRow.locator('button', { hasText: '重试' }).click();

    // 确认对话框
    await page.click('.el-message-box__btns button:has-text("确定")');

    // 验证成功提示
    await expect(page.locator('.el-message--success')).toHaveText(/已重新触发部署/);

    // 验证状态变为部署中
    await expect(errorRow.locator('.el-tag')).toHaveText('部署中');
  });
});
```

---

## 四、测试执行清单

执行测试时，按以下清单逐项核对：

### 4.1 冒烟测试（必做）

- [ ] TC-01 创建实例自动触发部署
- [ ] TC-02 实例列表状态机显示
- [ ] TC-03 查看部署进度日志
- [ ] TC-05 重试部署功能

### 4.2 功能测试（完整）

- [ ] TC-04 部署失败查看错误日志
- [ ] TC-06 查看运行时日志
- [ ] TC-07 部署成功状态流转
- [ ] TC-09 部署提交后页面跳转
- [ ] TC-10 端口检测功能
- [ ] TC-11 列表自动刷新机制

### 4.3 异常/边界测试

- [ ] TC-08 启动恢复机制
- [ ] TC-12 日志去重与追加逻辑
- [ ] 各用例的"异常场景"验证点

### 4.4 测试后清理

- [ ] 删除所有 `auto-test-*` 命名的实例
- [ ] 清理测试产生的 Docker 容器（`docker ps -a | grep auto-test`）
- [ ] 重置数据库测试数据
- [ ] 关闭后端/前端服务（如不需要保留）

---

## 五、缺陷报告模板

发现缺陷时，按以下模板记录：

```markdown
### BUG-XXX: 缺陷标题

**所属用例**：TC-XX
**环境**：后端 {版本} / 前端 {版本} / 浏览器 {版本}
**优先级**：P0/P1/P2/P3

**复现步骤**：
1. ...
2. ...

**预期结果**：
...

**实际结果**：
...

**附件**：
- 截图/录屏
- 网络请求响应
- 控制台错误日志
- 后端日志片段
```

---

## 六、附录

### 6.1 相关 API 端点

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/instances` | 创建实例（触发异步部署） |
| GET | `/api/instances` | 实例列表（含 status 字段） |
| GET | `/api/instances/{id}` | 实例详情 |
| DELETE | `/api/instances/{id}` | 删除实例 |
| GET | `/api/instances/{id}/deploy-progress` | 部署进度+日志 |
| POST | `/api/instances/{id}/retry-deploy` | 重试部署 |
| GET | `/api/instances/{id}/logs` | 运行时日志（Docker 容器日志） |
| GET | `/api/hosts/{id}/check-port?port=xxx` | 端口检测 |

### 6.2 状态机定义

```
创建实例 → deploying(5)
                ↓
        环境校验 → 失败 → error(2)
                ↓ 成功
        预部署 → 失败 → error(2)
                ↓ 成功
        部署 → 失败 → error(2)
                ↓ 成功
    autoStart? ── 否 → stopped(0)
                ↓ 是
        starting(6)
                ↓
        健康检查（3次重试）
            ↓ 成功              ↓ 失败
        running(1)           error(2)

error(2) → retry-deploy → deploying(5)
```

### 6.3 数据库字段映射

| 数据库字段 | API 字段 | 前端字段 | 说明 |
|-----------|---------|---------|------|
| run_status=0 | runStatus=0 | status="stopped" | 已停止 |
| run_status=1 | runStatus=1 | status="running" | 运行中 |
| run_status=2 | runStatus=2 | status="error" | 异常 |
| run_status=3 | runStatus=3 | status="stopping" | 停止中 |
| run_status=5 | runStatus=5 | status="deploying" | 部署中 |
| run_status=6 | runStatus=6 | status="starting" | 启动中 |

### 6.4 关键代码位置

| 文件 | 关键方法 | 说明 |
|------|---------|------|
| `DeployService.java` | `deploy()`、`deployAsync()`、`retryHealthCheck()` | 部署核心逻辑 |
| `DeployService.java` | `LogCollectingCallback` | 日志收集回调 |
| `InstanceServiceImpl.java` | `createInstance()` | 触发异步部署 |
| `InstanceServiceImpl.java` | `mapRunStatusToString()` | 状态映射 |
| `InstanceController.java` | `getDeployProgress()`、`retryDeploy()` | 新增端点 |
| `DeployRecoveryListener.java` | `onApplicationEvent()` | 启动恢复 |
| `DeployProgress.vue` | `fetchProgress()`、`appendRuntimeLogs()` | 双模式日志 |
| `instance/index.vue` | `handleViewLogs()`、`handleRetryDeploy()`、`startAutoRefresh()` | 列表交互 |
