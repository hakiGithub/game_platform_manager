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

## 主机环境工具领域（ADR-0021）

### 主机环境工具（Host Environment Tool）
宿主机上补丁安装流程所依赖的二进制工具（unzip、unrar、p7zip、xz、bzip2、tar、curl、wget、rsync），平台以**白名单**形式提供一键安装。与 Steam302 领域的"主机工具安装任务"（指 STEAM302_INSTALL 那类异步任务）是不同概念——本领域只谈发行版包管理器安装的系统包，且为**同步**执行。

### 主机能力探测（Host Capability Probe）
推送到主机执行的探测脚本产出的能力清单：环境工具的有无（command -v）、包管理器类型（apt/dnf/yum/apk/pacman/zypper）、提权能力（root / sudo）。内存缓存短 TTL，不落库；安装成功后立即失效对应主机的缓存。

### 发行版自适应安装（Distro-Adaptive Install）
平台按探测到的包管理器自动选择安装命令（如 `apt-get install -y unzip`）。识别不了的发行版不做猜测，引导用户用 Web 终端手动安装。

### Docker 代劳（Docker Tooling Proxy）
主机有 Docker 且能自行拉取平台工具镜像时，下载/解压借 `docker run --rm` 容器内预装工具完成，宿主机零改动、无需提权。主机拉不到镜像（无外网 LAN 等）则 Docker 代劳不可用，回退链：发行版自适应安装 / 平台代劳。不提供平台侧镜像投喂（save/load）。

### 平台工具镜像（Platform Tooling Image）
平台自维护的小型解压/下载工具镜像（alpine + p7zip/unrar 等预装），托管于平台镜像仓库，运行时零网络依赖（除首次拉取）。

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

---

## 插件市场领域（ADR-0022，plugin-l4d2）

### 插件市场三 Tab（Plugin Market Tabs）
L4D2 插件管理页内的三个平级视图：已安装 / 内置插件 / 远端仓库。"找插件装"与"管理已装"同页同语境，市场不弹窗、不设独立菜单。

### 内置插件（Builtin Plugin）
随插件分发的内置插件清单条目（必选/推荐/可选分类），支持批量安装，安装异步提交任务中心。

### 远端仓库插件（Remote Repository Plugin）
GitHub 远端插件仓库条目，逐个下载安装；详情（README + 文件列表）在表格行内展开，不弹抽屉。

### 仓库配置（Store Config）
远端仓库的运行时设置：仓库地址、分支、代理前缀、访问令牌。归 l4d2 插件自管（ADR-0002），不进主应用系统设置；仓库地址接受完整 URL 或 owner/repo 两种写法，统一以 owner/repo 为准。

### 未配置仓库（Unconfigured Store）
仓库地址为空的状态：远端仓库 Tab 展示"未配置仓库"引导去配置，而非报错。与连接失败（已配置但远端不可达）互斥——后者给可读错误文案。平台不内置默认仓库，新装即未配置。

### 插件任务反馈区（Plugin Task Strip）
插件管理页页头下方的 sticky 任务进度区：任意 tab 发起的安装/下载均在此可见，可展开明细；不锁页面、不阻止切换。

### 已安装名称匹配（Installed Name Match）
内置/远端 tab 标识"已安装"的前端规则：插件名去 `.smx` 后缀、忽略大小写与下划线/连字符差异后精确相等。已安装项打角标不隐藏，筛选 chips（全部/未安装/已安装）默认"全部"。匹配率不足时升级为后端 VO 返回 `installed` 字段。

---

## 容器认领领域（ADR-0023）

### 容器认领（Container Adoption）
在 Docker 容器页面把一个已存在的容器手动识别为游戏服务器实例：创建真实 `game_instance` 记录并回写 `runtime_metadata.containerId/containerName` + `adopted` 标记，**不触发部署**。认领后启停、状态对账、isLinked、控制台自动生效。

### 认领实例（Adopted Instance）
带 `adopted` 标记的实例。删除语义是**记录级删除**：默认只删实例记录不动容器，前端确认弹窗显式勾选"同时删除容器"才执行 docker rm。

### deployType 自动探测（Deploy Type Detection）
认领时按所选游戏元数据 `deployTypes` 在 Docker 类类型中取交集，优先级 docker > docker-compose > linuxgsm-docker；选定 docker-compose 时从容器的 `com.docker.compose.*` labels 预填 projectName/workDir/serviceName，labels 缺失则字段可编辑必填。

---

## UI 自动化测试领域

### 冒烟集（Smoke Suite）
CI 门禁自动执行的无主机 E2E 子集：登录认证、路由守卫、全页面可达性、游戏元数据管理与 YAML 导入、系统设置、任务中心、插件列表与插件 UI 资源可达、主机表单校验与错误态。红线是"CI 环境可重复执行"，因此不依赖任何真实主机。

### 全量回归集（Full Regression Suite）
打真实牺牲主机的完整链路 E2E，以 `docs/testing/ui-testing/07-e2e-checklist.md` 为蓝本。发版前/手动触发，不进 CI。

### 演练链（Drill Chain）
单个演练游戏在全量回归中的完整测试序列：部署 → 运维能力（启停/重启/备份还原）→ 插件能力（仅 l4d2 有）→ 删除实例清理现场 → 下一个游戏。游戏之间串行执行，互不影响。

### 演练游戏（Drill Game）
全量回归选定的部署靶子游戏：l4d2、dst、sdtd（7 Days to Die）。按真实在用游戏选型，接受单次全量回归耗时长（数 GB 下载）的代价。

### 牺牲主机（Sacrificial Test Host）
专供自动化折腾的测试 Linux 主机，授权全权限：部署、启停、备份还原、删除实例均可真实执行，测完自动清理。与开发/生产主机严格隔离，凭据经环境变量注入测试进程。

---

## 云盘能力领域（ADR-0024）

### 云盘传输层（Cloud Drive Transport Layer）
主应用嵌入 clp-sdk 提供的通用网盘能力：多 Provider（baidu/quark/aliyun/cloud189/xunlei/openlist）凭证执行、列目录、直链解析（url 与 headers 连带）、流式下载、同步转存。只认"凭证 + 路径 + 操作"，不理解任何游戏/地图语义。语义层（哪些地图、转存到哪、下载到主机何处）归插件。

### 云盘账号（Cloud Account）
平台级网盘凭证资产：providerType + 加密凭证（Cookie/token），落既有 `extensions` 共享表（`group="platform"`、`kind="cloud_account"`），管理员经主前端"云盘账号"页（`/system/accounts`）管理。凭证任何接口不回显明文。

### 宿主保留命名空间（Host Namespace `platform`）
core 以保留 pluginId `"platform"` 使用 ExtensionClient；插件绑定的 pluginId 与之互斥，按 group_name 过滤双向隔离。真实插件不得取该 ID。

### 默认挂载点（Default Mount）
账号的隐式挂载规则 `/{providerType}/{accountName}/`，由宿主派生，不做独立挂载管理；调用方以账号 name + 相对路径寻址。

### 同步转存（Synchronous Transfer）
`transfer(account, shareUrl, targetPath, timeout)` 阻塞契约：完成返回目标路径，超时尽力取消底层 Job（不留"调用方以为失败、网盘侧却成功"的脏状态）。任务状态机/重试/进度归业务方（插件 TaskHandler），SDK 异步 TransferJob 体系不使用。

### clp-sdk
本地姊妹项目 cloud_list_platform 的进程内 SDK（`com.haki.clouddrive:clp-sdk`），零 Spring、OkHttp/Jackson。索引/搜索/订阅能力被本平台明确排除（会引入索引表）。
