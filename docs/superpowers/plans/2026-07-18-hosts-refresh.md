# 宿主机 hosts 刷新功能实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在主机管理新增独立功能「刷新 hosts」，将宿主机 `/etc/hosts` 中指向 `127.0.0.1` 的非系统域名改为宿主机 LAN IP，让 bridge 网络模式下的 Docker 容器能通过宿主机反向代理访问 GitHub 等域名。

**Architecture:** 后端新增 `HostsFileRefresher` 服务，封装预检（`previewRefresh`）和执行（`refreshHosts`）两个方法，通过 SSH 读取 `/etc/hosts`、SFTP 上传临时文件、`sudo cp` 覆盖原文件。在 `HostController` 暴露 2 个端点：`GET /hosts/{id}/hosts-preview` 和 `POST /hosts/{id}/hosts-refresh`。前端在主机列表操作列新增「刷新 hosts」按钮，弹窗展示预检结果，用户确认后执行刷新。流程与部署解耦，权限（sudo 密码）由用户在主机管理场景一次性输入。

**Tech Stack:** Java 17 + Spring Boot 3.2.5 + MyBatis-Plus + Apache MINA SSHD + Lombok；Vue 3 + Element Plus + Vitest。

**参考设计文档:** `docs/superpowers/specs/2026-07-18-hosts-refresh-design.md`

---

## 文件结构

### 后端新建文件
- `backend/core/src/main/java/com/gameplatform/vo/HostsRefreshPreview.java` — 预检响应 VO
- `backend/core/src/main/java/com/gameplatform/vo/HostsRefreshResult.java` — 执行结果 VO
- `backend/core/src/main/java/com/gameplatform/service/HostsFileRefresher.java` — 核心服务类（预检 + 刷新 + sudo 处理 + 过滤规则）
- `backend/core/src/test/java/com/gameplatform/service/HostsFileRefresherTest.java` — 单元测试

### 后端修改文件
- `backend/core/src/main/java/com/gameplatform/controller/HostController.java` — 新增 2 个端点 + 请求体内部静态类

### 前端修改文件
- `frontend/src/api/host.js` — 新增 `previewHostsRefresh`、`refreshHosts` 两个 API 函数
- `frontend/src/views/host/index.vue` — 新增「刷新 hosts」按钮、弹窗、交互逻辑
- `frontend/src/tests/api/host.test.js` — 新增两个 API 函数的测试用例

### 不修改的部分
- `LinuxGsmDockerAdapter.java`、`DockerComposeAdapter.java`（部署流程不变）
- 数据库 schema（不涉及持久化）
- yml 配置文件

---

## Task 1: 后端 VO 类（API 契约）

**Files:**
- Create: `backend/core/src/main/java/com/gameplatform/vo/HostsRefreshPreview.java`
- Create: `backend/core/src/main/java/com/gameplatform/vo/HostsRefreshResult.java`

- [ ] **Step 1: 创建 HostsRefreshPreview.java**

文件路径：`backend/core/src/main/java/com/gameplatform/vo/HostsRefreshPreview.java`

```java
package com.gameplatform.vo;

import lombok.Data;

import java.util.List;

/**
 * 宿主机 hosts 刷新预检结果
 *
 * <p>用于前端弹窗展示「将修改哪些域名」+ sudo 状态。</p>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class HostsRefreshPreview {

    /**
     * 宿主机 LAN IP（目标 IP，来源 host.ipAddress）
     */
    private String hostLanIp;

    /**
     * 主机名（用于排除 hostname 自身的条目）
     */
    private String hostname;

    /**
     * 待改域名清单（当前指向 127.0.0.1 且非系统别名、非 hostname、非已是 hostLanIp 的域名）
     */
    private List<String> domainsToRefresh;

    /**
     * 免密 sudo 是否可用（sudo -n true 检测结果）
     */
    private boolean sudoAvailable;

    /**
     * 是否需要 sudo 密码（!sudoAvailable 时为 true）
     */
    private boolean needsSudoPassword;
}
```

- [ ] **Step 2: 创建 HostsRefreshResult.java**

文件路径：`backend/core/src/main/java/com/gameplatform/vo/HostsRefreshResult.java`

```java
package com.gameplatform.vo;

import lombok.Data;

import java.util.List;

/**
 * 宿主机 hosts 刷新执行结果
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class HostsRefreshResult {

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 错误信息（失败时填）
     */
    private String errorMessage;

    /**
     * 备份路径（如 /etc/hosts.bak.20260718120000）
     */
    private String backupPath;

    /**
     * 实际修改的域名清单（无待改域名时为空列表）
     */
    private List<String> refreshedDomains;

    /**
     * 宿主机 LAN IP
     */
    private String hostLanIp;
}
```

- [ ] **Step 3: 编译验证**

Run:
```bash
cd backend && mvn -pl core -am compile -DskipTests -q
```

Expected: BUILD SUCCESS（无错误）

- [ ] **Step 4: Commit**

```bash
cd backend
git add core/src/main/java/com/gameplatform/vo/HostsRefreshPreview.java core/src/main/java/com/gameplatform/vo/HostsRefreshResult.java
git commit -m "feat(hosts-refresh): add preview and result VO classes"
```

---

## Task 2: HostsFileRefresher - 域名解析与过滤逻辑（TDD）

**Files:**
- Create: `backend/core/src/test/java/com/gameplatform/service/HostsFileRefresherTest.java`
- Create: `backend/core/src/main/java/com/gameplatform/service/HostsFileRefresher.java`

本任务实现：解析 `/etc/hosts` 内容、应用过滤规则、`previewRefresh` 完整实现（含 sudo 检测，用于前端 UI 展示）。`refreshHosts` 留 stub，下个任务（Task 3）实现完整流程。

- [ ] **Step 1: 写失败测试 - 域名提取与过滤规则**

文件路径：`backend/core/src/test/java/com/gameplatform/service/HostsFileRefresherTest.java`

```java
package com.gameplatform.service;

import com.gameplatform.entity.Host;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.util.AesUtil;
import com.gameplatform.util.SshUtil;
import com.gameplatform.vo.HostsRefreshPreview;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * HostsFileRefresher 单元测试
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("宿主机 hosts 刷新服务测试")
class HostsFileRefresherTest {

    @Mock
    private HostMapper hostMapper;

    @Mock
    private SshUtil sshUtil;

    @Mock
    private AesUtil aesUtil;

    @InjectMocks
    private HostsFileRefresher refresher;

    private Host testHost;

    @BeforeEach
    void setUp() {
        testHost = new Host();
        testHost.setId(1L);
        testHost.setHostName("haki-pc");
        testHost.setIpAddress("192.168.111.253");
        testHost.setSshPort(22);
        testHost.setSshUser("haki");
        testHost.setSshPassword("enc-pwd");
        testHost.setSshPrivateKey("enc-key");

        // 默认：解密返回非空字符串
        when(aesUtil.decrypt("enc-pwd")).thenReturn("decrypted-pwd");
        when(aesUtil.decrypt("enc-key")).thenReturn("decrypted-key");
        when(hostMapper.selectById(1L)).thenReturn(testHost);
    }

    @Test
    @DisplayName("应正确提取 127.0.0.1 行的非系统别名域名")
    void shouldExtractDomainsFromLoopbackLine() {
        // Given: hosts 文件包含 127.0.0.1 行，混合系统别名和真实域名
        String hostsContent = "127.0.0.1 localhost\n" +
                "127.0.0.1 raw.githubusercontent.com github.com\n" +
                "::1 localhost ip6-localhost\n";

        when(sshUtil.executeCommand(
                eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("cat /etc/hosts"), anyLong()))
                .thenReturn(mockResult(hostsContent, true));

        when(sshUtil.executeCommand(
                eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("hostname"), anyLong()))
                .thenReturn(mockResult("haki-pc\n", true));

        when(sshUtil.executeCommand(
                eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("sudo -n true 2>/dev/null"), anyLong()))
                .thenReturn(mockResult("", false));

        // When
        HostsRefreshPreview preview = refresher.previewRefresh(1L);

        // Then: 系统别名 localhost 已过滤，仅保留真实域名
        assertNotNull(preview);
        assertEquals("192.168.111.253", preview.getHostLanIp());
        assertEquals("haki-pc", preview.getHostname());
        assertTrue(preview.getDomainsToRefresh().contains("raw.githubusercontent.com"));
        assertTrue(preview.getDomainsToRefresh().contains("github.com"));
        assertFalse(preview.getDomainsToRefresh().contains("localhost"));
    }

    @Test
    @DisplayName("应排除 hostname 自身（避免改主机名条目）")
    void shouldExcludeHostnameItself() {
        String hostsContent = "127.0.0.1 localhost haki-pc\n" +
                "127.0.0.1 github.com\n";

        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("cat /etc/hosts"), anyLong()))
                .thenReturn(mockResult(hostsContent, true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("hostname"), anyLong()))
                .thenReturn(mockResult("haki-pc\n", true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("sudo -n true 2>/dev/null"), anyLong()))
                .thenReturn(mockResult("", false));

        HostsRefreshPreview preview = refresher.previewRefresh(1L);

        assertTrue(preview.getDomainsToRefresh().contains("github.com"));
        assertFalse(preview.getDomainsToRefresh().contains("haki-pc"),
                "hostname 自身应被排除");
    }

    @Test
    @DisplayName("已是 hostLanIp 的条目应排除（幂等性）")
    void shouldExcludeAlreadyTargetIpEntries() {
        String hostsContent = "127.0.0.1 localhost\n" +
                "192.168.111.253 github.com\n" +  // 已是目标 IP
                "127.0.0.1 raw.githubusercontent.com\n";

        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("cat /etc/hosts"), anyLong()))
                .thenReturn(mockResult(hostsContent, true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("hostname"), anyLong()))
                .thenReturn(mockResult("haki-pc\n", true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("sudo -n true 2>/dev/null"), anyLong()))
                .thenReturn(mockResult("", false));

        HostsRefreshPreview preview = refresher.previewRefresh(1L);

        // github.com 已是目标 IP，不应出现在待改列表
        assertFalse(preview.getDomainsToRefresh().contains("github.com"));
        assertTrue(preview.getDomainsToRefresh().contains("raw.githubusercontent.com"));
    }

    @Test
    @DisplayName("无待改域名时应返回空列表")
    void shouldReturnEmptyListWhenNoDomainToRefresh() {
        String hostsContent = "127.0.0.1 localhost\n" +
                "192.168.111.253 github.com raw.githubusercontent.com\n";

        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("cat /etc/hosts"), anyLong()))
                .thenReturn(mockResult(hostsContent, true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("hostname"), anyLong()))
                .thenReturn(mockResult("haki-pc\n", true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("sudo -n true 2>/dev/null"), anyLong()))
                .thenReturn(mockResult("", false));

        HostsRefreshPreview preview = refresher.previewRefresh(1L);

        assertNotNull(preview.getDomainsToRefresh());
        assertTrue(preview.getDomainsToRefresh().isEmpty(),
                "无待改域名时应返回空列表");
    }

    @Test
    @DisplayName("读取 /etc/hosts 失败时应抛出业务异常")
    void shouldThrowWhenReadHostsFails() {
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("cat /etc/hosts"), anyLong()))
                .thenReturn(mockResult("", false));

        assertThrows(RuntimeException.class, () -> refresher.previewRefresh(1L));
    }

    /**
     * 构造 mock CommandResult
     */
    private SshUtil.CommandResult mockResult(String output, boolean success) {
        SshUtil.CommandResult r = new SshUtil.CommandResult();
        r.setOutput(output);
        r.setSuccess(success);
        r.setExitCode(success ? 0 : 1);
        return r;
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run:
```bash
cd backend && mvn -pl core test -Dtest=HostsFileRefresherTest -q
```

Expected: 编译失败（`HostsFileRefresher` 类不存在）

- [ ] **Step 3: 创建 HostsFileRefresher.java（仅 previewRefresh，refreshHosts 留 stub）**

文件路径：`backend/core/src/main/java/com/gameplatform/service/HostsFileRefresher.java`

```java
package com.gameplatform.service;

import cn.hutool.core.util.StrUtil;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.util.AesUtil;
import com.gameplatform.util.SshUtil;
import com.gameplatform.vo.HostsRefreshPreview;
import com.gameplatform.vo.HostsRefreshResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 宿主机 /etc/hosts 刷新服务
 *
 * <p>将 /etc/hosts 中指向 127.0.0.1 的非系统别名域名改为宿主机 LAN IP，
 * 让 bridge 网络模式下的 Docker 容器可通过宿主机反向代理访问对应域名。</p>
 *
 * <p>典型场景：LinuxGSM 容器需访问 GitHub 下载 serverlist.csv，但容器读宿主机 DNS
 * 时把 github.com 解析为 127.0.0.1（bridge 模式下指向容器自身，无反向代理）。</p>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HostsFileRefresher {

    private final HostMapper hostMapper;
    private final SshUtil sshUtil;
    private final AesUtil aesUtil;

    /**
     * 系统别名集合 - 这些域名不会被改为 LAN IP
     */
    private static final Set<String> SYSTEM_ALIASES = Set.of(
            "localhost", "localhost.localdomain",
            "ip6-localhost", "ip6-loopback",
            "localhost4", "localhost4.localdomain4",
            "localhost6", "localhost6.localdomain6"
    );

    /**
     * 预检：读取 /etc/hosts 并识别待修改域名，不写入。
     *
     * <p>用于前端弹窗展示「将修改哪些域名」+ sudo 状态。</p>
     *
     * @param hostId 主机 ID
     * @return 预检结果（待改域名清单 + sudo 状态）
     */
    public HostsRefreshPreview previewRefresh(Long hostId) {
        Host host = loadHost(hostId);
        SshCredentials creds = decryptCredentials(host);

        // 1. 读取 /etc/hosts
        String hostsContent = execCommand(host, creds, "cat /etc/hosts");
        if (StrUtil.isBlank(hostsContent)) {
            throw new RuntimeException("读取 /etc/hosts 失败：内容为空");
        }

        // 2. 读取 hostname
        String hostname = execCommand(host, creds, "hostname").trim();

        // 3. 解析并过滤域名
        String hostLanIp = host.getIpAddress();
        List<String> domainsToRefresh = extractDomainsToRefresh(hostsContent, hostname, hostLanIp);

        // 4. 检测免密 sudo
        SshUtil.CommandResult sudoCheck = sshUtil.executeCommand(
                host.getIpAddress(), host.getSshPort(), host.getSshUser(),
                creds.privateKey, creds.password,
                "sudo -n true 2>/dev/null", 5000L);
        boolean sudoAvailable = sudoCheck.isSuccess();

        HostsRefreshPreview preview = new HostsRefreshPreview();
        preview.setHostLanIp(hostLanIp);
        preview.setHostname(hostname);
        preview.setDomainsToRefresh(domainsToRefresh);
        preview.setSudoAvailable(sudoAvailable);
        preview.setNeedsSudoPassword(!sudoAvailable);
        return preview;
    }

    /**
     * 执行刷新：把 127.0.0.1 域名改为宿主机 IP。
     *
     * <p>本任务先返回 stub，Task 3 实现完整流程。</p>
     *
     * @param hostId       主机 ID
     * @param sudoPassword 可选，null/空表示尝试免密 sudo；非空表示用 sudo -S 传密码
     */
    public HostsRefreshResult refreshHosts(Long hostId, String sudoPassword) {
        throw new UnsupportedOperationException("refreshHosts 尚未实现（Task 3 完成）");
    }

    // ========== 内部方法 ==========

    /**
     * 从 /etc/hosts 内容提取待修改域名清单。
     *
     * 规则：
     * 1. 仅处理 127.0.0.1 和 ::1 行
     * 2. 排除系统别名（localhost 等）
     * 3. 排除 hostname 自身
     * 4. 排除已指向 hostLanIp 的域名（幂等性）
     *
     * @param hostsContent /etc/hosts 文件内容
     * @param hostname     主机名
     * @param hostLanIp    宿主机 LAN IP
     * @return 待改域名清单（去重，保留顺序）
     */
    List<String> extractDomainsToRefresh(String hostsContent, String hostname, String hostLanIp) {
        List<String> result = new ArrayList<>();
        Set<String> seen = new java.util.LinkedHashSet<>();

        String[] lines = hostsContent.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            // 拆分: IP 域名1 域名2 ...
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 2) {
                continue;
            }

            String ip = parts[0];
            // 仅处理回环地址行
            if (!"127.0.0.1".equals(ip) && !"::1".equals(ip)) {
                continue;
            }

            for (int i = 1; i < parts.length; i++) {
                String domain = parts[i].trim().toLowerCase();
                if (domain.isEmpty()) continue;
                if (SYSTEM_ALIASES.contains(domain)) continue;
                if (domain.equalsIgnoreCase(hostname)) continue;
                if (seen.contains(domain)) continue;
                seen.add(domain);
                result.add(domain);
            }
        }

        // 排除已指向 hostLanIp 的域名（需要重新扫描文件，因为它们在 hostLanIp 行）
        Set<String> alreadyOnLanIp = new java.util.HashSet<>();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 2) continue;
            if (hostLanIp.equals(parts[0])) {
                for (int i = 1; i < parts.length; i++) {
                    alreadyOnLanIp.add(parts[i].trim().toLowerCase());
                }
            }
        }

        result.removeIf(alreadyOnLanIp::contains);
        return result;
    }

    /**
     * 加载主机实体
     */
    private Host loadHost(Long hostId) {
        Host host = hostMapper.selectById(hostId);
        if (host == null) {
            throw new RuntimeException("主机不存在: id=" + hostId);
        }
        return host;
    }

    /**
     * 解密 SSH 凭据
     */
    private SshCredentials decryptCredentials(Host host) {
        String privateKey = null;
        String password = null;
        if (StrUtil.isNotBlank(host.getSshPrivateKey())) {
            privateKey = aesUtil.decrypt(host.getSshPrivateKey());
        }
        if (StrUtil.isNotBlank(host.getSshPassword())) {
            password = aesUtil.decrypt(host.getSshPassword());
        }
        return new SshCredentials(privateKey, password);
    }

    /**
     * 执行 SSH 命令并校验成功
     */
    private String execCommand(Host host, SshCredentials creds, String command) {
        SshUtil.CommandResult result = sshUtil.executeCommand(
                host.getIpAddress(), host.getSshPort(), host.getSshUser(),
                creds.privateKey, creds.password, command);
        if (!result.isSuccess()) {
            throw new RuntimeException("SSH 命令执行失败: " + command
                    + "，错误: " + result.getError());
        }
        return result.getOutput() != null ? result.getOutput() : "";
    }

    /**
     * SSH 凭据内部载体
     */
    private record SshCredentials(String privateKey, String password) {
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run:
```bash
cd backend && mvn -pl core test -Dtest=HostsFileRefresherTest -q
```

Expected: 5 个测试全部 PASS

- [ ] **Step 5: Commit**

```bash
cd backend
git add core/src/main/java/com/gameplatform/service/HostsFileRefresher.java core/src/test/java/com/gameplatform/service/HostsFileRefresherTest.java
git commit -m "feat(hosts-refresh): add HostsFileRefresher with domain extraction and filter rules"
```

---

## Task 3: HostsFileRefresher - refreshHosts 完整流程（TDD）

**Files:**
- Modify: `backend/core/src/test/java/com/gameplatform/service/HostsFileRefresherTest.java`（新增测试用例）
- Modify: `backend/core/src/main/java/com/gameplatform/service/HostsFileRefresher.java`（实现 refreshHosts）

本任务实现完整的 `refreshHosts` 方法：生成新内容 → SFTP 上传临时文件 → sudo 备份 + 覆盖 → 清理临时文件。包含免密 sudo 和密码 sudo 两种场景。

- [ ] **Step 1: 在测试类中新增 refreshHosts 测试用例**

在 `HostsFileRefresherTest.java` 类末尾的 `}` 之前，新增以下测试方法：

```java
    @Test
    @DisplayName("免密 sudo 可用时，应使用 sudo cp 完成刷新")
    void shouldRefreshWithPasswordlessSudo() {
        // Given: hosts 文件包含 1 个待改域名
        String hostsContent = "127.0.0.1 localhost\n" +
                "127.0.0.1 github.com\n";
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("cat /etc/hosts"), anyLong()))
                .thenReturn(mockResult(hostsContent, true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("hostname"), anyLong()))
                .thenReturn(mockResult("haki-pc\n", true));

        // 免密 sudo 可用
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("sudo -n true 2>/dev/null"), anyLong()))
                .thenReturn(mockResult("", true));

        // SFTP 上传成功（localPath 是 Java 临时文件路径，remotePath 是 /tmp/hosts-refresh-xxx.tmp）
        when(sshUtil.uploadFile(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                contains("hosts-refresh-"), startsWith("/tmp/hosts-refresh-")))
                .thenReturn(true);

        // sudo cp 备份 + 覆盖 都成功
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                matches("sudo cp /etc/hosts /etc/hosts\\.bak\\..+"), anyLong()))
                .thenReturn(mockResult("", true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                matches("sudo cp /tmp/hosts-refresh-.+\\.tmp /etc/hosts"), anyLong()))
                .thenReturn(mockResult("", true));

        // 临时文件清理
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                matches("rm -f /tmp/hosts-refresh-.+\\.tmp"), anyLong()))
                .thenReturn(mockResult("", true));

        // When: sudoPassword 为 null（免密 sudo）
        com.gameplatform.vo.HostsRefreshResult result = refresher.refreshHosts(1L, null);

        // Then
        assertTrue(result.isSuccess());
        assertEquals("192.168.111.253", result.getHostLanIp());
        assertTrue(result.getRefreshedDomains().contains("github.com"));
        assertNotNull(result.getBackupPath());
        assertTrue(result.getBackupPath().startsWith("/etc/hosts.bak."));
    }

    @Test
    @DisplayName("免密 sudo 不可用且未提供密码时，应返回失败并提示需要密码")
    void shouldFailWhenSudoPasswordMissing() {
        String hostsContent = "127.0.0.1 localhost\n127.0.0.1 github.com\n";
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("cat /etc/hosts"), anyLong()))
                .thenReturn(mockResult(hostsContent, true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("hostname"), anyLong()))
                .thenReturn(mockResult("haki-pc\n", true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("sudo -n true 2>/dev/null"), anyLong()))
                .thenReturn(mockResult("", false));

        com.gameplatform.vo.HostsRefreshResult result = refresher.refreshHosts(1L, null);

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
        assertTrue(result.getErrorMessage().toLowerCase().contains("sudo")
                || result.getErrorMessage().toLowerCase().contains("密码"),
                "错误信息应提示 sudo 密码相关");
    }

    @Test
    @DisplayName("提供 sudo 密码时，应使用 echo 'pwd' | sudo -S cp 命令")
    void shouldUseSudoWithPasswordWhenProvided() {
        String hostsContent = "127.0.0.1 localhost\n127.0.0.1 github.com\n";
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("cat /etc/hosts"), anyLong()))
                .thenReturn(mockResult(hostsContent, true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("hostname"), anyLong()))
                .thenReturn(mockResult("haki-pc\n", true));

        // 免密 sudo 不可用
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("sudo -n true 2>/dev/null"), anyLong()))
                .thenReturn(mockResult("", false));

        when(sshUtil.uploadFile(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                contains("hosts-refresh-"), startsWith("/tmp/hosts-refresh-")))
                .thenReturn(true);

        // 关键断言：使用 echo 'pwd' | sudo -S cp 命令
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                matches("echo 'mypassword' \\| sudo -S cp /etc/hosts /etc/hosts\\.bak\\..+"),
                anyLong()))
                .thenReturn(mockResult("", true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                matches("echo 'mypassword' \\| sudo -S cp /tmp/hosts-refresh-.+\\.tmp /etc/hosts"),
                anyLong()))
                .thenReturn(mockResult("", true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                matches("rm -f /tmp/hosts-refresh-.+\\.tmp"), anyLong()))
                .thenReturn(mockResult("", true));

        com.gameplatform.vo.HostsRefreshResult result = refresher.refreshHosts(1L, "mypassword");

        assertTrue(result.isSuccess());
        assertTrue(result.getRefreshedDomains().contains("github.com"));
    }

    @Test
    @DisplayName("无待改域名时应直接返回成功，不执行 sudo 操作")
    void shouldReturnSuccessWithoutSudoWhenNoDomainToRefresh() {
        String hostsContent = "127.0.0.1 localhost\n" +
                "192.168.111.253 github.com\n"; // 已是目标 IP
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("cat /etc/hosts"), anyLong()))
                .thenReturn(mockResult(hostsContent, true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("hostname"), anyLong()))
                .thenReturn(mockResult("haki-pc\n", true));

        com.gameplatform.vo.HostsRefreshResult result = refresher.refreshHosts(1L, null);

        assertTrue(result.isSuccess());
        assertTrue(result.getRefreshedDomains().isEmpty());
        // 不应调用 sudo 相关命令
        verify(sshUtil, never()).executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("sudo -n true 2>/dev/null"), anyLong());
    }

    @Test
    @DisplayName("sudo 命令失败时应返回失败并保留临时文件路径")
    void shouldReturnFailureWhenSudoCommandFails() {
        String hostsContent = "127.0.0.1 localhost\n127.0.0.1 github.com\n";
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("cat /etc/hosts"), anyLong()))
                .thenReturn(mockResult(hostsContent, true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("hostname"), anyLong()))
                .thenReturn(mockResult("haki-pc\n", true));
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                eq("sudo -n true 2>/dev/null"), anyLong()))
                .thenReturn(mockResult("", true));
        when(sshUtil.uploadFile(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                contains("hosts-refresh-"), startsWith("/tmp/hosts-refresh-")))
                .thenReturn(true);

        // sudo cp 备份命令失败
        when(sshUtil.executeCommand(eq("192.168.111.253"), eq(22), eq("haki"),
                eq("decrypted-key"), eq("decrypted-pwd"),
                matches("sudo cp /etc/hosts /etc/hosts\\.bak\\..+"), anyLong()))
                .thenReturn(mockResult("sudo: error", false));

        com.gameplatform.vo.HostsRefreshResult result = refresher.refreshHosts(1L, null);

        assertFalse(result.isSuccess());
        assertNotNull(result.getErrorMessage());
    }
```

- [ ] **Step 2: 运行测试验证失败**

Run:
```bash
cd backend && mvn -pl core test -Dtest=HostsFileRefresherTest -q
```

Expected: 5 个新测试 FAIL（`refreshHosts` 抛 `UnsupportedOperationException`），原有 5 个测试 PASS

- [ ] **Step 3: 实现 refreshHosts 方法**

在 `HostsFileRefresher.java` 中找到：

```java
    public HostsRefreshResult refreshHosts(Long hostId, String sudoPassword) {
        throw new UnsupportedOperationException("refreshHosts 尚未实现（Task 4 完成）");
    }
```

替换为：

```java
    /**
     * 执行刷新：把 127.0.0.1 域名改为宿主机 IP。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>读取 /etc/hosts 和 hostname</li>
     *   <li>Java 端生成新内容（127.0.0.1 行的待改域名 → 新建 hostLanIp 行）</li>
     *   <li>无待改域名 → 直接返回成功（幂等）</li>
     *   <li>SFTP 上传新内容到 /tmp/hosts-refresh-{timestamp}.tmp</li>
     *   <li>检测免密 sudo：可用则用 sudo cp；不可用则用 echo 'pwd' | sudo -S cp</li>
     *   <li>sudo cp /etc/hosts /etc/hosts.bak.{timestamp}（备份）</li>
     *   <li>sudo cp /tmp/xxx.tmp /etc/hosts（覆盖）</li>
     *   <li>rm -f /tmp/xxx.tmp（清理临时文件）</li>
     * </ol>
     *
     * @param hostId       主机 ID
     * @param sudoPassword 可选，null/空表示尝试免密 sudo；非空表示用 sudo -S 传密码
     */
    public HostsRefreshResult refreshHosts(Long hostId, String sudoPassword) {
        Host host = loadHost(hostId);
        SshCredentials creds = decryptCredentials(host);
        String hostLanIp = host.getIpAddress();

        HostsRefreshResult result = new HostsRefreshResult();
        result.setHostLanIp(hostLanIp);

        try {
            // 1. 读取 /etc/hosts 和 hostname
            String hostsContent = execCommand(host, creds, "cat /etc/hosts");
            if (StrUtil.isBlank(hostsContent)) {
                result.setSuccess(false);
                result.setErrorMessage("读取 /etc/hosts 失败：内容为空");
                return result;
            }
            String hostname = execCommand(host, creds, "hostname").trim();

            // 2. 提取待改域名
            List<String> domainsToRefresh = extractDomainsToRefresh(hostsContent, hostname, hostLanIp);

            // 3. 幂等：无待改域名 → 直接成功
            if (domainsToRefresh.isEmpty()) {
                result.setSuccess(true);
                result.setRefreshedDomains(new ArrayList<>());
                log.info("主机 {} 无需刷新的域名，hosts 文件已是目标状态", host.getHostName());
                return result;
            }

            // 4. 生成新内容
            String newContent = buildNewHostsContent(hostsContent, hostname, hostLanIp);

            // 5. SFTP 上传临时文件
            String timestamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            String tmpFilePath = "/tmp/hosts-refresh-" + timestamp + ".tmp";
            String localTmpPath = writeLocalTempFile(newContent);

            boolean uploaded = sshUtil.uploadFile(
                    host.getIpAddress(), host.getSshPort(), host.getSshUser(),
                    creds.privateKey, creds.password,
                    localTmpPath, tmpFilePath);
            if (!uploaded) {
                result.setSuccess(false);
                result.setErrorMessage("SFTP 上传临时文件失败: " + tmpFilePath);
                cleanupLocalTempFile(localTmpPath);
                return result;
            }
            cleanupLocalTempFile(localTmpPath);

            // 6. 检测免密 sudo（如果未提供密码）
            boolean usePasswordlessSudo = false;
            if (StrUtil.isBlank(sudoPassword)) {
                SshUtil.CommandResult sudoCheck = sshUtil.executeCommand(
                        host.getIpAddress(), host.getSshPort(), host.getSshUser(),
                        creds.privateKey, creds.password,
                        "sudo -n true 2>/dev/null", 5000L);
                usePasswordlessSudo = sudoCheck.isSuccess();
                if (!usePasswordlessSudo) {
                    result.setSuccess(false);
                    result.setErrorMessage("免密 sudo 不可用，请输入 sudo 密码后重试");
                    // 清理临时文件
                    sshUtil.executeCommand(host.getIpAddress(), host.getSshPort(), host.getSshUser(),
                            creds.privateKey, creds.password,
                            "rm -f " + tmpFilePath);
                    return result;
                }
            }

            // 7. 构造 sudo 命令前缀
            String sudoPrefix = StrUtil.isBlank(sudoPassword)
                    ? "sudo "
                    : "echo '" + sudoPassword + "' | sudo -S ";

            // 8. 备份原 /etc/hosts
            String backupPath = "/etc/hosts.bak." + timestamp;
            SshUtil.CommandResult backupResult = sshUtil.executeCommand(
                    host.getIpAddress(), host.getSshPort(), host.getSshUser(),
                    creds.privateKey, creds.password,
                    sudoPrefix + "cp /etc/hosts " + backupPath, 10000L);
            if (!backupResult.isSuccess()) {
                result.setSuccess(false);
                result.setErrorMessage("备份 /etc/hosts 失败，已中止刷新: "
                        + backupResult.getError());
                sshUtil.executeCommand(host.getIpAddress(), host.getSshPort(), host.getSshUser(),
                        creds.privateKey, creds.password,
                        "rm -f " + tmpFilePath);
                return result;
            }

            // 9. 覆盖 /etc/hosts
            SshUtil.CommandResult overwriteResult = sshUtil.executeCommand(
                    host.getIpAddress(), host.getSshPort(), host.getSshUser(),
                    creds.privateKey, creds.password,
                    sudoPrefix + "cp " + tmpFilePath + " /etc/hosts", 10000L);
            if (!overwriteResult.isSuccess()) {
                result.setSuccess(false);
                result.setErrorMessage("写入 /etc/hosts 失败: "
                        + overwriteResult.getError()
                        + "（临时文件已保留: " + tmpFilePath + "）");
                return result;
            }

            // 10. 清理临时文件
            sshUtil.executeCommand(host.getIpAddress(), host.getSshPort(), host.getSshUser(),
                    creds.privateKey, creds.password,
                    "rm -f " + tmpFilePath);

            // 11. 返回成功结果
            result.setSuccess(true);
            result.setBackupPath(backupPath);
            result.setRefreshedDomains(domainsToRefresh);
            log.info("主机 {} hosts 刷新成功，修改 {} 个域名，备份: {}",
                    host.getHostName(), domainsToRefresh.size(), backupPath);
            return result;

        } catch (Exception e) {
            log.error("主机 {} hosts 刷新异常", host.getHostName(), e);
            result.setSuccess(false);
            result.setErrorMessage(e.getMessage());
            return result;
        }
    }

    /**
     * 生成新的 /etc/hosts 内容：
     * - 保留原文件所有行
     * - 从 127.0.0.1 行中移除待改域名（保留系统别名和 hostname）
     * - 新增一行 hostLanIp + 所有待改域名
     */
    private String buildNewHostsContent(String originalContent, String hostname, String hostLanIp) {
        StringBuilder result = new StringBuilder();
        List<String> domainsToMove = new ArrayList<>();
        Set<String> systemAndHostname = new java.util.HashSet<>(SYSTEM_ALIASES);
        if (StrUtil.isNotBlank(hostname)) {
            systemAndHostname.add(hostname.toLowerCase());
        }

        for (String line : originalContent.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                result.append(line).append("\n");
                continue;
            }

            String[] parts = trimmed.split("\\s+");
            if (parts.length < 2
                    || (!"127.0.0.1".equals(parts[0]) && !"::1".equals(parts[0]))) {
                result.append(line).append("\n");
                continue;
            }

            // 处理回环行：保留系统别名和 hostname，收集待改域名
            String ip = parts[0];
            List<String> keepDomains = new ArrayList<>();
            for (int i = 1; i < parts.length; i++) {
                String d = parts[i].trim().toLowerCase();
                if (systemAndHostname.contains(d)) {
                    keepDomains.add(parts[i]);
                } else if ("127.0.0.1".equals(ip)) {
                    domainsToMove.add(d);
                }
                // ::1 行的非系统域名也移到 LAN IP 行（统一处理）
                if ("::1".equals(ip) && !systemAndHostname.contains(d)) {
                    domainsToMove.add(d);
                }
            }

            if (keepDomains.isEmpty()) {
                // 该行所有域名都被移走，跳过此行
                continue;
            }
            result.append(ip).append(" ").append(String.join(" ", keepDomains)).append("\n");
        }

        // 末尾追加新行：hostLanIp + 所有待改域名
        if (!domainsToMove.isEmpty()) {
            // 去重，保留顺序
            Set<String> seen = new java.util.LinkedHashSet<>();
            for (String d : domainsToMove) {
                if (!seen.contains(d)) seen.add(d);
            }
            result.append(hostLanIp).append(" ").append(String.join(" ", seen)).append("\n");
        }

        return result.toString();
    }

    /**
     * 写本地临时文件（供 SFTP 上传）
     */
    private String writeLocalTempFile(String content) {
        try {
            Path tmp = Files.createTempFile("hosts-refresh-", ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            return tmp.toAbsolutePath().toString();
        } catch (IOException e) {
            throw new RuntimeException("写本地临时文件失败: " + e.getMessage(), e);
        }
    }

    /** 删除本地临时文件 */
    private void cleanupLocalTempFile(String path) {
        try {
            Files.deleteIfExists(Paths.get(path));
        } catch (IOException ignored) {
        }
    }
```

然后在文件顶部 imports 区域新增：

```java
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
```

- [ ] **Step 4: 运行测试验证通过**

Run:
```bash
cd backend && mvn -pl core test -Dtest=HostsFileRefresherTest -q
```

Expected: 10 个测试全部 PASS

- [ ] **Step 5: Commit**

```bash
cd backend
git add core/src/main/java/com/gameplatform/service/HostsFileRefresher.java core/src/test/java/com/gameplatform/service/HostsFileRefresherTest.java
git commit -m "feat(hosts-refresh): implement refreshHosts with sudo handling and backup"
```

---

## Task 4: HostController 端点集成

**Files:**
- Modify: `backend/core/src/main/java/com/gameplatform/controller/HostController.java`

在 `HostController` 新增 2 个端点：
- `GET /hosts/{id}/hosts-preview` → 调用 `HostsFileRefresher.previewRefresh`
- `POST /hosts/{id}/hosts-refresh` → 调用 `HostsFileRefresher.refreshHosts`

- [ ] **Step 1: 修改 HostController，新增依赖注入**

打开 `backend/core/src/main/java/com/gameplatform/controller/HostController.java`，找到类声明部分：

```java
@Tag(name = "主机管理", description = "主机相关接口")
@RestController
@RequestMapping("/hosts")
@RequiredArgsConstructor
@Validated
public class HostController {

    private final HostService hostService;
    private final GamePlatformConfig gamePlatformConfig;
    private final SshUtil sshUtil;
```

在 `private final SshUtil sshUtil;` 之后新增一行：

```java
    private final com.gameplatform.service.HostsFileRefresher hostsFileRefresher;
```

- [ ] **Step 2: 新增 2 个端点方法**

在 `HostController.java` 中找到 `getResources` 方法的结尾（VO 区域之前），即在 `// ========== VO ==========` 注释之前，新增以下两个方法：

```java
    /**
     * 预检 hosts 刷新：返回待修改域名清单 + sudo 状态
     */
    @Operation(summary = "预检 hosts 刷新", description = "读取 /etc/hosts 并识别待修改域名，不写入")
    @GetMapping("/{id}/hosts-preview")
    public Result<com.gameplatform.vo.HostsRefreshPreview> previewHostsRefresh(
            @Parameter(description = "主机ID") @PathVariable Long id) {
        try {
            com.gameplatform.vo.HostsRefreshPreview preview = hostsFileRefresher.previewRefresh(id);
            return Result.success(preview);
        } catch (Exception e) {
            log.error("预检 hosts 刷新失败: hostId={}", id, e);
            return Result.fail("预检失败: " + e.getMessage());
        }
    }

    /**
     * 执行 hosts 刷新
     */
    @Operation(summary = "执行 hosts 刷新", description = "将 127.0.0.1 域名改为宿主机 LAN IP")
    @PostMapping("/{id}/hosts-refresh")
    @OperationLog(type = "UPDATE", target = "HOST", description = "刷新宿主机 hosts")
    public Result<com.gameplatform.vo.HostsRefreshResult> refreshHosts(
            @Parameter(description = "主机ID") @PathVariable Long id,
            @RequestBody HostsRefreshRequest request) {
        try {
            com.gameplatform.vo.HostsRefreshResult result =
                    hostsFileRefresher.refreshHosts(id, request.getSudoPassword());
            if (result.isSuccess()) {
                return Result.success(result);
            } else {
                return Result.fail(result.getErrorMessage());
            }
        } catch (Exception e) {
            log.error("执行 hosts 刷新失败: hostId={}", id, e);
            return Result.fail("刷新失败: " + e.getMessage());
        }
    }
```

- [ ] **Step 3: 新增请求体内部静态类**

在 `HostController.java` 文件末尾的最后一个 `}` 之前（即 `ResourceInfoVO` 类之后、类关闭 `}` 之前），新增：

```java
    /**
     * hosts 刷新请求体
     */
    @Data
    public static class HostsRefreshRequest {
        /**
         * sudo 密码（免密 sudo 时为 null）
         */
        private String sudoPassword;
    }
```

- [ ] **Step 4: 添加 logger 导入**

`HostController` 中已使用 `log.error`，但原类未声明 logger。在 `HostController.java` 顶部 import 区域新增：

```java
import lombok.extern.slf4j.Slf4j;
```

然后在 `@Tag(name = "主机管理", description = "主机相关接口")` 之上（或 `@RestController` 之上）新增注解：

```java
@Slf4j
```

最终类注解应为：

```java
@Tag(name = "主机管理", description = "主机相关接口")
@Slf4j
@RestController
@RequestMapping("/hosts")
@RequiredArgsConstructor
@Validated
public class HostController {
```

- [ ] **Step 5: 编译验证**

Run:
```bash
cd backend && mvn -pl core -am compile -DskipTests -q
```

Expected: BUILD SUCCESS

- [ ] **Step 6: 运行全部测试验证无回归**

Run:
```bash
cd backend && mvn -pl core test -Dtest=HostsFileRefresherTest,HostServiceTest -q
```

Expected: 所有测试 PASS

- [ ] **Step 7: Commit**

```bash
cd backend
git add core/src/main/java/com/gameplatform/controller/HostController.java
git commit -m "feat(hosts-refresh): expose preview and refresh endpoints in HostController"
```

---

## Task 5: 前端 API 封装与测试（TDD）

**Files:**
- Modify: `frontend/src/api/host.js`
- Modify: `frontend/src/tests/api/host.test.js`

- [ ] **Step 1: 在 host.test.js 末尾新增测试用例**

打开 `frontend/src/tests/api/host.test.js`，在最后一个 `});`（即 `describe("host API", ...)` 块的结束）之前，新增以下测试：

```javascript
  describe("previewHostsRefresh - 预检 hosts 刷新", () => {
    it("应该正确调用预检接口", async () => {
      const mockResponse = {
        hostLanIp: "192.168.111.253",
        hostname: "haki-pc",
        domainsToRefresh: ["raw.githubusercontent.com", "github.com"],
        sudoAvailable: true,
        needsSudoPassword: false,
      };
      request.mockResolvedValue(mockResponse);

      const result = await hostApi.previewHostsRefresh(1);

      expect(request).toHaveBeenCalledWith({
        url: "/hosts/1/hosts-preview",
        method: "get",
      });
      expect(result.domainsToRefresh).toHaveLength(2);
      expect(result.sudoAvailable).toBe(true);
    });

    it("应该处理预检失败", async () => {
      request.mockRejectedValue(new Error("SSH 连接失败"));

      await expect(hostApi.previewHostsRefresh(999)).rejects.toThrow(
        "SSH 连接失败",
      );
    });
  });

  describe("refreshHosts - 执行 hosts 刷新", () => {
    it("应该使用 POST 方法调用刷新接口，带 sudo 密码", async () => {
      const mockResponse = {
        success: true,
        backupPath: "/etc/hosts.bak.20260718120000",
        refreshedDomains: ["github.com", "raw.githubusercontent.com"],
        hostLanIp: "192.168.111.253",
      };
      request.mockResolvedValue(mockResponse);

      const result = await hostApi.refreshHosts(1, "mypassword");

      expect(request).toHaveBeenCalledWith({
        url: "/hosts/1/hosts-refresh",
        method: "post",
        data: { sudoPassword: "mypassword" },
      });
      expect(result.success).toBe(true);
      expect(result.refreshedDomains).toHaveLength(2);
    });

    it("sudoPassword 为 null 时应正确传递", async () => {
      const mockResponse = {
        success: true,
        backupPath: "/etc/hosts.bak.20260718120000",
        refreshedDomains: ["github.com"],
        hostLanIp: "192.168.111.253",
      };
      request.mockResolvedValue(mockResponse);

      await hostApi.refreshHosts(1, null);

      expect(request).toHaveBeenCalledWith({
        url: "/hosts/1/hosts-refresh",
        method: "post",
        data: { sudoPassword: null },
      });
    });

    it("应该处理刷新失败 - sudo 密码错误", async () => {
      request.mockRejectedValue(new Error("sudo 密码错误，请重试"));

      await expect(hostApi.refreshHosts(1, "wrongpwd")).rejects.toThrow(
        "sudo 密码错误",
      );
    });
  });
```

- [ ] **Step 2: 运行测试验证失败**

Run:
```bash
cd frontend && npx vitest run src/tests/api/host.test.js
```

Expected: 新增的 5 个测试 FAIL（`hostApi.previewHostsRefresh` / `hostApi.refreshHosts` 未定义）

- [ ] **Step 3: 在 host.js 新增 API 函数**

打开 `frontend/src/api/host.js`，在文件末尾（`getHostResources` 函数之后）新增：

```javascript
/**
 * 预检 hosts 刷新
 * @param {number} id - 主机ID
 * @returns {Promise<{hostLanIp: string, hostname: string, domainsToRefresh: string[], sudoAvailable: boolean, needsSudoPassword: boolean}>}
 */
export function previewHostsRefresh(id) {
  return request({
    url: `/hosts/${id}/hosts-preview`,
    method: "get",
  });
}

/**
 * 执行 hosts 刷新
 * @param {number} id - 主机ID
 * @param {string|null} sudoPassword - sudo密码（免密sudo时为null）
 * @returns {Promise<{success: boolean, errorMessage: string, backupPath: string, refreshedDomains: string[], hostLanIp: string}>}
 */
export function refreshHosts(id, sudoPassword) {
  return request({
    url: `/hosts/${id}/hosts-refresh`,
    method: "post",
    data: { sudoPassword },
  });
}
```

- [ ] **Step 4: 运行测试验证通过**

Run:
```bash
cd frontend && npx vitest run src/tests/api/host.test.js
```

Expected: 所有测试 PASS（包括原有测试和新增 5 个）

- [ ] **Step 5: Commit**

```bash
cd frontend
git add src/api/host.js src/tests/api/host.test.js
git commit -m "feat(hosts-refresh): add previewHostsRefresh and refreshHosts API functions"
```

---

## Task 6: 前端 UI - 刷新 hosts 按钮与弹窗

**Files:**
- Modify: `frontend/src/views/host/index.vue`

在主机列表操作列新增「刷新 hosts」按钮，点击后弹窗：
1. 调用 `previewHostsRefresh` 显示预检结果
2. 根据 `needsSudoPassword` 决定是否显示密码框
3. 用户确认后调用 `refreshHosts`

- [ ] **Step 1: 修改 host/index.vue - 新增 import 和状态**

打开 `frontend/src/views/host/index.vue`，找到 `<script setup>` 区域顶部的 import：

```javascript
import {
  getHostList,
  getHostResources,
  createHost,
  updateHost,
  deleteHost,
  testHostConnection,
} from "@/api/host";
```

替换为：

```javascript
import {
  getHostList,
  getHostResources,
  createHost,
  updateHost,
  deleteHost,
  testHostConnection,
  previewHostsRefresh,
  refreshHosts,
} from "@/api/host";
```

- [ ] **Step 2: 在 script setup 中新增 hosts 刷新状态和方法**

在 `// 删除确认弹窗` 注释之前（即 `const deleteDialogVisible = ref(false);` 之前），新增：

```javascript
// hosts 刷新弹窗
const hostsDialogVisible = ref(false);
const hostsPreviewLoading = ref(false);
const hostsRefreshLoading = ref(false);
const hostsPreview = ref(null);
const hostsSudoPassword = ref("");
const hostsTargetHost = ref(null);

// 打开 hosts 刷新弹窗
async function handleRefreshHosts(row) {
  if (row.status !== 1) {
    ElMessage.warning("主机离线，无法刷新 hosts");
    return;
  }
  hostsTargetHost.value = row;
  hostsDialogVisible.value = true;
  hostsPreviewLoading.value = true;
  hostsPreview.value = null;
  hostsSudoPassword.value = "";

  try {
    const data = await previewHostsRefresh(row.id);
    hostsPreview.value = data;
  } catch (error) {
    ElMessage.error("预检失败：" + (error.message || "未知错误"));
    hostsDialogVisible.value = false;
  } finally {
    hostsPreviewLoading.value = false;
  }
}

// 确认刷新 hosts
async function confirmRefreshHosts() {
  if (!hostsPreview.value) return;

  // 需要密码但未输入
  if (hostsPreview.value.needsSudoPassword && !hostsSudoPassword.value) {
    ElMessage.warning("请输入 sudo 密码");
    return;
  }

  // 无待改域名
  if (
    !hostsPreview.value.domainsToRefresh ||
    hostsPreview.value.domainsToRefresh.length === 0
  ) {
    ElMessage.info("无需刷新，hosts 文件已是目标状态");
    hostsDialogVisible.value = false;
    return;
  }

  hostsRefreshLoading.value = true;
  try {
    const result = await refreshHosts(
      hostsTargetHost.value.id,
      hostsPreview.value.needsSudoPassword ? hostsSudoPassword.value : null,
    );
    if (result.success) {
      ElMessage.success(
        `已修改 ${result.refreshedDomains?.length || 0} 个域名，备份路径：${result.backupPath || "(无)"}`,
      );
      hostsDialogVisible.value = false;
    } else {
      ElMessage.error(result.errorMessage || "刷新失败");
    }
  } catch (error) {
    ElMessage.error("刷新失败：" + (error.message || "未知错误"));
  } finally {
    hostsRefreshLoading.value = false;
  }
}
```

- [ ] **Step 3: 在操作列新增「刷新 hosts」按钮**

在 `<template>` 中找到操作列的 `<el-table-column label="操作" width="220" fixed="right">`，把 width 改为 `300`，并在「测试」按钮之后、「编辑」按钮之前新增「刷新 hosts」按钮：

修改后的操作列模板：

```html
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <el-button
              type="primary"
              link
              size="small"
              :disabled="row.status !== 1"
              @click="handleTerminal(row)"
            >
              <el-icon><Monitor /></el-icon>
              终端
            </el-button>
            <el-button
              type="primary"
              link
              size="small"
              @click="handleTest(row)"
            >
              测试
            </el-button>
            <el-button
              type="warning"
              link
              size="small"
              :disabled="row.status !== 1"
              @click="handleRefreshHosts(row)"
            >
              hosts
            </el-button>
            <el-button
              type="primary"
              link
              size="small"
              @click="handleEdit(row)"
            >
              编辑
            </el-button>
            <el-button
              type="danger"
              link
              size="small"
              @click="handleDelete(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
```

- [ ] **Step 4: 在模板末尾新增 hosts 刷新弹窗**

在模板中找到删除确认弹窗 `<!-- 删除确认弹窗 -->` 之前，新增 hosts 刷新弹窗：

```html
    <!-- hosts 刷新弹窗 -->
    <el-dialog
      v-model="hostsDialogVisible"
      title="刷新宿主机 hosts（反向代理）"
      width="560px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <div v-loading="hostsPreviewLoading" class="hosts-refresh-content">
        <template v-if="hostsPreview">
          <div class="hosts-info-row">
            <span class="label">宿主机 IP：</span>
            <span class="value">{{ hostsPreview.hostLanIp }}</span>
          </div>
          <div class="hosts-info-row">
            <span class="label">主机名：</span>
            <span class="value">{{ hostsPreview.hostname }}</span>
          </div>

          <el-divider content-position="left"> 待改域名清单 </el-divider>

          <div v-if="hostsPreview.domainsToRefresh?.length > 0">
            <p class="hosts-tip">
              检测到以下域名指向 127.0.0.1，将被改为
              <strong>{{ hostsPreview.hostLanIp }}</strong>：
            </p>
            <ul class="domains-list">
              <li
                v-for="d in hostsPreview.domainsToRefresh"
                :key="d"
              >
                {{ d }}
              </li>
            </ul>
          </div>
          <div v-else>
            <el-alert
              title="无需刷新，hosts 文件已是目标状态"
              type="success"
              :closable="false"
              show-icon
            />
          </div>

          <el-divider content-position="left"> sudo 权限 </el-divider>

          <div class="sudo-status">
            <el-tag
              v-if="hostsPreview.sudoAvailable"
              type="success"
              >免密 sudo 可用</el-tag
            >
            <el-tag v-else type="warning">需要 sudo 密码</el-tag>
          </div>

          <el-form-item
            v-if="hostsPreview.needsSudoPassword"
            label="sudo 密码"
            label-width="90px"
            class="sudo-pwd-form"
          >
            <el-input
              v-model="hostsSudoPassword"
              type="password"
              placeholder="请输入 sudo 密码"
              show-password
            />
          </el-form-item>

          <el-alert
            v-if="hostsPreview.domainsToRefresh?.length > 0"
            type="warning"
            :closable="false"
            show-icon
            class="backup-tip"
          >
            <template #title>
              将备份原 /etc/hosts 到 /etc/hosts.bak.{timestamp}
            </template>
          </el-alert>
        </template>
      </div>
      <template #footer>
        <el-button @click="hostsDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="hostsRefreshLoading"
          :disabled="
            !hostsPreview ||
            !hostsPreview.domainsToRefresh?.length ||
            (hostsPreview.needsSudoPassword && !hostsSudoPassword)
          "
          @click="confirmRefreshHosts"
        >
          确认刷新
        </el-button>
      </template>
    </el-dialog>
```

- [ ] **Step 5: 在 `<style>` 区域新增弹窗样式**

在 `<style lang="scss" scoped>` 区域末尾（最后一个 `}` 之前，即 `.delete-confirm-content` 块之后），新增：

```scss
// hosts 刷新弹窗
.hosts-refresh-content {
  .hosts-info-row {
    display: flex;
    margin-bottom: 8px;
    font-size: var(--platform-font-size-sm);

    .label {
      width: 80px;
      color: var(--el-text-color-secondary);
    }

    .value {
      color: var(--el-text-color-primary);
      font-weight: var(--platform-font-weight-medium);
    }
  }

  .hosts-tip {
    font-size: var(--platform-font-size-sm);
    color: var(--el-text-color-regular);
    margin-bottom: 8px;
    line-height: 1.6;
  }

  .domains-list {
    background: var(--el-fill-color-lighter);
    border-radius: 4px;
    padding: 12px 16px 12px 28px;
    margin: 0 0 12px 0;
    max-height: 200px;
    overflow-y: auto;

    li {
      font-family: monospace;
      font-size: var(--platform-font-size-sm);
      color: var(--el-text-color-primary);
      line-height: 1.8;
    }
  }

  .sudo-status {
    margin-bottom: 12px;
  }

  .sudo-pwd-form {
    margin-bottom: 12px;
  }

  .backup-tip {
    margin-top: 8px;
  }
}
```

- [ ] **Step 6: 运行前端测试验证无回归**

Run:
```bash
cd frontend && npx vitest run src/tests/api/host.test.js src/tests/views/host.test.js
```

Expected: 所有测试 PASS

- [ ] **Step 7: 启动前端开发服务器，手动验证 UI**

Run:
```bash
cd frontend && npm run dev
```

打开浏览器访问前端，进入主机管理页面，验证：
- 操作列新增「hosts」按钮
- 点击按钮弹窗显示加载中 → 加载完成后显示预检结果
- 弹窗显示宿主机 IP、主机名、待改域名清单、sudo 状态
- 免密 sudo 可用时，无密码框；否则显示密码框
- 「确认刷新」按钮在无待改域名或需密码但未输入时禁用
- 点击「确认刷新」成功后显示成功消息并关闭弹窗

- [ ] **Step 8: Commit**

```bash
cd frontend
git add src/views/host/index.vue
git commit -m "feat(hosts-refresh): add refresh hosts button and dialog in host list"
```

---

## Task 7: 主仓库子模块引用更新

**Files:**
- Modify: `backend`（子模块）
- Modify: `frontend`（子模块）
- Modify: 主仓库 `d:\program\ai\game_platform_manger`

- [ ] **Step 1: 更新主仓库子模块引用**

Run:
```bash
cd d:\program\ai\game_platform_manger
git add backend frontend
git status
```

Expected: 显示子模块引用已更新

- [ ] **Step 2: 提交主仓库**

Run:
```bash
cd d:\program\ai\game_platform_manger
git commit -m "chore: update submodules for hosts-refresh feature"
```

---

## 验收清单

完成所有任务后，按设计文档第 10 节验收标准逐项确认：

- [ ] 主机管理列表点击「hosts」按钮 → 弹窗显示预检结果
- [ ] 免密 sudo 可用时，确认后直接刷新成功，显示修改的域名清单和备份路径
- [ ] 需要密码时，弹窗显示密码框，输入正确密码后刷新成功
- [ ] 密码错误时显示「sudo 密码错误，请重试」
- [ ] 无 127.0.0.1 域名时显示「无需刷新，hosts 文件已是目标状态」
- [ ] 重复刷新不产生副作用（幂等）
- [ ] 后端 `HostsFileRefresherTest` 全部通过
- [ ] 前端 `host.test.js` 全部通过

---

*最后更新: 2026-07-18*
