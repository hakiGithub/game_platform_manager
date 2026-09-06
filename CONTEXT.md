# CONTEXT - 领域术语表

> 本文件只做术语表（ubiquitous language），不含实现细节。实现决策见 `docs/design/adr/`。

---

## RCON 领域

### RCON（远程控制台）
游戏服务器的远程命令协议通道。本平台指 **Source RCON 协议**（Valve 制定的 TCP 文本协议，被 Source 引擎及大量第三方游戏服务器采用）。协议实现归**主应用**，是通用传输能力。

### 传输层（Transport Layer）
主应用提供的 RCON 通用能力：协议编解码、连接建立/认证、连接池、按实例的端点解析。只认字节，不理解任何游戏命令语义。

### 语义层（Semantic Layer）
游戏命令的业务含义：`status` 输出解析、地图列表、kick/ban 语法、难度/模式切换等。归**插件**（如 plugin-l4d2）。同一命令在不同游戏上语义不同，因此永远不上提到主应用。

### RconEndpoint（RCON 端点）
一次 RCON 连接所需的三元组：主机地址、端口、密码。由端点解析器从实例与主机数据推导，不可由插件直接指定密码。

### 端点解析回退链（Endpoint Resolution Fallback）
从实例数据推导 RconEndpoint 的规则。主应用只认**标准键**：`configInfo.rconPort`（缺省 27015）与 `configInfo.rconPassword`。专属键名归一化发生在游戏元数据层——`games/{gameCode}.yml` 的部署变量名直接采用标准键（容器 env 名保持容器契约，经占位符映射）；存量实例由启动时幂等迁移修复。专属回退链不属于主应用契约。

### 连接池借用（Connection Borrow）
调用方通过主应用 RCON 服务借用一个已认证连接执行命令，用毕归还。同实例的借用是串行的；池参数（空闲回收、保活、借用超时）由主应用统一配置。

### 调用方标识（Caller Identity）
每次 RCON 命令执行自动携带的来源信息（插件 ID 或 "main-app"），用于统一审计。调用方无法伪造或省略。

---

## 实例动态信息领域

### 实例信息提供者（InstanceInfoProvider）
插件实现的扩展点：按实例查询动态信息（当前玩家数等）。插件以 `@Component` 声明即被主应用按游戏编码注册；未实现提供者的游戏走降级默认值。

### 实例动态信息（InstanceDynamicInfo）
提供者返回的类型化结果：`playerCount`（当前玩家数）与 `maxPlayerCount`（人数上限，可为 null 表示无法提供）是主应用消费的契约字段；`extras`（开放扩展袋，主应用不解释、透传给详情展示）。返回 null 表示"本次不可知"。字段按"extras 先行、按需升级为类型化"的路径演进。

### 降级默认值（Fallback Value）
提供者未实现、返回 null 或查询超时/超预算时展示的玩家数：RUNNING 实例用 `game_instance.online_players` 存量值，非 RUNNING 实例固定 0。降级不覆盖已落库的上次真实值。

### 信息缓存（Instance Info Cache）
主应用持有的实例维度 TTL 缓存（15 秒，Guava Cache）。实时查询先过缓存，命中即复用；插件卸载、实例停止/删除时失效对应条目。缓存窗口内不提供强制穿透。

### 查询预算（Query Budget）
列表页并发查询的整体时间上限（3 秒）。单实例查询超时 5 秒，但被整体预算截断；预算内未完成的实例本次用降级默认值，已发起查询的结果仍会落库并进缓存。

### 地图上传任务（Map Upload Task）
地图文件经上传接口暂存后提交执行队列的异步任务（taskType=map-upload，按实例互斥）。同步阶段只做扩展名校验与暂存；VPK 解析、SSH 上传、自动裁剪在队列中执行，进度与失败原因在任务详情可查。

### VPK 提取（VPK Extraction）
压缩包（zip/rar/7z）解压后只收集 `.vpk`、其余条目丢弃的策略；目录结构剥离、macOS 垃圾文件跳过、越界路径拒绝。多 VPK 压缩包逐个处理，部分失败不回滚已成功者（结果汇总进任务详情）。

### 解压安全上限（Extraction Limits）
解压总字节数与条目数的硬上限（默认 4GB / 10000 条），超限立即中止并清理临时目录，防压缩炸弹。上限属插件配置，不随压缩包格式变化。

### 玩家数唯一事实源（Player Count Source of Truth）
`game_instance.online_players` 列。提供者查询成功且值变化时回写；列表、详情、导出等所有读方都读这一列，不再从部署适配器统计口径取玩家数。

---

## 地图领域

### 切换地图（Change Map）
经游戏协议命令让服务器立即加载指定地图，当前对局中断、玩家进入新图。改变的是"现在玩哪张图"。

### 地图热重载（Map Hot Reload）
不切换地图、不重启服务器，让服务器重新扫描已安装的地图资源（VPK 路径与 mission 定义）。改变的是"哪些图可用"。

### 官方章节目录（Official Chapter Catalog）
平台内置的官方战役章节清单（战役名 + 章节地图码）。用于把服务器实际可用的地图归类为官方或三方，并为官方图提供章节级换图目标。

### 三方地图（Custom Map）
以 VPK 形式安装在服务器 addons 目录、不在官方章节目录内的地图。

---

## 实例重启领域

### 重启模式（Restart Mode）
执行实例重启的语义方式：RCON（经游戏协议通道重启）、COMMAND（在主机上执行命令或容器重启）、AUTO（不显式指定，交由重启偏好分流）。调用方主动发起重启时显式选择 RCON 或 COMMAND；AUTO 只表达"按配置决定"，不是一种独立的重启手段。

### 重启偏好（Restart Preference）
AUTO 模式的分流依据：优先经 RCON 重启还是优先命令重启。插件全局一份，修改后持久保存，不随插件或主应用重启丢失。

---

## 平台通用

### 宿主能力服务（Host Capability Service）
主应用通过插件 Spring 子容器注册给插件的单例服务接口（如 InstanceQueryService、HostQueryService、RconService）。插件只依赖 SDK 接口，不依赖主应用实现。

### 实例标准配置键（Instance Standard Config Keys）
实例表 `configInfo` JSON 中由主应用契约约定的键。RCON 相关为 `rconPort`、`rconPassword`。游戏专属键不算标准键。

---

## UI 页面术语（对齐 docs/design/ui-design-spec.md）

### 页面标准命名
页面标题 = 菜单名 = 面包屑末级。标准名：主机列表、实例列表、任务中心、游戏元数据（页面 UI 可称"游戏目录"）、插件列表。

### 实例（Instance）
跑在主机上的游戏服务器。UI 中禁止使用"服务编队 / 编队 / 服务单元"等别称。

### 任务（Task）
任务中心的异步执行记录。页面、菜单、表格统一用"任务"词汇（任务列表 / 任务记录），不再使用"执行链 / 执行队列"作为用户可见标题。

### 新增主机（Add Host）
主机列表唯一的新建入口名。不再使用"纳管主机"作为按钮文案。

### 连接测试门禁（Connection Test Gate）
新增主机必须先"测试连接"成功才允许保存（表单参数直测，无需先落库）。

### YAML 导入（Metadata Import）
游戏元数据支持上传 YAML 文件零代码新增游戏（后端 `/games/import`，上传前自动校验格式）。

---

## Steam302 主机加速（ADR-0019）

### Steam302 精简包（Steam302 Headless Bundle）
无 GUI 的 Steamcommunity 302 分发形态：`steamcommunity_302.cli` + `steamcommunity_302.caddy` + `S302.ini` + `S302_rules.ini` + `S302.hosts` 五文件。官方 AppImage（GUI 形态）不用于纳管主机。

### 服务开关（Service Switch）
`S302.ini` `[Setting]` 区的布尔功能键（如 `github`、`Steam_store`），配置页唯一编辑对象。分组：Steam / EA / 其他服务。修改保存后需重启容器生效。

### 服务域名映射（Service Domain Map）
服务开关 → 该开关代理的域名清单。由离线脚本逐开关生成 Caddyfile 提取，静态快照随代码入库、与镜像版本绑定；运行时"生效域名数"以主机上真实 Caddyfile 为准。

### 主机工具安装任务（Host Tool Install Task）
主机级工具的异步安装任务（如 STEAM302_INSTALL）：scopeType=HOST、scopeKey=hostId 互斥，同一主机同时只跑一个。
