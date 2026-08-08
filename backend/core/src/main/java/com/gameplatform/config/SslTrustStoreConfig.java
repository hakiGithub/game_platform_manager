package com.gameplatform.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * SSL 信任库配置：让 JDK 使用操作系统证书库而非自带 cacerts。
 *
 * <p><b>背景：</b>JDK 默认使用 $JAVA_HOME/lib/security/cacerts 作为信任库，
 * 该文件可能缺少某些系统已信任的 CA 证书（如企业自签 CA、系统更新推送的根证书），
 * 导致 HTTPS 请求抛 PKIX path building failed。
 *
 * <p><b>方案：</b>检测操作系统，按平台特性切换信任库来源：
 * <ul>
 *   <li><b>Windows：</b>设置 {@code javax.net.ssl.trustStore=Windows-ROOT}，
 *       通过 sun.security.mscapi KeyStore 访问"受信任的根证书颁发机构"</li>
 *   <li><b>macOS：</b>设置 {@code javax.net.ssl.trustStore=KeychainStore}，
 *       通过 KeychainStore KeyStore 访问系统钥匙串</li>
 *   <li><b>Linux：</b>读取系统证书 bundle（PEM 格式，如
 *       /etc/ssl/certs/ca-certificates.crt），加载到内存 KeyStore，
 *       设置为默认 SSLContext。覆盖 Debian/Ubuntu/RHEL/CentOS/Fedora/OpenSUSE/Alpine 等主流发行版</li>
 * </ul>
 *
 * <p><b>生效时机：</b>static 初始化块在类加载时执行，早于 Spring Bean 初始化和任何 HTTPS 请求。
 * 本类被 {@code @Configuration} 标注，会被组件扫描加载，确保 static 块在应用启动早期执行。
 *
 * <p><b>影响范围：</b>全局生效，所有使用 JDK 默认 SSLContext 的 HTTP 客户端
 * （RestClient / Apache HttpClient 5 / HttpURLConnection）都使用系统证书库。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Configuration
public class SslTrustStoreConfig {

    /**
     * Linux 系统证书 bundle 常见路径（按发行版优先级排序）。
     */
    private static final String[] LINUX_CERT_PATHS = {
            "/etc/ssl/certs/ca-certificates.crt",  // Debian/Ubuntu (update-ca-certificates)
            "/etc/pki/tls/certs/ca-bundle.crt",    // RHEL/CentOS/Fedora (ca-certificates 包)
            "/etc/ssl/certs/ca-bundle.crt",        // OpenSUSE
            "/etc/ssl/cert.pem",                    // Alpine Linux / OpenBSD
            "/etc/pki/tls/cacert.pem"               // 某些自定义安装
    };

    /**
     * 静态初始化块：在类加载时立即配置 SSL 信任库。
     *
     * <p>使用 static 块而非 @PostConstruct，确保在任何 SSLContext 实例化前完成配置。
     * 一旦 SSLContext 首次初始化，后续修改系统属性不会影响已缓存的 SSLContext。
     */
    static {
        configureSystemTrustStore();
    }

    /**
     * 根据操作系统配置 SSL 信任库。
     */
    private static void configureSystemTrustStore() {
        String osName = System.getProperty("os.name", "");
        String osNameLower = osName.toLowerCase();

        // 避免重复设置（可能被 JVM 启动参数预先配置）
        String existingTrustStore = System.getProperty("javax.net.ssl.trustStore");
        if (existingTrustStore != null && !existingTrustStore.isBlank()) {
            log.info("SSL 信任库已通过启动参数配置: {}, 跳过自动配置", existingTrustStore);
            return;
        }

        if (osNameLower.contains("windows")) {
            // Windows-ROOT 对应"受信任的根证书颁发机构"证书库
            // JDK 通过 sun.security.mscapi KeyStore 实现访问 Windows 证书库
            System.setProperty("javax.net.ssl.trustStore", "Windows-ROOT");
            System.setProperty("javax.net.ssl.trustStoreType", "Windows-ROOT");
            log.info("SSL 信任库已配置为 Windows 系统证书库 (Windows-ROOT)");
        } else if (osNameLower.contains("mac") || osNameLower.contains("darwin")) {
            // macOS 使用 KeychainStore 访问系统钥匙串
            System.setProperty("javax.net.ssl.trustStore", "KeychainStore");
            System.setProperty("javax.net.ssl.trustStoreType", "KeychainStore");
            log.info("SSL 信任库已配置为 macOS 系统钥匙串 (KeychainStore)");
        } else {
            // Linux/其他：加载系统证书 bundle 到内存 KeyStore
            configureLinuxSystemCertificates();
        }
    }

    /**
     * Linux 平台：读取系统证书 bundle（PEM 格式），加载到内存 KeyStore，
     * 设置为默认 SSLContext。
     *
     * <p>JDK 不支持直接将 PEM 文件作为 {@code javax.net.ssl.trustStore}（仅支持 JKS/PKCS12），
     * 因此需要解析 PEM 并构建内存 KeyStore，再通过 {@link SSLContext#setDefault(SSLContext)}
     * 替换默认 SSLContext。此方法影响所有通过 {@link SSLContext#getDefault()} 获取
     * SSLContext 的 HTTP 客户端（RestClient / Apache HttpClient 5 / HttpURLConnection）。
     *
     * <p>如果系统证书 bundle 不存在或加载失败，回退到 JDK 默认 cacerts（不影响现有行为）。
     */
    private static void configureLinuxSystemCertificates() {
        File certFile = findLinuxCertBundle();
        if (certFile == null) {
            log.warn("Linux 系统证书 bundle 未找到，回退到 JDK 默认 cacerts。已检查路径: {}",
                    String.join(", ", LINUX_CERT_PATHS));
            return;
        }

        try {
            // 解析 PEM 文件中的所有 X.509 证书
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> certs;
            try (InputStream is = new FileInputStream(certFile)) {
                certs = cf.generateCertificates(is);
            }

            if (certs.isEmpty()) {
                log.warn("Linux 系统证书 bundle 为空: {}", certFile.getPath());
                return;
            }

            // 构建内存 KeyStore，将所有系统证书加入
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int idx = 0;
            List<Certificate> certList = new ArrayList<>(certs);
            for (Certificate cert : certList) {
                ks.setCertificateEntry("system-ca-" + idx, cert);
                idx++;
            }

            // 创建 TrustManagerFactory 并初始化
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);

            // 创建并设置默认 SSLContext
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, tmf.getTrustManagers(), null);
            SSLContext.setDefault(sslContext);

            log.info("Linux 系统证书库已加载: {} ({} 个证书)", certFile.getPath(), certList.size());
        } catch (Exception e) {
            log.error("加载 Linux 系统证书库失败: {} - {}", certFile.getPath(), e.getMessage());
        }
    }

    /**
     * 查找 Linux 系统证书 bundle 文件。
     *
     * @return 第一个存在的证书 bundle 文件，未找到返回 null
     */
    private static File findLinuxCertBundle() {
        for (String path : LINUX_CERT_PATHS) {
            File f = new File(path);
            if (f.exists() && f.canRead()) {
                return f;
            }
        }
        return null;
    }
}

