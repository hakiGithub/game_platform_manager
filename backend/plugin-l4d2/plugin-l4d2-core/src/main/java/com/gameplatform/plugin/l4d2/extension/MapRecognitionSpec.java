package com.gameplatform.plugin.l4d2.extension;

import lombok.Data;

import java.io.Serializable;

/**
 * L4D2 地图识别业务数据（ADR-0027）。
 *
 * <p>识别记录按 VPK sha-256 摘要键控、插件级共享（跨实例复用，同一文件全平台
 * 只识别一次）。状态机：
 * <ul>
 *   <li>OK：识别成功（title/chaptersJson 有效）</li>
 *   <li>FAILED：分析执行失败（errorMessage 记录原因，可重试）</li>
 *   <li>INVALID：文件存在但非有效 L4D2 地图 VPK（无 mission 信息）</li>
 * </ul>
 */
@Data
public class MapRecognitionSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    /** VPK sha-256 摘要（小写 hex，作为 Resource name） */
    private String digest;

    /** 识别状态：OK / FAILED / INVALID */
    private String status;

    /** 战役标题（mission DisplayTitle） */
    private String title;

    /** 章节列表 JSON：[{"code","title","modes":[...]}]，code 即开图命令地图码 */
    private String chaptersJson;

    /** 开图命令 JSON：["map xxx", ...]（安装命中地图中心元数据时回填） */
    private String launchCommandsJson;

    /** 地图中心来源 ID（开图命令匹配来源，可空） */
    private String sourceId;

    /** 首次见到的 VPK 文件名（信息性，不做键） */
    private String sampleVpkName;

    /** 识别时间（ISO 格式字符串） */
    private String analyzedAt;

    /** 失败/无效原因 */
    private String errorMessage;
}
