package com.gameplatform.util;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.deploy.HostCredentials;
import com.gameplatform.util.SshUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * sudo 感知的远程命令执行器（原 Steam302 专用，ADR-0021 上提为共享组件）
 *
 * <p>提权策略（ADR-0019）：
 * <ul>
 *   <li>SSH 账号是 root → 直接执行；</li>
 *   <li>非 root 且主机存有 SSH 密码 → 复用该密码经 {@code sudo -S} 提权
 *       （运维惯例 SSH 密码 = sudo 密码）；</li>
 *   <li>非 root 且仅密钥登录 → 无法提权，抛出带明确指引的异常。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SudoAwareSshRunner {

    private static final long PRIVILEGE_CHECK_TIMEOUT_MS = 10_000;

    private final SshUtil sshUtil;
    private final DeploymentAccess deployAccess;

    /** 以登录账号身份执行命令（不提权） */
    public SshUtil.CommandResult run(Long hostId, String command, long timeoutMs) {
        HostCredentials conn = deployAccess.credentials(hostId);
        return sshUtil.executeCommand(conn.host(), conn.port(), conn.username(),
                conn.privateKey(), conn.password(), command, timeoutMs);
    }

    /** 以 root 身份执行命令（root 登录直跑，否则 sudo 提权） */
    public SshUtil.CommandResult runPrivileged(Long hostId, String command, long timeoutMs) {
        HostCredentials conn = deployAccess.credentials(hostId);
        if (isRoot(conn)) {
            return sshUtil.executeCommand(conn.host(), conn.port(), conn.username(),
                    conn.privateKey(), conn.password(), command, timeoutMs);
        }
        if (conn.password() == null || conn.password().isBlank()) {
            throw new BusinessException("主机 " + conn.host() + " 使用密钥登录且账号非 root，"
                    + "无法执行需要 root 权限的操作；请在主机配置中补充密码（sudo 提权需要）");
        }
        // sudo 的 -S 从 stdin 读密码；显式在命令末尾追加 `exit $?`，
        // 避免 sh -c 包装时只回传最后一条语句的状态，造成写入失败假成功。
        String wrapped = "printf '%s\\n' '" + escape(conn.password()) + "'"
                + " | sudo -S -p '' sh -c '" + escape(command) + "; exit $?'";
        return sshUtil.executeCommand(conn.host(), conn.port(), conn.username(),
                conn.privateKey(), conn.password(), wrapped, timeoutMs);
    }

    /**
     * 以 root 身份通过 base64 写文件（规避引号/换行转义问题），
     * 内容先落到同目录临时文件，再用输入重定向覆盖目标文件。
     *
     * <p>不能使用 {@code mv} 覆盖：目标文件可能是 Docker 绑定挂载点
     * （例如 {@code /etc/hosts}），对挂载点执行 rename 会返回
     * {@code Device or resource busy}。在 WSL 的 Docker 单文件绑定挂载下，
     * {@code cp} 覆盖也可能返回 {@code File exists}；使用
     * {@code cat tmp > target} 保持目标 inode 不变，写入后显式回读校验。</p>
     */
    public void writeFilePrivileged(Long hostId, String path, String content, long timeoutMs) {
        String b64 = java.util.Base64.getEncoder().encodeToString(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String expectedSha = sha256(content);
        String tmpPath = path + ".platform-write.tmp";
        String cmd = "printf %s " + b64 + " | base64 -d > " + tmpPath
                + " && sha256sum " + tmpPath
                + " && tee " + path + " < " + tmpPath + " > /dev/null"
                + " && sha256sum " + path
                + " && rm -f " + tmpPath
                + " && test \"$(sha256sum " + path + " | awk '{print $1}')\" = \"" + expectedSha + "\"";
        SshUtil.CommandResult result = runPrivileged(hostId, cmd, timeoutMs);
        if (!result.isSuccess()) {
            throw new BusinessException("写入远程文件失败: " + path
                    + " - " + firstLine(result.getError())
                    + "，expectedSha=" + expectedSha
                    + "，stdout=" + summarize(result.getOutput())
                    + "（已尝试 tee 覆盖并回读校验）");
        }
    }

    private String sha256(String content) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }

    private boolean isRoot(HostCredentials conn) {
        SshUtil.CommandResult r = sshUtil.executeCommand(conn.host(), conn.port(), conn.username(),
                conn.privateKey(), conn.password(), "id -u", PRIVILEGE_CHECK_TIMEOUT_MS);
        return r.isSuccess() && "0".equals(r.getOutput().trim());
    }

    /** 单引号包裹转义 */
    private String escape(String s) {
        return s.replace("'", "'\\''");
    }

    private String firstLine(String s) {
        if (s == null || s.isBlank()) {
            return "未知错误";
        }
        return s.strip().split("\n")[0];
    }

    private String summarize(String s) {
        if (s == null || s.isBlank()) {
            return "(empty)";
        }
        String oneLine = s.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= 240 ? oneLine : oneLine.substring(0, 240) + "...";
    }
}
