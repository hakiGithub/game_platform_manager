# 03 - 测试金字塔

> Game Platform Manager - 测试分层比例与职责

---

## 1. 金字塔模型

```
                  ▲
                 / \
                / E2E \           场景级（10%）
               / (10%) \
              /----------\
             /  集成测试   \        跨模块协作（20%）
            /   (20%)      \
           /-----------------\
          /   组件测试         \    独立组件（40%）
         /     (40%)            \
        /------------------------\
       /       单元测试            \  纯函数/Store（30%）
      /         (30%)               \
     /------------------------------\
```

## 2. 各层职责详解

### 2.1 单元测试（Unit）- 30%

**目标**：验证纯函数、工具类、Store 的逻辑正确性

**对象**：
- `src/utils/*.js`（request、websocket、index）
- `src/stores/*.js`（user、host、instance、backup）
- `src/api/*.js`（请求封装）
- 插件子应用的 `utils/*.ts`（runtime、gameConstants、statusParser）

**特点**：
- 无 DOM 依赖
- 无网络调用（Mock 掉）
- 执行速度快（< 100ms / 用例）

**示例**：
```javascript
// frontend/src/tests/utils/request.test.js
describe('request', () => {
  it('应该在 401 时清除 token 并跳转登录', async () => {
    // ... 验证逻辑
  })
})
```

### 2.2 组件测试（Component）- 40%

**目标**：验证 Vue 组件的渲染、交互、props/emit

**对象**：
- 通用组件（`components/*.vue`）
- 业务组件（页面内的子组件）
- 插件子应用组件（`components/*.vue`）

**特点**：
- 使用 `@vue/test-utils` 挂载
- Mock 子组件或 API
- 验证 DOM、事件、状态变化

**示例**：
```javascript
// frontend/src/tests/components/BackupForm.test.js
describe('BackupForm', () => {
  it('应该在表单校验失败时禁止提交', async () => {
    const wrapper = mount(BackupForm)
    await wrapper.find('button').trigger('click')
    expect(wrapper.text()).toContain('请输入备份名称')
  })
})
```

### 2.3 集成测试（Integration）- 20%

**目标**：验证多个组件/Store/API 之间的协作

**对象**：
- 页面级组件（`views/*.vue`）
- Wujie 通信链路（主应用 ↔ 子应用）
- 路由 + 守卫 + Store

**特点**：
- 挂载完整页面
- Mock 后端 API（MSW 或 vi.mock）
- 验证用户操作流（点击 → 状态变化 → UI 更新）

**示例**：
```javascript
// frontend/src/tests/views/instance.test.js
describe('实例管理页', () => {
  it('应该完成创建实例的完整流程', async () => {
    // 1. 挂载页面
    // 2. 填写表单
    // 3. 提交
    // 4. 验证列表更新
  })
})
```

### 2.4 E2E 测试（End-to-End）- 10%

**目标**：验证真实用户场景，包含前后端

**对象**：
- 核心业务流（登录 → 部署实例 → 启动 → 管理）
- Wujie 微前端集成（主应用加载子应用、菜单切换、通信）
- 关键回归场景（发版前必跑）

**特点**：
- 真实浏览器
- 真实后端（开发环境）
- 执行慢（10s+ / 场景）
- 脆弱性高（依赖环境）

**示例**：
```typescript
// e2e/deploy-instance.spec.ts（规划中）
test('部署 L4D2 实例', async ({ page }) => {
  await page.goto('http://localhost:3000/login')
  await page.fill('[data-testid=username]', 'admin')
  await page.fill('[data-testid=password]', 'admin123')
  await page.click('[data-testid=login-btn]')
  await page.goto('http://localhost:3000/instances/list')
  // ... 创建实例并验证
})
```

---

## 3. 当前测试用例分布

### 3.1 主应用前端（frontend/src/tests/）

| 层级 | 文件数 | 用例数 | 占比 |
|------|--------|--------|------|
| 单元（utils） | 3 | - | - |
| 单元（stores） | 4 | - | - |
| 单元（api） | 6 | - | - |
| 组件 | 3 | - | - |
| 集成（views） | 3 | - | - |
| 集成（plugins） | 6 | - | - |
| **合计** | **25** | - | - |

### 3.2 插件子应用（plugin-l4d2/frontend/src/）

| 文件 | 类型 | 说明 |
|------|------|------|
| `pages/InstanceSelect.test.ts` | 组件 | 实例选择对话框 |
| `utils/runtime.test.ts` | 单元 | 运行时模式检测 |

---

## 4. 比例目标

| 层级 | 当前 | 目标 | 差距 |
|------|------|------|------|
| Unit | ? | 30% | - |
| Component | ? | 40% | - |
| Integration | ? | 20% | - |
| E2E | ? | 10% | - |

**建议**：优先补充组件测试（40%）和单元测试（30%），E2E 保留给核心业务流。

---

## 5. 反模式（避免）

❌ **冰激凌金字塔**（E2E 占比过高）
- 问题：E2E 慢、脆弱、难以定位
- 解决：将 E2E 中的逻辑下沉到组件测试

❌ **测试依赖执行顺序**
- 问题：用例之间共享状态，并行执行失败
- 解决：每个 `it` 独立 setup + teardown

❌ **测试实现细节**
- 问题：测试内部状态/私有方法，重构即失败
- 解决：测试公开行为（输入 → 输出）

❌ **过度 Mock**
- 问题：Mock 掉被测对象本身，测试无意义
- 解决：只 Mock 外部依赖（API、浏览器 API）

---

*最后更新: 2026-07-20*
