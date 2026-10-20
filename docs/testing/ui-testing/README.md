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
| 09 | [Instance File Service SPI 用例](./09-instance-file-service-spi-cases.md) | 实例文件服务 SPI 契约与边界用例 |
| 10 | [实例部署演练说明](./10-deploy-instance-walkthrough.md) | l4d2 / DST / SDTD 部署与生命周期演练 |
| 11 | [E2E 覆盖现状与缺口分析](./11-e2e-coverage-gap-analysis.md) | 当前自动化覆盖、Steam302/L4D2/RCON 缺口与补测顺序 |

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

## UI Audit 与交互遍历（历史一次性审计记录）

> **这是历史记录，不是项目当前具备的能力。** 下述流程只在 2026-08 的一次 agent 会话里被人工执行过一轮，从未沉淀为仓库内的脚本、测试用例或 CI 环节，结果也无法通过重跑仓库内容复现。其全部产物（脚本 / 报告 / 截图目录）按 `.gitignore` 约定属工作产物，不入库，当前仓库中并不存在。

那次审计的做法是：遍历关键页面并截图，据此分析视觉一致性、布局对齐、标签截断、按钮状态；再逐个对页面上的可交互元素执行点击/悬停/输入，截图记录操作前后的变化。涉及的产物文件名如下：

| 产物 | 文件/目录（未入库） |
|------|--------------------|
| 审计报告 | `ui_audit_report.md` |
| 交互审计报告 | `ui_interactive_audit_report.md` |
| 回归报告 | `ui_regression_report.md` |
| 截图目录 | `ui_audit_screenshots/`、`ui_interactive_audit_screenshots/`、`ui_regression_screenshots/` |
| 审计脚本 | `ui_audit.py`、`ui_interactive_audit.py`、`ui_regression.py` |

`.gitignore` 第 56–57 行与第 61–67 行就是为这批文件名添加的（引入提交 `20ff1492`，2026-08-01 `chore: ignore temporary scripts and test artifacts`）：这既是那次审计确实发生过的痕迹，也是本节保留的原因——撤掉这段说明，这批 ignore 条目就成了无出处的孤儿规则。

## 当前状态

| 维度 | 状态 | 备注 |
|------|------|------|
| 单元测试 | ✅ 已有 | 主应用 frontend/src/tests/、插件 backend/plugin-l4d2/frontend/src/ |
| 组件测试 | ✅ 已有 | 覆盖 BackupForm、PluginContainer 等关键组件 |
| E2E 测试 | ✅ 已有 | 基于 Playwright 的页面遍历与回归验证 |
| 视觉回归 | ✅ 已有 | 通过 Playwright screenshot 进行 UI Audit 与回归对比 |
| 性能测试 | 📋 规划中 | Lighthouse CI 接入 |

## 维护约定

1. **新增功能**：同步更新对应测试用例文档
2. **Bug 修复**：在 E2E 清单中补充回归用例
3. **架构变更**：更新 01-strategy 和 03-test-pyramid
4. **工具链升级**：更新 02-toolchain 和 08-ci-integration
5. **UI 优化**：同步更新 UI/UE 设计规范与 E2E 清单，确保设计稿、实现、测试三者一致

---

*最后更新: 2026-10-20*
