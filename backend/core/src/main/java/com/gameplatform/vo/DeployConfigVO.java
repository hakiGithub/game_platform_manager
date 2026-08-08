package com.gameplatform.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 部署配置响应VO
 * 用于返回指定部署类型的配置信息（如变量元信息、compose模板等）
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class DeployConfigVO {

    /**
     * 部署类型
     */
    private String deployType;

    /**
     * Compose 模板原文（docker-compose 和 linuxgsm-docker 类型有值）
     */
    private String composeTemplate;

    /**
     * 变量元信息列表（docker-compose 和 linuxgsm-docker 类型有值）
     * 每个变量含 name/label/type/defaultValue/required/description/hidden 字段
     */
    private List<Map<String, Object>> variables;

    /**
     * 命名卷列表（docker-compose 和 linuxgsm-docker 类型有值）
     * 用于后端识别需要 inspect 的卷
     */
    private List<String> namedVolumes;

    /**
     * 其他配置项（docker/linuxgsm/linuxgsm-docker 类型的完整配置）
     * linuxgsm-docker 类型包含 shortname/imageRepo/imageTag 等字段
     */
    private Map<String, Object> config;
}
