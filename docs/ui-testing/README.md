# UI 测试文档总览

> Game Platform Manager - UI 测试分层文档入口

---

## 文档结构

| 编号 | 文档 | 描述 |
|------|------|------|
| 01 | [测试策略与分层](./01-strategy.md) | 顶层策略：测试目标、分层原则、覆盖范围 |
| 02 | [工具链与环境](./02-toolchain.md) | Vitest / Vue Test Utils / Playwright / happy-dom 配置与运行 |
| 03 | [测试金字塔](./03-test-pyramid.md) | 单元 → 组件 → 集成 → E2E 的金字塔结构与比例 |
| 04 | [主应用测试用例](./04-main-app-cases.md) | 主应用前端（frontend/）的视图、组件、Store 用例 |
| 05 | [插件子应用测试用例](./05-plugin-subapp-cases.md) | plugin-l4d2/frontend/ 的页面与工具用例 |
| 06 | [Wujie 微前端集成测试](./06-wujie-integration-cases.md) | 主应用与插件子应用通信、菜单、路由集成 |
| 07 | [E2E 验证清单](./07-e2e-checklist.md) | 手动/半自动 E2E 验收清单（按业务场景） |
| 08 | [CI 集成与自动化](./08-ci-integration.md) | GitHub Actions / Jenkins 配置、覆盖率门槛 |

## 快速导航

### 默认验证账号

> 初始账号来源：`backend/core/src/main/resources/db/data.sql`，首次启动自动初始化。

| 字段 | 值 |
|------|----|
| 用户名 | `admin` |
| 密码 | `admin123` |
| 登录端点 | `POST /api/auth/login` |
| 前端入口 | http://localhost:3000/ |
| 后端入口 | http://localhost:8080/ |

详细登录示例与环境就绪验证清单见 [02-toolchain.md §5](./02-toolchain.md) 与 [07-e2e-checklist.md §2](./07-e2e-checklist.md)。

### 重启操作速查

| 场景 | 命令 |
|------|------|
| 全栈编译并重启 | `.\scripts\rebuild-restart-all.ps1` |
| 仅改后端 Java 代码 | `.\scripts\rebuild-restart-all.ps1 -SkipPlugins` |
| 仅改前端代码 | `.\scripts\rebuild-restart-all.ps1 -SkipBackendCompile -SkipPlugins` |
| 仅改插件代码 | `.\scripts\rebuild-restart-all.ps1`（含插件打包） |
| 仅重启前端 | `cd frontend; .\scripts\rebuild-restart.ps1` |
| 仅改后端配置文件（最快） | `cd backend; .\scripts\rebuild-restart.ps1 -SkipCompile` |

完整脚本路径、停止服务、日志查看等见 [02-toolchain.md §5.6](./02-toolchain.md)。

### 按角色
- **测试人员** → 从 [07-e2e-checklist.md](./07-e2e-checklist.md) 开始（含验证账号 + 前置步骤）
- **前端开发** → 从 [04-main-app-cases.md](./04-main-app-cases.md) 和 [05-plugin-subapp-cases.md](./05-plugin-subapp-cases.md) 开始
- **架构师** → 从 [01-strategy.md](./01-strategy.md) 和 [03-test-pyramid.md](./03-test-pyramid.md) 开始
- **DevOps** → 从 [02-toolchain.md](./02-toolchain.md) 和 [08-ci-integration.md](./08-ci-integration.md) 开始

### 按场景
- **新增页面** → 阅读 04 / 05 → 复制对应模板 → 补充用例
- **新增插件** → 阅读 06 → 编写通信契约用例
- **发布前验收** → 执行 07 中的 E2E 清单（先执行 §2 环境准备）
- **调试 Wujie 集成** → 阅读 06 中的常见问题章节
- **环境启动失败** → 阅读 [02-toolchain.md §5.5](./02-toolchain.md) 一键启动脚本参数说明

## 当前状态

| 维度 | 状态 | 备注 |
|------|------|------|
| 单元测试 | ✅ 已有 | 主应用 frontend/src/tests/、插件 backend/plugin-l4d2/frontend/src/ |
| 组件测试 | ✅ 已有 | 覆盖 BackupForm、PluginContainer 等关键组件 |
| E2E 测试 | ⚠️ 部分 | 浏览器自动化验证通过 TRAE browser_use 工具执行 |
| 视觉回归 | 📋 规划中 | 待引入 Playwright screenshot 对比 |
| 性能测试 | 📋 规划中 | Lighthouse CI 接入 |

## 维护约定

1. **新增功能**：同步更新对应测试用例文档
2. **Bug 修复**：在 E2E 清单中补充回归用例
3. **架构变更**：更新 01-strategy 和 03-test-pyramid
4. **工具链升级**：更新 02-toolchain 和 08-ci-integration

---

*最后更新: 2026-07-20*
