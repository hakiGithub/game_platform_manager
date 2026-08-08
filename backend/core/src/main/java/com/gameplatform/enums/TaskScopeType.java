package com.gameplatform.enums;

/**
 * 任务作用域类型枚举
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public enum TaskScopeType {

    /** 实例级作用域（如部署/备份任务） */
    INSTANCE,

    /** 主机级作用域（如主机配置任务） */
    HOST,

    /** 全局作用域（如全量爬取、清理任务） */
    GLOBAL
}
