package com.gameplatform.adapter;

import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.Host;
import com.gameplatform.util.SshUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * LinuxGSM部署适配器
 * 封装LinuxGSM脚本全量命令，支持Minecraft、Palworld等游戏服务器
 *
 * LinuxGSM命令参考：
 * - ./gameserver install    - 安装游戏服务器
 * - ./gameserver start      - 启动服务器
 * - ./gameserver stop       - 停止服务器
 * - ./gameserver restart    - 重启服务器
 * - ./gameserver update     - 更新游戏服务器
 * - ./gameserver force-update - 强制更新
 * - ./gameserver validate   - 验证游戏文件
 * - ./gameserver monitor    - 监控服务器状态
 * - ./gameserver details    - 显示服务器详情
 * - ./gameserver postdetails - 显示服务器详情（上传）
 * - ./gameserver backup     - 备份服务器
 * - ./gameserver console    - 进入控制台
 * - ./gameserver debug      - 调试模式启动
 * - ./gameserver send       - 发送命令到控制台
 * - ./gameserver update-lgsm - 更新LinuxGSM
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
public class LinuxGsmAdapter extends AbstractDeployAdapter {

    // LinuxGSM支持的游戏服务器简称映射
    private static final Map<String, String> GAME_SHORTNAME_MAP = new HashMap<>();

    static {
        // Minecraft系列
        GAME_SHORTNAME_MAP.put("minecraft", "mcserver");
        GAME_SHORTNAME_MAP.put("minecraft-bedrock", "mcbserver");
        GAME_SHORTNAME_MAP.put("minecraft-paper", "mcserver");
        GAME_SHORTNAME_MAP.put("minecraft-forge", "mcserver");

        // 生存游戏
        GAME_SHORTNAME_MAP.put("palworld", "pwserver");
        GAME_SHORTNAME_MAP.put("valheim", "vhserver");
        GAME_SHORTNAME_MAP.put("rust", "rustserver");
        GAME_SHORTNAME_MAP.put("ark", "arkserver");
        GAME_SHORTNAME_MAP.put("7daystodie", "sdtdserver");

        // FPS游戏
        GAME_SHORTNAME_MAP.put("csgo", "csgoserver");
        GAME_SHORTNAME_MAP.put("cs2", "cs2server");
        GAME_SHORTNAME_MAP.put("tf2", "tf2server");
        GAME_SHORTNAME_MAP.put("gmod", "gmodserver");

        // 其他热门游戏
        GAME_SHORTNAME_MAP.put("terraria", "terrariaserver");
        GAME_SHORTNAME_MAP.put("starbound", "sbserver");
        GAME_SHORTNAME_MAP.put("satisfactory", "sfserver");
        GAME_SHORTNAME_MAP.put("factorio", "fctrserver");
    }

    @Override
    public DeployType getDeployType() {
        return DeployType.LINUX_GSM;
    }

    @Override
    public boolean validateEnvironment(Long hostId, Map<String, Object> config) {
        Host host = getHost(hostId);
        if (host == null) {
            log.error("主机不存在: {}", hostId);
            return false;
        }

        // 检查必要的依赖
        String[] requiredCommands = {"curl", "wget", "tar", "gzip", "jq"};
        for (String cmd : requiredCommands) {
            SshUtil.CommandResult result = executeCommand(host, "which " + cmd);
            if (!result.isSuccess() || result.getOutput().trim().isEmpty()) {
                log.warn("主机 {} 缺少必要依赖: {}", hostId, cmd);
                // 某些依赖不是必须的，仅记录警告
            }
        }

        // 检查磁盘空间（至少需要5GB）
        String installPath = getConfigString(config, "installPath", "/home/gameserver");
        double availableSpace = getAvailableDiskSpace(host, installPath);
        if (availableSpace < 5) {
            log.error("主机 {} 磁盘空间不足: {}GB < 5GB", hostId, availableSpace);
            return false;
        }

        // 检查内存（至少需要2GB）
        long availableMemory = getAvailableMemory(host);
        if (availableMemory < 2048) {
            log.error("主机 {} 内存不足: {}MB < 2048MB", hostId, availableMemory);
            return false;
        }

        // 检查端口是否被占用
        int gamePort = getConfigInt(config, "gamePort", 0);
        if (gamePort > 0 && isPortInUse(host, gamePort)) {
            log.error("主机 {} 端口 {} 已被占用", hostId, gamePort);
            return false;
        }

        return true;
    }

    @Override
    public boolean preDeploy(Long instanceId, Map<String, Object> config, DeployProgressCallback callback) {
        notifyStageStart(callback, "PRE_DEPLOY", "开始预部署准备");

        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            notifyError(callback, "实例或主机不存在", "PRE_DEPLOY", false);
            return false;
        }

        Host host = info.host();
        String installPath = getConfigString(config, "installPath", "/home/gameserver/instance_" + instanceId);
        String gameType = getConfigString(config, "gameType", "minecraft");

        try {
            notifyProgress(callback, 10, "PRE_DEPLOY", "创建安装目录");
            // 创建安装目录
            SshUtil.CommandResult mkdirResult = executeCommand(host, "mkdir -p " + installPath);
            if (!mkdirResult.isSuccess()) {
                notifyError(callback, "创建安装目录失败: " + mkdirResult.getError(), "PRE_DEPLOY", false);
                return false;
            }

            notifyProgress(callback, 30, "PRE_DEPLOY", "安装LinuxGSM依赖");
            // 安装必要的依赖
            executeCommand(host, "sudo dpkg --add-architecture i386 2>/dev/null || true");
            executeCommand(host, "sudo apt-get update -qq");
            String deps = "bc binutils bsdmainutils bzip2 ca-certificates cpio curl distro-info file gzip " +
                    "hostname jq lib32gcc-s1 lib32stdc++6 netcat-openbsd python3 tar tmux unzip util-linux xz-utils";
            executeCommand(host, "sudo apt-get install -y " + deps, 300000);

            notifyProgress(callback, 50, "PRE_DEPLOY", "下载LinuxGSM脚本");
            // 下载LinuxGSM
            String shortname = getGameShortname(gameType);
            String lgsmScript = installPath + "/" + shortname;

            SshUtil.CommandResult downloadResult = executeCommand(host,
                    String.format("cd %s && curl -Lo %s https://linuxgsm.sh && chmod +x %s",
                            installPath, shortname, shortname), 120000);

            if (!downloadResult.isSuccess()) {
                notifyError(callback, "下载LinuxGSM失败: " + downloadResult.getError(), "PRE_DEPLOY", false);
                return false;
            }

            notifyProgress(callback, 80, "PRE_DEPLOY", "初始化LinuxGSM");
            // 初始化LinuxGSM
            SshUtil.CommandResult initResult = executeCommand(host,
                    String.format("cd %s && ./%s", installPath, shortname), 60000);

            if (!initResult.isSuccess()) {
                notifyError(callback, "初始化LinuxGSM失败: " + initResult.getError(), "PRE_DEPLOY", false);
                return false;
            }

            notifyProgress(callback, 100, "PRE_DEPLOY", "预部署完成");
            notifyStageComplete(callback, "PRE_DEPLOY", true, "预部署准备完成");
            return true;

        } catch (Exception e) {
            log.error("预部署失败", e);
            notifyError(callback, "预部署异常: " + e.getMessage(), "PRE_DEPLOY", false);
            return false;
        }
    }

    @Override
    public boolean deploy(Long instanceId, Map<String, Object> config, DeployProgressCallback callback) {
        notifyStageStart(callback, "DEPLOY", "开始部署游戏服务器");

        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            notifyError(callback, "实例或主机不存在", "DEPLOY", false);
            return false;
        }

        Host host = info.host();
        String installPath = getConfigString(config, "installPath", "/home/gameserver/instance_" + instanceId);
        String gameType = getConfigString(config, "gameType", "minecraft");
        String shortname = getGameShortname(gameType);

        try {
            notifyProgress(callback, 10, "DEPLOY", "执行游戏服务器安装");
            // 执行安装（这可能需要很长时间）
            SshUtil.CommandResult installResult = executeCommand(host,
                    String.format("cd %s && ./%s auto-install", installPath, shortname), 1800000);

            if (!installResult.isSuccess()) {
                notifyError(callback, "安装游戏服务器失败: " + installResult.getError(), "DEPLOY", false);
                return false;
            }

            notifyProgress(callback, 60, "DEPLOY", "配置游戏服务器");
            // 应用自定义配置
            if (!applyGameConfig(host, installPath, shortname, config)) {
                notifyError(callback, "应用游戏配置失败", "DEPLOY", true);
                // 配置失败不阻止部署继续
            }

            notifyProgress(callback, 80, "DEPLOY", "验证安装");
            // 验证安装
            SshUtil.CommandResult detailsResult = executeCommand(host,
                    String.format("cd %s && ./%s details", installPath, shortname), 30000);

            if (!detailsResult.isSuccess()) {
                notifyError(callback, "验证安装失败: " + detailsResult.getError(), "DEPLOY", false);
                return false;
            }

            // 更新实例信息
            GameInstance instance = info.instance();
            instance.setInstallPath(installPath);
            instance.setStartCommand(String.format("cd %s && ./%s start", installPath, shortname));
            instance.setStopCommand(String.format("cd %s && ./%s stop", installPath, shortname));
            instanceMapper.updateById(instance);

            notifyProgress(callback, 100, "DEPLOY", "部署完成");
            notifyStageComplete(callback, "DEPLOY", true, "游戏服务器部署成功");
            return true;

        } catch (Exception e) {
            log.error("部署失败", e);
            notifyError(callback, "部署异常: " + e.getMessage(), "DEPLOY", false);
            return false;
        }
    }

    @Override
    public boolean start(Long instanceId, Map<String, Object> config) {
        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            return false;
        }

        Host host = info.host();
        String installPath = info.instance().getInstallPath();
        String gameType = getConfigString(config, "gameType", "minecraft");
        String shortname = getGameShortname(gameType);

        if (installPath == null || installPath.isEmpty()) {
            log.error("实例 {} 未设置安装路径", instanceId);
            return false;
        }

        SshUtil.CommandResult result = executeCommand(host,
                String.format("cd %s && ./%s start", installPath, shortname), 60000);

        return result.isSuccess() && (result.getOutput().contains("started") || result.getOutput().contains("already running"));
    }

    @Override
    public boolean stop(Long instanceId, Map<String, Object> config) {
        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            return false;
        }

        Host host = info.host();
        String installPath = info.instance().getInstallPath();
        String gameType = getConfigString(config, "gameType", "minecraft");
        String shortname = getGameShortname(gameType);

        if (installPath == null || installPath.isEmpty()) {
            log.error("实例 {} 未设置安装路径", instanceId);
            return false;
        }

        SshUtil.CommandResult result = executeCommand(host,
                String.format("cd %s && ./%s stop", installPath, shortname), 60000);

        return result.isSuccess() && (result.getOutput().contains("stopped") || result.getOutput().contains("not running"));
    }

    @Override
    public boolean restart(Long instanceId, Map<String, Object> config) {
        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            return false;
        }

        Host host = info.host();
        String installPath = info.instance().getInstallPath();
        String gameType = getConfigString(config, "gameType", "minecraft");
        String shortname = getGameShortname(gameType);

        if (installPath == null || installPath.isEmpty()) {
            log.error("实例 {} 未设置安装路径", instanceId);
            return false;
        }

        SshUtil.CommandResult result = executeCommand(host,
                String.format("cd %s && ./%s restart", installPath, shortname), 120000);

        return result.isSuccess() && result.getOutput().contains("restarted");
    }

    @Override
    public boolean healthCheck(Long instanceId, Map<String, Object> config) {
        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            return false;
        }

        Host host = info.host();
        String installPath = info.instance().getInstallPath();
        String gameType = getConfigString(config, "gameType", "minecraft");
        String shortname = getGameShortname(gameType);

        if (installPath == null || installPath.isEmpty()) {
            return false;
        }

        // 使用monitor命令检查状态
        SshUtil.CommandResult result = executeCommand(host,
                String.format("cd %s && ./%s monitor", installPath, shortname), 30000);

        return result.isSuccess() && result.getOutput().contains("OK");
    }

    @Override
    public boolean update(Long instanceId, Map<String, Object> config, DeployProgressCallback callback) {
        notifyStageStart(callback, "UPDATE", "开始更新游戏服务器");

        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            notifyError(callback, "实例或主机不存在", "UPDATE", false);
            return false;
        }

        Host host = info.host();
        String installPath = info.instance().getInstallPath();
        String gameType = getConfigString(config, "gameType", "minecraft");
        String shortname = getGameShortname(gameType);

        if (installPath == null || installPath.isEmpty()) {
            notifyError(callback, "实例未设置安装路径", "UPDATE", false);
            return false;
        }

        try {
            notifyProgress(callback, 30, "UPDATE", "停止服务器");
            // 先停止服务器
            stop(instanceId, config);

            notifyProgress(callback, 50, "UPDATE", "执行更新");
            // 执行更新
            SshUtil.CommandResult result = executeCommand(host,
                    String.format("cd %s && ./%s update", installPath, shortname), 600000);

            if (!result.isSuccess()) {
                notifyError(callback, "更新失败: " + result.getError(), "UPDATE", false);
                return false;
            }

            notifyProgress(callback, 90, "UPDATE", "重新启动服务器");
            // 重新启动
            start(instanceId, config);

            notifyProgress(callback, 100, "UPDATE", "更新完成");
            notifyStageComplete(callback, "UPDATE", true, "游戏服务器更新成功");
            return true;

        } catch (Exception e) {
            log.error("更新失败", e);
            notifyError(callback, "更新异常: " + e.getMessage(), "UPDATE", false);
            return false;
        }
    }

    @Override
    public boolean uninstall(Long instanceId, Map<String, Object> config, DeployProgressCallback callback) {
        notifyStageStart(callback, "UNINSTALL", "开始卸载游戏服务器");

        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            notifyError(callback, "实例或主机不存在", "UNINSTALL", false);
            return false;
        }

        Host host = info.host();
        String installPath = info.instance().getInstallPath();

        if (installPath == null || installPath.isEmpty()) {
            notifyError(callback, "实例未设置安装路径", "UNINSTALL", false);
            return false;
        }

        try {
            notifyProgress(callback, 30, "UNINSTALL", "停止服务器");
            // 先停止服务器
            stop(instanceId, config);

            notifyProgress(callback, 60, "UNINSTALL", "删除安装目录");
            // 删除安装目录
            SshUtil.CommandResult result = executeCommand(host, "rm -rf " + installPath);

            if (!result.isSuccess()) {
                notifyError(callback, "删除目录失败: " + result.getError(), "UNINSTALL", true);
                // 继续尝试清理
            }

            notifyProgress(callback, 100, "UNINSTALL", "卸载完成");
            notifyStageComplete(callback, "UNINSTALL", true, "游戏服务器卸载成功");
            return true;

        } catch (Exception e) {
            log.error("卸载失败", e);
            notifyError(callback, "卸载异常: " + e.getMessage(), "UNINSTALL", false);
            return false;
        }
    }

    @Override
    public String getLogs(Long instanceId, Map<String, Object> config, int lines) {
        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            return "";
        }

        Host host = info.host();
        String installPath = info.instance().getInstallPath();
        String gameType = getConfigString(config, "gameType", "minecraft");
        String shortname = getGameShortname(gameType);

        if (installPath == null || installPath.isEmpty()) {
            return "";
        }

        // 获取日志文件路径
        String logPath = installPath + "/log/console/" + shortname + "-console.log";

        SshUtil.CommandResult result = executeCommand(host,
                String.format("tail -n %d %s 2>/dev/null || echo '日志文件不存在'", lines, logPath));

        return result.getOutput();
    }

    @Override
    public InstanceStatus getStatus(Long instanceId, Map<String, Object> config) {
        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            return InstanceStatus.ERROR;
        }

        Host host = info.host();
        String installPath = info.instance().getInstallPath();
        String gameType = getConfigString(config, "gameType", "minecraft");
        String shortname = getGameShortname(gameType);

        if (installPath == null || installPath.isEmpty()) {
            return InstanceStatus.NOT_INSTALLED;
        }

        // 检查进程状态
        SshUtil.CommandResult result = executeCommand(host,
                String.format("cd %s && ./%s status", installPath, shortname), 10000);

        String output = result.getOutput().toLowerCase();

        if (output.contains("running") || output.contains("online")) {
            return InstanceStatus.RUNNING;
        } else if (output.contains("stopped") || output.contains("offline")) {
            return InstanceStatus.STOPPED;
        } else if (output.contains("starting")) {
            return InstanceStatus.STARTING;
        } else if (output.contains("stopping")) {
            return InstanceStatus.STOPPING;
        } else {
            return InstanceStatus.ERROR;
        }
    }

    @Override
    public Map<String, Object> getDetails(Long instanceId, Map<String, Object> config) {
        Map<String, Object> details = new HashMap<>();

        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            return details;
        }

        Host host = info.host();
        String installPath = info.instance().getInstallPath();
        String gameType = getConfigString(config, "gameType", "minecraft");
        String shortname = getGameShortname(gameType);

        if (installPath == null || installPath.isEmpty()) {
            return details;
        }

        // 获取LinuxGSM详情
        SshUtil.CommandResult result = executeCommand(host,
                String.format("cd %s && ./%s details", installPath, shortname), 30000);

        if (result.isSuccess()) {
            String output = result.getOutput();
            details.put("rawOutput", output);

            // 解析关键信息
            parseDetails(output, details);
        }

        // 添加实例信息
        details.put("instanceId", instanceId);
        details.put("installPath", installPath);
        details.put("gameType", gameType);
        details.put("shortname", shortname);

        return details;
    }

    @Override
    public String executeCommand(Long instanceId, Map<String, Object> config, String command) {
        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            return "实例或主机不存在";
        }

        Host host = info.host();
        String installPath = info.instance().getInstallPath();
        String gameType = getConfigString(config, "gameType", "minecraft");
        String shortname = getGameShortname(gameType);

        if (installPath == null || installPath.isEmpty()) {
            return "实例未设置安装路径";
        }

        // 执行LinuxGSM命令
        SshUtil.CommandResult result = executeCommand(host,
                String.format("cd %s && ./%s %s", installPath, shortname, command), 60000);

        return result.getOutput() + (result.getError().isEmpty() ? "" : "\n错误: " + result.getError());
    }

    @Override
    public String backup(Long instanceId, Map<String, Object> config, DeployProgressCallback callback) {
        notifyStageStart(callback, "BACKUP", "开始备份游戏服务器");

        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            notifyError(callback, "实例或主机不存在", "BACKUP", false);
            return null;
        }

        Host host = info.host();
        String installPath = info.instance().getInstallPath();
        String gameType = getConfigString(config, "gameType", "minecraft");
        String shortname = getGameShortname(gameType);

        if (installPath == null || installPath.isEmpty()) {
            notifyError(callback, "实例未设置安装路径", "BACKUP", false);
            return null;
        }

        try {
            notifyProgress(callback, 50, "BACKUP", "执行备份");
            // 执行备份
            SshUtil.CommandResult result = executeCommand(host,
                    String.format("cd %s && ./%s backup", installPath, shortname), 300000);

            if (!result.isSuccess()) {
                notifyError(callback, "备份失败: " + result.getError(), "BACKUP", false);
                return null;
            }

            // 获取备份文件路径
            String backupDir = installPath + "/backup";
            SshUtil.CommandResult listResult = executeCommand(host,
                    String.format("ls -t %s/*.tar.gz 2>/dev/null | head -1", backupDir));

            String backupPath = listResult.getOutput().trim();

            notifyProgress(callback, 100, "BACKUP", "备份完成");
            notifyStageComplete(callback, "BACKUP", true, "备份成功: " + backupPath);

            return backupPath;

        } catch (Exception e) {
            log.error("备份失败", e);
            notifyError(callback, "备份异常: " + e.getMessage(), "BACKUP", false);
            return null;
        }
    }

    @Override
    public boolean restore(Long instanceId, Map<String, Object> config, String backupPath, DeployProgressCallback callback) {
        notifyStageStart(callback, "RESTORE", "开始恢复游戏服务器");

        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            notifyError(callback, "实例或主机不存在", "RESTORE", false);
            return false;
        }

        Host host = info.host();
        String installPath = info.instance().getInstallPath();

        if (installPath == null || installPath.isEmpty()) {
            notifyError(callback, "实例未设置安装路径", "RESTORE", false);
            return false;
        }

        try {
            notifyProgress(callback, 20, "RESTORE", "停止服务器");
            // 先停止服务器
            stop(instanceId, config);

            notifyProgress(callback, 40, "RESTORE", "备份当前数据");
            // 备份当前数据
            String currentBackup = installPath + "/serverfiles_backup_" + System.currentTimeMillis();
            executeCommand(host, String.format("mv %s/serverfiles %s 2>/dev/null || true", installPath, currentBackup));

            notifyProgress(callback, 60, "RESTORE", "解压备份文件");
            // 解压备份
            SshUtil.CommandResult result = executeCommand(host,
                    String.format("cd %s && tar -xzf %s", installPath, backupPath), 300000);

            if (!result.isSuccess()) {
                notifyError(callback, "解压备份失败: " + result.getError(), "RESTORE", false);
                // 尝试恢复
                executeCommand(host, String.format("rm -rf %s/serverfiles && mv %s %s/serverfiles",
                        installPath, currentBackup, installPath));
                return false;
            }

            notifyProgress(callback, 80, "RESTORE", "启动服务器");
            // 启动服务器
            start(instanceId, config);

            notifyProgress(callback, 100, "RESTORE", "恢复完成");
            notifyStageComplete(callback, "RESTORE", true, "恢复成功");
            return true;

        } catch (Exception e) {
            log.error("恢复失败", e);
            notifyError(callback, "恢复异常: " + e.getMessage(), "RESTORE", false);
            return false;
        }
    }

    @Override
    public boolean cleanup(Long instanceId, Map<String, Object> config, DeployProgressCallback callback) {
        notifyStageStart(callback, "CLEANUP", "开始清理残留资源");

        InstanceHostInfo info = getInstanceHostInfo(instanceId);
        if (info == null) {
            notifyStageComplete(callback, "CLEANUP", true, "实例不存在，无需清理");
            return true;
        }

        Host host = info.host();
        String installPath = info.instance().getInstallPath();

        if (installPath != null && !installPath.isEmpty()) {
            try {
                // 停止相关进程
                executeCommand(host, String.format("pkill -f '%s' 2>/dev/null || true", installPath));

                // 删除安装目录
                executeCommand(host, "rm -rf " + installPath);

                notifyStageComplete(callback, "CLEANUP", true, "清理完成");
                return true;
            } catch (Exception e) {
                log.error("清理失败", e);
                notifyError(callback, "清理异常: " + e.getMessage(), "CLEANUP", true);
                return false;
            }
        }

        notifyStageComplete(callback, "CLEANUP", true, "无残留资源需要清理");
        return true;
    }

    // ========== 私有方法 ==========

    /**
     * 获取游戏简称
     *
     * @param gameType 游戏类型
     * @return LinuxGSM简称
     */
    private String getGameShortname(String gameType) {
        return GAME_SHORTNAME_MAP.getOrDefault(gameType.toLowerCase(), "mcserver");
    }

    /**
     * 应用游戏配置
     *
     * @param host       主机
     * @param installPath 安装路径
     * @param shortname  简称
     * @param config     配置
     * @return 是否成功
     */
    private boolean applyGameConfig(Host host, String installPath, String shortname, Map<String, Object> config) {
        try {
            // 构建配置内容
            StringBuilder cfgContent = new StringBuilder();
            cfgContent.append("## Server Settings\n");

            // 端口配置
            int gamePort = getConfigInt(config, "gamePort", 0);
            if (gamePort > 0) {
                cfgContent.append(String.format("port=\"%d\"\n", gamePort));
            }

            // 查询端口
            int queryPort = getConfigInt(config, "queryPort", 0);
            if (queryPort > 0) {
                cfgContent.append(String.format("queryport=\"%d\"\n", queryPort));
            }

            // RCON端口和密码
            int rconPort = getConfigInt(config, "rconPort", 0);
            if (rconPort > 0) {
                cfgContent.append(String.format("rconport=\"%d\"\n", rconPort));
            }

            String rconPassword = getConfigString(config, "rconPassword", "");
            if (!rconPassword.isEmpty()) {
                cfgContent.append(String.format("rconpassword=\"%s\"\n", rconPassword));
            }

            // 游戏特定配置
            String maxPlayers = getConfigString(config, "maxPlayers", "");
            if (!maxPlayers.isEmpty()) {
                cfgContent.append(String.format("maxplayers=\"%s\"\n", maxPlayers));
            }

            String serverName = getConfigString(config, "serverName", "");
            if (!serverName.isEmpty()) {
                cfgContent.append(String.format("servername=\"%s\"\n", serverName));
            }

            // 写入配置文件
            String configFile = installPath + "/" + shortname + ".cfg";
            String tempFile = "/tmp/" + shortname + "_" + System.currentTimeMillis() + ".cfg";

            // 创建临时文件并上传
            java.nio.file.Files.write(java.nio.file.Paths.get(tempFile), cfgContent.toString().getBytes());
            boolean uploaded = uploadFile(host, tempFile, configFile);

            // 删除临时文件
            java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get(tempFile));

            return uploaded;

        } catch (Exception e) {
            log.error("应用游戏配置失败", e);
            return false;
        }
    }

    /**
     * 解析详情输出
     *
     * @param output  原始输出
     * @param details 详情映射
     */
    private void parseDetails(String output, Map<String, Object> details) {
        String[] lines = output.split("\n");
        String currentSection = "";

        for (String line : lines) {
            line = line.trim();

            // 检测章节
            if (line.startsWith("=") && line.endsWith("=")) {
                currentSection = line.replace("=", "").trim().toLowerCase().replace(" ", "_");
                continue;
            }

            // 解析键值对
            if (line.contains(":") && !currentSection.isEmpty()) {
                String[] parts = line.split(":", 2);
                if (parts.length == 2) {
                    String key = currentSection + "." + parts[0].trim().toLowerCase().replace(" ", "_");
                    String value = parts[1].trim();
                    details.put(key, value);
                }
            }
        }
    }
}
