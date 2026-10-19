# Ticket Board

本地 issue tracker 的可视化入口：

- 浏览器打开 `index.html` 即可查看，不需要 npm、后端或开发服务器。
- 页面汇总 `.scratch/**/issues/*.md`，默认只展示未完成票。
- 支持搜索、按项目域/状态/状态来源筛选、按优先级排序、折叠状态分组和查看 ticket 详情。
- 详情抽屉会显示摘要、验收、待确认项、来源文件与 Markdown 原文链接。

## 状态规则

已关闭状态（`done`、`resolved`、`closed`、`wontfix`、`cancelled`）不会进入“全部未完成”视图。

- 新票优先读取文件内的 `Status:` 行。
- `.scratch/backlog/` 与 `.scratch/architecture-review-*/` 的旧票没有统一 `Status:` 行，页面分别标记为“目录内待处理”和“架构评审待处理”。
- 缺少 `Status:` 的其他旧票标记为“待登记状态”，提醒后续处理时补齐状态。

## 重新生成

修改 ticket 后，在仓库根目录运行：

```powershell
node .scratch/tickets/build-tickets.mjs
```

只检查页面数据是否落后于 ticket（不写文件）：

```powershell
node .scratch/tickets/build-tickets.mjs --check
```

生成器会读取所有 `.scratch/**/issues/*.md`，写出：

- `index.html`：自包含的可视化页面，数据已嵌入。
- `tickets.json`：同一份 ticket 数据，便于后续脚本或外部工具使用。

生成器只读取 ticket，并写入本目录，不会修改 ticket 原文。
