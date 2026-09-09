# E2E 自动化用例集（全量回归）

按「页面 × 页面内功能」组织的 Playwright E2E 用例集。规格见 `.scratch/ui-e2e-automation/spec.md`。

## 运行

```bash
cd frontend

# 受管模式（推荐）：临时 SQLite 起后端+前端 → 跑全部用例 → 报告 → 清理
npm run e2e

# 跳过 mvn 编译（之前编译过一次、core/target/cp.txt 存在时）
E2E_FAST=1 npm run e2e

# 附着模式：对已运行的环境跑用例（不起栈、不清理）
npm run e2e -- --attach

# 查看 HTML 报告（总数/通过/失败/跳过 + 失败截图与 trace）
npm run e2e:report
```

首次运行前：`npm install` + `npx playwright install chromium`。

## 环境契约（凭据不入库）

| 变量 | 缺省 | 说明 |
|------|------|------|
| `E2E_BASE_URL` | `http://localhost:3000` | 前端地址 |
| `E2E_BACKEND_URL` | `http://localhost:8080` | 后端地址（API 断言通道） |
| `E2E_ADMIN_USER` / `E2E_ADMIN_PASS` | `admin` / `admin123` | 种子管理员 |
| `E2E_TEST_HOST_ADDRESS` / `_PORT` / `_USERNAME` / `_AUTH` | 空 | 牺牲主机凭据；任一缺失 ⇒ 主机类用例整组 `SKIP (no test host)` |
| `E2E_WORKERS` | `1` | 并行 worker 数（用例共享一个后端，默认串行） |
| `E2E_FAST` | 空 | `1` = 受管模式跳过 mvn 编译 |

## 通过判定

单条用例 = 断言全过即通过；**整轮通过 = 除显式 SKIP 外全部用例通过**。

## 目录约定（新增用例不改框架）

```
e2e/
├── support/            # 框架层：环境契约(env)、平台 API 断言客户端(api)、主机 SKIP(skip)
├── main-app/           # 主应用用例：按「页面/功能」建目录，如 auth/login.spec.js
└── plugins/            # 插件用例包：每个插件一个子目录（见 plugins/README.md）
```

新增一条用例 = 在对应命名空间下新建 `<功能>.spec.js`，文件头按既有格式声明
「页面 / 用例 / 前置 / 步骤 / 通过标准」。
