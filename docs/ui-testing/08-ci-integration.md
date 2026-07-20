# 08 - CI 集成与自动化

> 持续集成中的 UI 测试配置与自动化

---

## 1. CI 流程概览

```
┌─────────┐   ┌───────────┐   ┌────────────┐   ┌──────────┐   ┌──────────┐
│  Push   │ → │  Install  │ → │   Lint     │ → │  Unit    │ → │  Build   │
│  /PR    │   │  Deps     │   │  (ESLint)  │   │  Tests   │   │  Check   │
└─────────┘   └───────────┘   └────────────┘   └──────────┘   └──────────┘
                                                                    ↓
                                                              ┌──────────┐
                                                              │  E2E     │
                                                              │ (Nightly)│
                                                              └──────────┘
```

---

## 2. GitHub Actions 配置（规划）

### 2.1 主应用前端 CI

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

### 2.2 插件子应用 CI

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

### 2.3 Maven 构建 + JAR 打包

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

### 2.4 E2E 测试（Nightly）

**`.github/workflows/e2e-nightly.yml`**

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

### 3.1 配置（vitest.config.ts）

```typescript
coverage: {
  provider: 'v8',
  reporter: ['text', 'json', 'html', 'lcov'],
  exclude: [
    'node_modules/',
    'src/tests/',
    '**/*.d.ts',
    'src/main.ts',
    'src/auto-imports.d.ts',
    'src/components.d.ts'
  ],
  thresholds: {
    statements: 70,
    branches: 70,
    functions: 70,
    lines: 70
  }
}
```

### 3.2 关键模块覆盖率目标

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

## 4. Pre-commit Hook（可选）

**`.husky/pre-commit`**

```bash
#!/bin/sh
. "$(dirname -- "$0")/_/husky.sh"

cd frontend && npm run lint
cd frontend && npm run test:run -- --changed
```

---

## 5. 本地 CI 模拟

### 5.1 模拟 CI 流程（Windows PowerShell）

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

### 5.2 运行

```powershell
.\scripts\local-ci.ps1
```

---

## 6. 监控与报告

### 6.1 测试结果追踪

| 指标 | 来源 | 频率 |
|------|------|------|
| 测试通过率 | CI 日志 | 每次 PR |
| 覆盖率 | Codecov / Istanbul | 每次 PR |
| 构建时长 | CI 日志 | 每次 PR |
| E2E 通过率 | Playwright Report | 每日 |
| Bug 逃逸率 | Issue Tracker | 每月 |

### 6.2 失败通知

- GitHub Actions 失败 → PR 状态检查阻塞
- Nightly E2E 失败 → 邮件通知 + Slack/飞书（如配置）

---

## 7. 待办事项

- [ ] 创建 `.github/workflows/` 目录下的 CI 配置
- [ ] 接入 Codecov 覆盖率上报
- [ ] 编写 Playwright E2E 测试用例（参考 07-e2e-checklist.md）
- [ ] 配置 Husky pre-commit hook
- [ ] 创建 `scripts/local-ci.ps1` 本地 CI 模拟脚本
- [ ] 评估接入 Lighthouse CI 性能监控

---

*最后更新: 2026-07-20*
