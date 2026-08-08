package com.gameplatform.plugin.extension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 列表查询选项。
 * <p>
 * 支持 status 列过滤、metadata.labels 过滤、spec 字段过滤、时间范围、分页与排序。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public class ListOptions {

    /** status 列过滤 */
    private String status;

    /** metadata.labels.x = y 过滤 */
    private Map<String, String> labelSelector = new HashMap<>();

    /** spec 字段过滤（翻译为 json_extract 或内存过滤） */
    private List<SpecFilter> specFilters = new ArrayList<>();

    /** creation_timestamp > 此值 */
    private Long createdAfter;

    /** 每页数量，默认 100 */
    private int limit = 100;

    /** 偏移量 */
    private int offset = 0;

    /** 排序字段，默认 creation_timestamp */
    private String orderBy = "creation_timestamp";

    public String getStatus() {
        return status;
    }

    public ListOptions setStatus(String status) {
        this.status = status;
        return this;
    }

    public Map<String, String> getLabelSelector() {
        return labelSelector;
    }

    public ListOptions setLabelSelector(Map<String, String> labelSelector) {
        this.labelSelector = labelSelector;
        return this;
    }

    public List<SpecFilter> getSpecFilters() {
        return specFilters;
    }

    public ListOptions setSpecFilters(List<SpecFilter> specFilters) {
        this.specFilters = specFilters;
        return this;
    }

    public Long getCreatedAfter() {
        return createdAfter;
    }

    public ListOptions setCreatedAfter(Long createdAfter) {
        this.createdAfter = createdAfter;
        return this;
    }

    public int getLimit() {
        return limit;
    }

    public ListOptions setLimit(int limit) {
        this.limit = limit;
        return this;
    }

    public int getOffset() {
        return offset;
    }

    public ListOptions setOffset(int offset) {
        this.offset = offset;
        return this;
    }

    public String getOrderBy() {
        return orderBy;
    }

    public ListOptions setOrderBy(String orderBy) {
        this.orderBy = orderBy;
        return this;
    }

    /**
     * 创建 builder。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder。
     */
    public static class Builder {
        private final ListOptions opts = new ListOptions();

        public Builder status(String status) {
            opts.status = status;
            return this;
        }

        public Builder label(String key, String value) {
            opts.labelSelector.put(key, value);
            return this;
        }

        public Builder specFilter(String path, String op, Object value) {
            opts.specFilters.add(new SpecFilter(path, op, value));
            return this;
        }

        public Builder createdAfter(Long ts) {
            opts.createdAfter = ts;
            return this;
        }

        public Builder limit(int limit) {
            opts.limit = limit;
            return this;
        }

        public Builder offset(int offset) {
            opts.offset = offset;
            return this;
        }

        public Builder orderBy(String orderBy) {
            opts.orderBy = orderBy;
            return this;
        }

        public ListOptions build() {
            return opts;
        }
    }
}
