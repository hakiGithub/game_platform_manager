package com.gameplatform.plugin.l4d2.util;

import com.gameplatform.plugin.l4d2.vo.BuildInfoVO;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.pf4j.Plugin;
import org.springframework.boot.SpringBootVersion;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 构建信息读取器（对齐 plan §6.3.1）。
 *
 * <p>启动时通过 {@link ClassPathResource} 读取 {@code META-INF/build.properties}
 * （由 Maven 资源过滤在打包阶段生成），解析后填充 version / commit / buildTime 字段。
 * jdkVersion / pf4jVersion / pluginId / pluginDescription / springBootVersion 始终从代码获取，
 * 不依赖 properties 文件。
 *
 * <p>容错策略：读取失败仅 {@code log.warn}，所有字段降级为 {@code "unknown"}，不抛异常，
 * 不影响插件启动。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
public class BuildInfoReader {

    /** build.properties 在 classpath 中的位置 */
    static final String BUILD_PROPERTIES_PATH = "META-INF/build.properties";

    /** 默认值：字段无法解析时使用 */
    static final String UNKNOWN = "unknown";

    /** 固定的插件 ID */
    static final String PLUGIN_ID = "plugin-l4d2";

    /** 固定的插件描述 */
    static final String PLUGIN_DESCRIPTION = "L4D2 游戏服务器增强插件";

    /** 环境变量名：用于注入 Git commit hash（CI 注入） */
    static final String ENV_BUILD_COMMIT = "BUILD_COMMIT";

    /** 系统属性名：用于注入 Git commit hash（备选） */
    static final String SYS_BUILD_COMMIT = "build.commit";

    /** PF4J 版本回退值（与父 pom 的 pf4j.version 一致） */
    static final String PF4J_VERSION_FALLBACK = "3.10.0";

    private final ClassPathResource buildPropertiesResource;

    /** 从 build.properties 读取的版本号；文件缺失或解析失败时为 {@link #UNKNOWN} */
    private String version = UNKNOWN;

    /** Git commit hash（短），来自环境变量/系统属性；未设置则为 {@link #UNKNOWN} */
    private String commit = UNKNOWN;

    /** 构建时间（来自 Maven ${maven.build.timestamp}）；失败时为 {@link #UNKNOWN} */
    private String buildTime = UNKNOWN;

    /** JDK 版本（运行时获取） */
    private String jdkVersion = UNKNOWN;

    /** PF4J 框架版本（运行时从 PF4J JAR 的 Package 信息获取，回退到硬编码） */
    private String pf4jVersion = PF4J_VERSION_FALLBACK;

    /** 插件 ID（固定） */
    private String pluginId = PLUGIN_ID;

    /** 插件描述（固定） */
    private String pluginDescription = PLUGIN_DESCRIPTION;

    /** Spring Boot 版本（运行时获取） */
    private String springBootVersion = UNKNOWN;

    public BuildInfoReader() {
        this(new ClassPathResource(BUILD_PROPERTIES_PATH));
    }

    /**
     * 测试用构造器：允许注入自定义 {@link ClassPathResource}。
     *
     * @param buildPropertiesResource build.properties 资源
     */
    BuildInfoReader(ClassPathResource buildPropertiesResource) {
        this.buildPropertiesResource = buildPropertiesResource;
    }

    @PostConstruct
    public void init() {
        // 始终从代码获取的字段
        jdkVersion = System.getProperty("java.version", UNKNOWN);
        pf4jVersion = resolvePf4jVersion();
        springBootVersion = resolveSpringBootVersion();

        // 从 build.properties 读取的字段
        Properties props = loadBuildProperties();
        if (props != null) {
            version = props.getProperty("version", UNKNOWN);
            buildTime = props.getProperty("buildTime", UNKNOWN);
        }

        // commit 字段尝试从环境变量/系统属性获取
        commit = resolveCommit();

        log.info("BuildInfo loaded: version={}, commit={}, buildTime={}, jdk={}, pf4j={}, springBoot={}",
                version, commit, buildTime, jdkVersion, pf4jVersion, springBootVersion);
    }

    /**
     * 读取并解析 build.properties；文件缺失或读取失败返回 null。
     */
    private Properties loadBuildProperties() {
        try {
            if (!buildPropertiesResource.exists()) {
                log.warn("build.properties 不存在 path={}", BUILD_PROPERTIES_PATH);
                return null;
            }
            Properties props = new Properties();
            try (InputStream is = buildPropertiesResource.getInputStream()) {
                props.load(is);
            }
            return props;
        } catch (IOException e) {
            log.warn("读取 build.properties 失败 path={}, err={}", BUILD_PROPERTIES_PATH, e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("解析 build.properties 异常 path={}, err={}", BUILD_PROPERTIES_PATH, e.getMessage());
            return null;
        }
    }

    /**
     * 解析 PF4J 版本：优先从 PF4J JAR 的 Package 实现 版本获取，失败回退到硬编码。
     */
    private String resolvePf4jVersion() {
        try {
            Package pkg = Plugin.class.getPackage();
            if (pkg != null) {
                String v = pkg.getImplementationVersion();
                if (v != null && !v.isBlank()) {
                    return v;
                }
                v = pkg.getSpecificationVersion();
                if (v != null && !v.isBlank()) {
                    return v;
                }
            }
        } catch (Exception e) {
            log.warn("解析 PF4J 版本失败 err={}", e.getMessage());
        }
        return PF4J_VERSION_FALLBACK;
    }

    /**
     * 解析 Spring Boot 版本：调用 SpringBootVersion.getVersion()，null 时回退 unknown。
     */
    private String resolveSpringBootVersion() {
        try {
            String v = SpringBootVersion.getVersion();
            return (v != null && !v.isBlank()) ? v : UNKNOWN;
        } catch (Exception e) {
            log.warn("解析 Spring Boot 版本失败 err={}", e.getMessage());
            return UNKNOWN;
        }
    }

    /**
     * 解析 Git commit hash：环境变量 BUILD_COMMIT 优先，系统属性 build.commit 次之，否则 unknown。
     */
    private String resolveCommit() {
        try {
            String env = System.getenv(ENV_BUILD_COMMIT);
            if (env != null && !env.isBlank()) {
                return env;
            }
            String sys = System.getProperty(SYS_BUILD_COMMIT);
            if (sys != null && !sys.isBlank()) {
                return sys;
            }
        } catch (Exception e) {
            log.warn("解析 Git commit 失败 err={}", e.getMessage());
        }
        return UNKNOWN;
    }

    /**
     * 转换为 VO，包含所有字段。
     */
    public BuildInfoVO toVO() {
        BuildInfoVO vo = new BuildInfoVO();
        vo.setVersion(version);
        vo.setCommit(commit);
        vo.setBuildTime(buildTime);
        vo.setJdkVersion(jdkVersion);
        vo.setPf4jVersion(pf4jVersion);
        vo.setPluginId(pluginId);
        vo.setPluginDescription(pluginDescription);
        vo.setSpringBootVersion(springBootVersion);
        return vo;
    }

    /**
     * 仅返回版本号字符串（对齐源项目极简接口）。
     */
    public String getVersion() {
        return version;
    }
}
