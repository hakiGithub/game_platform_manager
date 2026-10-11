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

    /**
     * 版本目录条目（design.md §16.4）：仅 {@code versionCatalogState == AVAILABLE} 时为合法条目，
     * 其余三态一律是空数组而非 {@code null}——前端「是否渲染版本控件」的谓词因此是条目数 ≥ 1。
     */
    private List<VersionEntryVO> deployVersions = List.of();

    /**
     * 目录读取状态 {@code ABSENT/EMPTY/INVALID/AVAILABLE}，让「空目录」与「声明不合法」
     * 在响应里可区分（RISK-13 / AC-24 ③）。界面是否使用它归 @Designer。
     */
    private String versionCatalogState;

    /** 仅 {@code INVALID} 非空：校验失败要点，供部署日志说明行与验收归因，不进界面。 */
    private String versionCatalogReason;
}
