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
 * 定时计划实体类（ADR-0011）
 *
 * <p>对应表 scheduled_task。主键为 String 雪花 ID（应用层 ExtensionIdGenerator 生成），
 * 不继承 {@link BaseEntity}（同 {@link TaskRecord}）。
 *
 * <p>状态组合：
 * <ul>
 *   <li>enabled=1 + paused=0：调度中（UI 显示"启用"）</li>
 *   <li>enabled=1 + paused=1：系统暂停（如插件停用，UI 显示"暂停"）</li>
 *   <li>enabled=0：用户禁用（UI 显示"禁用"）</li>
 * </ul>
 *
 * <p>逻辑删除（is_deleted）同时充当声明式计划的复活墓碑：
 * 用户删除的计划，插件重载 upsert 时检测到墓碑不再复活。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@TableName("scheduled_task")
public class ScheduledTask implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID（雪花ID，应用层生成）
     */
    @TableId(type = IdType.INPUT)
    private String id;

    /**
     * 计划名称
     */
    private String name;

    /**
     * ScheduledTaskHandler 的 key（触发时按 (source, handlerKey) 解析）
     */
    private String handlerKey;

    /**
     * cron 表达式（6 位，Spring 语法，服务器时区）
     */
    private String cron;

    /**
     * payload 模板 JSON（每次触发以快照传给 Handler）
     */
    private String payload;

    /**
     * 用户启用意图: 1-启用 0-禁用
     */
    private Integer enabled;

    /**
     * 系统暂停（如插件停用）: 1-暂停 0-正常
     */
    private Integer paused;

    /**
     * 暂停原因（paused=1 时填充）
     */
    private String pauseReason;

    /**
     * 来源(大写): MAIN / {gameCode}
     */
    private String source;

    /**
     * 插件ID（MAIN 来源为 null）
     */
    private String pluginId;

    /**
     * 声明稳定键（pluginId:key，声明式计划才有；upsert 定位用）
     */
    private String declarationKey;

    /**
     * 用户是否改过: 1-是（声明式计划的声明 upsert 跳过整行）
     */
    private Integer userModified;

    /**
     * 创建人
     */
    private String createBy;

    /**
     * 更新人
     */
    private String updateBy;

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
     * 逻辑删除标识 0-未删除 1-已删除（声明复活墓碑）
     */
    @TableLogic
    @TableField("is_deleted")
    private Integer deleted;

    /**
     * 备注
     */
    private String remark;
}
