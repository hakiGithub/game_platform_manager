package com.gameplatform.plugin.l4d2.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * VPK 裁剪结果
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class VpkTrimResultVO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String fileName;
    private long originalSize;
    private long trimmedSize;
    private long savedBytes;
    private int totalEntries;
    private int trimmedEntries;
    private boolean backupCreated;
    private String backupFileName;
}
