package com.gameplatform.plugin.task;

import java.util.Map;

/**
 * 任务参数载体
 *
 * <p>包装任务输入参数的 Map，提供类型安全的访问方法。
 * 由 {@link TaskService#submit} 在反序列化 payload JSON 后构建，
 * 传递给 {@link TaskHandler#onSubmit} / {@link TaskHandler#execute} 等方法。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public class TaskPayload {

    private final Map<String, Object> data;

    public TaskPayload(Map<String, Object> data) {
        this.data = data != null ? data : Map.of();
    }

    /**
     * 获取原始数据 Map（只读视图）
     */
    public Map<String, Object> asMap() {
        return Map.copyOf(data);
    }

    /**
     * 获取字符串参数
     */
    public String getString(String key) {
        Object v = data.get(key);
        return v == null ? null : v.toString();
    }

    /**
     * 获取字符串参数（带默认值）
     */
    public String getString(String key, String defaultValue) {
        String v = getString(key);
        return v == null ? defaultValue : v;
    }

    /**
     * 获取整数参数
     */
    public Integer getInteger(String key) {
        Object v = data.get(key);
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).intValue();
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 获取整数参数（带默认值）
     */
    public Integer getInteger(String key, Integer defaultValue) {
        Integer v = getInteger(key);
        return v == null ? defaultValue : v;
    }

    /**
     * 获取布尔参数
     */
    public Boolean getBoolean(String key) {
        Object v = data.get(key);
        if (v == null) return null;
        if (v instanceof Boolean) return (Boolean) v;
        return Boolean.parseBoolean(v.toString());
    }

    /**
     * 获取布尔参数（带默认值）
     */
    public Boolean getBoolean(String key, Boolean defaultValue) {
        Boolean v = getBoolean(key);
        return v == null ? defaultValue : v;
    }

    /**
     * 获取长整数参数
     */
    public Long getLong(String key) {
        Object v = data.get(key);
        if (v == null) return null;
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 获取 Long 参数（带默认值）
     */
    public Long getLong(String key, Long defaultValue) {
        Long v = getLong(key);
        return v == null ? defaultValue : v;
    }

    /**
     * 获取 Object 参数
     */
    public Object get(String key) {
        return data.get(key);
    }

    /**
     * 判断是否包含指定 key
     */
    public boolean containsKey(String key) {
        return data.containsKey(key);
    }
}
