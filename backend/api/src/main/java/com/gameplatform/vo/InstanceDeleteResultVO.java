package com.gameplatform.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 实例删除结果。
 *
 * <p>数据库记录删除成功不代表远端资源已完全清理。该结果用于向前端明确
 * 区分完整成功、部分成功、远端清理失败和未尝试远端清理。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstanceDeleteResultVO {

    private Long instanceId;
    private boolean deleted;
    private String remoteCleanupStatus;
    private String message;
    private List<String> residualPaths;
}
