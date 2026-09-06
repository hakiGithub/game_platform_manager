# ADR-0019: Steam302 主机加速——无头精简包 + Docker 化部署

| 字段 | 值 |
|------|----|
| 状态 | Accepted |
| 日期 | 2026-09-06 |
| 决策者 | User (grill-with-docs session) |
| 关联 | [ADR-0002](0002-main-app-plugin-scope-isolation.md)（范围隔离规约）、[ADR-0006](0006-deploy-task-state-machine.md)（任务中心互斥，若存在） |
| Supersedes | 无 |

## 背景（Context）

平台纳管的 Linux 游戏主机访问 Steam（商店/社区/创意工坊/CDN 下载）需要加速。社区工具 Steamcommunity 302（dogfight360）官方 Linux 发行版是带 WebKit GUI 的 AppImage（约 120MB），而纳管主机是无头服务器，GUI 形态不可用。

工具的无头能力实测确认：同目录下的 `steamcommunity_302.cli`（7MB，Go 静态链接）不需要 DISPLAY 即可完成全部核心工作——按 `S302.ini` 服务开关 + `S302_rules.ini` 域名规则库生成 Caddyfile、拉起 `steamcommunity_302.caddy`（44MB，静态链接）反向代理、按 `Auto_Modify_Hosts` 开关改写 `/etc/hosts`（`#S302` 标记条目 + 自动备份）、自签 TLS 证书（`ssl_expire=10` 为 10 年有效期，CA 长期稳定）。

## 决策（Decision）

### 决策 1：不用官方 AppImage，采用无头 CLI 精简包

分发包只含 5 个文件：`steamcommunity_302.cli` + `steamcommunity_302.caddy` + `S302.ini` + `S302_rules.ini` + `S302.hosts`（约 52MB，压缩后 ~35MB）。排除项与理由：GUI 主程序（81MB，无头环境无用）、Skia/HarfBuzz 图形库、`.launcher` 桌面脚本（GUI 环境）；证书不打包——CLI 首次启动自签生成（已实测验证）；`dns_hosts.txt`/`dns_blacklist.txt` 不打包——CLI 缺失时自动生成默认值。

`S302.hosts`（868 条 `127.0.0.1 域名 #S302` 模板）**必须**随包分发：它是 hosts 劫持条目的数据源，`#S302` 标记是工具增删自己条目的依据。

### 决策 2：Docker 化，平台只做容器编排，不做裸机安装

容器形态（`--network host` + `/etc/hosts` 读写挂载 + `/opt/steam302:/data` 卷）：

```sh
docker run -d --name steam302 --restart unless-stopped \
  --network host \
  -v /etc/hosts:/etc/hosts \
  -v /opt/steam302:/data \
  steam302:15.0.4
```

- **必须 host 网络**：hosts 重定向把目标域名指向 `127.0.0.1`，反代必须监听宿主机网络栈。
- **❌ 修订（实测发现）：不挂载 `/etc/hosts`**。原方案 `-v /etc/hosts:/etc/hosts` 实测失败——CLI 改写 hosts 采用「写临时文件 + rename 原子替换」，rename 跨挂载点必然 `EBUSY`（容器日志"重命名 hosts 文件最终失败: device or resource busy"），工具随之回退仅监听模式（caddy 挂随机端口，自带端口转发占 0.0.0.0:443/80，但域名劫持完全缺失）。改为**平台在宿主机侧管理 hosts**：容器启动就绪后，`Steam302HostsSync` 解析数据目录中生成的 Caddyfile 站点块（行首 `https://` 地址行，剥离端口、丢弃通配符），以 `127.0.0.1 域名 #S302` 格式整体覆盖写入 /etc/hosts（root 直跑/sudo 提权）；停止时清除全部 `#S302` 行。劫持条目与 Caddyfile 启用的服务严格一致——禁用的服务域名不写 hosts，避免"解析到 127.0.0.1 却无代理路由"导致服务彻底不可达。工具日志中的 rename 报错属预期噪音，不影响功能。
- **镜像内 entrypoint 语义**：二进制无条件从镜像刷新到 `/data`（镜像升级即更新二进制）；配置文件仅首装种子（保留用户修改）。
- **平台不做裸机安装路径**（解压 + nohup）：单一实现路径，主机无 Docker 直接报明确错误。镜像名 `gfw-302`，分发至阿里云深圳仓库 `registry.cn-shenzhen.aliyuncs.com/haki_hub/gfw-302:15.0.4`（配置项 `game-platform.steam302.image`，2026-09-06 已推送）。

### 决策 3：提权 = root 直跑 / 复用 SSH 密码 sudo -S / 密钥-only 明确报错

- SSH 账号是 root → 直接执行。
- 非 root 且主机存有 SSH 密码 → 复用存储的 SSH 密码经 `sudo -S` 提权（运维惯例 SSH 密码 = sudo 密码）。docker 命令先直跑，`permission denied` 时才提权（docker 组用户免 sudo）。
- 非 root 且仅密钥登录 → 无法提权，抛出"请在主机配置中补充密码"的明确错误。不做独立 `sudoPassword` 字段（后续增强可加）。

CA 证书信任是唯一每次安装都需要 root 的步骤：从 `/opt/steam302/steamcommunityCA.pem` 按发行版装入信任库（Debian 系 `update-ca-certificates` / RHEL 系 `update-ca-trust extract`）。信任 **CA**（长期稳定）而非叶证书，叶证书重签无需重新信任。

**❌ 修订（实测发现）：系统信任库对 dockerd 不够**。Go 程序（dockerd）只在进程启动时加载系统信任库——主机上先于本安装存在的 dockerd 不会感知新装的 CA，`docker pull` 走 302 代理时报 `x509: certificate signed by unknown authority`（WSL 实测踩中）。安装任务额外为 dockerd 写 `/etc/docker/certs.d/{registry-1.docker.io,auth.docker.io,production.cloudflare.docker.com}/ca.crt`：certs.d 由 docker 按次拉取读取，**无需重启 daemon**，对已运行的 dockerd 立即生效。

### 决策 4：配置页 = 服务列表（GUI 同构），平台不碰 hosts 与规则库

三文件分工：`S302.ini` 是用户开关层（配置页唯一编辑对象）；`S302_rules.ini`（编码的域名规则库）与 `S302.hosts`（hosts 模板）是工具自消费的数据文件，随包分发、平台不读写。数据流：开关写 ini → 重启容器 → CLI 自己改写 /etc/hosts + 重建 Caddyfile（GUI 右栏原文"将在重启服务后生效"）。

- 前端**硬编码**服务元数据（`frontend/src/constants/steam302Services.js`）：key → 中文名 → 三分组（Steam 16 项 / EA 2 项 / 其他服务 36 项），未收录键兜底显示键名。
- **服务→域名映射**（"看 github 到底代理了哪些域名"）来自离线提取脚本 `docker/steam302/extract-services.py`：逐开关生成 Caddyfile 解析站点块（规则文件自身是自定义编码，不做运行时破解），产出静态 JSON（528 域名）随代码入库、与镜像版本绑定；运行时"实际生效域名数"另行解析主机上的真实 Caddyfile。
- 保存 ≠ 生效：保存只写 ini，"保存并重启"（stop + start）才生效。

### 决策 5：安装走任务中心，其余操作同步

taskType=`STEAM302_INSTALL`（source=MAIN，scopeType=HOST、scopeKey=hostId 互斥），15 分钟超时覆盖镜像拉取。安装步骤：探测 Docker → 解析镜像（本地有则免拉取）→ 建数据目录 → 起容器 → 轮询等待证书生成（90s）→ 信任 CA。启动/停止/状态/配置读写均为同步接口（秒级 SSH 命令）。

## 后果（Consequences）

- **正向**：镜像 ~60MB（对比 AppImage 120MB）；无 GUI 依赖；配置持久化在宿主机卷，容器可随意重建；服务列表 UI 与工具版本解耦（动态渲染 ini 键 + 静态域名映射兜底）。
- **代价**：域名映射是离线提取的静态快照，工具规则库大版本升级后需重跑 `extract-services.py`；`ssl_expire=10`（年）期内无需处理证书轮换，但 CA 若轮换需重跑信任步骤（列为实现期验证项）。
- **待验证**（真机部署时）：CLI 重启时是否严格按开关过滤 /etc/hosts 条目（而非 868 条全量写入）；非 Debian/RHEL 发行版的信任库路径。
- **归属**：core 模块主机级能力（`com.gameplatform.steam302` 包），不含任何游戏业务配置，符合 ADR-0002。

### 决策 6：hosts 劫持目标二态 + 容器共享加速（ADR 修订 2026-09-06）

平台原有人工「hosts 刷新」功能（HostsFileRefresher）把 127.0.0.1 条目搬到宿主机 LAN IP 行，供 bridge 容器绕行访问代理。平台托管 Steam302 后两者会互相覆盖（sync 重写回 127.0.0.1；refresh 的 LAN 行无标记、stop 清理不摘除形成永久残留）。收敛为：

- **hosts 唯一写入者 = Steam302HostsSync**。目标 IP 二态：默认 `127.0.0.1`（#S302，服务本机进程）；开启「容器共享加速」（数据卷 flag 文件 `.platform-container-share`）后写宿主机 LAN IP（`#S302-LAN`）——caddy 监听 0.0.0.0，本机与 bridge 容器走同一条目，无解析歧义。开关切换即时重写 hosts，无需重启容器；两种标记行在重写与 stop 清理时统一摘除。
- **人工刷新降级**：Steam302 处于 RUNNING 时，hosts-preview/hosts-refresh 直接拒绝（提示走面板），杜绝双写者打架；停止后可手动管理。
- **自愈**：status 检测到「容器 RUNNING 但劫持条目为 0」（宿主机重启后 docker 策略自起容器、WSL 重新生成 /etc/hosts 等场景）自动补写。
- **容器侧边界**：bridge 容器的 DNS 链路决定其能否受益（容器不读宿主机 /etc/hosts）——host-network 容器必生效；bridge 容器依赖部署流程的 extra_hosts 注入与 DNS 环境。WSL 测试环境另有 Windows 宿主 hosts 残留遮蔽问题，非平台缺陷。

## 实现落点

- 镜像：`docker/steam302/{Dockerfile,entrypoint.sh,build.sh,extract-services.py}`
- 后端：`Steam302Properties` / `Steam302Service(Impl)` / `Steam302Controller` / `steam302/Steam302InstallHandler` / `steam302/Steam302InstallExecutor` / `steam302/Steam302HostsSync` / `steam302/SudoAwareSshRunner`
- 前端：`frontend/src/views/host/components/Steam302Panel.vue`（主机详情页第 06 面板）、`frontend/src/constants/steam302Services.{js,json}`、`frontend/src/api/host.js`
