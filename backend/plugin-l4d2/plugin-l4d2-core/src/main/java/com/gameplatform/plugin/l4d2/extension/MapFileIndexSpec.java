package com.gameplatform.plugin.l4d2.extension;

import lombok.Data;

import java.io.Serializable;

/**
 * L4D2 实例地图文件索引业务数据（ADR-0027）。
 *
 * <p>实例维度轻量索引：addons 目录里的 vpk 文件 → 共享识别记录摘要。
 * refresh 对目录新增文件记 PENDING、对已消失文件清条目；摘要由识别流程回填。
 */
@Data
public class MapFileIndexSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 索引键（作为 Resource name）："{instanceId}:{vpkName}" */
    private String indexKey;

    /** 实例ID */
    private Long instanceId;

    /** VPK 文件名 */
    private String vpkName;

    /** VPK sha-256 摘要（识别流程回填；PENDING 时为空） */
    private String digest;

    /** 索引状态：PENDING（待识别）/ READY（已关联识别记录） */
    private String status;

    /** 更新时间（ISO 格式字符串） */
    private String updatedAt;
}
