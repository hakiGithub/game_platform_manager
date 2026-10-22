# dnf_tw 用例包

DNF 台服（`plugin-dnf-tw`，`plugin.id=plugin-dnf-tw`，gameCode=`dnf_tw`）的插件子应用 E2E。

## 可行性结论（2026-10-23 实测）

| 项目 | 结论 |
|------|------|
| 部署方式 | `docker-compose`，平台 `DockerComposeAdapter` |
| 镜像 | `llnut/dnf:debian13-qf1031-latest`，牺牲主机本地已存在（约 594 MB） |
| 部署耗时 | 约 40 秒到达「运行中」 |
| MySQL | 容器内自带；实测存在 `d_taiwan` / `taiwan_cain` / `taiwan_cain_2nd` / `taiwan_billing` / `taiwan_login` |
| 插件连接 | 实例源自动解析为 `connected=true`，账号/角色/金币接口可查 |
| 端口 | `MYSQL_PORT` 默认 3000，与本机前端冲突，E2E 固定改为 `13306` |
| 容器内存 | 上游 `mem_limit: 1g`；实测 1g / 1.5g 都会 OOM，用例改用 `memoryLimit=2.5` |

### 已知环境问题（实测）

上游 `mem_limit=1g` 会把两个 `df_game_r` 分片压到反复 SIGKILL
（`dmesg` 可见 `Memory cgroup out of memory: Killed process ... (df_game_r)`）。
1.5g 也只够跑一小段：约 1 分钟后分片仍被杀，插件开始刷
`Communications link failure`，`/dashboard/overview` 因逐源探活而长时间阻塞。

本包用 `resources.memoryLimit=2.5`（`E2E_DNF_MEMORY_LIMIT_G` 可调）覆盖：
该容器实测常驻 1.2~1.5 GiB，2.5 只是上限，WSL 3.8 GiB 下可承受。

即便如此，**页面契约断言仍不依赖游戏库持续可用**；需要库的页面按「有数据」或
「页面明确提示错误」二者之一断言。

## 跑法

```bash
bash scripts/e2e-docker-run.sh e2e/plugins/dnf_tw
npm run e2e -- e2e/plugins/dnf_tw     # Windows 受管模式
```

未设置 `E2E_TEST_HOST_*` 时整组 SKIP。

## 用例结构

- 10 个菜单页逐一进入 + 页面根渲染 + 关键文案/控件/空态。
- 写操作只选「连接管理」：连接档案是插件自管的 `ExtensionClient` 资源，
  不依赖游戏库内容，创建 → 测试/回读 → 修改 → 删除可以完整闭环。
- 账号/角色/活动/拍卖行等业务操作只做只读查询；牺牲主机是空服，
  不为了"写成功"去伪造玩家数据或改动游戏数据库。

## 已知限制

- 无 Klei 等外部凭据的页面一律按空态断言。
- 部分业务接口在没有真实角色/活动数据时会返回业务错误；用例只断言页面给出
  可理解提示，不把后端 500 静默当成通过（见用例内 [CHECK] 日志）。
- `pvf` 字典首次导入是异步任务，E2E 不等待其完成，只断言状态区与搜索入口。
- 固定端口 `13306`（上游 compose 只开放 `MYSQL_PORT` 变量），跑前确认没有残留容器占用。
- `afterAll` 删实例时可能返回 `partial_success`：compose 里 MySQL 容器以 root 创建
  `mysql/mysql.sock`，SSH 用户 `rm` 无权限，远端目录残留（平台清理逻辑缺陷，另开票）。
