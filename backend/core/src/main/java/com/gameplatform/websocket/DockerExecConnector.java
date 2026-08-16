package com.gameplatform.websocket;

import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.entity.Host;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.sshd.client.channel.ChannelExec;
import org.springframework.stereotype.Component;

/**
 * docker exec 交互终端连接器（共享组件）。
 *
 * <p>统一「SSH 建连 + docker exec 交互通道」逻辑，供
 * {@link DockerExecWebSocketHandler}（docker exec 终端页）与
 * {@link InstanceConsoleWebSocketHandler}（实例控制台 docker 分支）复用，
 * 避免两处各写一套 pty/TERM 配置。
 *
 * <p>交互命令使用 {@code TERM=xterm-256color} 与 {@code ${SHELL:-/bin/sh}}
 * 回退：容器内无 bash 时自动使用 sh。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DockerExecConnector {

    private final DeploymentAccess deployAccess;

    /**
     * 建立 SSH 连接并打开 docker exec 交互通道（分配 pty）。
     *
     * @return SSH 连接 + 已打开的 exec 通道；调用方负责在关闭会话时一并释放
     */
    public OpenedExec openInteractive(Host host, String containerId) throws Exception {
        DeploymentAccess.SshConnection ssh = deployAccess.connect(host);
        // SSH exec channel 的 stdin 是管道，docker CLI 检测 isatty(stdin) 失败 →
        // -t 被忽略（"the input device is not a TTY"）→ 容器内 sh 非交互 + stdin EOF 立即退出。
        // 用宿主机 script(1) 包一层 pty：docker exec 的 stdin 变成 pty slave，-it 生效保持交互。
        String execCommand = String.format(
                "script -qec 'docker exec -i %s sh -c \"TERM=xterm-256color; exec ${SHELL:-/bin/sh}\"' /dev/null",
                containerId);
        ChannelExec channel = ssh.session().createExecChannel(execCommand);
        // 分配 pty：docker exec -it 需要 TTY 才能保持交互（清屏/终端控制符/命令回显）
        channel.setPtyType("xterm");
        channel.setPtyColumns(120);
        channel.setPtyLines(30);
        channel.open().verify(10000, java.util.concurrent.TimeUnit.MILLISECONDS);
        return new OpenedExec(ssh, channel);
    }

    /** SSH 连接 + 已打开的 exec 通道 */
    public record OpenedExec(DeploymentAccess.SshConnection ssh, ChannelExec channel) {
    }
}
