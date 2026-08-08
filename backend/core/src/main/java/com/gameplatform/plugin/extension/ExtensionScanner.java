package com.gameplatform.plugin.extension;

import com.gameplatform.api.extension.AbstractExtension;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * 扩展资源模型扫描器。
 * <p>
 * 用 Spring 内置的 {@link ClassPathScanningCandidateComponentProvider} 扫描
 * 带有 {@link ExtensionModel} 注解的 {@link AbstractExtension} 子类。
 * 使用插件 ClassLoader 以确保扫到插件 JAR 内的类。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Component
public class ExtensionScanner {

    /**
     * 扫描指定包路径下带有 @ExtensionModel 注解的类。
     *
     * @param basePackage    扫描的基础包
     * @param pluginClassLoader 插件 ClassLoader
     * @return 扫描到的模型类集合
     */
    @SuppressWarnings("unchecked")
    public Set<Class<? extends AbstractExtension<?>>> scan(String basePackage, ClassLoader pluginClassLoader) {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(ExtensionModel.class));
        scanner.setResourceLoader(new DefaultResourceLoader(pluginClassLoader));

        Set<Class<? extends AbstractExtension<?>>> result = new HashSet<>();
        for (BeanDefinition bd : scanner.findCandidateComponents(basePackage)) {
            try {
                Class<?> clazz = Class.forName(bd.getBeanClassName(), false, pluginClassLoader);
                if (AbstractExtension.class.isAssignableFrom(clazz)) {
                    result.add((Class<? extends AbstractExtension<?>>) clazz);
                }
            } catch (ClassNotFoundException e) {
                // 跳过无法加载的类
            }
        }
        return result;
    }
}
