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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
     * SSH 命令默认超时（毫秒），与 HostServiceImpl.SSH_TIMEOUT 对齐
     */
    private static final long SSH_DEFAULT_TIMEOUT_MS = 30000L;

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
     * 执行刷新：把 127.0.0.1 域名改为宿主机 IP（全部候选域名）。
     *
     * <p>兼容旧调用，等价于 {@link #refreshHosts(Long, String, List)} 传入 null。</p>
     *
     * @param hostId       主机 ID
     * @param sudoPassword 可选，null/空表示尝试免密 sudo；非空表示用 sudo -S 传密码
     */
    public HostsRefreshResult refreshHosts(Long hostId, String sudoPassword) {
        return refreshHosts(hostId, sudoPassword, null);
    }

    /**
     * 执行刷新：把指定的 127.0.0.1 域名改为宿主机 IP。
     *
     * <p>流程：</p>
     * <ol>
     *   <li>读取 /etc/hosts 和 hostname</li>
     *   <li>提取候选域名，按 selectedDomains 过滤；无待改域名 → 直接返回成功（幂等）</li>
     *   <li>检测免密 sudo（sudoPassword 为空时）：不可用则直接失败，避免无谓的 SFTP 上传</li>
     *   <li>Java 端生成新内容（仅 selectedDomains 中的域名从 127.0.0.1 行移到 hostLanIp 行）</li>
     *   <li>SFTP 上传新内容到 /tmp/hosts-refresh-{timestamp}.tmp</li>
     *   <li>sudo cp /etc/hosts /etc/hosts.bak.{timestamp}（备份）</li>
     *   <li>sudo cp /tmp/xxx.tmp /etc/hosts（覆盖）</li>
     *   <li>rm -f /tmp/xxx.tmp（清理临时文件）</li>
     *   <li>刷新 DNS 缓存（resolvectl / systemd-resolve / nscd，失败不阻塞）</li>
     * </ol>
     *
     * @param hostId          主机 ID
     * @param sudoPassword    可选，null/空表示尝试免密 sudo；非空表示用 sudo -S 传密码
     * @param selectedDomains 可选，null/空表示刷新全部候选域名；非空表示只刷新指定域名（用于跳过广告屏蔽条目）
     */
    public HostsRefreshResult refreshHosts(Long hostId, String sudoPassword, List<String> selectedDomains) {
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

            // 2. 提取候选域名，按 selectedDomains 过滤
            List<String> allCandidates = extractDomainsToRefresh(hostsContent, hostname, hostLanIp);
            List<String> domainsToRefresh;
            if (selectedDomains != null && !selectedDomains.isEmpty()) {
                // 用户选中的域名（小写匹配）
                Set<String> selected = selectedDomains.stream()
                        .filter(StrUtil::isNotBlank)
                        .map(String::trim)
                        .map(String::toLowerCase)
                        .collect(Collectors.toSet());
                domainsToRefresh = allCandidates.stream()
                        .filter(selected::contains)
                        .collect(Collectors.toList());
            } else {
                domainsToRefresh = allCandidates;
            }

            // 3. 幂等：无待改域名 → 直接成功
            if (domainsToRefresh.isEmpty()) {
                result.setSuccess(true);
                result.setRefreshedDomains(new ArrayList<>());
                log.info("主机 {} 无需刷新的域名，hosts 文件已是目标状态", host.getHostName());
                return result;
            }

            // 4. 检测免密 sudo（如果未提供密码）—— 提前检测，避免无谓的 SFTP 上传
            if (StrUtil.isBlank(sudoPassword)) {
                SshUtil.CommandResult sudoCheck = sshUtil.executeCommand(
                        host.getIpAddress(), host.getSshPort(), host.getSshUser(),
                        creds.privateKey, creds.password,
                        "sudo -n true 2>/dev/null", 5000L);
                if (!sudoCheck.isSuccess()) {
                    result.setSuccess(false);
                    result.setErrorMessage("免密 sudo 不可用，请输入 sudo 密码后重试");
                    return result;
                }
            }

            // 5. 生成新内容（只移动选中的域名，其他 127.0.0.1 条目保持原样）
            Set<String> domainsToMoveSet = domainsToRefresh.stream()
                    .map(String::toLowerCase)
                    .collect(Collectors.toSet());
            String newContent = buildNewHostsContent(hostsContent, hostname, hostLanIp, domainsToMoveSet);

            // 6. SFTP 上传临时文件
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
                        "rm -f " + tmpFilePath, SSH_DEFAULT_TIMEOUT_MS);
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
                    "rm -f " + tmpFilePath, SSH_DEFAULT_TIMEOUT_MS);

            // 11. 刷新 DNS 缓存（非阻塞，失败只记录日志）
            flushDnsCache(host, creds, sudoPrefix);

            // 12. 返回成功结果
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
     * 刷新宿主机 DNS 缓存。
     *
     * <p>修改 /etc/hosts 后，systemd-resolved / nscd 等解析器可能仍缓存旧记录，
     * 导致 Docker 容器或本机程序短暂解析到旧 IP。依次尝试以下命令（任一成功即返回）：</p>
     * <ul>
     *   <li>resolvectl flush-caches（systemd &gt;= 252）</li>
     *   <li>systemd-resolve --flush-caches（systemd &lt; 252）</li>
     *   <li>nscd -i hosts（若使用 nscd）</li>
     * </ul>
     * <p>所有命令均需要 sudo。失败不阻塞主流程，仅记录日志（DNS 缓存会自然过期）。</p>
     */
    private void flushDnsCache(Host host, SshCredentials creds, String sudoPrefix) {
        String[] commands = {
                "resolvectl flush-caches",
                "systemd-resolve --flush-caches",
                "nscd -i hosts"
        };
        for (String cmd : commands) {
            try {
                SshUtil.CommandResult r = sshUtil.executeCommand(
                        host.getIpAddress(), host.getSshPort(), host.getSshUser(),
                        creds.privateKey, creds.password,
                        sudoPrefix + cmd + " 2>/dev/null", 5000L);
                if (r != null && r.isSuccess()) {
                    log.info("主机 {} DNS 缓存刷新成功: {}", host.getHostName(), cmd);
                    return;
                }
            } catch (Exception e) {
                log.debug("DNS 缓存刷新命令失败（可忽略）: {} - {}", cmd, e.getMessage());
            }
        }
        log.warn("主机 {} DNS 缓存刷新失败，所有命令均未成功（可忽略，DNS 缓存会自然过期）",
                host.getHostName());
    }

    /**
     * 生成新的 /etc/hosts 内容：
     * - 保留原文件所有行
     * - 从 127.0.0.1 / ::1 行中移除 domainsToMoveSet 中的域名（保留系统别名、hostname 和未选中的域名）
     * - 末尾新增一行 hostLanIp + 所有被移走的域名
     *
     * @param originalContent 原 /etc/hosts 内容
     * @param hostname        主机名（用于排除）
     * @param hostLanIp       宿主机 LAN IP
     * @param domainsToMove   要从回环行移到 LAN IP 行的域名集合（小写）
     */
    private String buildNewHostsContent(String originalContent, String hostname, String hostLanIp,
                                        Set<String> domainsToMove) {
        StringBuilder result = new StringBuilder();
        List<String> movedDomains = new ArrayList<>();
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

            // 处理回环行：保留系统别名、hostname 和未选中的域名，仅移走选中的域名
            String ip = parts[0];
            List<String> keepDomains = new ArrayList<>();
            StringBuilder commentPart = new StringBuilder();
            boolean inComment = false;
            for (int i = 1; i < parts.length; i++) {
                String token = parts[i];
                String d = token.trim().toLowerCase();
                // 遇到 # 行内注释：剩余部分作为注释保留，不再当域名处理
                if (!inComment && d.startsWith("#")) {
                    inComment = true;
                }
                if (inComment) {
                    if (commentPart.length() > 0) commentPart.append(" ");
                    commentPart.append(token);
                    continue;
                }
                if (d.isEmpty()) continue;
                if (systemAndHostname.contains(d)) {
                    // 系统别名或 hostname：保留
                    keepDomains.add(token);
                } else if (domainsToMove.contains(d)) {
                    // 用户选中的待改域名：移走
                    movedDomains.add(d);
                } else {
                    // 未选中的域名（如广告屏蔽条目）：保留在原行
                    keepDomains.add(token);
                }
            }

            if (keepDomains.isEmpty()) {
                // 该行所有域名都被移走，跳过此行（注释也一并舍弃，因为注释绑定于这些域名）
                continue;
            }
            StringBuilder lineBuilder = new StringBuilder();
            lineBuilder.append(ip).append(" ").append(String.join(" ", keepDomains));
            if (commentPart.length() > 0) {
                lineBuilder.append(" ").append(commentPart);
            }
            lineBuilder.append("\n");
            result.append(lineBuilder);
        }

        // 末尾追加新行：hostLanIp + 所有被移走的域名（去重，保留顺序）
        if (!movedDomains.isEmpty()) {
            Set<String> seen = new java.util.LinkedHashSet<>();
            for (String d : movedDomains) {
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
                // 遇到 # 行内注释立即停止，避免把 #S302 等来源标记当作域名
                if (domain.startsWith("#")) {
                    break;
                }
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
                    String d = parts[i].trim().toLowerCase();
                    if (d.startsWith("#")) break;
                    alreadyOnLanIp.add(d);
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
     *
     * <p>使用 7 参数重载（显式传入超时），与 SshUtil.executeCommand(..., long) 一致，
     * 便于单元测试通过 anyLong() 匹配器统一 stub。</p>
     */
    private String execCommand(Host host, SshCredentials creds, String command) {
        SshUtil.CommandResult result = sshUtil.executeCommand(
                host.getIpAddress(), host.getSshPort(), host.getSshUser(),
                creds.privateKey, creds.password, command, SSH_DEFAULT_TIMEOUT_MS);
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
