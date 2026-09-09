# 03 — 认证与导航用例

**What to build:** 主应用认证与路由链路的自动化用例：登录成功进工作台、错误密码提示、退出登录回登录页、token 失效后操作自动跳登录、未登录访问受保护路由跳登录（带 redirect）、已登录访问登录页跳首页、访问不存在路由显示 404、工作台仪表盘加载与统计卡渲染。

**Blocked by:** 01

**Status:** done

- [x] 上述每个场景各有一条用例且全绿
- [x] 每条用例头声明前置条件、步骤、通过标准
- [x] 无主机依赖，附着/受管两种模式均可跑

> 实施备注：8 条用例分布于 auth/login.spec.js（未登录重定向、登录成功、错误密码提示、退出登录确认+token 清除）、auth/navigation.spec.js（受保护路由 redirect 参数、已登录访问 /login 回工作台、伪造 token 会话过期自动登出、404 页）、workspace/overview.spec.js（仪表盘标题/态势条/核心指标/快捷入口）。新增 support/session.js（API 换真实 JWT 注入 localStorage，键名对齐 stores/user.js）。实施中修正的选择器事实：用户下拉菜单为 trigger=click（非 hover）；vue-router 的 query 值不做百分号编码；仪表盘并发 401 会叠多条警告 toast，断言取 first()。
