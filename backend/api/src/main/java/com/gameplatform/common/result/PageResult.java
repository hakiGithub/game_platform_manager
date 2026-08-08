package com.gameplatform.common.result;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 分页结果封装类
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 当前页码
     */
    private Long current;

    /**
     * 每页大小
     */
    private Long size;

    /**
     * 总记录数
     */
    private Long total;

    /**
     * 总页数
     */
    private Long pages;

    /**
     * 数据列表
     */
    private List<T> records;

    public PageResult() {
    }

    public PageResult(Long current, Long size, Long total, List<T> records) {
        this.current = current;
        this.size = size;
        this.total = total;
        this.pages = size > 0 ? (total + size - 1) / size : 0L;
        this.records = records;
    }

    /**
     * 便捷构造函数
     */
    public PageResult(List<T> records, Long total, Integer pageNum, Integer pageSize) {
        this.current = pageNum.longValue();
        this.size = pageSize.longValue();
        this.total = total;
        this.pages = pageSize > 0 ? (total + pageSize - 1) / pageSize : 0L;
        this.records = records;
    }

}
