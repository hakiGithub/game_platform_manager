# InstanceFileService SPI 迁移 - 浏览器测试用例

> **覆盖范围**：L4D2 插件 8 个服务从 FileAccessService（hostId+绝对路径）迁移到 InstanceFileService（instanceId+相对路径）后的功能回归验证。
>
> **测试目标**：验证文件操作类功能在 SPI 改造后仍正常工作，重点覆盖 Native（linuxgsm）与 Docker（docker-compose）两种路由路径。
>
> **测试环境**：
> - 后端：`http://localhost:8080`（PID 见 `netstat -ano | findstr :8080`）
> - 前端：`http://localhost:3000`（主应用，代理 `/api` → 8080）
> - 测试实例：`instanceId=54`（gameCode=l4d2, deployType=docker-compose, instanceName=l4d2_server）
> - 默认账号：`admin / admin123`
>
> **执行工具**：TRAE `webapp-testing` skill 或 `agent-browser` skill
>
> **最后更新**：2026-07-25

---

## 0. 前置准备

### 0.1 环境检查

- [ ] 后端 8080 端口监听
- [ ] 前端 3000 端口监听
- [ ] 插件 plugin-l4d2 状态为 STARTED（`GET /api/pf4j/plugins` 返回 `running: true`）
- [ ] 测试实例 54 存在（`GET /api/instances?gameCode=l4d2` 返回 id=54）

### 0.2 实例上下文

| 字段 | 值 |
|------|-----|
| instanceId | 54 |
| instanceName | l4d2_server |
| gameCode | l4d2 |
| deployType | docker-compose |
| 主机 | 见 `GET /api/instances/54` 的 hostId |

> **注意**：实例 54 当前 `runStatus=0`（已停止）。部分依赖运行中服务器的测试（如 RCON 热重载）需先启动实例。文件操作类测试（地图列表、插件列表、备份列表等）不依赖运行状态。

---

## 1. 迁移服务与页面映射

| # | 迁移服务 | 主对应页面 | 路由路径 | 关键 API |
|---|---------|-----------|---------|---------|
| S1 | MapService | 地图管理 | `/maps` | `GET/POST/DELETE /api/plugin/l4d2/maps/*` |
| S2 | PluginInstallService | 插件管理 | `/plugins` | `GET/POST/DELETE /api/plugin/l4d2/plugins/*` |
| S3 | PluginExportService | 插件管理 | `/plugins` | `GET /api/plugin/l4d2/plugins/export-all/*` |
| S4 | BackupService | 备份还原 | `/backup` | `GET/POST/DELETE /api/plugin/l4d2/backups/*` |
| S5 | SourceModCfgService | 插件配置 | `/plugin-config` | `GET/POST /api/plugin/l4d2/plugin-config/*` |
| S6 | SourceModLogService | 日志 | `/logs` | `GET /api/plugin/l4d2/logs/*` |
| S7 | ChunkUploadService | 地图/插件上传 | `/maps`, `/plugins` | `POST /api/plugin/l4d2/chunk-upload/*` |
| S8 | FileRefsService | 内部服务（被 S2 调用） | `/plugins` | 通过 `DELETE /plugins/{name}` 间接验证 |

---

## 2. 浏览器测试用例

### E2E-IFS-001：登录主应用并进入 L4D2 插件地图管理页

**优先级**：✅ 必须通过
**覆盖服务**：S1（MapService）
**验证点**：Wujie 加载、SPI 注入、地图列表 API 调用

- [ ] **Step 1**：打开 `http://localhost:3000/`
- [ ] **Step 2**：登录页输入 `admin / admin123`，点击登录
- [ ] **Step 3**：等待跳转到首页，侧边栏出现「求生之路2」一级菜单
- [ ] **Step 4**：点击「求生之路2」展开，显示 10 个子菜单
- [ ] **Step 5**：点击「地图管理」
- [ ] **Step 6**：若弹出实例选择对话框，选择 `l4d2_server` 实例
- [ ] **Step 7**：等待地图管理页加载完成
- [ ] **验证**：
  - URL 包含 `/plugin/l4d2/maps?instanceId=54`
  - 页面标题显示「地图管理」
  - 浏览器 Network 面板：`GET /api/plugin/l4d2/maps/list?instanceId=54` 返回 200
  - 浏览器 Console：无红色 error
  - 后端日志：出现 `已注册插件可用服务: ... InstanceFileService`

**期望结果**：地图管理页正常渲染，列表为空或显示已上传地图。SPI 注入日志确认 InstanceFileService 已注册。

---

### E2E-IFS-002：地图列表加载（Docker 路由验证）

**优先级**：✅ 必须通过
**覆盖服务**：S1（MapService）+ InstanceFileService Docker 路由
**验证点**：Docker compose 实例通过 `docker exec` 读取容器内文件

- [ ] **Step 1**：在 E2E-IFS-001 基础上，确保已进入地图管理页
- [ ] **Step 2**：点击「刷新地图列表」按钮（如有）
- [ ] **Step 3**：等待列表加载
- [ ] **验证**：
  - Network：`GET /api/plugin/l4d2/maps/list?instanceId=54` 返回 200，`code: 200`
  - 后端日志：出现 `docker exec <containerId> sh -c 'ls -la /app/left4dead2/addons'` 类似命令（或 `docker compose -p ... ps -q` 解析容器 ID）
  - 无 500 错误，无 `IllegalStateException: 容器未运行` 异常

**期望结果**：地图列表正常加载（可能为空数组 `[]`，但请求成功）。Docker 路由的容器 ID 解析、workDir 拼接、`docker exec` 命令执行均正常。

---

### E2E-IFS-003：上传 VPK 地图文件（小文件直传）

**优先级**：✅ 必须通过
**覆盖服务**：S1（MapService）
**验证点**：`POST /maps/upload` multipart 上传

- [ ] **Step 1**：在地图管理页，点击「上传地图」按钮
- [ ] **Step 2**：选择一个 VPK 测试文件（< 5MB，避开分片上传阈值）
- [ ] **Step 3**：等待上传完成
- [ ] **验证**：
  - Network：`POST /api/plugin/l4d2/maps/upload?instanceId=54` 返回 200
  - 上传进度条正常显示
  - 上传完成后列表自动刷新，新地图出现在列表中
  - 后端日志：InstanceFileService 通过 `docker cp` 将文件上传到容器

**期望结果**：VPK 地图上传成功，列表刷新显示新地图。

---

### E2E-IFS-004：上传大文件 VPK（分片上传 ChunkUploadService）

**优先级**：⚠️ 尽量通过
**覆盖服务**：S7（ChunkUploadService）
**验证点**：`/chunk-upload/init` → `/chunk-upload/{id}/chunk` → `/chunk-upload/{id}/complete` 完整流程

- [ ] **Step 1**：准备一个 > 10MB 的 VPK 测试文件（或配置较低的分片阈值）
- [ ] **Step 2**：在地图管理页点击「上传地图」，选择该文件
- [ ] **Step 3**：观察上传过程
- [ ] **验证**：
  - Network 依次出现：
    1. `POST /api/plugin/l4d2/chunk-upload/init` 返回 uploadId
    2. 多次 `POST /api/plugin/l4d2/chunk-upload/{uploadId}/chunk?index=N`（分片上传）
    3. `POST /api/plugin/l4d2/chunk-upload/{uploadId}/complete`（合并完成）
  - 进度条显示总体进度（0% → 100%）
  - 上传完成后列表刷新

**期望结果**：分片上传全流程成功，文件最终合并到容器内正确路径。

---

### E2E-IFS-005：删除地图

**优先级**：✅ 必须通过
**覆盖服务**：S1（MapService）
**验证点**：`DELETE /maps/{mapName}` 删除容器内文件

- [ ] **Step 1**：确保列表中至少有一个地图（可由 E2E-IFS-003 上传产生）
- [ ] **Step 2**：点击某地图的「删除」按钮
- [ ] **Step 3**：确认删除对话框
- [ ] **验证**：
  - Network：`DELETE /api/plugin/l4d2/maps/{mapName}?instanceId=54` 返回 200
  - 列表中该地图消失
  - 后端日志：InstanceFileService 通过 `docker exec rm -f` 删除容器内文件

**期望结果**：地图删除成功，列表更新。

---

### E2E-IFS-006：插件列表加载

**优先级**：✅ 必须通过
**覆盖服务**：S2（PluginInstallService）
**验证点**：`GET /plugins/list` 读取容器内 sourcemod/plugins 目录

- [ ] **Step 1**：从侧边栏点击「插件管理」
- [ ] **Step 2**：等待插件列表加载
- [ ] **验证**：
  - URL 包含 `/plugin/l4d2/plugins?instanceId=54`
  - Network：`GET /api/plugin/l4d2/plugins/list?instanceId=54` 返回 200
  - 列表显示已安装的 .smx 插件（可能为空，但请求成功）
  - 后端日志：`docker exec <containerId> sh -c 'ls -la /app/left4dead2/addons/sourcemod/plugins'`

**期望结果**：插件列表正常加载。

---

### E2E-IFS-007：上传 SourceMod 插件（.smx）

**优先级**：✅ 必须通过
**覆盖服务**：S2（PluginInstallService）+ S8（FileRefsService 间接）
**验证点**：`POST /plugins/upload` 上传 + 自动安装 + 文件引用登记

- [ ] **Step 1**：在插件管理页，点击「上传插件」
- [ ] **Step 2**：选择一个 .smx 测试文件
- [ ] **Step 3**：等待上传完成
- [ ] **验证**：
  - Network：`POST /api/plugin/l4d2/plugins/upload?instanceId=54` 返回 200
  - 上传成功提示
  - 列表刷新显示新插件
  - 后端日志：FileRefsService.addRefs 被调用，写入 `.file_refs.json` 到容器

**期望结果**：插件上传成功，文件引用被正确登记。

---

### E2E-IFS-008：删除插件（FileRefsService 归零文件清理）

**优先级**：✅ 必须通过
**覆盖服务**：S2（PluginInstallService）+ S8（FileRefsService）
**验证点**：删除插件时，无引用的共享 cfg 文件被清理

- [ ] **Step 1**：确保列表中至少有一个插件（可由 E2E-IFS-007 上传产生）
- [ ] **Step 2**：点击某插件的「删除」按钮
- [ ] **Step 3**：确认删除
- [ ] **验证**：
  - Network：`DELETE /api/plugin/l4d2/plugins/{pluginName}?instanceId=54` 返回 200
  - 列表中该插件消失
  - 后端日志：
    - FileRefsService.removeRefs 被调用
    - 返回归零的共享文件列表
    - InstanceFileService.deleteFile 删除无引用的共享 cfg 文件

**期望结果**：插件删除成功，关联的共享文件被正确清理。

---

### E2E-IFS-009：全量导出插件（PluginExportService）

**优先级**：⚠️ 尽量通过
**覆盖服务**：S3（PluginExportService）
**验证点**：`/export-all/start` → `/export-all/status` 轮询 → `/export-all/download`

- [ ] **Step 1**：在插件管理页，点击「全量导出」按钮
- [ ] **Step 2**：等待导出任务启动
- [ ] **Step 3**：观察导出进度（前端轮询 status）
- [ ] **Step 4**：导出完成后点击下载
- [ ] **验证**：
  - Network：
    1. `GET /api/plugin/l4d2/plugins/export-all/start?instanceId=54` 返回 200
    2. 多次 `GET /api/plugin/l4d2/plugins/export-all/status?instanceId=54`（轮询，返回 progress）
    3. `GET /api/plugin/l4d2/plugins/export-all/download?instanceId=54`（下载 ZIP）
  - 浏览器触发文件下载
  - 后端日志：InstanceFileService 通过 `docker cp` 从容器下载文件到主机临时目录，再打包 ZIP

**期望结果**：全量导出成功，ZIP 文件下载到本地。

---

### E2E-IFS-010：备份列表加载

**优先级**：✅ 必须通过
**覆盖服务**：S4（BackupService）
**验证点**：`GET /backups/list` 读取备份目录

- [ ] **Step 1**：从侧边栏点击「备份还原」
- [ ] **Step 2**：等待备份列表加载
- [ ] **验证**：
  - URL 包含 `/plugin/l4d2/backup?instanceId=54`
  - Network：`GET /api/plugin/l4d2/backups/list?instanceId=54` 返回 200
  - 列表显示已有备份（可能为空）

**期望结果**：备份列表正常加载。

---

### E2E-IFS-011：创建备份

**优先级**：✅ 必须通过
**覆盖服务**：S4（BackupService）
**验证点**：`POST /backups/create` 打包容器内游戏数据

- [ ] **Step 1**：在备份还原页，点击「创建备份」
- [ ] **Step 2**：输入备份名称（如 `test_backup_001`）
- [ ] **Step 3**：确认创建
- [ ] **Step 4**：等待备份完成
- [ ] **验证**：
  - Network：`POST /api/plugin/l4d2/backups/create` 返回 200
  - 进度提示
  - 备份完成后列表刷新，新备份出现
  - 后端日志：InstanceFileService 通过 `docker cp` 将容器内目录下载到主机，再打包

**期望结果**：备份创建成功，列表更新。

---

### E2E-IFS-012：还原备份

**优先级**：⚠️ 尽量通过
**覆盖服务**：S4（BackupService）
**验证点**：`POST /backups/restore` 将备份还原到容器

- [ ] **Step 1**：确保列表中至少有一个备份（可由 E2E-IFS-011 创建）
- [ ] **Step 2**：点击某备份的「还原」按钮
- [ ] **Step 3**：确认还原对话框
- [ ] **Step 4**：等待还原完成
- [ ] **验证**：
  - Network：`POST /api/plugin/l4d2/backups/restore` 返回 200
  - 还原成功提示
  - 后端日志：InstanceFileService 通过 `docker cp` 将主机备份上传到容器

**期望结果**：备份还原成功。

---

### E2E-IFS-013：重命名 + 删除备份

**优先级**：✅ 必须通过
**覆盖服务**：S4（BackupService）
**验证点**：`POST /backups/rename` + `DELETE /backups/{id}`

- [ ] **Step 1**：在备份列表中，点击某备份的「重命名」
- [ ] **Step 2**：输入新名称，确认
- [ ] **验证**：Network `POST /api/plugin/l4d2/backups/rename` 返回 200，列表更新
- [ ] **Step 3**：点击「删除」按钮
- [ ] **Step 4**：确认删除
- [ ] **验证**：Network `DELETE /api/plugin/l4d2/backups/{backupId}` 返回 200，列表更新

**期望结果**：重命名和删除均成功。

---

### E2E-IFS-014：插件配置 - 获取候选 cfg 路径

**优先级**：✅ 必须通过
**覆盖服务**：S5（SourceModCfgService）
**验证点**：`GET /plugin-config/candidates` 检测容器内文件存在性

- [ ] **Step 1**：从侧边栏点击「插件配置」
- [ ] **Step 2**：选择一个插件（如 `admin-flatloader`）
- [ ] **Step 3**：等待配置加载
- [ ] **验证**：
  - Network：`GET /api/plugin/l4d2/plugin-config/candidates?instanceId=54&pluginName=admin-flatloader` 返回 200
  - 响应 data 包含候选路径列表，每项含 `path`（相对路径如 `cfg/sourcemod/admin-flatloader.cfg`）和 `exists`（布尔值）
  - 后端日志：InstanceFileService.exists 通过 `docker exec test -e` 检测文件

**期望结果**：候选 cfg 路径正确返回，相对路径格式符合 SPI 规范。

---

### E2E-IFS-015：插件配置 - 获取并更新配置

**优先级**：✅ 必须通过
**覆盖服务**：S5（SourceModCfgService）
**验证点**：`GET /plugin-config/get` + `POST /plugin-config/update`

- [ ] **Step 1**：在插件配置页，选择有 cfg 文件的插件
- [ ] **Step 2**：等待配置表单加载
- [ ] **验证**：Network `GET /api/plugin/l4d2/plugin-config/get?instanceId=54&pluginName=...` 返回 200
- [ ] **Step 3**：修改某个配置项
- [ ] **Step 4**：点击「保存」
- [ ] **验证**：
  - Network：`POST /api/plugin/l4d2/plugin-config/update` 返回 200
  - 保存成功提示
  - 后端日志：InstanceFileService.writeTextFile 通过 `docker exec base64 -d >` 写入容器

**期望结果**：配置读取和保存均正常。

---

### E2E-IFS-016：日志文件列表加载

**优先级**：✅ 必须通过
**覆盖服务**：S6（SourceModLogService）
**验证点**：`GET /logs/files` 读取容器内日志目录

- [ ] **Step 1**：从侧边栏点击「日志」
- [ ] **Step 2**：等待日志文件列表加载
- [ ] **验证**：
  - URL 包含 `/plugin/l4d2/logs?instanceId=54`
  - Network：`GET /api/plugin/l4d2/logs/files?instanceId=54` 返回 200
  - 列表显示日志文件（可能为空，但请求成功）
  - 后端日志：`docker exec <containerId> sh -c 'ls -la /app/left4dead2/addons/sourcemod/logs'`

**期望结果**：日志文件列表正常加载。

---

### E2E-IFS-017：日志内容查看

**优先级**：✅ 必须通过
**覆盖服务**：S6（SourceModLogService）
**验证点**：`GET /logs/content` 读取容器内日志文件内容

- [ ] **Step 1**：确保日志文件列表中至少有一个文件
- [ ] **Step 2**：点击某日志文件
- [ ] **Step 3**：等待内容加载
- [ ] **验证**：
  - Network：`GET /api/plugin/l4d2/logs/content?instanceId=54&file=...` 返回 200
  - 日志内容显示在页面中
  - 后端日志：InstanceFileService 通过 `docker exec base64 -w0` 读取容器内文件

**期望结果**：日志内容正常显示。

---

### E2E-IFS-018：SSE 实时日志流

**优先级**：⚠️ 尽量通过
**覆盖服务**：S6（SourceModLogService）
**验证点**：`GET /logs/stream` SSE 推送

- [ ] **Step 1**：在日志页，点击「实时日志」或选择某文件开启实时流
- [ ] **Step 2**：等待 SSE 连接建立
- [ ] **验证**：
  - Network：`GET /api/plugin/l4d2/logs/stream?instanceId=54&file=...` 类型为 `text/event-stream`
  - 浏览器接收 SSE 事件（data 行）
  - 后端日志：InstanceFileService.tailFile 通过 `docker exec tail -c +N` 读取增量

**期望结果**：SSE 连接建立，实时推送日志增量。

---

### E2E-IFS-019：Wujie 集成 - 主题切换同步

**优先级**：⚠️ 尽量通过
**覆盖服务**：无（Wujie 集成回归）
**验证点**：主应用主题切换同步到子应用

- [ ] **Step 1**：在主应用，点击主题切换按钮（深色/浅色）
- [ ] **Step 2**：观察 L4D2 子应用页面
- [ ] **验证**：
  - 子应用主题同步切换
  - 无明显闪烁

**期望结果**：主题切换同步生效。

---

### E2E-IFS-020：浏览器 Console 无错误

**优先级**：✅ 必须通过
**覆盖服务**：全部
**验证点**：整个测试流程中浏览器 Console 无红色 error

- [ ] **Step 1**：在所有测试用例执行过程中，打开浏览器 DevTools Console
- [ ] **Step 2**：记录所有 error 级别日志
- [ ] **验证**：
  - 无 `Failed to load resource: 404` 错误（特别是 JS/CSS 资源）
  - 无 `Failed to load resource: 403` 错误（SecurityConfig 配置生效）
  - 无 JavaScript 运行时错误
  - 允许 warning 级别日志

**期望结果**：Console 无 error 级别日志。

---

## 3. 路径校验专项测试（安全边界）

### E2E-IFS-021：路径越界防护

**优先级**：✅ 必须通过
**覆盖服务**：AbstractInstanceFileService.validateRelativePath
**验证点**：相对路径包含 `..` 时被拒绝

- [ ] **Step 1**：通过 curl/Postman 直接调用 API，传入包含 `..` 的路径
- [ ] **Step 2**：尝试以下场景：
  ```
  GET /api/plugin/l4d2/logs/content?instanceId=54&file=../../etc/passwd
  GET /api/plugin/l4d2/plugin-config/get?instanceId=54&pluginName=../../../etc/hosts
  ```
- [ ] **验证**：
  - 后端返回 400 或 500，错误信息包含「相对路径禁止包含 ..」
  - 后端日志：`IllegalArgumentException: 相对路径禁止包含 ..`
  - 无任何文件内容泄露

**期望结果**：路径越界请求被拒绝，无敏感文件泄露。

---

### E2E-IFS-022：不支持的部署类型

**优先级**：⚠️ 尽量通过
**覆盖服务**：InstanceFileServiceImpl.buildRoute
**验证点**：未知 deployType 抛出 UnsupportedOperationException

- [ ] **Step 1**：构造一个 deployType 为 `unknown` 的测试实例（通过数据库直接修改或 mock）
- [ ] **Step 2**：调用该实例的任意文件 API
- [ ] **验证**：
  - 后端返回 500，错误信息包含「不支持的部署类型」
  - 无 NPE 或其他异常

**期望结果**：未知部署类型被明确拒绝。

---

## 4. 摘要计算专项测试

### E2E-IFS-023：MD5 摘要计算（Native 路由）

**优先级**：⚠️ 尽量通过
**覆盖服务**：InstanceFileService.computeDigest
**验证点**：Native 路由通过 `md5sum` 命令计算摘要

- [ ] **Step 1**：准备一个 Native（linuxgsm）部署类型的 L4D2 实例（如 instanceId=X）
- [ ] **Step 2**：通过插件配置页或直接 API 调用，触发摘要计算
- [ ] **验证**：
  - 后端日志：`md5sum /path/to/file` 命令执行
  - 返回 32 位十六进制摘要字符串

**期望结果**：MD5 摘要正确返回。

> **注意**：当前测试实例 54 为 docker-compose 类型，摘要计算会通过 `docker cp` 到主机临时目录再 `md5sum`。如需测试 Native 路由，需准备 linuxgsm 实例。

---

## 5. 测试结果汇总模板

| 用例 ID | 用例名称 | 优先级 | 结果 | 备注 |
|---------|---------|--------|------|------|
| E2E-IFS-001 | 登录并进入地图管理页 | ✅ 必须 | ⬜ | |
| E2E-IFS-002 | 地图列表加载（Docker 路由） | ✅ 必须 | ⬜ | |
| E2E-IFS-003 | 上传 VPK 地图（小文件） | ✅ 必须 | ⬜ | |
| E2E-IFS-004 | 上传大文件 VPK（分片） | ⚠️ 尽量 | ⬜ | |
| E2E-IFS-005 | 删除地图 | ✅ 必须 | ⬜ | |
| E2E-IFS-006 | 插件列表加载 | ✅ 必须 | ⬜ | |
| E2E-IFS-007 | 上传 SourceMod 插件 | ✅ 必须 | ⬜ | |
| E2E-IFS-008 | 删除插件（FileRefs 清理） | ✅ 必须 | ⬜ | |
| E2E-IFS-009 | 全量导出插件 | ⚠️ 尽量 | ⬜ | |
| E2E-IFS-010 | 备份列表加载 | ✅ 必须 | ⬜ | |
| E2E-IFS-011 | 创建备份 | ✅ 必须 | ⬜ | |
| E2E-IFS-012 | 还原备份 | ⚠️ 尽量 | ⬜ | |
| E2E-IFS-013 | 重命名 + 删除备份 | ✅ 必须 | ⬜ | |
| E2E-IFS-014 | 插件配置 - 候选 cfg 路径 | ✅ 必须 | ⬜ | |
| E2E-IFS-015 | 插件配置 - 获取并更新 | ✅ 必须 | ⬜ | |
| E2E-IFS-016 | 日志文件列表加载 | ✅ 必须 | ⬜ | |
| E2E-IFS-017 | 日志内容查看 | ✅ 必须 | ⬜ | |
| E2E-IFS-018 | SSE 实时日志流 | ⚠️ 尽量 | ⬜ | |
| E2E-IFS-019 | Wujie 主题切换同步 | ⚠️ 尽量 | ⬜ | |
| E2E-IFS-020 | Console 无错误 | ✅ 必须 | ⬜ | |
| E2E-IFS-021 | 路径越界防护 | ✅ 必须 | ⬜ | |
| E2E-IFS-022 | 不支持的部署类型 | ⚠️ 尽量 | ⬜ | |
| E2E-IFS-023 | MD5 摘要计算 | ⚠️ 尽量 | ⬜ | |

**结果标记**：✅ 通过 / ❌ 失败 / ⚠️ 阻塞 / ⬜ 未执行

---

## 6. 已知限制与注意事项

### 6.1 测试实例限制

- 当前测试实例 54 为 `docker-compose` 部署类型，覆盖 Docker 路由
- Native（linuxgsm）路由未覆盖，需额外准备 linuxgsm 实例
- 实例当前 `runStatus=0`（已停止），依赖运行状态的测试（如 RCON 热重载）需先启动实例

### 6.2 Wujie 沙箱注意事项

- 浏览器自动化操作子应用 DOM 时，可能需要 `browser_evaluate` 进入 Wujie iframe 上下文
- Network 面板可能不显示子应用内的请求，需通过后端日志或 `browser_network_requests` 工具捕获

### 6.3 文件上传测试数据

- VPK 测试文件需自行准备（或使用任意 .vpk 文件）
- .smx 插件测试文件需自行准备（可从 SourceMod 官方仓库下载）
- 大文件分片上传测试需准备 > 10MB 的文件

### 6.4 SPI 路由验证重点

- **Docker 路由**：通过后端日志验证 `docker exec` / `docker cp` 命令执行
- **容器 ID 解析**：`docker-compose` 类型通过 `docker compose -p <projectName> ps -q <serviceName>` 解析
- **containerWorkDir**：由部署适配器写入 configInfo，InstanceFileServiceImpl 读取后拼接相对路径
- **路径安全**：所有相对路径经 `validateRelativePath` 校验，禁止 `..` 越界

---

## 7. 关联文档

- [InstanceFileService SPI 设计](../superpowers/specs/2026-07-24-instance-file-service-spi-design.md)
- [InstanceFileService SPI 实现计划](../superpowers/plans/2026-07-24-instance-file-service-spi.md)
- [E2E 验证清单](07-e2e-checklist.md)
- [插件子应用测试用例](05-plugin-subapp-cases.md)
- [Wujie 集成测试用例](06-wujie-integration-cases.md)
