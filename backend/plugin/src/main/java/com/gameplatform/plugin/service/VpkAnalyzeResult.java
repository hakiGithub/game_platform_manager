package com.gameplatform.plugin.service;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * VPK 地图分析结果（ADR-0027）：{@link HostToolingService#analyzeVpk} 的返回值。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class VpkAnalyzeResult implements Serializable {

    private static final long serialVersionUID = 1L;

    /** VPK 文件 sha-256 摘要（小写 hex，64 位） */
    private String digest;

    /** 战役标题（mission DisplayTitle） */
    private String title;

    /** 章节（mission 定义，code 即开图命令的地图码） */
    private List<Chapter> chapters = new ArrayList<>();

    @Data
    public static class Chapter implements Serializable {
        private static final long serialVersionUID = 1L;
        /** 章节地图码（map <code>） */
        private String code;
        /** 章节显示名 */
        private String title;
        /** 支持的游戏模式（coop/versus/survival/...） */
        private List<String> modes = new ArrayList<>();
    }
}
