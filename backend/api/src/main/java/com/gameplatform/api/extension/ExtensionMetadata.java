package com.gameplatform.api.extension;

import java.util.Map;

/**
 * 扩展资源元数据，序列化为宽表的 metadata 列。
 * <p>
 * 存储标签、注解、创建/更新时间戳等框架级元信息。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public class ExtensionMetadata {

    /** 标签（用于 labelSelector 过滤） */
    private Map<String, String> labels;

    /** 注解（任意附加信息，不参与过滤） */
    private Map<String, String> annotations;

    /** 创建时间戳（毫秒） */
    private Long creationTimestamp;

    /** 更新时间戳（毫秒） */
    private Long updateTimestamp;

    public Map<String, String> getLabels() {
        return labels;
    }

    public void setLabels(Map<String, String> labels) {
        this.labels = labels;
    }

    public Map<String, String> getAnnotations() {
        return annotations;
    }

    public void setAnnotations(Map<String, String> annotations) {
        this.annotations = annotations;
    }

    public Long getCreationTimestamp() {
        return creationTimestamp;
    }

    public void setCreationTimestamp(Long creationTimestamp) {
        this.creationTimestamp = creationTimestamp;
    }

    public Long getUpdateTimestamp() {
        return updateTimestamp;
    }

    public void setUpdateTimestamp(Long updateTimestamp) {
        this.updateTimestamp = updateTimestamp;
    }
}
