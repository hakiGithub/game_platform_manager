# ADR-0021: 主机环境工具安装——白名单 + 发行版自适应 + 同步执行

| 字段 | 值 |
|------|----|
| 状态 | Accepted（2026-09-07 同日修订：新增决策 6 Docker 代劳优先） |
| 日期 | 2026-09-07 |
| 决策者 | User (grill-with-docs session) |
| 关联 | [ADR-0002](0002-main-app-plugin-scope-isolation.md)（范围隔离：主机环境管理归主应用）、[ADR-0006](0006-patch-install-decision-tree.md)（补丁决策树，本能力补齐其"主机不能自治"场景）、[ADR-0019](0019-steam302-headless-docker.md)（SudoAwareSshRunner 上提来源） |
| Supersedes | 无 |

## 背景（Context）

补丁安装决策树（ADR-0006）依赖宿主机自备下载/解压工具。`HostCapabilityProber` 探测 11 个工具后，缺工具的 LAN 主机自动降级为"平台代劳"策略；但 WAN 主机不能自治时直接失败，且报错不写明缺哪个工具。典型场景：宿主机没装 `unzip`/`unrar`，用户除了开 Web 终端手动 `apt install` 没有别的路。

平台需要一个"帮宿主机安装环境依赖二进制"的能力，让用户在 UI 上点一下就能补齐缺失工具，而不必 SSH 上去手敲。

## 决策（Decision）

### 决策 1：白名单制，不做任意命令/任意包名

可安装工具为平台预定义白名单：`unzip, unrar, p7zip, xz, bzip2, tar, curl, wget, rsync`。每个工具映射各发行版的包名与安装命令，由平台维护；不提供用户自定义命令或任意包名入口。

- 替代方案（否决）：用户输入任意命令——等同 Web 终端已覆盖，且引入注入面；任意包名——场景就是"补齐解压/下载工具"，白名单足够且 UI 可以做成按钮。
- `unrar` 在 Debian 系需 non-free 源，可能安装失败：保留在白名单中，失败时如实把包管理器 stderr 报给用户，提示走 Web 终端手动处理。

### 决策 2：发行版与包管理器自适应

扩展 `probe_capabilities.sh` 与 `HostCapabilities`：探测包管理器（apt/dnf/yum/apk/pacman/zypper）与提权能力（是否 root、sudo 是否可用）。安装时平台按探测结果自动选拼接命令（如 `apt-get install -y unzip`），用户无感。识别不了的发行版：面板显示"不支持自动安装，请用 Web 终端手动安装"，不猜命令。

### 决策 3：同步执行，不走任务中心

安装一个小工具通常数秒到数十秒。接口为同步 HTTP（执行超时约 120 秒），UI 即时反馈成功/失败及完整输出。不提交任务中心。

- 替代方案（否决）：任务中心异步——复用度好但交互过重，且任务中心语义是长耗时多阶段任务（部署、补丁安装、地图上传），秒级命令安装进去反而稀释任务列表。
- 已装状态不落库：每次现探测（`command -v` 很便宜），避免状态漂移；安装成功后清除该主机的探测缓存，让面板立即刷新。

### 决策 4：提权复用 SudoAwareSshRunner，并上提到共享位置

`SudoAwareSshRunner` 从 `com.gameplatform.steam302` 上提到共享包（steam302 引用随迁，行为不变）：root 直跑；SSH 密码可复用则 `sudo -S`；密钥登录非 root 无免密 sudo 则失败，报错引导用户用 Web 终端手动安装。不新增任何凭据传递通道。

### 决策 5：归主应用，不暴露插件 SDK

主机环境管理是平台管理员职责。落在新包 `com.gameplatform.hosttool`，接口挂在主应用：

- `GET /api/hosts/{hostId}/tools` —— 白名单工具的已装/未装状态 + 包管理器/提权能力（复用 HostCapabilityProber 的探测与 60s 缓存）；
- `POST /api/hosts/{hostId}/tools/{tool}/install` —— 同步安装。

插件 SDK 不新增对应服务：插件不应有"往主机上装系统包"的能力（与 ADR-0002 范围隔离一致）。

### 决策 6：Docker 代劳优先，包安装兜底

主机有 Docker 且能拉取镜像时，下载/解压一律**借容器完成**（`docker run --rm -v <目录>... <平台镜像> <解压/下载命令>`），宿主机零改动、不需要 sudo、工具版本平台统一：

- 平台自维护一个小工具镜像（alpine + p7zip/unrar 等预装齐），推送至 `registry.cn-shenzhen.aliyuncs.com/haki_hub/`（与 gfw-302 同管线，ADR-0013 CI）。运行时除首次拉取外零网络依赖，不在容器里临时 `apk add`。
- 镜像由**目标主机自行 `docker pull`**：不做平台的 save→SFTP→load 投喂路径。拉不动（典型：无外网的 LAN 主机、无镜像源）则 Docker 代劳不可用，回退到决策 1 的白名单安装或既有"平台代劳"策略。
- 探测脚本新增 Docker 可用性检测；补丁决策引擎（ADR-0006）的"解压/下载工具缺失"分支优先级调整为：**Docker 代劳（若可用）→ 平台代劳（LAN）/ 失败报错（WAN，文案提示可装工具或用 Docker）**。
- 诚实代价：Docker 代劳恰好在"无外网 LAN 主机"上失效，此时真正兜底的是平台代劳（LAN 本就支持）而非 Docker——接受此限制，不引入镜像投喂的复杂度。

### 决策 7：补丁报错文案顺带修复

`PatchInstallExecutor` 中"主机不能自治"的报错改为写明具体缺失的工具类别（下载/解压、缺哪些命令），并提示可到主机详情页"环境工具"面板安装，形成体验闭环。

## 后果（Consequences）

- `probe_capabilities.sh` 新增探测项后，旧平台发的新脚本对新主机向后兼容（探测脚本每次 SFTP 推送，无版本残留问题）。
- 白名单扩展（如加 `zstd`）只需改平台代码与包名映射，无数据迁移。
- `SudoAwareSshRunner` 上提是一次纯移动重构，steam302 行为不变，但需回归 Steam302 安装/hosts 同步路径。
- 前端在主机详情页新增"环境工具"面板（工具状态列表 + 安装按钮），属主应用前端改动。
