package com.gameplatform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 操作日志实体类
 * 对应表: operation_log
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("operation_log")
public class OperationLog extends BaseEntity {

    /**
     * 操作人
     */
    private String operator;

    /**
     * 操作类型
     */
    private String operationType;

    /**
     * 操作目标
     */
    private String operationTarget;

    /**
     * 操作内容
     */
    private String operationContent;

    /**
     * 操作结果 success/fail
     */
    private String operationResult;

    /**
     * IP地址
     */
    private String ipAddress;

    /**
     * 错误信息
     */
    private String errorMessage;

}
