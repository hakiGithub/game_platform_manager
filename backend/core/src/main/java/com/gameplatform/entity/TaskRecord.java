package com.gameplatform.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 任务记录实体类
 *
 * <p>对应表 task_record。不继承 {@link BaseEntity}，因为：
 * <ul>
 *   <li>主键类型不同：BaseEntity 用 Long 自增，本表使用 String 雪花 ID</li>
 *   <li>主键生成方式不同：BaseEntity 依赖数据库自增，本表由应用层 ExtensionIdGenerator 生成</li>
 * </ul>
 *
 * <p>create_time / update_time 复用 MyBatis-Plus 自动填充机制（{@link com.gameplatform.handler.MyMetaObjectHandler}）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@TableName("task_record")
public class TaskRecord implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID（雪花ID，应用层生成）
     */
    @TableId(type = IdType.INPUT)
    private String id;

    /**
     * 任务类型: crawl / deploy / backup / restart / export 等
     */
    private String taskType;

    /**
     * 任务来源(大写): MAIN / L4D2 / {gameCode}
     */
    private String source;

    /**
     * 状态: PENDING / RUNNING / COMPLETED / FAILED / CANCELLED
     */
    private String status;

    /**
     * 提交者用户名或 SYSTEM
     */
    private String submitter;

    /**
     * 作用域: INSTANCE / HOST / GLOBAL
     */
    private String scopeType;

    /**
     * 作用域键，如 instanceId=55
     */
    private String scopeKey;

    /**
     * 作用域名称，如实例名 l4d2_server
     */
    private String scopeName;

    /**
     * 输入参数 JSON（上限 64KB，应用层校验）
     */
    private String payload;

    /**
     * 输出结果 JSON（上限 256KB，应用层校验）
     */
    private String result;

    /**
     * Handler 生成的结果摘要（列表页展示）
     */
    private String resultSummary;

    /**
     * 进度百分比 0-100
     */
    private Integer progress;

    /**
     * 进度描述文本
     */
    private String progressMessage;

    /**
     * 失败时的错误信息
     */
    private String errorMessage;

    /**
     * 失败时的堆栈（详情页可折叠展示）
     */
    private String stackTrace;

    /**
     * 已重试次数
     */
    private Integer retryCount;

    /**
     * 重试时关联的原任务ID
     */
    private String parentTaskId;

    /**
     * 开始执行时间（应用层填充）
     */
    private LocalDateTime startedAt;

    /**
     * 完成/失败/取消时间（应用层填充）
     */
    private LocalDateTime completedAt;

    /**
     * 耗时毫秒
     */
    private Long durationMs;

    /**
     * 创建时间（MetaObjectHandler 自动填充）
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间（MetaObjectHandler 自动填充）
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 逻辑删除标识 0-未删除 1-已删除
     */
    @TableLogic
    @TableField("is_deleted")
    private Integer deleted;

    /**
     * 备注
     */
    private String remark;
}
