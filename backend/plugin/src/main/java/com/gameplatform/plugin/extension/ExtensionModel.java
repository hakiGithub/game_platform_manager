package com.gameplatform.plugin.extension;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 扩展资源模型注解，声明存储策略与身份信息。
 * <p>
 * 标注在 {@link com.gameplatform.api.extension.AbstractExtension} 的子类上，
 * 框架据此决定物理表名与隔离粒度。
 *
 * <h3>策略说明</h3>
 * <ul>
 *   <li>{@link Strategy#SHARED}：存入全局 {@code extensions} 大表，所有插件所有模型混居</li>
 *   <li>{@link Strategy#PLUGIN_ISOLATED}：存入 {@code ext_{pluginId}}，该插件所有模型混居，插件间物理隔离</li>
 *   <li>{@link Strategy#MODEL_ISOLATED}：存入 {@code ext_{pluginId}_{kind}}，仅该插件该模型，隔离粒度最细</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExtensionModel {

    /**
     * 存储策略，默认 SHARED。
     */
    Strategy strategy() default Strategy.SHARED;

    /**
     * API Group，空则用 pluginId。
     */
    String group() default "";

    /**
     * 资源类型，空则用类的 simpleName。
     */
    String kind() default "";
}
