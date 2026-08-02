# 02 - 工具链与环境

> Game Platform Manager - UI 测试工具链配置

---

## 1. 技术栈总览

| 工具 | 版本 | 用途 | 配置文件 |
|------|------|------|----------|
| Vitest | ^1.4.0 | 单元/组件测试运行器 | `vitest.config.ts` |
| @vue/test-utils | ^2.4.5 | Vue 组件挂载与交互 | - |
| happy-dom | ^14.3.0 | 浏览器 DOM 模拟 | vitest config |
| @vitest/coverage-v8 | ^1.4.0 | 覆盖率统计 | vitest config |
| @vitest/ui | ^1.4.0 | 测试可视化 UI | - |
| playwright-core | ^1.61.0 | E2E 浏览器自动化 | 规划中 |
| MSW | 规划中 | API Mock | 规划中 |

---

## 2. 主应用前端（frontend/）

### 2.1 配置文件

**`frontend/vitest.config.ts`**（如不存在则参考 `package.json` 的 test 字段）

```typescript
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'happy-dom',
    globals: true,
    setupFiles: ['./src/tests/setup.js'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json', 'html'],
      exclude: ['node_modules/', 'src/tests/', '**/*.d.ts']
    }
  },
  resolve: {
    alias: {
      '@': resolve(__dirname, './src')
    }
  }
})
```

### 2.2 setup 文件

**`frontend/src/tests/setup.js`**

- 全局引入 Element Plus
- 全局引入路由、Pinia
- mock `window.matchMedia`、`IntersectionObserver` 等浏览器 API

### 2.3 运行命令

```bash
cd frontend

# 运行所有测试（watch 模式）
npm test

# 单次运行（CI 友好）
npm run test:run

# 生成覆盖率报告
npm run test:coverage

# 可视化 UI
npx vitest --ui
```

---

## 3. 插件子应用（plugin-l4d2/frontend/）

### 3.1 配置文件

**`backend/plugin-l4d2/frontend/vitest.config.ts`**

```typescript
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  test: {
    environment: 'happy-dom',
    globals: true,
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json', 'html']
    }
  },
  resolve: {
    alias: {
      '@': resolve(__dirname, './src')
    }
  }
})
```

### 3.2 运行命令

```bash
cd backend/plugin-l4d2/frontend

# 运行所有测试
npx vitest run

# 生成覆盖率
npx vitest run --coverage

# 可视化 UI
npx vitest --ui
```

---

## 4. E2E 测试环境

### 4.1 当前方案
- 使用 TRAE IDE 内置 `browser_use` 子代理执行浏览器自动化
- 适用于开发阶段的快速验证和回归测试

### 4.2 规划方案（Playwright）

**安装**
```bash
cd frontend
npm install -D @playwright/test
npx playwright install
```

**`frontend/playwright.config.ts`**
```typescript
import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: 'html',
  use: {
    baseURL: 'http://localhost:3000',
    trace: 'on-first-retry'
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } }
  ],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:3000',
    reuseExistingServer: !process.env.CI
  }
})
```

---

## 5. 测试环境前置条件

### 5.1 验证账号（默认初始账号）

> 来源：`backend/core/src/main/resources/db/data.sql` 首次启动自动初始化，仅开发/测试环境使用。

| 字段 | 值 | 说明 |
|------|----|------|
| 用户名 | `admin` | 系统管理员，不可删除 |
| 密码 | `admin123` | SHA256 加密存储，初始密码 |
| 角色 | 系统管理员 | 拥有所有权限 |
| 端点 | `POST /api/auth/login` | 登录接口 |

**登录请求示例（用于 E2E/API 测试）：**
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
```

**响应示例：**
```json
{
  "code": 200,
  "message": "操作成功",
  "data": { "token": "eyJhbGciOi...", "username": "admin" },
  "timestamp": 1711084800000
}
```

> ⚠️ 生产环境部署后请立即通过 `系统设置 → 用户管理` 修改默认密码。测试数据库被清空后会自动重建该账号。

### 5.2 环境依赖（执行前必检）

| 依赖 | 最低版本 | 验证命令 | 用途 |
|------|----------|----------|------|
| JDK | 17 | `java -version` | 后端运行 |
| Maven | 3.8 | `mvn -v` | 后端编译/打包 |
| Node.js | 18 | `node -v` | 前端构建/运行 |
| npm | 9 | `npm -v` | 前端依赖管理 |
| PowerShell | 5.1（Windows 内置） | `$PSVersionTable.PSVersion` | 执行启动脚本 |
| Docker | 24+（可选） | `docker --version` | 实例部署测试 |
| SSH 主机 | 任意 Linux（可选） | - | 实例部署/SSH 终端测试 |

### 5.3 主应用前端（手动启动）
```bash
# 1. 安装依赖
cd frontend && npm install

# 2. 启动后端（独立终端）
cd ../backend && mvn spring-boot:run -pl core

# 3. 启动前端（独立终端，固定 3000 端口，自动代理 /api → 8080）
cd frontend && npm run dev
```

### 5.4 插件子应用（手动启动 + 插件打包）
```bash
# 1. 安装依赖
cd backend/plugin-l4d2/frontend && npm install

# 2. 打包插件 JAR（独立终端）
cd ../../../backend && mvn clean package -pl plugin-l4d2/plugin-l4d2-core -DskipTests

# 3. 部署 JAR 到 plugins 目录
Copy-Item plugin-l4d2/plugin-l4d2-core/target/plugin-l4d2-core-1.0.0.jar plugins/ -Force

# 4. 启动后端（首次启动会自动加载 plugins 目录下的插件）
mvn spring-boot:run -pl core
```

### 5.5 一键启动（推荐）

```powershell
# 在项目根目录
.\scripts\rebuild-restart-all.ps1
```

脚本执行流程：
1. 停止旧的后端进程（占用 8080 端口）
2. 编译后端 core 模块（`mvn clean compile -pl core -am`）
3. 打包插件 JAR 并部署到 `backend/plugins/`（除非 `-SkipPlugins`）
4. 通过 `java -cp` 启动后端，等待 8080 端口监听 + 登录接口 200
5. 停止旧的前端进程（占用 3000/3001/3002 端口）
6. 通过 `node vite.js --port 3000 --strictPort` 启动前端

**常用参数：**
| 参数 | 作用 |
|------|------|
| `-SkipBackendCompile` | 跳过后端编译（仅修改前端时使用） |
| `-SkipPlugins` | 跳过插件 JAR 打包（仅修改后端代码时使用） |
| `-SkipFrontend` | 跳过前端启动 |
| `-FrontendPort 3001` | 指定前端端口（默认 3000） |

### 5.6 重启脚本与操作场景

测试期间最常见的操作是按需重启某一部分。下表列出所有可用的脚本路径与典型用法：

#### 5.6.1 脚本路径速查

| 脚本 | 路径 | 作用 |
|------|------|------|
| 全栈一键 | `scripts/rebuild-restart-all.ps1` | 编译后端 + 打包插件 + 启动后端 + 启动前端 |
| 后端 | `backend/scripts/rebuild-restart.ps1` | 编译后端 + 打包插件 + 启动后端 |
| 前端 | `frontend/scripts/rebuild-restart.ps1` | 仅启动前端（Vite 热重载，无需编译） |

> 所有脚本均需在项目根目录或对应子目录执行；脚本支持 PowerShell 5.1+ 和 PowerShell 7。

#### 5.6.2 场景速查表

| # | 场景 | 命令 | 适用时机 |
|---|------|------|----------|
| 1 | 全栈编译并重启（默认） | `.\scripts\rebuild-restart-all.ps1` | 首次启动 / 拉取最新代码后 |
| 2 | 跳过插件打包 | `.\scripts\rebuild-restart-all.ps1 -SkipPlugins` | 仅修改后端 Java 代码 |
| 3 | 跳过后端编译 | `.\scripts\rebuild-restart-all.ps1 -SkipBackendCompile` | 仅修改前端代码 |
| 4 | 仅重启后端（含插件打包） | `cd backend; .\scripts\rebuild-restart.ps1` | 修改后端 + 插件代码 |
| 5 | 仅重启后端（跳过编译，最快） | `cd backend; .\scripts\rebuild-restart.ps1 -SkipCompile` | 仅改了 classpath 资源 / 配置文件 |
| 6 | 仅重启后端（跳过插件打包） | `cd backend; .\scripts\rebuild-restart.ps1 -SkipPlugins` | 仅修改后端 Java 代码 |
| 7 | 仅重启前端 | `cd frontend; .\scripts\rebuild-restart.ps1` | 修改前端代码后强制重启 Vite |
| 8 | 指定前端端口 | `cd frontend; .\scripts\rebuild-restart.ps1 -Port 3001` | 3000 端口被占用 |
| 9 | 仅打包插件（不重启） | `cd backend; mvn clean package -pl plugin-l4d2/plugin-l4d2-core -DskipTests` | 手动验证插件 JAR 构建 |

#### 5.6.3 操作流程示例

**场景 A：测试过程中修改了后端 Java 代码**

```powershell
# 在项目根目录执行
.\scripts\rebuild-restart-all.ps1
# 等待脚本输出 "后端启动成功" + "前端启动成功" 即可继续测试
```

**场景 B：测试过程中修改了前端 Vue 代码**

```powershell
# Vite 通常会自动热重载，无需重启
# 如果热重载失效或修改了 vite.config.ts：
.\scripts\rebuild-restart-all.ps1 -SkipBackendCompile -SkipPlugins
```

**场景 C：测试过程中修改了插件代码**

```powershell
# 必须重新打包插件 JAR 并重启后端
.\scripts\rebuild-restart-all.ps1
# 或仅重启后端：
cd backend
.\scripts\rebuild-restart.ps1
```

**场景 D：测试过程中仅修改了配置文件（application.yml 等）**

```powershell
cd backend
.\scripts\rebuild-restart.ps1 -SkipCompile
```

**场景 E：测试过程中需要切换前端端口**

```powershell
.\scripts\rebuild-restart-all.ps1 -FrontendPort 3001
```

#### 5.6.4 停止服务（不重启）

如果仅需要停止服务（不重启），可手动执行：

```powershell
# 停止后端（按 8080 端口查找进程）
Get-NetTCPConnection -LocalPort 8080 -State Listen -ErrorAction SilentlyContinue |
    ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }

# 停止前端（按 3000 端口查找进程）
Get-NetTCPConnection -LocalPort 3000 -State Listen -ErrorAction SilentlyContinue |
    ForEach-Object { Stop-Process -Id $_.OwningProcess -Force }
```

#### 5.6.5 日志查看

| 日志类型 | 路径 | 用途 |
|----------|------|------|
| 后端应用日志 | `backend/logs/application.log` | 查看 Spring Boot 输出 |
| 后端启动日志 | `backend/logs/startup.log` | 查看启动脚本执行过程 |
| 前端应用日志 | `frontend/logs/frontend.log` | 查看 Vite 输出 |
| 前端错误日志 | `frontend/logs/frontend.err.log` | 查看 Vite 错误 |
| 全栈启动日志 | `logs/startup-all.log` | 查看全栈脚本执行过程 |
| 后端 PID | `backend/logs/game-platform-manager.pid` | 后端进程 PID |
| 前端 PID | `frontend/logs/frontend.pid` | 前端进程 PID |

**实时查看日志：**
```powershell
# 后端日志
Get-Content backend/logs/application.log -Wait -Tail 50

# 前端日志
Get-Content frontend/logs/frontend.log -Wait -Tail 50
```

### 5.7 环境就绪验证清单

启动完成后，按下表逐项验证：

| # | 检查项 | 期望 | 验证方式 |
|---|--------|------|----------|
| 1 | 后端运行 | HTTP 200 | `curl http://localhost:8080/api/auth/login -X POST -H "Content-Type: application/json" -d '{"username":"admin","password":"admin123"}'` |
| 2 | 前端运行 | HTTP 200 | `curl http://localhost:3000/` |
| 3 | API 代理 | `/api/*` 转发到 8080 | `curl http://localhost:3000/api/auth/login -X POST ...` |
| 4 | 插件加载 | 返回 `status: "STARTED"` | `curl http://localhost:8080/api/pf4j/plugins` |
| 5 | 插件 UI 静态资源 | HTTP 200 | `curl http://localhost:8080/api/pf4j/plugin/l4d2/ui/index.html` |
| 6 | 数据库初始化 | `admin` 账号存在 | 登录接口返回 token |
| 7 | 前端页面 | 登录页正常渲染 | 浏览器打开 http://localhost:3000/ |

### 5.8 测试数据准备（可选）

部分测试用例（实例管理、主机管理、RCON 等）需要预先准备数据：

| 数据 | 准备方式 | 影响的测试 |
|------|----------|-----------|
| 主机记录 | 登录后进入 `主机管理 → 新增`，填入可达的 SSH 主机 | E2E-010 ~ E2E-014、E2E-020+ |
| L4D2 游戏实例 | `实例管理 → 新建实例`，选择 L4D2 + 主机 + 部署方式 | E2E-020 ~ E2E-028、E2E-050+ |
| 运行中的 L4D2 服务器 | 实例启动成功，状态为 `运行中` | E2E-050 ~ E2E-065 |
| VPK 地图文件 | 通过插件 `地图管理 → 上传` 上传 .vpk 文件 | E2E-070 ~ E2E-073 |

> 测试数据库位置：`backend/data/game_platform.db`，删除后重启会自动重建初始数据。

---

## 6. 常见问题

### Q1: happy-dom 报错 "element is not defined"
检查 `vitest.config.ts` 的 `test.environment` 是否为 `'happy-dom'`。

### Q2: Element Plus 组件未渲染
检查 `src/tests/setup.js` 是否全局注册了 Element Plus。

### Q3: 测试中 `useRoute` 返回 undefined
组件测试需要挂载 `RouterLink` 或 mock 路由：
```typescript
vi.mock('vue-router', () => ({
  useRoute: () => ({ path: '/test' }),
  useRouter: () => ({ push: vi.fn() })
}))
```

### Q4: Wujie 子应用测试中 `window.__POWERED_BY_WUJIE__` 未定义
在测试 setup 中显式设置：
```typescript
beforeEach(() => {
  window.__POWERED_BY_WUJIE__ = true
})
```

---

*最后更新: 2026-07-20*
