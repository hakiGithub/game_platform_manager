# 08 - CI 集成与自动化

> 持续集成中的 UI 测试配置与自动化

---

## 1. CI 流程概览（现状）

```
  push main / push tag v* / workflow_dispatch      （GitLab 另有 merge_request_event）
                    ↓
   ┌────────────────────┐   ┌─────────────────────────┐
   │   test-backend     │   │     test-frontend       │   并行
   │   mvn -B test      │   │ npm ci && test:run      │   ← 均为单元测试
   └────────────────────┘   └─────────────────────────┘
                    ↓ 两者都通过
   ┌──────────────────────────────────────────────┐
   │   build（Docker 镜像，ADR-0013）               │
   │     tag v* → 构建并推送 registry               │
   │     main   → 仅构建验证，不推送                 │
   └──────────────────────────────────────────────┘
```

CI 里**没有** Lint、覆盖率上报、也没有任何 E2E / Playwright 环节：门禁止步于单元测试 + 镜像构建。

---

## 2. CI 配置

### 2.0 实际生效的配置

| 文件 | 触发 | 作业 |
|------|------|------|
| `.github/workflows/docker-build.yml` | `push` 到 `main`、`push` tag `v*`、`workflow_dispatch`（**无 `pull_request` 触发**） | `test-backend`（`mvn -B test`）、`test-frontend`（`npm ci && npm run test:run`）、`build-push`（needs 前两者；仅 tag 推送 ghcr.io，main 只做构建验证） |
| `.gitlab-ci.yml` | merge_request_event、tag `v*`、`main` | `test-backend`、`test-frontend`（同上）、`build-backend` / `build-frontend`（仅 tag，推 GitLab Registry）、`build-verify`（仅 main，构建不推送） |

两份配置都源自 ADR-0013，测试阶段完全等价：**后端 `mvn -B test` + 前端 `npm run test:run`（Vitest）**。前端 E2E 的 `npm run e2e` / `e2e:round` 不在其中。

> 下面 2.1–2.4 是**尚未落地的提案**：这四个 workflow 文件在仓库中都不存在，仅作后续实施参考，不代表当前门禁。

### 2.1 主应用前端 CI（提案，未落地）

**`.github/workflows/frontend-ci.yml`**

```yaml
name: Frontend CI

on:
  push:
    paths:
      - 'frontend/**'
      - '.github/workflows/frontend-ci.yml'
  pull_request:
    paths:
      - 'frontend/**'

jobs:
  test:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: frontend

    steps:
      - uses: actions/checkout@v4

      - name: Setup Node.js
        uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: 'npm'
          cache-dependency-path: frontend/package-lock.json

      - name: Install dependencies
        run: npm ci

      - name: Lint
        run: npm run lint

      - name: Unit tests
        run: npm run test:run -- --coverage

      - name: Upload coverage
        uses: codecov/codecov-action@v3
        with:
          directory: frontend/coverage

      - name: Build check
        run: npm run build
```

### 2.2 插件子应用 CI（提案，未落地）

**`.github/workflows/plugin-l4d2-ci.yml`**

```yaml
name: Plugin L4D2 CI

on:
  push:
    paths:
      - 'backend/plugin-l4d2/**'
      - '.github/workflows/plugin-l4d2-ci.yml'
  pull_request:
    paths:
      - 'backend/plugin-l4d2/**'

jobs:
  test:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: backend/plugin-l4d2/frontend

    steps:
      - uses: actions/checkout@v4

      - name: Setup Node.js
        uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: 'npm'
          cache-dependency-path: backend/plugin-l4d2/frontend/package-lock.json

      - name: Install dependencies
        run: npm ci

      - name: Unit tests
        run: npx vitest run --coverage

      - name: Build check
        run: npm run build

      - name: Verify UI resources in JAR
        run: |
          # 检查 build 产物是否包含必要资源
          test -f ../plugin-l4d2-core/src/main/resources/ui/index.html
          test -f ../plugin-l4d2-core/src/main/resources/ui/assets/index-*.js
```

### 2.3 Maven 构建 + JAR 打包（提案，未落地）

**`.github/workflows/backend-ci.yml`**

```yaml
name: Backend CI

on:
  push:
    paths:
      - 'backend/**'
      - '.github/workflows/backend-ci.yml'
  pull_request:
    paths:
      - 'backend/**'

jobs:
  build:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: backend

    steps:
      - uses: actions/checkout@v4

      - name: Setup JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: 'maven'

      - name: Build core
        run: mvn -B clean compile -pl core -am

      - name: Build plugin JAR
        run: mvn -B clean package -pl plugin-l4d2/plugin-l4d2-core -DskipTests

      - name: Backend tests
        run: mvn -B test

      - name: Verify plugin JAR
        run: |
          JAR=plugin-l4d2/plugin-l4d2-core/target/plugin-l4d2-core-1.0.0.jar
          test -f $JAR
          # 验证中文常量未损坏
          unzip -p $JAR com/gameplatform/plugin/l4d2/L4D2Extension.class | strings | grep -q "求生之路2"
```

### 2.4 E2E 测试（Nightly）（提案，未落地）

**`.github/workflows/e2e-nightly.yml`** — 该文件不存在，当前没有任何定时或 CI 触发的 E2E 作业。

> 若要落地，下面 YAML 里的 `npx playwright test` 需换成受管入口 `npm run e2e`（`e2e/runner.mjs`）：真实用例集由 runner 临时拉起后端+前端并在结束后清理，裸调 Playwright 不会起被测栈。牺牲主机凭据缺失时，主机类用例整组 `SKIP (no test host)`（见 `frontend/e2e/README.md`）。

```yaml
name: E2E Tests (Nightly)

on:
  schedule:
    - cron: '0 2 * * *'  # 每天凌晨 2 点
  workflow_dispatch:

jobs:
  e2e:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Setup JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Setup Node.js
        uses: actions/setup-node@v4
        with:
          node-version: '20'

      - name: Build backend
        run: |
          cd backend
          mvn -B clean package -pl plugin-l4d2/plugin-l4d2-core -DskipTests
          mvn -B clean compile -pl core -am

      - name: Start backend
        run: |
          cd backend
          mvn spring-boot:run -pl core &
          sleep 30

      - name: Install frontend deps
        run: cd frontend && npm ci

      - name: Install Playwright
        run: cd frontend && npx playwright install --with-deps

      - name: Run E2E tests
        run: cd frontend && npx playwright test

      - name: Upload test report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: playwright-report
          path: frontend/playwright-report/
```

---

## 3. 覆盖率门槛

### 3.1 配置（`frontend/vitest.config.js`）

```javascript
coverage: {
  provider: 'v8',
  reporter: ['text', 'json', 'html'],
  reportsDirectory: './coverage',
  thresholds: {
    lines: 70,
    functions: 70,
    branches: 60,
    statements: 70
  },
  include: [
    'src/utils/**/*.js',
    'src/stores/**/*.js',
    'src/api/**/*.js',
    'src/plugins/**/*.js',
    'src/composables/**/*.js'
  ],
  exclude: ['src/tests/**', 'node_modules/**']
}
```

这些阈值只在**本地**执行 `npm run test:coverage` 时生效：CI 的前端作业跑的是 `npm run test:run`（不带 `--coverage`），所以覆盖率目前不是合并门禁，也没有上报 Codecov。

### 3.2 关键模块覆盖率目标

下表是目标值，尚未度量、也未在任何地方强制：

| 模块 | 目标 | 当前 |
|------|------|------|
| `src/utils/` | ≥ 80% | 待统计 |
| `src/stores/` | ≥ 80% | 待统计 |
| `src/api/` | ≥ 70% | 待统计 |
| `src/components/` | ≥ 70% | 待统计 |
| `src/views/` | ≥ 60% | 待统计 |
| `src/plugins/` | ≥ 80% | 待统计 |
| 整体 | ≥ 70% | 待统计 |

---

## 4. Pre-commit Hook（提案，未落地）

仓库中没有 `.husky/` 目录，当前不存在任何 pre-commit 钩子。若要引入，`.husky/pre-commit` 可参考：

```bash
#!/bin/sh
. "$(dirname -- "$0")/_/husky.sh"

cd frontend && npm run lint
cd frontend && npm run test:run -- --changed
```

---

## 5. 本地 CI 模拟

今天要在本地复现 CI 的测试门禁，直接跑这两条即可（与 CI 作业等价）：

```bash
cd backend  && mvn -B test
cd frontend && npm ci && npm run test:run
```

E2E 不属于 CI 门禁，用受管入口单独跑：`cd frontend && npm run e2e`（整轮编排 `npm run e2e:round`，报告 `npm run e2e:report`）。

### 5.1 一键模拟脚本（提案，未落地）

`scripts/local-ci.ps1` 尚不存在——`scripts/` 下现有的是 `deploy-image.sh`、`deploy-plugin.sh`、`rebuild-restart-all.ps1`、`restart-all.ps1`、`start-all.sh`。下面是提案稿：

```powershell
# scripts/local-ci.ps1
$ErrorActionPreference = "Stop"

Write-Host "===== [1/5] 编译后端 ====="
cd backend
mvn -B clean compile -pl core -am -q
if ($LASTEXITCODE -ne 0) { throw "后端编译失败" }

Write-Host "===== [2/5] 打包插件 JAR ====="
mvn -B clean package -pl plugin-l4d2/plugin-l4d2-core -DskipTests -q
if ($LASTEXITCODE -ne 0) { throw "插件打包失败" }

Write-Host "===== [3/5] 后端测试 ====="
mvn -B test -q
if ($LASTEXITCODE -ne 0) { throw "后端测试失败" }

Write-Host "===== [4/5] 前端测试 ====="
cd ../frontend
npm run test:run
if ($LASTEXITCODE -ne 0) { throw "前端测试失败" }

Write-Host "===== [5/5] 前端构建 ====="
npm run build
if ($LASTEXITCODE -ne 0) { throw "前端构建失败" }

Write-Host "===== CI 模拟通过 ====="
```

### 5.2 运行（脚本落地后）

```powershell
.\scripts\local-ci.ps1
```

---

## 6. 监控与报告

### 6.1 测试结果追踪

| 指标 | 来源 | 频率 |
|------|------|------|
| 单测通过率 | CI 日志（`test-backend` / `test-frontend`） | 每次 push main、tag `v*`；GitLab 另有 MR |
| 覆盖率 | 本地 `npm run test:coverage` | 按需，CI 不采集、不上报 |
| 镜像构建时长 | CI 日志 | 每次 push |
| E2E 通过率 | 本地 `npm run e2e:report`（`frontend/e2e/.report`） | 手动触发，无定时作业 |
| Bug 逃逸率 | Issue Tracker | 每月，人工统计 |

### 6.2 失败通知

- GitHub Actions 失败 → 表现为 workflow 运行失败。`docker-build.yml` **没有配置 `pull_request` 触发**，因此它不会成为 PR 上的状态检查，也就不阻塞 PR 合并。
- GitLab CI → MR 事件会跑 `test` 阶段；是否阻塞合并取决于项目侧的 "Pipelines must succeed" 设置，仓库配置本身不强制。
- Nightly E2E 失败通知 → 不适用，当前没有 nightly E2E 作业。

---

## 7. 待办事项

- [x] 创建 CI 配置 —— 已有 `.github/workflows/docker-build.yml` 与 `.gitlab-ci.yml`（ADR-0013）
- [x] 编写 Playwright E2E 测试用例 —— 已落地 `frontend/e2e/`（规格见 `.scratch/ui-e2e-automation/spec.md`，运行方式见 `frontend/e2e/README.md`）
- [ ] 把 E2E（至少无主机的冒烟子集）接入 CI —— 目前 E2E 完全不在 CI 中，只能本地跑
- [ ] 接入 Codecov 覆盖率上报，并让 CI 真正执行带 `--coverage` 的前端测试
- [ ] 配置 Husky pre-commit hook
- [ ] 创建 `scripts/local-ci.ps1` 本地 CI 模拟脚本
- [ ] 评估接入 Lighthouse CI 性能监控

---

*最后更新: 2026-10-16*
