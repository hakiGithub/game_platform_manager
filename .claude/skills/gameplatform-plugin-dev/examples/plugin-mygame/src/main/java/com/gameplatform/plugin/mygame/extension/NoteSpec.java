package com.gameplatform.plugin.mygame.extension;

import lombok.Data;

import java.io.Serializable;

/**
 * 笔记业务数据（Spec）。
 * <p>
 * Spec 是 POJO，承载具体业务字段，由 {@link NoteResource} 持有并通过 ExtensionClient 持久化。
 * 建议实现 Serializable 以兼容 JSON 序列化与缓存场景。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class NoteSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 关联实例 ID（用于按实例过滤） */
    private Long instanceId;

    /** 笔记标题 */
    private String title;

    /** 笔记内容 */
    private String content;

    /** 是否置顶 */
    private Boolean pinned;
}
