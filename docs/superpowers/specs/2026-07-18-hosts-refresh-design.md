# 宿主机 hosts 刷新功能设计

**日期**: 2026-07-18
**状态**: Draft
**作者**: GamePlatform Team

---

## 1. 背景与问题

### 1.1 问题场景

用户在 Windows + WSL2 环境部署游戏服务器时，宿主机通过 80 端口运行反向代理（nginx/caddy），将 `raw.githubusercontent.com`、`github.com` 等域名反向代理到 GitHub，解决容器内访问 GitHub 下载 LinuxGSM `serverlist.csv` 失败的问题。

用户在宿主机 `/etc/hosts` 中将相关域名改为 `127.0.0.1`，让宿主机 DNS 解析到本地反向代理。

**问题**：LinuxGSM Docker 容器读取宿主机 DNS 解析（实测容器内 `github.com` 解析为 `127.0.0.1`），但容器在 bridge 网络模式下 `127.0.0.1` 指向容器自身（容器没有反向代理），导致 `curl` 请求失败。

### 1.2 根因分析

| 网络模式 | 容器内 127.0.0.1 | 容器读宿主机 DNS |
|---------|-----------------|----------------|
| host    | 宿主机（反向代理可用） | ✓ |
| bridge  | 容器自身（无反向代理）  | ✓（仍读宿主机 DNS） |

bridge 模式下，容器解析域名得到 `127.0.0.1`，但访问的是容器自身的 80 端口（无服务），所以反向代理失效。

### 1.3 解决方向

把宿主机 `/etc/hosts` 中 `127.0.0.1 域名` 改为 `宿主机 LAN IP 域名`，这样：
- 宿主机解析域名 → 宿主机 LAN IP → 访问自身反向代理（同一台机器）
- 容器解析域名（读宿主机 DNS）→ 宿主机 LAN IP → 访问宿主机反向代理

两种模式都能正常工作。

---

## 2. 设计目标

1. **独立功能**：作为主机管理的独立功能，与部署流程解耦
2. **手动触发**：用户在主机管理页面主动触发，不在部署流程中自动执行
3. **权限安全**：正确处理 sudo 权限，支持免密 sudo 和密码 sudo 两种场景
4. **可预览**：执行前展示将修改的域名清单，用户确认后才写入
5. **可回滚**：自动备份原 /etc/hosts，支持手动恢复
6. **幂等**：重复执行不产生副作用

---

## 3. 功能定位

**位置**：主机管理列表 → 操作列 → "刷新 hosts" 按钮

**为什么独立而非部署时自动执行**：
- /etc/hosts 是宿主机系统文件，修改影响范围大，应在用户主动操作下进行
- 一次刷新后多次部署都生效（hosts 修改是持久的），无需每次部署都改
- 权限处理（sudo）由用户在主机管理场景明确授权，不与部署的自动化混淆
- 部署流程保持现有逻辑不变（不动 `LinuxGsmDockerAdapter.deploy` / `DockerComposeAdapter.deploy`）

---

## 4. 整体架构

```
前端：
  主机管理列表 → 操作列新增「刷新 hosts」按钮
    ↓ 弹窗显示：检测到的 127.0.0.1 域名 + 目标 IP（宿主机 LAN IP）
    ↓ 如需 sudo 密码，弹窗输入（一次性，不存）
  确认 → 调用后端 API

后端：
  GET  /api/hosts/{id}/hosts-preview   预检
  POST /api/hosts/{id}/hosts-refresh   执行刷新
    ↓ 检测 sudo 权限（sudo -n true）
    ↓ 有免密 sudo → 直接刷新
    ↓ 无免密 sudo → 用传入的 sudoPassword 执行 sudo -S
    ↓ 返回：修改的域名列表 + 备份路径
```

---

## 5. 后端设计

### 5.1 新增服务类 `HostsFileRefresher`

**位置**: `backend/core/src/main/java/com/gameplatform/service/HostsFileRefresher.java`

**接口**：

```java
@Service
public class HostsFileRefresher {
    
    /**
     * 预检：读取 /etc/hosts 并识别待修改域名，不写入。
     * 用于前端弹窗展示"将修改哪些域名"。
     */
    public HostsRefreshPreview previewRefresh(Host host);
    
    /**
     * 执行刷新：把 127.0.0.1 域名改为宿主机 IP。
     * 
     * @param sudoPassword 可选，null 表示尝试免密 sudo；非空表示用 sudo -S 传密码
     */
    public HostsRefreshResult refreshHosts(Host host, String sudoPassword);
}
```

### 5.2 数据结构

```java
@Data
public class HostsRefreshPreview {
    private String hostLanIp;              // 宿主机 LAN IP
    private String hostname;               // 主机名（排除用）
    private List<String> domainsToRefresh; // 待改域名清单
    private boolean sudoAvailable;         // 免密 sudo 是否可用
    private boolean needsSudoPassword;     // 是否需要 sudo 密码
}

@Data
public class HostsRefreshResult {
    private boolean success;
    private String errorMessage;
    private String backupPath;             // /etc/hosts.bak.{timestamp}
    private List<String> refreshedDomains; // 实际修改的域名
    private String hostLanIp;
}
```

### 5.3 核心执行流程

```
步骤 1：读取 /etc/hosts（普通权限）
  SSH: cat /etc/hosts

步骤 2：读取 hostname（普通权限）
  SSH: hostname

步骤 3：在 Java 端生成新内容
  遍历每行，按过滤规则处理 127.0.0.1/::1 行的域名
  如果无待改域名 → 直接返回 success=true, refreshedDomains=[]

步骤 4：上传新内容到临时文件（普通权限）
  SFTP: 上传到 /tmp/hosts-refresh-{timestamp}.tmp

步骤 5：执行 sudo 命令
  - 若 sudoPassword 为空（StringUtils.isBlank）：
      SSH: sudo -n true 2>/dev/null
      - exit code 0 → 免密 sudo 可用，用 sudo（无密码）
      - exit code 非 0 → 返回 needsSudoPassword=true, success=false
  - 若 sudoPassword 非空 → 用 sudo -S 传密码

  说明：
  - sudoPassword 的空值判断统一用 StringUtils.isBlank，处理 null 和空字符串两种情况。
  - refreshHosts 内部自行检测 sudo 可用性，不依赖 previewRefresh 的预检结果。
    预检结果仅用于前端 UI 展示，避免用户输入密码后才发现免密 sudo 可用。
  - previewRefresh 中也执行 sudo -n true 检测，结果用于前端 UI（是否显示密码框）。

步骤 6：执行备份 + 覆盖
  SSH: sudo cp /etc/hosts /etc/hosts.bak.{timestamp}
  SSH: sudo cp /tmp/hosts-refresh-{timestamp}.tmp /etc/hosts

步骤 7：清理临时文件
  SSH: rm -f /tmp/hosts-refresh-{timestamp}.tmp

步骤 8：返回结果
  success=true, backupPath, refreshedDomains
```

**预检逻辑（previewRefresh 中的 sudo 检测）**：

```
SSH: sudo -n true 2>/dev/null
  - exit code 0 → sudoAvailable=true, needsSudoPassword=false
  - exit code 非 0 → sudoAvailable=false, needsSudoPassword=true
```

前端根据预检结果决定是否显示密码框，refreshHosts 不再重复检测 sudo（避免重复 SSH 调用）。

### 5.4 sudo 命令构造

**免密 sudo**：
```bash
sudo cp /tmp/xxx.tmp /etc/hosts
```

**有密码 sudo**（通过 stdin 传密码，不在命令行出现）：
```bash
echo '{密码}' | sudo -S cp /tmp/xxx.tmp /etc/hosts
```

**为什么用 `sudo cp` 而非 `sudo tee`**：
- `sudo tee` 会把 stdin 通过管道传给 sudo，SSH exec 通道的引号转义复杂
- `sudo cp /tmp/xxx /etc/hosts` 命令简单，引号处理风险低
- 临时文件通过 SFTP 上传，内容完整性由 SFTP 保证

### 5.5 过滤规则

**不修改的域名**（系统别名）：

```java
private static final Set<String> SYSTEM_ALIASES = Set.of(
    "localhost", "localhost.localdomain",
    "ip6-localhost", "ip6-loopback",
    "localhost4", "localhost4.localdomain4",
    "localhost6", "localhost6.localdomain6"
);
```

**完整排除清单**：
- 系统别名（上述常量）
- `hostname` 命令输出（主机名自身）
- 已经是 `hostLanIp` 的条目（幂等性）

### 5.6 宿主机 IP 来源

`host.getIpAddress()`（用户在主机管理中配置的 SSH IP，即宿主机 LAN IP）。

不做自动网卡检测，避免 docker0/br-xxx 等虚拟网卡干扰。

### 5.7 幂等性

- 所有 127.0.0.1 域名已是 `hostLanIp` → 跳过写入，返回 `refreshedDomains=[]`
- 重复刷新不产生副作用
- 备份文件按时间戳命名，不覆盖

### 5.8 密码安全

- 密码仅在内存中传递，不写日志（日志中用 `****` 替代）
- 方法执行完毕后，密码字符串由 JVM GC 回收
- 后端不存储 sudo 密码（与 SSH 密码不同，sudo 密码是临时授权）

### 5.9 API 端点

在 `HostController.java` 新增：

```
GET  /api/hosts/{id}/hosts-preview
  → 预检：返回待修改域名清单 + sudo 状态
  → 响应：HostsRefreshPreview

POST /api/hosts/{id}/hosts-refresh
  → 请求体：{ "sudoPassword": "可选，免密 sudo 时为 null" }
  → 执行刷新
  → 响应：HostsRefreshResult
```

---

## 6. 前端设计

### 6.1 API 封装

在 `frontend/src/api/host.js` 新增：

```javascript
export function previewHostsRefresh(hostId) {
  return request.get(`/api/hosts/${hostId}/hosts-preview`);
}

export function refreshHosts(hostId, sudoPassword) {
  return request.post(`/api/hosts/${hostId}/hosts-refresh`, { sudoPassword });
}
```

### 6.2 UI 流程

主机列表操作列新增「刷新 hosts」按钮，点击后弹窗：

```
┌─────────────────────────────────────────────┐
│ 刷新宿主机 hosts（反向代理）              │
├─────────────────────────────────────────────┤
│ 宿主机 IP：192.168.111.253                  │
│ 主机名：haki-pc                             │
│                                             │
│ 检测到以下域名指向 127.0.0.1，将被改为      │
│ 192.168.111.253：                           │
│  • raw.githubusercontent.com                │
│  • github.com                               │
│                                             │
│ sudo 权限：[✓ 免密 sudo 可用 / ⚠ 需要密码]  │
│                                             │
│ sudo 密码（仅需密码时显示）：               │
│ [___________________________]               │
│                                             │
│ ⚠ 将备份原 /etc/hosts 到 /etc/hosts.bak.xxx │
│                                             │
│           [取消]  [确认刷新]                │
└─────────────────────────────────────────────┘
```

### 6.3 交互流程

```
1. 点击「刷新 hosts」→ 调用 previewHostsRefresh
2. 弹窗加载中 → 显示预检结果
3. 用户确认 → 调用 refreshHosts
   - 免密 sudo 可用 → 不显示密码框，直接提交
   - 需要密码 → 显示密码框，用户输入后提交
4. 提交后 loading → 成功显示"已修改 N 个域名，备份路径 xxx"
                  → 失败显示错误信息
```

### 6.4 错误场景展示

| 场景 | 弹窗提示 |
|------|---------|
| 无 127.0.0.1 域名待改 | "无需刷新，hosts 文件已是目标状态" |
| 需要密码但未输入 | "请输入 sudo 密码" |
| sudo 密码错误 | "sudo 密码错误，请重试" |
| SSH 连接失败 | "SSH 连接失败：xxx" |
| 备份失败 | "备份 /etc/hosts 失败，已中止刷新" |
| 覆盖失败 | "写入 /etc/hosts 失败：xxx（临时文件已保留：/tmp/xxx）" |

---

## 7. 测试设计

### 7.1 后端测试

**位置**: `backend/core/src/test/java/com/gameplatform/service/HostsFileRefresherTest.java`

测试用例：
1. **域名解析**：127.0.0.1 行 → 正确提取域名
2. **过滤规则**：系统别名（localhost 等）排除
3. **过滤规则**：hostname 排除
4. **过滤规则**：已是 hostLanIp 的条目排除（幂等）
5. **无待改域名**：返回空列表，不写入
6. **免密 sudo 可用**：构造正确的 `sudo cp` 命令
7. **需密码 sudo**：构造 `echo 'pwd' | sudo -S cp` 命令
8. **备份成功**：正确生成 `/etc/hosts.bak.{timestamp}`
9. **临时文件清理**：执行后 rm 临时文件
10. **SSH 异常**：读取 /etc/hosts 失败时不阻断，返回错误信息

mock `SshUtil` 测试命令构造，不实际 SSH。

### 7.2 前端测试

**位置**: `frontend/src/tests/api/host.test.js`

测试用例：
1. `previewHostsRefresh(hostId)` 调用正确的 URL
2. `refreshHosts(hostId, sudoPassword)` 调用 POST 方法
3. sudoPassword 为 null 时正确处理

---

## 8. 安全考虑

### 8.1 sudo 密码安全

- 密码仅在内存中传递，不持久化
- 日志中密码字段用 `****` 替代
- 通过 `sudo -S` 从 stdin 传密码，不在命令行明文出现（避免 `ps` 看到）

### 8.2 /etc/hosts 修改风险

- 修改前强制备份，备份失败则中止
- 备份路径在响应中返回，便于用户手动恢复
- 仅修改非系统别名域名，保留 `localhost` 等关键条目

### 8.3 SSH 权限

- 复用现有 SSH 连接池（`SshUtil`）
- 不需要额外提升 SSH 账号权限，sudo 由 `SshUtil.executeCommand` 在远程执行

---

## 9. 实施范围

### 9.1 后端新增文件

- `backend/core/src/main/java/com/gameplatform/service/HostsFileRefresher.java`
- `backend/core/src/main/java/com/gameplatform/vo/HostsRefreshPreview.java`
- `backend/core/src/main/java/com/gameplatform/vo/HostsRefreshResult.java`
- `backend/core/src/test/java/com/gameplatform/service/HostsFileRefresherTest.java`

### 9.2 后端修改文件

- `backend/core/src/main/java/com/gameplatform/controller/HostController.java`（新增 2 个端点）

### 9.3 前端修改文件

- `frontend/src/api/host.js`（新增 2 个 API 函数）
- `frontend/src/views/host/index.vue`（新增「刷新 hosts」按钮和弹窗）
- `frontend/src/tests/api/host.test.js`（新增测试用例）

### 9.4 不修改的部分

- `LinuxGsmDockerAdapter.java`（部署流程不变）
- `DockerComposeAdapter.java`（部署流程不变）
- yml 配置文件（不需要新增字段）
- 数据库 schema（不涉及持久化）

---

## 10. 验收标准

1. 主机管理列表点击「刷新 hosts」→ 弹窗显示预检结果
2. 免密 sudo 可用时，确认后直接刷新成功，显示修改的域名清单和备份路径
3. 需要密码时，弹窗显示密码框，输入正确密码后刷新成功
4. 密码错误时显示"sudo 密码错误"
5. 无 127.0.0.1 域名时显示"无需刷新"
6. 重复刷新不产生副作用（幂等）
7. 后端测试全部通过
8. 前端测试全部通过

---

## 11. 后续可能的扩展

- 批量刷新多台主机的 hosts
- 支持自定义目标 IP（不只是 host.ipAddress）
- 支持回滚到指定备份
- 集成到部署流程（作为可选步骤）

当前版本不实现这些扩展，保持简单。

---

*最后更新: 2026-07-18*
