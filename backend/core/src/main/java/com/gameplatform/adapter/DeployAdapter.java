package com.gameplatform.adapter;

import com.gameplatform.entity.GameInstance;

import java.util.Map;

/**
 * 部署适配器接口
 * 定义统一的游戏服务器部署生命周期接口
 * 支持多种部署方式：LinuxGSM、Docker、Docker Compose
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface DeployAdapter {

    /**
     * 部署类型枚举
     */
    enum DeployType {
        LINUX_GSM("linuxgsm", "LinuxGSM部署"),
        DOCKER("docker", "Docker部署"),
        DOCKER_COMPOSE("docker-compose", "Docker Compose部署"),
        LINUX_GSM_DOCKER("linuxgsm-docker", "LinuxGSM Docker部署");

        private final String code;
        private final String description;

        DeployType(String code, String description) {
            this.code = code;
            this.description = description;
        }

        public String getCode() {
            return code;
        }

        public String getDescription() {
            return description;
        }

        public static DeployType fromCode(String code) {
            for (DeployType type : values()) {
                if (type.code.equals(code)) {
                    return type;
                }
            }
            return null;
        }
    }

    /**
     * 实例状态枚举
     *
     * <p>game_instance.run_status 列的唯一权威词汇表（ADR-0005）：
     * code 为持久化数字，wireKey 为线上英文键（InstanceVO.status），description 为中文文本唯一来源。
     */
    enum InstanceStatus {
        STOPPED(0, "stopped", "已停止"),
        RUNNING(1, "running", "运行中"),
        STARTING(2, "starting", "启动中"),
        STOPPING(3, "stopping", "停止中"),
        ERROR(4, "error", "异常"),
        INSTALLING(5, "installing", "安装中"),
        UPDATING(6, "updating", "更新中"),
        NOT_INSTALLED(7, "not_installed", "未安装");

        private final int code;
        private final String wireKey;
        private final String description;

        InstanceStatus(int code, String wireKey, String description) {
            this.code = code;
            this.wireKey = wireKey;
            this.description = description;
        }

        public int getCode() {
            return code;
        }

        public String getWireKey() {
            return wireKey;
        }

        public String getDescription() {
            return description;
        }

        public static InstanceStatus fromCode(int code) {
            for (InstanceStatus status : values()) {
                if (status.code == code) {
                    return status;
                }
            }
            return null;
        }
    }

    /**
     * 获取部署类型
     *
     * @return 部署类型
     */
    DeployType getDeployType();

    /**
     * 环境校验
     * 检查目标主机是否满足部署条件
     *
     * @param hostId 主机ID
     * @param config 部署配置
     * @return 是否通过校验
     */
    boolean validateEnvironment(Long hostId, Map<String, Object> config);

    /**
     * 预部署（下载、准备）
     * 执行部署前的准备工作，如下载安装包、创建目录等
     *
     * @param instanceId 实例ID
     * @param config     部署配置
     * @param callback   进度回调
     * @return 是否准备成功
     */
    boolean preDeploy(Long instanceId, Map<String, Object> config, DeployProgressCallback callback);

    /**
     * 部署
     * 执行实际的部署操作
     *
     * @param instanceId 实例ID
     * @param config     部署配置
     * @param callback   进度回调
     * @return 是否部署成功
     */
    boolean deploy(Long instanceId, Map<String, Object> config, DeployProgressCallback callback);

    /**
     * 启动实例
     *
     * @param instanceId 实例ID
     * @param config     部署配置
     * @return 是否启动成功
     */
    boolean start(Long instanceId, Map<String, Object> config);

    /**
     * 停止实例
     *
     * @param instanceId 实例ID
     * @param config     部署配置
     * @return 是否停止成功
     */
    boolean stop(Long instanceId, Map<String, Object> config);

    /**
     * 重启实例
     *
     * @param instanceId 实例ID
     * @param config     部署配置
     * @return 是否重启成功
     */
    boolean restart(Long instanceId, Map<String, Object> config);

    /**
     * 健康检查
     *
     * @param instanceId 实例ID
     * @param config     部署配置
     * @return 是否健康
     */
    boolean healthCheck(Long instanceId, Map<String, Object> config);

    /**
     * 停止服务器（游戏进程级，容器保持运行）。
     *
     * <p>与 {@link #stop}（停止容器）区分：linuxgsm-docker 部署下
     * LinuxGSM 管理的游戏进程可独立于容器启停。默认不支持。
     *
     * @param instanceId 实例ID
     * @param config     部署配置
     * @return 是否停止成功
     */
    default boolean stopServer(Long instanceId, Map<String, Object> config) {
        throw new UnsupportedOperationException("该部署方式不支持独立停止服务器");
    }

    /**
     * 更新实例
     *
     * @param instanceId 实例ID
     * @param config     部署配置
     * @param callback   进度回调
     * @return 是否更新成功
     */
    boolean update(Long instanceId, Map<String, Object> config, DeployProgressCallback callback);

    /**
     * 卸载实例
     *
     * @param instanceId 实例ID
     * @param config     部署配置
     * @param callback   进度回调
     * @return 是否卸载成功
     */
    boolean uninstall(Long instanceId, Map<String, Object> config, DeployProgressCallback callback);

    /**
     * 获取日志
     *
     * @param instanceId 实例ID
     * @param config     部署配置
     * @param lines      获取行数
     * @return 日志内容
     */
    String getLogs(Long instanceId, Map<String, Object> config, int lines);

    /**
     * 获取实例状态
     *
     * @param instanceId 实例ID
     * @param config     部署配置
     * @return 实例状态
     */
    InstanceStatus getStatus(Long instanceId, Map<String, Object> config);

    /**
     * 获取实例详情
     *
     * @param instanceId 实例ID
     * @param config     部署配置
     * @return 实例详情信息
     */
    Map<String, Object> getDetails(Long instanceId, Map<String, Object> config);

    /**
     * 执行自定义命令
     *
     * @param instanceId 实例ID
     * @param config     部署配置
     * @param command    命令
     * @return 命令执行结果
     */
    String executeCommand(Long instanceId, Map<String, Object> config, String command);

    /**
     * 备份实例
     *
     * @param instanceId 实例ID
     * @param config     部署配置
     * @param callback   进度回调
     * @return 备份文件路径
     */
    String backup(Long instanceId, Map<String, Object> config, DeployProgressCallback callback);

    /**
     * 恢复实例
     *
     * @param instanceId 实例ID
     * @param config     部署配置
     * @param backupPath 备份文件路径
     * @param callback   进度回调
     * @return 是否恢复成功
     */
    boolean restore(Long instanceId, Map<String, Object> config, String backupPath, DeployProgressCallback callback);

    /**
     * 清理残留资源
     * 部署失败时调用，清理已创建的资源
     *
     * @param instanceId 实例ID
     * @param config     部署配置
     * @param callback   进度回调
     * @return 是否清理成功
     */
    boolean cleanup(Long instanceId, Map<String, Object> config, DeployProgressCallback callback);
}
