# 04 — 修 l4d2 日志页用例（日志文件列表空）

**Status:** resolved
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

- [x] 该用例在整轮中稳定通过（连续两轮）。
- [x] 若采用 A：fixture 造文件与清理都在用例包内自洽，跑完无残留。
- [x] 若判定为产品缺陷：另开票并在用例里注明阻塞原因，不静默改断言。

## 结论（2026-10-22，容器化跑测）

采用 **A**，产品缺陷另开 [票 09](09-l4d2-docker-path-root-mismatch.md)。

### 根因（已实测确认，非猜测）

E2E 部署的 l4d2 实例是 Docker 类型，`InstanceFileService` 对 Docker 路由的
相对路径基准是 `configInfo.containerWorkDir`，而该值来自 `games/l4d2.yml` 的
`docker.workingDir: /home/steam/l4d2-dedicated`。实测本地镜像里这个目录不存在：

```
$ docker run --rm --entrypoint /bin/sh cm2network/left4dead2:latest \
    -c 'test -d /home/steam/l4d2-dedicated && echo exists || echo MISSING'
MISSING
$ docker run --rm --entrypoint /bin/sh cm2network/left4dead2:latest \
    -c 'ls -d /l4d2/left4dead2'
/l4d2/left4dead2
```

所以 `listFiles` 在容器内 `find`/`ls` 双双失败 → 返回 `List.of()`，
`SourceModLogService` 拿到空列表再按 `(L\d{8}\.log|errors_\d{8}\.log)` 过滤，
结果恒为空。这不是"日志还没生成"，是**目录压根读不到**。

另外该镜像 tag 已被顶成 `laoyutang/l4d2-pure`（RepoDigest 可证），它同样不含
SourceMod（`/l4d2/left4dead2/addons` 里只有 `readme.txt`），即使路径修对了也不会
天然产出 `L*.log`。所以夹具造文件这条路是必须的，路径修复无法替代它。

### 改动

1. 新增 `frontend/e2e/support/instance-files.js`：经**平台文件 API**
   （`PUT /instances/{id}/files/content`、`POST /instances/{id}/files/directory`、
   `DELETE /instances/{id}/files`）在实例内造/删文件。用 API 而不是 SSH/docker exec，
   是为了让夹具路径语义和被测插件路径语义**同源**——插件读不到就说明真有问题。
2. 用例在断言前写两份符合命名规则的文件（`L20261022.log`、`errors_20261022.log`），
   并先断言 `GET /instances/{id}/files?path=...` 能列出它们（把"文件没写进去"和
   "插件路径不对"两种失败区分开），`finally` 里删除。
3. 修了一个被夹具"点亮"的既有断言缺陷：`getByText("已连接")` 会同时命中页头
   `hero-state` 与查看器 `el-tag`，在连接成功后触发 strict 模式冲突。改为断言
   `.logs-page .hero-state`。

用例头部注释里写明了"为什么自己造日志"，并指向票 09，避免以后有人把它当成
多余夹具删掉。

### 实测

| 轮次 | 范围 | 结果 |
|------|------|------|
| 修复前 | `-g 服务器日志页渲染文件表与刷新入口` | ✘ 34.9s，rows=0（复现票内原始失败） |
| 修复后 | 同上 | ✓ 10.8s |
| 整轮 1 | `plugin-subapp.spec.js` 全量 | ✓ 28 passed (3.2m) |
| 整轮 2 | `plugin-subapp.spec.js` 全量 | ✓ 28 passed (3.3m) |

均在 `bash scripts/e2e-docker-run.sh`（WSL + Docker 容器栈）下执行，
`E2E_TEST_HOST_ADDRESS=172.17.0.1`（容器内后端需回连 WSL 宿主 SSH）。
夹具文件在用例结束时删除，跑完 `sourcemod/logs` 目录无残留。
