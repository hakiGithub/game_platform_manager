package com.gameplatform.steam302;

import com.gameplatform.util.SudoAwareSshRunner;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.config.Steam302Properties;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.util.SshUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Steam302 hosts 劫持同步器（ADR-0019 修订）
 *
 * <p>CLI 改写 /etc/hosts 采用「写临时文件 + rename 原子替换」，在 Docker 单文件挂载
 * （-v /etc/hosts:/etc/hosts）下 rename 对挂载点必然 EBUSY（日志"重命名 hosts 文件最终失败"），
 * 因此容器不挂载 /etc/hosts，改由平台在宿主机侧管理劫持条目：</p>
 *
 * <ul>
 *   <li>启动/安装后：解析数据目录中生成的 Caddyfile 站点块（行首 https:// 的地址行，
 *       剥离端口、丢弃通配符），以 {@code <目标IP> 域名 <标记>} 写入 /etc/hosts；</li>
 *   <li>停止时：移除全部标记行（含两种标记），恢复直连。</li>
 * </ul>
 *
 * <p><b>目标 IP 二态</b>（数据目录 flag 文件 {@code .platform-container-share} 控制）：
 * 默认 {@code 127.0.0.1}（服务宿主机本机进程）；开启「容器共享加速」后改为宿主机 LAN IP
 * （caddy 监听 0.0.0.0，本机与 bridge 容器同走一条目，无解析歧义），标记 {@code #S302-LAN}。
 * 条目与 Caddyfile 启用的服务严格一致——禁用的服务域名不写 hosts，
 * 避免其解析到代理却无路由而彻底不可达。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Steam302HostsSync {

    /** 本机模式标记 */
    public static final String MARKER_LOCAL = "#S302";
    /** 容器共享模式标记 */
    public static final String MARKER_LAN = "#S302-LAN";

    /** 容器共享加速 flag 文件（存在即开启），随数据卷持久化 */
    public static final String CONTAINER_SHARE_FLAG = ".platform-container-share";

    private static final String ETC_HOSTS = "/etc/hosts";
    /** Caddyfile 站点地址行（行首 https://，可一行多域名，以 { 结尾） */
    private static final Pattern SITE_LINE =
            Pattern.compile("(?m)^((?:https?://\\S+[ \\t]+)*https?://\\S+)[ \\t]*\\{");
    private static final Pattern HOST_PORT = Pattern.compile("^https?://([^/:]+)(:\\d+)?$");

    private final SudoAwareSshRunner runner;
    private final Steam302Properties props;
    private final HostMapper hostMapper;

    /**
     * 从 Caddyfile 提取启用服务的域名集合并同步到 /etc/hosts（目标 IP 由 flag 决定）
     *
     * @return 本次实际写入的域名数（内容无变化时返回 -1）
     */
    public int syncFromCaddyfile(Long hostId) {
        Set<String> domains = readActiveDomains(hostId);
        if (domains.isEmpty()) {
            throw new BusinessException("Caddyfile 中未解析到任何代理域名，跳过 hosts 同步");
        }
        boolean shareMode = isContainerShareEnabled(hostId);
        String targetIp = shareMode ? hostLanIp(hostId) : "127.0.0.1";
        return writeHosts(hostId, domains, targetIp, shareMode ? MARKER_LAN : MARKER_LOCAL);
    }

    /** 容器共享加速是否开启（flag 文件存在） */
    public boolean isContainerShareEnabled(Long hostId) {
        String flagPath = props.getDataDir() + "/" + CONTAINER_SHARE_FLAG;
        SshUtil.CommandResult r = runner.runPrivileged(hostId, "test -f " + flagPath + " && echo YES", 10_000);
        return r.isSuccess() && r.getOutput().contains("YES");
    }

    /** 清除本工具写入的全部劫持条目（两种标记都清） */
    public void clear(Long hostId) {
        String current = readHosts(hostId);
        if (!current.contains(MARKER_LOCAL) && !current.contains(MARKER_LAN)) {
            return;
        }
        writeHostsFile(hostId, stripManagedLines(current));
        log.info("已清除 Steam302 hosts 劫持条目: hostId={}", hostId);
    }

    private Set<String> readActiveDomains(Long hostId) {
        String caddyfilePath = props.getDataDir() + "/steamcommunity_302.caddy.json";
        SshUtil.CommandResult r = runner.run(hostId, "cat " + caddyfilePath, 30_000);
        if (!r.isSuccess()) {
            throw new BusinessException("读取 Caddyfile 失败: " + caddyfilePath);
        }
        Set<String> domains = new LinkedHashSet<>();
        Matcher m = SITE_LINE.matcher(r.getOutput());
        while (m.find()) {
            for (String site : m.group(1).split("[ \t]+")) {
                Matcher h = HOST_PORT.matcher(site);
                if (h.matches()) {
                    String host = h.group(1);
                    // hosts 无法表达通配符，丢弃 *.github.io 之类
                    if (!host.startsWith("*") && host.contains(".")) {
                        domains.add(host);
                    }
                }
            }
        }
        return domains;
    }

    /** 宿主机 LAN IP（来自主机记录） */
    private String hostLanIp(Long hostId) {
        Host host = hostMapper.selectById(hostId);
        if (host == null || host.getIpAddress() == null || host.getIpAddress().isBlank()) {
            throw new BusinessException("主机记录缺少 IP 地址，无法启用容器共享加速");
        }
        return host.getIpAddress();
    }

    /**
     * 以「现有内容去掉两种标记行 + 新劫持条目」整体覆盖 /etc/hosts，内容无变化时跳过
     */
    private int writeHosts(Long hostId, Set<String> domains, String targetIp, String marker) {
        String current = readHosts(hostId);
        StringBuilder sb = new StringBuilder(stripManagedLines(current));
        for (String d : domains) {
            sb.append(targetIp).append('\t').append(d).append('\t').append(marker).append('\n');
        }
        String target = sb.toString();
        if (target.equals(current) || target.equals(current + "\n")) {
            return -1;
        }
        writeHostsFile(hostId, target);
        return domains.size();
    }

    /** 去掉两种标记行（保留其余内容） */
    private String stripManagedLines(String content) {
        StringBuilder sb = new StringBuilder();
        for (String line : content.split("\n", -1)) {
            if (!line.contains(MARKER_LOCAL) && !line.contains(MARKER_LAN)) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    private void writeHostsFile(Long hostId, String content) {
        runner.writeFilePrivileged(hostId, ETC_HOSTS, content, 30_000);
    }

    private String readHosts(Long hostId) {
        SshUtil.CommandResult r = runner.run(hostId, "cat " + ETC_HOSTS, 15_000);
        if (!r.isSuccess()) {
            throw new BusinessException("读取 /etc/hosts 失败: " + r.getError());
        }
        return normalize(r.getOutput());
    }

    /** 统一换行，避免远程文件以 \r\n 返回时比对失真 */
    private String normalize(String s) {
        return s == null ? "" : s.replace("\r\n", "\n");
    }
}
