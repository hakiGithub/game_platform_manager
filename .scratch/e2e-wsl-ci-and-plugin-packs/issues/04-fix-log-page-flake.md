# 04 — 修 l4d2 日志页用例（日志文件列表空）

**Status:** ready-for-agent
**Type:** bug
**Blocked by:** -

## 现象（2026-10-21 整轮实测）

`e2e/plugins/l4d2/plugin-subapp.spec.js:622` 「服务器日志页渲染文件表与刷新入口」失败：

```
Error: expect(received).toBeGreaterThan(expected)
Expected: > 1
Received:   0
  at ...:644  await expect.poll(() => rows.count(), { timeout: 30_000 }).toBeGreaterThan(1);
```

页面本身渲染正常（标题「服务器日志」、日志文件表格、「未连接」徽标都在），
但 `.log-file-table .el-table__row` 等 30 秒仍是 0 行。

`GET /api/plugin/l4d2/logs/files` 返回 ok，但后端 `SourceModLogService.listFiles` 的过滤规则
`(L\d{8}\.log|errors_\d{8}\.log)` 在该环境下没匹配到任何文件 → 空列表。

## 判断

**不是本次文档改动引入的**（该轮改动只碰 `.scratch/` / `docs/` / `AGENTS.md`，零代码文件）。
属于环境/fixture 层面的存量问题：E2E 造的 l4d2 实例（Docker 部署）在 SourceMod 日志目录下
没有生成符合命名规则的日志文件。

## 待查

1. 确认实例是否真的装了 SourceMod（插件腿的镜像是 `laoyutang/l4d2-pure`，E2E 部署用的是
   元数据默认镜像，两者可能不同）。
2. 确认日志目录 `pathResolver.getSourceModLogsPath()` 在 Docker 实例下解析到哪 ——
   与票 07 记录的"地图列表读宿主机 installPath，而 Docker 实例的 addons 在容器/卷内"
   是同一类问题（Docker 实例的文件视图与宿主机路径不一致）。
3. 决定修法：
   - **A**：fixture 里先造一个符合命名规则的日志文件（经平台文件 API 写入），再断言可见；
   - **B**：用例改为接受"空列表"也是合法渲染结果，只断言表格与刷新入口可用；
   - **C**：修 `SourceModLogService` 在 Docker 实例下的路径解析（产品侧问题，另有票）。

倾向 **A**（保住断言力度，又不掩盖真实缺陷），并把 C 另开产品票。

## 验收

- [ ] 该用例在整轮中稳定通过（连续两轮）。
- [ ] 若采用 A：fixture 造文件与清理都在用例包内自洽，跑完无残留。
- [ ] 若判定为产品缺陷：另开票并在用例里注明阻塞原因，不静默改断言。
