package com.gameplatform.plugin.l4d2.vo;

import lombok.Data;

/**
 * 候选 cfg 文件路径响应 VO。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class CandidatePathVO {
    /** 相对 left4dead2 目录的路径 */
    private String path;
    /** 文件是否存在 */
    private boolean exists;
}
