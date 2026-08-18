# ADR-0009：平台侧能力需求（dnf-tw 多实例依赖）

- 状态：已接受（2026-08-18；平台侧代码实施另起任务）
- 关联：ADR-0002（范围隔离）、ADR-0008（插件部署配置扩展）
- 发起方：dnf-tw 插件（独立仓库）多实例连接模型

## 背景

dnf-tw 插件需为每个游戏实例维护独立的 MySQL 连接池（五库），而 MySQL 运行在
实例所在主机的容器内（宿主机映射端口 `MYSQL_PORT`，容器内固定 4000）。平台与
目标主机之间仅 SSH 可达，插件无法直连数据库。由此对平台提出三项需求，按依赖
强度排序：SshTunnelService SPI（硬依赖）、configInfo.database 组装（优化项，
插件侧有裸变量回退）、onInstanceUpdate 钩子（优化项，插件侧有失败重查兜底）。

## 决策

### 1. SshTunnelService SPI（硬依赖）

`backend/plugin` 新增接口（DTO 用嵌套 record，与 `FileAccessService.FileInfo`
惯例一致），core 基于 `SshUtil` 共享 SshClient/会话池 + Apache MINA SSHD
`ClientSession.createLocalPortForwarding` 实现，经 `PluginSpringContextFactory`
注册进插件子容器：

```java
public interface SshTunnelService {
    /** 用平台已登记主机的凭据开隧道；hostId 不存在或 SSH 失败抛 BusinessException */
    TunnelHandle openByHost(Long hostId, String remoteHost, int remotePort);
    /** 用调用方自带凭据开隧道（宿主不落库、不写日志，插件连接档案场景） */
    TunnelHandle openWithCredentials(SshEndpoint ssh, String remoteHost, int remotePort);
    /** 幂等关闭：引用计数减至 0 才真正关闭隧道 */
    void close(TunnelHandle handle);

    record SshEndpoint(String host, int port, String user, String password, String privateKey) {}
    record TunnelHandle(String id, int localPort, String remoteHost, int remotePort,
                        String ownerPluginId) {}
}
```

**隧道生命周期——会话钉住（session pinning）**：

- `openByHost` 复用 `SshUtil` 会话池中的 `CachedSession`，TunnelHandle 钉住该
  会话（引用计数），reaper 跳过被钉住的会话。早期表述"隧道句柄纳入 reaper
  空闲回收周期"按字面不可行：隧道转发流量不刷新 `CachedSession.lastUsed`，
  活跃隧道会被 5 分钟空闲回收误杀。
- 隧道本身不做空闲回收，仅在三种情况关闭：`close()` 引用计数归零、插件卸载
  兜底、宿主删除主机联动（仅平台凭据隧道）。
- `openWithCredentials` 持有专用 ClientSession（不入共享池），随隧道关闭而
  关闭。原因：共享池键 `HostKey(host, port, username)` 不含凭据，插件凭据与
  平台凭据同键不同密码时复用池会拿到错误会话。

**去重与引用计数**：去重键 = `(ownerPluginId, hostId, remoteHost, remotePort)`
（openWithCredentials 为 `(ownerPluginId, 凭据指纹, remoteHost, remotePort)`）。
同插件重复 open 同一目标返回同一 handle 并 +1 引用；跨插件不共享（即使目标
相同），宿主兜底语义最简。

**安全默认**：

- 本地转发端口仅绑定 `127.0.0.1`（隧道不暴露网络），端口由 OS 随机分配
  （bind `:0` 后取实际端口）。
- 平台凭据（openByHost）与插件凭据（openWithCredentials）完全隔离，后者宿主
  不持久化；`SshEndpoint` 禁止 toString/日志泄露凭据。
- 信任边界：接受可信插件模型——任何已安装插件可对任意已登记主机开隧道
  （转发到该主机可达的任意 remoteHost:remotePort），与 ExtensionClient、
  InstanceFileService 等现有 SDK 服务同信任级别（插件由管理员手动安装、运行
  于同一 JVM），不做按插件授权。

**兜底**：插件 `onUnload()` 主动 close 自己的句柄是加速路径；宿主在
PluginLifecycleHook 插件 stop/unload 时强制关闭该 ownerPluginId 的全部句柄；
HostService 删除主机时关闭该 hostId 开出的全部（平台凭据）隧道。

### 2. configInfo.database 组装（优化项，插件侧有裸变量回退）

- **声明位置与 schema**：游戏元数据 yml 的 `dockerCompose` 部署节内新增
  `database` 子节，字段采用变量名引用式（字面量 + `portVar`/`passwordVar`
  变量名引用）：

```yaml
game:
  dockerCompose:
    # ... 既有 composeTemplate / variables
    database:
      type: mysql
      host: 127.0.0.1          # 语义：实例所在主机的回环地址（经 SSH 隧道访问）
      portVar: MYSQL_PORT       # 取部署变量最终值（用户输入 > 默认值）
      user: root
      passwordVar: DNF_DB_ROOT_PASSWORD
      databases: [cain, siroco, ...]   # 五库名占位，实施时由 dnf-tw 插件侧确认
```

- **归属 ADR-0008 合并体系**：database 子节随部署节整节替换合并——yml 为
  基线，插件 `getDeployConfigs()` 覆盖部署节时 variables + database 一起
  自洽替换，不单独定义第二套合并规则。
- **组装时机与路径**：`DockerComposeAdapter` 部署完成时按声明组装（用户输入
  值 > 默认值，与 .env 生成同源同规则），写入实例已有 `configInfo` JSON 字段：
  `configInfo.database = {type, host, port, user, password, databases[]}`。
  `InstanceServiceImpl.updateInstance` 写回 configInfo 时走同一私有组装方法
  重算——部署与更新同一条代码路径，避免裸变量与 database 节不一致。
- 不新增表字段、不改 `InstanceVO`（configInfo 已透出）。
- 组装机制上线前部署的老实例由插件侧裸 compose 变量回退解析
  （configInfo.MYSQL_PORT / DNF_DB_ROOT_PASSWORD），无需重部署。
- 时序说明：`onInstanceCreate` 在部署前触发，收到的 configInfo 尚无
  database 节；插件按"懒建"模型在首次连接时经 `getInstanceById` 读取。

### 3. onInstanceUpdate 钩子（优化项，插件侧有失败重查兜底）

- `GameEnhancementExtension` 新增
  `default void onInstanceUpdate(Long instanceId, Map<String, Object> config)`，
  实参为 update 后的**完整新 configInfo**（与 onInstanceCreate 对称）。
- 接线：`InstanceServiceImpl.updateInstance` 在 `updateById` 之后调用
  `PluginLifecycleHook.executeInstanceUpdateHooks`（与现有四个实例钩子同模式：
  gameCode 匹配、try-catch 吞异常、插件异常不影响实例更新事务）。
- 每次更新都触发，不做平台侧 configInfo diff（配置是否真变由插件自行比对）。
- 插件消费：实例 configInfo 变更（改密码/端口）→ 主动失效对应连接池。

### 4. 已有能力确认（无需平台改动）

- `onInstanceCreate/Start/Stop/Delete` 四钩子已存在且已接线
  （PluginLifecycleHook + InstanceServiceImpl）；插件仅消费 Delete（关闭对应
  池+隧道），Create/Start/Stop 不消费（懒建 + 池兜底）。
- `InstanceQueryService.getInstanceById/listByGameCode`、`HostQueryService.getHostById`
  满足自动解析需求；`HostVO` 不含 SSH 凭据，凭据边界见第 1 条。

## 理由

- 隧道复用共享会话池省 SSH 握手；钉住模型改动最小，避免流量探活的实现复杂度
- 去重键含 ownerPluginId 把跨插件共享的引用记账简化为按插件隔离，覆盖
  dnf-tw（单插件多实例多连接池）场景而无额外复杂度
- database 声明纳入 ADR-0008 合并体系，避免第二套合并语义；变量名引用式比
  `${}` 内插解析简单且无转义歧义
- onInstanceUpdate 与现有四钩子同模式；插件侧已有兜底，平台侧风险低

## 后果

- `backend/plugin` SDK 增加 SshTunnelService（空壳文件已就位），
  `PluginSpringContextFactory` 注册清单 +1
- 平台向插件暴露基于主机凭据的任意端口转发能力（可信插件模型，见决策 1）；
  未来若出现不受信插件来源，需增加按插件授权
- dnf_tw.yml 增加 database 子节（五库名待 dnf-tw 插件侧确认后补全）
- DockerComposeAdapter 与 InstanceServiceImpl 共用 database 组装私有方法，
  其他带 DB 的 compose 游戏可直接复用该声明机制

## 备选方案

- 隧道独立专用会话（不入池）：实现最简但每隧道一次 SSH 握手，放弃
- 共享池键扩展凭据指纹：openWithCredentials 也可复用池，但记账复杂，放弃
- 跨插件共享隧道（owners 集合各持引用）：省端口但兜底/计数语义复杂，
  且无现实场景，放弃
- database 节放 game 顶层：概念独立但脱离 ADR-0008 部署类型合并维度，
  需第二套合并规则，放弃
- 平台侧 configInfo diff 后再触发 onInstanceUpdate：Map 深比较叠加
  BeanUtil 部分更新语义易错，放弃
