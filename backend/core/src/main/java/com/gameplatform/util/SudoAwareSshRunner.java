package com.gameplatform.util;

import com.gameplatform.hostaccess.CommandResult;
import com.gameplatform.hostaccess.RemoteHost;
import lombok.RequiredArgsConstructor;
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
@Component
@RequiredArgsConstructor
public class SudoAwareSshRunner {

    private final RemoteHost remoteHost;

    /** 以登录账号身份执行命令（不提权） */
    public CommandResult run(Long hostId, String command, long timeoutMs) {
        return remoteHost.exec(hostId, command, timeoutMs);
    }

    /** 以 root 身份执行命令（root 登录直跑，否则 sudo 提权） */
    public CommandResult runPrivileged(Long hostId, String command, long timeoutMs) {
        return remoteHost.execPrivileged(hostId, command, timeoutMs);
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
        remoteHost.writeFilePrivileged(hostId, path, content, timeoutMs);
    }
}
