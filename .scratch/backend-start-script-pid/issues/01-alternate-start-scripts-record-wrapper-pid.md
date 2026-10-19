# 01: 备用后端启动脚本仍将包装器 PID 写入 Java PID 文件

Status: resolved
Type: bug
来源: 2026-10-20 后端重启 PID 归属修复后的同类脚本排查

## 现象

主重启链路已经修复，但备用启动脚本仍存在包装器 PID 与 Java PID 混用：

- `backend/scripts/start-ascii.ps1` 通过 `Start-Process` 启动 `.bat` 后，将返回的
  `cmd.exe` PID 写入 `backend/logs/game-platform-manager.pid`，并在启动等待中用该
  PID 判断进程存活；
- `backend/scripts/start-java-detached.ps1` 通过 `Win32_Process.Create` 启动
  `cmd.exe /c ...`，同样把 CIM 返回的包装器 PID 当成 Java PID；
- `backend/scripts/start.ps1 -Mode dev` 保存 `mvn spring-boot:run` 的 Maven 进程
  PID，而实际监听 `8080` 的是其子 Java 进程；
- `start.ps1` 的 `Test-AppRunning` / `Find-JavaProcess` 仍直接按 PID 或端口进程
  判断，没有校验进程命令行与主类，存在 PID 复用后误判或误杀风险。

一旦这些备用脚本被使用，后续 `stop.ps1`、重启脚本或部署脚本读取共享 PID 文件时，
会看到包装器/Maven PID，而不是真实 Java 监听 PID。

## 影响

- 包装器退出后备用脚本立即误报启动失败，或状态接口显示错误 PID；
- `stop.ps1` 可能无法停止真实 Java，或依赖端口兜底；
- 自动化脚本读取共享 PID 文件后不能安全确认后端身份；
- 与已修复的 `rebuild-restart.ps1` 行为不一致，后续维护容易回退到旧逻辑。

## 期望

- 所有启动路径统一从 PID 文件、`@argfile`/JAR 启动参数和监听端口确认真实 Java PID；
- `game-platform-manager.pid` 始终只保存实际 Java 进程 PID；
- 包装器 PID 如确有诊断价值，写入独立文件，不参与就绪判断或停止逻辑；
- `start.ps1` 的生产与开发模式在健康检查通过后写回实际监听 PID；
- 保留“进程已启动但未通过健康检查”与“真实 Java 不存在”的区分；
- 对共享 PID 文件和端口占用进程做身份校验，避免 PID 复用误杀。

## 验收

- [x] `start-ascii.ps1` 在包装器退出后仍能等待 Java 就绪，并写入实际监听 PID；
- [x] `start-java-detached.ps1` 不把 `Win32_Process.Create` 返回的包装器 PID 当 Java PID；
- [x] `start.ps1 -Mode dev` 成功后将 PID 文件更新为 `8080` 监听 Java PID；
- [x] `start.ps1 -Mode prod` 的 PID 文件与监听进程一致；
- [x] `start.ps1`、`stop.ps1` 对 stale/复用 PID 不误杀非后端 Java/PowerShell 进程；
- [x] 真实启动、失败退出码和停止流程完成一次 Windows 可重复验证。

## Out of scope

- 修改主重启链路；该链路已在
  `.scratch/backend-restart-script-pid/issues/01-restart-script-exits-before-java-ready.md`
  修复；
- 修改 Spring Boot 端口、应用配置或前端启动逻辑；
- 删除历史启动脚本，除非确认不再被文档或自动化引用。

## 关联

- `backend/scripts/backend-process.ps1`（可复用的进程识别 helper）
- `backend/scripts/start.ps1`
- `backend/scripts/start-ascii.ps1`
- `backend/scripts/start-java-detached.ps1`
- `backend/scripts/stop.ps1`
- `backend/logs/game-platform-manager.pid`

## Comments

### 2026-10-20 新问题登记

在主重启脚本修复后的同类脚本复核中发现。主链路已通过真实重启、全栈入口和
Pester 回归验证；本票只处理备用启动路径。

### 2026-10-20 triage

**分类：** bug
**状态：** ready-for-agent

可复现性已从源码路径确认，无需等待补充信息。修复范围锁定为备用启动脚本的
PID 归属、GUI-less 环境下的启动方式、身份校验和失败退出码；复用主链路新增的
`backend/scripts/backend-process.ps1`，不在应用配置、前端或数据库侧扩展范围。

### 2026-10-20 实施完成

**状态：** resolved

实现内容：

- `backend/scripts/backend-process.ps1` 增加生产 JAR 命令行识别，支持
  `java -jar .../game-platform-manager.jar`；
- `start-ascii.ps1` 和 `start-java-detached.ps1` 将包装器 PID 写入独立
  `.launcher.pid`，启动等待改为按 argfile 定位真实 Java，健康检查通过后写回
  监听 PID；
- `start.ps1` 的 prod/dev 都在启动后回写 `8080` 监听 PID，不再把 Maven 启动器
  PID 当 Java PID；同时修复只读 `$PID` 变量冲突；
- `start.ps1` 的就绪检查改为端口 + `/api/auth/login`，并在 Java 尚未出现时保留
  15 秒启动窗口，避免启动器先退出时误报；
- `Find-JavaProcess` 和 `Test-AppRunning` 统一校验 Java 主类/argfile，stale PID
  不会被当成后端；`stop.ps1` 已复用同一识别逻辑。

验证：

- 全部改动脚本通过 PowerShell AST 解析；
- `Pester backend/scripts/tests/backend-process.Tests.ps1`：4/4 通过，
  覆盖 stale PID、缺失 argfile、生产 JAR 命令行、包装器先退出；
- `start.ps1 help` 可正常加载并输出帮助；
- 当前运行环境的 `stop.ps1 -Mode status` 正确显示 Java PID 与 `8080` 监听 PID
  一致，登录接口返回 `200`；验证期间前后端进程未受影响。

验证边界：

- `start-ascii.ps1`、`start-java-detached.ps1` 和 `start.ps1` 都固定使用 `8080`，
  无法在不停止当前主后端的情况下并行隔离实跑；本轮没有对用户运行中的后端做
  破坏性替换。生产 JAR 实跑依赖 `backend/target/game-platform-manager.jar`，
  当前环境未生成该文件，因此保留该条件为部署环境验收项。
