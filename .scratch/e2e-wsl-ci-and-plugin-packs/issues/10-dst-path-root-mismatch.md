# 10 — DST 插件读写路径基准错配（产品缺陷，比票 09 更严重）

**Status:** ready-for-human
**Type:** bug
**Blocked by:** -

## 现象

`plugin-dst` 的 `DstConstants` 明确约定：

> InstanceFileService 的 relativePath 以容器数据卷 `/data` 为根
> LGSM 容器内 linuxgsm 用户 HOME = /data，Klei 集群目录在其下

但 `LinuxGsmDockerAdapter.deploy()` 把 `configInfo.containerWorkDir` 写死成 **`/app`**：

```java
// 写入 configInfo 供 InstanceFileService 解析容器内文件路径
// LinuxGSM 镜像 WORKDIR 固定为 /app
configInfo.put("containerWorkDir", "/app");
```

而 `InstanceFileServiceImpl.buildRoute()` 对 docker 类实例用的正是这个值作相对路径基准。
于是插件所有 `instanceFileService.read/write/exists` 全部落在 `/app` 下，
而真实游戏数据在 `/data` 下。

## 实测证据（2026-10-22，linuxgsm-docker 部署的 dst 实例）

```
# 平台文件 API 的根（containerWorkDir=/app）
$ GET /api/instances/1/files?path=/
['entrypoint-healthcheck.sh', 'entrypoint.sh', 'entrypoint-user.sh',
 'log', 'dstserver', 'lgsm', 'serverfiles', 'linuxgsm.sh']

# 真实集群数据在 /data
$ docker exec probe-dst-lgsm ls /data/.klei/DoNotStarveTogether
Cluster_1
$ docker exec probe-dst-lgsm ls /app/.klei
No such file or directory
```

插件读 `cluster.ini` 时文件不存在 → `readOrEmpty` 返回内置默认值，
所以**界面看起来一切正常**（返回了一套完整的 cluster.ini 内容），
但那是 `IniFile.defaultClusterIni()` 的默认值，不是服务器上的配置。

再验证写入落点：

```
$ POST /api/plugin/dst/settings/ini {"file":"cluster","sections":{"GAMEPLAY":{"max_players":"9"}}}
{"code":200,...}

$ docker exec probe-dst-lgsm find /app -path "*DoNotStarveTogether*" -name cluster.ini
/app/.klei/DoNotStarveTogether/MyDediServer/cluster.ini     ← 凭空造出来的假文件

$ grep max_players /data/.klei/DoNotStarveTogether/Cluster_1/cluster.ini
(不存在 / 未变)
```

## 第二层问题：集群名默认值也不匹配

插件默认 `clusterName = "MyDediServer"`（`DstConstants.DEFAULT_CLUSTER_NAME`），
但实测该镜像生成的是 **`Cluster_1`**。即使把路径基准修成 `/data`，
`/data/.klei/DoNotStarveTogether/MyDediServer` 依然不存在，还是会读默认值、
写到错目录。

（注：本次实例里 `Cluster_1/cluster.ini` 尚未生成，只有 `Master/server.ini` ——
LGSM 首启只生成了 server.ini。需要确认 cluster.ini 的生成时机，或改为读取现有
集群目录名而不是写死默认值。）

## 影响面

`plugin-dst` 里所有走 `InstanceFileService` 的能力在 linuxgsm-docker 实例上全部失效：

| 页面/能力 | 后果 |
|-----------|------|
| 房间设置（cluster.ini / server.ini） | 读到内置默认值，写入造出假文件，**玩家改了没效果** |
| 服务器令牌（cluster_token.txt） | 同上，读「未设置」、写入无效 → DST 启动即退的根因之一 |
| 玩家管理（adminlist / whitelist / blocklist） | 读空列表，写入无效 |
| Mod 管理（modoverrides.lua） | 读空，写入无效 |
| 备份 / 回档快照 | 读不到真实目录 |
| 玩家统计 / 聊天记录 | 读不到 `server_log.txt` / `server_chat_log.txt` |

这解释了为什么 `drill-legs.spec.js` 里 dst 腿只能断言"部署完成"——依赖运行中
服务器的能力本来就都是坏的。

## 待定修法（需产品决策）

- **A. 适配器分游戏配置基准**：`LinuxGsmDockerAdapter` 不该写死 `/app`。
  `games/dst.yml` 增加 `linuxgsmDocker.workDir: /data`（或按 `DATA_VOLUME`
  挂载点推导），适配器读元数据而不是硬编码。
- **B. 集群名动态解析**：插件改为列举
  `/data/.klei/DoNotStarveTogether/` 下的实际目录（排除 `Cluster_*` 前先确认
  是否只有唯一一个），而不是硬编码 `MyDediServer`；或把 clusterName 提升为
  部署变量写入 `configInfo.dst.clusterName`。
- **C. 部署后自检**：部署阶段校验"元数据声明的数据根 + 插件声明的集群目录"存在，
  不存在则部署直接失败并给出可操作错误，而不是让上层静默读默认值。

倾向 **A + B + C**。A 是根因，B 是同级隐患，C 防止下次再静默。

## 为什么不在票 06 里修

与票 09 同样的理由：E2E 用例包负责把「插件装到主应用上之后是否可用」测出来，
不负责改产品。且 A 的改动面跨 `core`（适配器）+ `games/*.yml`（元数据），
需要回归全部 linuxgsm-docker 游戏（dst / sdtd 等），应单独排期。

## 验收

- [ ] 新建 linuxgsm-docker 的 dst 实例后，平台文件 API 根目录能看到 `.klei`。
- [ ] 房间设置页读到的 cluster.ini 与容器内真实文件一致（不是内置默认值）。
- [ ] 保存后容器内真实文件被修改，且未在表单出现的键原样保留。
- [ ] 集群名从实际目录/部署变量解析，不硬编码。
- [ ] 相关 E2E 用例的 SKIP 标记可以去掉，改为强断言。

## 关联

- 票 09（l4d2 Docker 路径基准错配）—— 同一类缺陷的不同表现：那边是元数据写错
  目录、这边是适配器写死目录。
- 票 06（dst 用例包）—— 受本缺陷阻塞的部分已在用例里显式标注。
