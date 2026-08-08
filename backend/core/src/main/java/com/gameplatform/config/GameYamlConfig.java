package com.gameplatform.config;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * YAML游戏元数据配置类
 * 用于解析 games/ 目录下的YAML配置文件
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class GameYamlConfig {

    /**
     * 游戏基本信息
     */
    private GameInfo game;

    /**
     * 游戏基本信息内部类
     */
    @Data
    public static class GameInfo {
        /**
         * 游戏代码(唯一标识)
         */
        private String code;

        /**
         * 游戏名称
         */
        private String name;

        /**
         * 游戏描述
         */
        private String description;

        /**
         * 游戏版本
         */
        private String version;

        /**
         * 图标路径
         */
        private String icon;

        /**
         * 支持的部署方式
         */
        private List<String> deployTypes = new ArrayList<>();

        // SnakeYAML 需要显式的 setter 方法
        public void setDeployTypes(List<String> deployTypes) {
            this.deployTypes = deployTypes != null ? deployTypes : new ArrayList<>();
        }

        /**
         * 默认端口配置
         */
        private Map<String, Integer> defaultPorts = new HashMap<>();

        /**
         * 环境依赖
         */
        private Map<String, String> dependencies = new HashMap<>();

        /**
         * Docker部署配置
         */
        private DockerConfig docker;

        /**
         * LinuxGSM部署配置
         */
        private LinuxGsmConfig linuxgsm;

        /**
         * Docker Compose部署配置
         */
        private DockerComposeConfig dockerCompose;

        /**
         * LinuxGSM Docker部署配置
         * 基于 gameservermanagers/gameserver 镜像，将 LinuxGSM 封装在 Docker 容器中
         */
        private LinuxGsmDockerConfig linuxgsmDocker;

        /**
         * 配置Schema(用于生成可视化表单)
         */
        private ConfigSchema configSchema;

        /**
         * 自定义操作列表
         */
        private List<CustomOperation> customOperations = new ArrayList<>();
    }

    /**
     * Docker部署配置
     */
    @Data
    public static class DockerConfig {
        /**
         * Docker镜像
         */
        private String image;

        /**
         * 镜像标签(版本)
         */
        private String tag;

        /**
         * 环境变量
         */
        private Map<String, String> env = new HashMap<>();

        /**
         * 挂载卷
         */
        private List<String> volumes = new ArrayList<>();

        /**
         * 端口映射
         */
        private List<String> ports = new ArrayList<>();

        /**
         * 重启策略
         */
        private String restartPolicy = "unless-stopped";

        /**
         * 网络模式
         */
        private String networkMode = "bridge";

        /**
         * 资源限制
         */
        private ResourceLimits resources;

        /**
         * 健康检查配置
         */
        private HealthCheck healthCheck;
    }

    /**
     * 资源限制配置
     */
    @Data
    public static class ResourceLimits {
        /**
         * CPU限制(如: 1.5表示1.5核)
         */
        private String cpu;

        /**
         * 内存限制(如: 2G)
         */
        private String memory;

        /**
         * 内存交换限制
         */
        private String memorySwap;
    }

    /**
     * 健康检查配置
     */
    @Data
    public static class HealthCheck {
        /**
         * 是否启用
         */
        private Boolean enabled = true;

        /**
         * 检查命令
         */
        private String test;

        /**
         * 检查间隔(秒)
         */
        private Integer interval = 30;

        /**
         * 超时时间(秒)
         */
        private Integer timeout = 10;

        /**
         * 重试次数
         */
        private Integer retries = 3;

        /**
         * 启动等待时间(秒)
         */
        private Integer startPeriod = 60;
    }

    /**
     * LinuxGSM部署配置
     */
    @Data
    public static class LinuxGsmConfig {
        /**
         * LinuxGSM脚本名称
         */
        private String script;

        /**
         * 游戏代码
         */
        private String gameCode;

        /**
         * 配置文件名
         */
        private String configFile;

        /**
         * 安装目录
         */
        private String installDir;

        /**
         * 启动参数
         */
        private String startParams;

        /**
         * 需要开放的端口
         */
        private List<String> ports = new ArrayList<>();
    }

    /**
     * Docker Compose部署配置
     */
    @Data
    public static class DockerComposeConfig {
        /**
         * Compose模板原文（保留 ${VAR:default} 变量语法）
         */
        private String composeTemplate;

        /**
         * 容器内游戏数据根目录（用于 InstanceFileService 解析相对路径）
         */
        private String workingDir;

        /**
         * 变量元信息列表（用于前端表单渲染和后端校验）
         */
        private List<VariableDefinition> variables = new ArrayList<>();

        /**
         * 命名卷列表（用于后端识别需要 inspect 的卷）
         */
        private List<String> namedVolumes = new ArrayList<>();

        /**
         * 是否将宿主机 SSL 证书挂载到容器中（默认 false）
         * <p>
         * 适用场景：宿主机使用反向代理（如将 GitHub 等域名解析到 127.0.0.1），
         * 代理使用宿主机信任的自签 CA 证书。容器内 curl 访问这些域名时，
         * 由于容器内没有该 CA 证书，会报 "SSL certificate problem: unable to get local issuer certificate"。
         * 启用此项后，会将宿主机的 ca-certificates.crt 只读挂载到容器相同路径。
         * <p>
         * 注意：此字段作为默认值，最终是否挂载以部署时用户在前端的选择为准（前端会覆盖此默认值）。
         */
        private boolean mountHostCerts = false;

        /**
         * 宿主机 CA 证书路径（默认 /etc/ssl/certs/ca-certificates.crt）
         * <p>
         * 仅当 mountHostCerts=true 时生效。
         * 该文件会以只读方式挂载到容器内同路径，覆盖镜像自带的证书 bundle。
         */
        private String hostCertPath = "/etc/ssl/certs/ca-certificates.crt";

        // SnakeYAML 需要显式的 setter 方法
        public void setVariables(List<VariableDefinition> variables) {
            this.variables = variables != null ? variables : new ArrayList<>();
        }

        public void setNamedVolumes(List<String> namedVolumes) {
            this.namedVolumes = namedVolumes != null ? namedVolumes : new ArrayList<>();
        }
    }

    /**
     * LinuxGSM Docker部署配置
     * 基于 gameservermanagers/gameserver 镜像（https://github.com/GameServerManagers/docker-gameserver）
     * <p>
     * 特性：
     * - 镜像内置 LinuxGSM 和游戏服务器脚本，首次启动自动安装
     * - 容器以 linuxgsm 用户运行（通过 gosu 切换）
     * - 数据目录 /data 为 linuxgsm 用户的家目录
     * - 推荐 network_mode: host
     * - 通过 docker exec --user linuxgsm <container> ./<shortname> <command> 执行 LinuxGSM 命令
     */
    @Data
    public static class LinuxGsmDockerConfig {
        /**
         * LinuxGSM 脚本名（用于 docker exec 调用，如 l4d2server、cs2server）
         */
        private String shortname;

        /**
         * 镜像 tag（即 LinuxGSM 游戏服务器简称，如 l4d2server、cs2server）
         * 完整镜像名为 gameservermanagers/gameserver:{tag}
         */
        private String imageTag;

        /**
         * 镜像仓库（默认 gameservermanagers/gameserver）
         */
        private String imageRepo = "gameservermanagers/gameserver";

        /**
         * Compose模板原文（保留 ${VAR:default} 变量语法）
         * 与 dockerCompose.composeTemplate 格式一致
         */
        private String composeTemplate;

        /**
         * 变量元信息列表（用于前端表单渲染和后端校验）
         */
        private List<VariableDefinition> variables = new ArrayList<>();

        /**
         * 命名卷列表（用于后端识别需要 inspect 的卷）
         */
        private List<String> namedVolumes = new ArrayList<>();

        /**
         * 是否将宿主机 SSL 证书挂载到容器中（默认 false）
         * <p>
         * 适用场景：宿主机使用反向代理（如将 GitHub 等域名解析到 127.0.0.1），
         * 代理使用宿主机信任的自签 CA 证书。容器内 curl 访问这些域名时，
         * 由于容器内没有该 CA 证书，会报 "SSL certificate problem: unable to get local issuer certificate"。
         * 启用此项后，会将宿主机的 ca-certificates.crt 只读挂载到容器相同路径。
         */
        private boolean mountHostCerts = false;

        /**
         * 宿主机 CA 证书路径（默认 /etc/ssl/certs/ca-certificates.crt）
         * <p>
         * 仅当 mountHostCerts=true 时生效。
         * 该文件会以只读方式挂载到容器内同路径，覆盖镜像自带的证书 bundle。
         */
        private String hostCertPath = "/etc/ssl/certs/ca-certificates.crt";

        // SnakeYAML 需要显式的 setter 方法
        public void setVariables(List<VariableDefinition> variables) {
            this.variables = variables != null ? variables : new ArrayList<>();
        }

        public void setNamedVolumes(List<String> namedVolumes) {
            this.namedVolumes = namedVolumes != null ? namedVolumes : new ArrayList<>();
        }
    }

    /**
     * 变量定义
     */
    @Data
    public static class VariableDefinition {
        /**
         * 变量名（对应 compose 中的 ${VAR_NAME}）
         */
        private String name;

        /**
         * 前端表单显示标签
         */
        private String label;

        /**
         * 变量类型: string/integer/boolean/password
         */
        private String type;

        /**
         * 默认值（字符串形式）
         */
        private String defaultValue;

        /**
         * 是否必填
         */
        private boolean required;

        /**
         * 描述文本
         */
        private String description;

        /**
         * 是否在前端隐藏（高级选项）
         */
        private boolean hidden;
    }

    /**
     * 配置Schema(用于生成可视化表单)
     */
    @Data
    public static class ConfigSchema {
        /**
         * 配置属性定义
         */
        private Map<String, ConfigProperty> properties = new HashMap<>();

        /**
         * 必填字段列表
         */
        private List<String> required = new ArrayList<>();

        /**
         * 表单布局配置
         */
        private FormLayout layout;
    }

    /**
     * 配置属性定义
     */
    @Data
    public static class ConfigProperty {
        /**
         * 属性类型(string, integer, boolean, array, object)
         */
        private String type;

        /**
         * 属性标签(显示名称)
         */
        private String label;

        /**
         * 属性描述
         */
        private String description;

        /**
         * 默认值
         */
        private Object defaultValue;

        /**
         * 枚举值(用于下拉选择)
         */
        private List<Object> enumValues;

        /**
         * 最小值(用于数字类型)
         */
        private Number minimum;

        /**
         * 最大值(用于数字类型)
         */
        private Number maximum;

        /**
         * 最小长度(用于字符串类型)
         */
        private Integer minLength;

        /**
         * 最大长度(用于字符串类型)
         */
        private Integer maxLength;

        /**
         * 正则表达式验证
         */
        private String pattern;

        /**
         * 是否必填
         */
        private Boolean required;

        /**
         * 占位符提示
         */
        private String placeholder;

        /**
         * 帮助文本
         */
        private String helpText;

        /**
         * 组件类型(input, select, switch, textarea, number, password等)
         */
        private String component;

        // 用于SnakeYAML的setter方法，处理default关键字
        public void setDefault(Object defaultValue) {
            this.defaultValue = defaultValue;
        }

        // 用于SnakeYAML的setter方法，处理enum关键字
        public void setEnum(List<Object> enumValues) {
            this.enumValues = enumValues;
        }
    }

    /**
     * 表单布局配置
     */
    @Data
    public static class FormLayout {
        /**
         * 列数(1-4)
         */
        private Integer columns = 1;

        /**
         * 字段分组
         */
        private List<FieldGroup> groups = new ArrayList<>();
    }

    /**
     * 字段分组
     */
    @Data
    public static class FieldGroup {
        /**
         * 分组标题
         */
        private String title;

        /**
         * 分组描述
         */
        private String description;

        /**
         * 分组包含的字段
         */
        private List<String> fields = new ArrayList<>();
    }

    /**
     * 自定义操作
     */
    @Data
    public static class CustomOperation {
        /**
         * 操作名称
         */
        private String name;

        /**
         * 操作命令标识
         */
        private String command;

        /**
         * 操作描述
         */
        private String description;

        /**
         * 图标名称
         */
        private String icon;

        /**
         * 是否需要确认
         */
        private Boolean confirm = false;

        /**
         * 确认提示信息
         */
        private String confirmMessage;

        /**
         * 操作类型(backup, maintenance, management)
         */
        private String type = "management";

        /**
         * 执行参数Schema
         */
        private Map<String, Object> paramsSchema;

        /**
         * 是否异步执行
         */
        private Boolean async = false;

        /**
         * 执行超时时间(秒)
         */
        private Integer timeout = 300;
    }

    /**
     * 验证配置是否有效
     *
     * @return 验证结果
     */
    public boolean isValid() {
        if (game == null) {
            return false;
        }
        if (game.getCode() == null || game.getCode().trim().isEmpty()) {
            return false;
        }
        if (game.getName() == null || game.getName().trim().isEmpty()) {
            return false;
        }
        return true;
    }

    /**
     * 获取验证错误信息
     *
     * @return 错误信息
     */
    public String getValidationError() {
        if (game == null) {
            return "游戏配置不能为空";
        }
        if (game.getCode() == null || game.getCode().trim().isEmpty()) {
            return "游戏代码不能为空";
        }
        if (game.getName() == null || game.getName().trim().isEmpty()) {
            return "游戏名称不能为空";
        }
        return null;
    }

    /**
     * 检查是否支持指定部署类型
     *
     * @param deployType 部署类型
     * @return 是否支持
     */
    public boolean supportsDeployType(String deployType) {
        if (game == null || game.getDeployTypes() == null) {
            return false;
        }
        return game.getDeployTypes().contains(deployType);
    }

    /**
     * 获取主端口
     *
     * @return 主端口号
     */
    public Integer getMainPort() {
        if (game == null || game.getDefaultPorts() == null) {
            return null;
        }
        // 优先返回game端口，其次是第一个端口
        Integer port = game.getDefaultPorts().get("game");
        if (port != null) {
            return port;
        }
        return game.getDefaultPorts().values().stream().findFirst().orElse(null);
    }
}
