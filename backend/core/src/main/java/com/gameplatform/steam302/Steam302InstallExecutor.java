package com.gameplatform.steam302;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.config.Steam302Properties;
import com.gameplatform.util.SshUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Steam302 安装执行器（任务中心异步调用）
 *
 * <p>步骤：探测 Docker → 解析镜像（本地有则免拉取）→ 建数据目录 →
 * 起容器（host 网络 + /etc/hosts 读写挂载 + 数据卷）→ 等待 CLI 自签证书生成 →
 * 按发行版信任 CA 到宿主机信任库。docker 命令权限被拒时自动经 sudo 提权重试。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class Steam302InstallExecutor {

    /** docker pull 单独放宽（国内网络拉镜像较慢） */
    private static final long PULL_TIMEOUT_MS = 10 * 60 * 1000L;
    /** docker 命令的常规超时 */
    private static final long DOCKER_TIMEOUT_MS = 60_000;
    /** 信任库安装命令可能较慢（重建信任链） */
    private static final long TRUST_TIMEOUT_MS = 120_000;

    private final Steam302Properties props;
    private final SudoAwareSshRunner runner;
    private final Steam302HostsSync hostsSync;

    /**
     * 安装进度监听（TaskContext 适配）
     */
    public interface ProgressListener {
        void onProgress(int percent, String message);

        void onLog(String message);

        boolean isCancelled();
    }

    public void execute(Long hostId, ProgressListener progress) {
        String image = props.getImage();
        String dataDir = props.getDataDir();
        String container = props.getContainerName();

        progress.onProgress(5, "探测 Docker 环境");
        SshUtil.CommandResult dockerVersion = runDocker(hostId, "docker version --format '{{.Server.Version}}'", DOCKER_TIMEOUT_MS);
        if (!dockerVersion.isSuccess()) {
            throw new BusinessException("主机未安装 Docker 或当前账号无权访问（"
                    + firstLine(dockerVersion.getError()) + "），请先安装 Docker");
        }
        progress.onLog("Docker 版本: " + firstLine(dockerVersion.getOutput()));

        progress.onProgress(15, "解析镜像 " + image);
        SshUtil.CommandResult inspect = runDocker(hostId, "docker image inspect " + image, DOCKER_TIMEOUT_MS);
        if (!inspect.isSuccess()) {
            progress.onLog("本地无镜像，开始拉取（可能耗时较长）...");
            SshUtil.CommandResult pull = runDocker(hostId, "docker pull " + image, PULL_TIMEOUT_MS);
            if (!pull.isSuccess()) {
                throw new BusinessException("拉取镜像失败: " + image + " - " + firstLine(pull.getError()));
            }
        } else {
            progress.onLog("使用本地已有镜像");
        }

        progress.onProgress(40, "准备数据目录 " + dataDir);
        SshUtil.CommandResult mkdir = runner.runPrivileged(hostId,
                "mkdir -p " + dataDir, DOCKER_TIMEOUT_MS);
        if (!mkdir.isSuccess()) {
            throw new BusinessException("创建数据目录失败: " + dataDir + " - " + firstLine(mkdir.getError()));
        }

        progress.onProgress(50, "清理旧容器");
        runDocker(hostId, "docker rm -f " + container, DOCKER_TIMEOUT_MS);

        progress.onProgress(55, "启动容器");
        // 注意：不挂载 /etc/hosts——CLI 的 rename 原子替换对单文件挂载点必然 EBUSY，
        // hosts 劫持由 Steam302HostsSync 在宿主机侧管理（ADR-0019 修订）
        String runCmd = "docker run -d --name " + container
                + " --restart unless-stopped"
                + " --network host"
                + " -v " + dataDir + ":/data"
                + " " + image;
        SshUtil.CommandResult run = runDocker(hostId, runCmd, DOCKER_TIMEOUT_MS);
        if (!run.isSuccess()) {
            throw new BusinessException("启动容器失败: " + firstLine(run.getError()));
        }

        progress.onProgress(70, "等待证书生成");
        String caPath = dataDir + "/steamcommunityCA.pem";
        boolean certReady = false;
        long deadline = System.currentTimeMillis() + props.getCertWaitTimeoutSeconds() * 1000;
        while (System.currentTimeMillis() < deadline) {
            if (progress.isCancelled()) {
                throw new BusinessException("安装任务已取消");
            }
            SshUtil.CommandResult check = runner.run(hostId, "test -f " + caPath, 10_000);
            if (check.isSuccess()) {
                certReady = true;
                break;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new BusinessException("安装任务被中断");
            }
        }
        if (!certReady) {
            throw new BusinessException("等待证书生成超时（" + props.getCertWaitTimeoutSeconds()
                    + "s），请检查容器日志: docker logs " + container);
        }

        progress.onProgress(85, "信任 CA 证书到宿主机");
        String trustWarning = trustCertificate(hostId, dataDir);
        if (trustWarning != null) {
            progress.onLog(trustWarning);
        }
        progress.onLog(trustDockerDaemon(hostId, dataDir));

        progress.onProgress(92, "写入 hosts 劫持条目");
        awaitProxyReady(hostId);
        int synced = hostsSync.syncFromCaddyfile(hostId);
        progress.onLog(synced >= 0 ? "已写入 " + synced + " 条 hosts 劫持条目"
                : "hosts 劫持条目已就绪（无变化）");

        progress.onProgress(100, "安装完成");
        progress.onLog("Steam302 部署完成，容器: " + container + "，数据目录: " + dataDir);
    }

    /**
     * 将 CA 证书装入宿主机信任库；返回 null 表示成功，否则返回可容忍的警告文案
     */
    private String trustCertificate(Long hostId, String dataDir) {
        String caPath = dataDir + "/steamcommunityCA.pem";
        SshUtil.CommandResult deb = runner.runPrivileged(hostId,
                "test -d /usr/local/share/ca-certificates && echo DEB", 10_000);
        SshUtil.CommandResult rhel = runner.runPrivileged(hostId,
                "test -d /etc/pki/ca-trust/source/anchors && echo RHEL", 10_000);

        String trustCmd;
        String distro;
        if (deb.isSuccess() && deb.getOutput().contains("DEB")) {
            distro = "Debian/Ubuntu";
            trustCmd = "cp " + caPath + " /usr/local/share/ca-certificates/steam302-steamcommunityCA.crt"
                    + " && update-ca-certificates";
        } else if (rhel.isSuccess() && rhel.getOutput().contains("RHEL")) {
            distro = "RHEL/CentOS";
            trustCmd = "cp " + caPath + " /etc/pki/ca-trust/source/anchors/steam302-steamcommunityCA.pem"
                    + " && update-ca-trust extract";
        } else {
            return "未识别的发行版信任库，CA 证书未自动信任；请手动安装 " + caPath;
        }

        SshUtil.CommandResult trust = runner.runPrivileged(hostId, trustCmd, TRUST_TIMEOUT_MS);
        if (!trust.isSuccess()) {
            return "CA 证书信任失败（" + distro + "）: " + firstLine(trust.getError())
                    + "；请手动安装 " + caPath;
        }
        log.info("CA 证书已信任: hostId={}, distro={}", hostId, distro);
        return null;
    }

    /**
     * 为 dockerd 单独建立 certs.d 信任：Go 程序只在进程启动时加载系统信任库，
     * 主机上先于本安装存在的 dockerd 不会感知新装的 CA（pull 报 x509 unknown authority）。
     * certs.d 由 docker 按次读取，无需重启 daemon。返回可展示的结果文案。
     */
    private String trustDockerDaemon(Long hostId, String dataDir) {
        String caPath = dataDir + "/steamcommunityCA.pem";
        SshUtil.CommandResult hasDocker = runner.run(hostId, "test -d /etc/docker && echo YES", 10_000);
        if (!hasDocker.isSuccess() || !hasDocker.getOutput().contains("YES")) {
            return "未检测到 /etc/docker，跳过 dockerd 信任配置";
        }
        String cmd = "for r in registry-1.docker.io auth.docker.io production.cloudflare.docker.com; do"
                + " mkdir -p /etc/docker/certs.d/$r && cp " + caPath + " /etc/docker/certs.d/$r/ca.crt; done";
        SshUtil.CommandResult r = runner.runPrivileged(hostId, cmd, 30_000);
        return r.isSuccess() ? "已为 dockerd 配置 certs.d 信任（registry-1.docker.io 等 3 个域）"
                : "dockerd certs.d 信任配置失败: " + firstLine(r.getError());
    }

    /**
     * 等待代理端口就绪（host 网络下工具的端口转发监听 80/443，随后 Caddyfile 才是最终内容）
     */
    private void awaitProxyReady(Long hostId) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            SshUtil.CommandResult r = runner.run(hostId,
                    "(ss -tln 2>/dev/null || netstat -tln 2>/dev/null) | grep -q ':443 '", 10_000);
            if (r.isSuccess() && r.getExitCode() == 0) {
                return;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("等待 443 监听超时（30s），继续执行 hosts 同步: hostId={}", hostId);
    }

    /**
     * 执行 docker 命令：先直跑，权限被拒（不在 docker 组）时经 sudo 提权重试
     */
    private SshUtil.CommandResult runDocker(Long hostId, String command, long timeoutMs) {
        SshUtil.CommandResult direct = runner.run(hostId, command, timeoutMs);
        if (direct.isSuccess()) {
            return direct;
        }
        String err = direct.getError() == null ? "" : direct.getError();
        if (err.contains("permission denied") || err.contains("Permission denied")) {
            return runner.runPrivileged(hostId, command, timeoutMs);
        }
        return direct;
    }

    private String firstLine(String s) {
        if (s == null || s.isBlank()) {
            return "未知错误";
        }
        return s.strip().split("\n")[0];
    }
}
