package com.gameplatform.api.extension;

/**
 * 扩展资源基类。
 * <p>
 * 插件开发者继承此类，泛型参数 {@code T} 为业务 spec 的 POJO 类型。
 * 字段与宽表列一一对应：
 * <ul>
 *   <li>{@code id} → id 列（雪花ID，框架生成，PRIMARY KEY）</li>
 *   <li>{@code name} → name 列（业务标识，插件自定，同表内 UNIQUE）</li>
 *   <li>{@code groupName} → group_name 列（框架填充 = pluginId，插件只读）</li>
 *   <li>{@code kind} → kind 列（框架填充 = 类名，可注解覆盖）</li>
 *   <li>{@code version} → version 列（乐观锁版本号，框架管理）</li>
 *   <li>{@code metadata} → metadata 列（labels/annotations/时间戳，序列化为 TEXT）</li>
 *   <li>{@code spec} → spec 列（业务数据，序列化为 TEXT）</li>
 *   <li>{@code status} → status 列（高频过滤字段，如 'ACTIVE'）</li>
 * </ul>
 *
 * @param <T> 业务 spec 的 POJO 类型
 * @author GamePlatform
 * @version 1.1.0
 */
public abstract class AbstractExtension<T> {

    /** 资源全局唯一标识（雪花ID），框架生成，PRIMARY KEY */
    private String id;

    /** 业务标识（插件自定，如 instanceId-steamId），同表内 UNIQUE */
    private String name;

    /** API Group，框架填充为 pluginId（可注解覆盖），插件只读 */
    private String groupName;

    /** 资源类型，框架填充为类名（可注解覆盖） */
    private String kind;

    /** 乐观锁版本号，框架管理 */
    private Integer version;

    /** 元数据（标签、注解、时间戳），序列化为 metadata 列 */
    private ExtensionMetadata metadata;

    /** 业务数据，序列化为 spec 列 */
    private T spec;

    /** 状态字段，高频过滤（如 'ACTIVE'） */
    private String status;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public ExtensionMetadata getMetadata() {
        return metadata;
    }

    public void setMetadata(ExtensionMetadata metadata) {
        this.metadata = metadata;
    }

    public T getSpec() {
        return spec;
    }

    public void setSpec(T spec) {
        this.spec = spec;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
