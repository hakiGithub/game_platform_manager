# 11 — 「进入工作区」硬编码 dashboard 路由，没有该路由的插件进不去

**Status:** ready-for-human
**Type:** bug
**Blocked by:** -

## 现象

插件扩展台的「进入工作区」按钮，无论哪个插件，都把目标路径写死成
`dashboard`：

```js
// frontend/src/views/plugin/index.vue
function enterPluginWithInstance(gameCode, instance) {
  router.push({
    path: `/extensions/app/${gameCode}/dashboard`,
    query: { instanceId: instance.id },
  });
}
```

但 4 个插件里只有 l4d2 与 sdtd 声明了 `/dashboard`：

| 插件 | 第一个菜单 | 有 `/dashboard`？ |
|------|-----------|------------------|
| `plugin-l4d2` | `/dashboard` | 有 |
| `plugin-sdtd` | `/dashboard` | 有 |
| `plugin-dst` | `/console` | **没有** |
| `plugin-dnf-tw` | `/dashboard` | 有（按 docs/plugins.md） |

而 `plugin-dst` 的前端路由表（`frontend/src/router/index.ts`）里既没有
`/dashboard`，也**没有 catch-all 兜底路由**：

```ts
{ path: '/', redirect: '/console' },
{ path: '/console', ... },
{ path: '/settings', ... },
// …没有 /dashboard，也没有 '/:pathMatch(.*)*'
```

## 实测（2026-10-22，容器化 E2E）

点「进入工作区」后，宿主 URL 停在
`/extensions/app/dst/dashboard?instanceId=2`，而 dst 的 manifest 声明的菜单是
`/console /settings /players /player-stats /mods /backup /schedules`
（无 `/dashboard`）。子应用侧无匹配路由、无兜底 → 工作区内容区无内容。

## 影响

- dst 用户从插件扩展台进去看到的是空工作区，必须先点侧边栏「服务器控制台」才正常。
- 任何新插件只要第一个菜单不叫 `/dashboard`，就会踩同一个坑。
- E2E 侧被迫在用例里绕开这条路径（先按 dashboard 进、再显式 goto `/console`），
  掩盖了真实的产品缺陷 —— 这正是本次要避免的。

## 建议修法

主应用不猜路径，按插件 manifest 的 `frontend.menus[0].path`（或按 `order` 排序后的第一个）
决定落地页，取不到时才回退 `dashboard`：

```js
const target = manifest?.frontend?.menus?.[0]?.path ?? "/dashboard";
router.push({ path: `/extensions/app/${gameCode}${target}`, query: { instanceId: instance.id } });
```

顺带建议插件侧路由表都加 catch-all → 首页/第一菜单，避免任何深链落空。

## 验收

- [ ] 从插件扩展台进入 dst 工作区，落地页是 `/extensions/app/dst/console` 且渲染控制台。
- [ ] l4d2 / sdtd / dnf_tw 的落地页仍是各自 `/dashboard`（行为不回归）。
- [ ] 无法从 manifest 读到菜单时回退 `/dashboard`，不白屏。
- [ ] dst E2E 用例里的"绕行 goto"可以删掉。

## 关联

- 票 06（dst 用例包）—— 用例里显式记录了这条绕行及其原因。
