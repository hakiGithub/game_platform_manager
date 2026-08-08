package com.gameplatform.annotation;

import java.lang.annotation.*;

/**
 * 操作日志注解
 * 用于标记需要记录操作日志的方法
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OperationLog {

    /**
     * 操作类型
     * 如: LOGIN, LOGOUT, CREATE, UPDATE, DELETE, START, STOP等
     */
    String type() default "";

    /**
     * 操作目标
     * 如: USER, HOST, INSTANCE, GAME, PLUGIN等
     */
    String target() default "";

    /**
     * 操作描述
     */
    String description() default "";

    /**
     * 是否记录方法参数
     * 默认记录
     */
    boolean recordParams() default true;

}
