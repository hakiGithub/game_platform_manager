# 04: 主前端"云盘账号"页面

Status: ready-for-human

## 任务

- 新页面 `frontend/src/views/system/accounts.vue`（参照 `views/system/settings.vue` 风格），路由 `/system/accounts`，挂系统设置组，菜单名"云盘账号"。
- 功能：账号列表（providerType 标签、displayName、status、quota）、新增/编辑（providerType 下拉 + 凭证粘贴框，明文只写不读）、删除确认、verify 按钮、quota 展示。
- Header"账号与安全"占位跳转**不改**。
- UI 遵循 docs/design/ui-design-spec.md。

## 验收

- admin 登录可见页面，CRUD/verify/quota 全部可用，表单不回显明文凭证。
- 非该权限用户不见菜单/路由拒绝。

Blocked by: 03
