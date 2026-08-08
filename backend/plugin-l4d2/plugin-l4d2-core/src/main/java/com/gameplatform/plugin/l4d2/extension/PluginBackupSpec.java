package com.gameplatform.plugin.l4d2.extension;

import com.gameplatform.plugin.l4d2.vo.backup.BackupContent;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * L4D2 插件备份业务数据。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class PluginBackupSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 实例ID */
    private Long instanceId;

    /** 主机ID */
    private Long hostId;

    /** 备份名称（用户可读） */
    private String name;

    /** 备份描述 */
    private String description;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 备份内容 */
    private BackupContent content;

    /** 创建者 */
    private String owner;
}
