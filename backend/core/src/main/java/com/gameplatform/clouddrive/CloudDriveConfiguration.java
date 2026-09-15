package com.gameplatform.clouddrive;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.extension.ExtensionRouter;
import com.gameplatform.plugin.extension.ExtensionClientImpl;
import com.gameplatform.plugin.extension.ExtensionIdGenerator;
import com.gameplatform.plugin.extension.ExtensionQueryDialect;
import com.haki.clouddrive.core.security.AesGcmCredentialCipher;
import com.haki.clouddrive.core.security.CredentialCipher;
import com.haki.clouddrive.sdk.CloudDriveClient;
import com.haki.clouddrive.sdk.CloudDriveConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * 云盘宿主能力装配（ADR-0024）。
 * <p>
 * 进程内单例 {@link CloudDriveClient}（探针/转存引擎/连接池随容器关闭释放）；
 * 主应用以保留命名空间 {@code platform} 持有宿主 ExtensionClient，
 * 云盘账号等宿主数据落既有 {@code extensions} 共享表（ADR-0024，不新增表）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class CloudDriveConfiguration {

    /** 宿主保留命名空间（ADR-0024）：真实插件不得取该 pluginId */
    public static final String HOST_NAMESPACE = "platform";

    private final JdbcTemplate jdbcTemplate;
    private final ExtensionRouter extensionRouter;
    private final ExtensionQueryDialect extensionQueryDialect;
    private final ObjectMapper objectMapper;
    private final ExtensionIdGenerator idGenerator;

    @Bean(destroyMethod = "close")
    public CloudDriveClient cloudDriveClient(CloudDriveProperties props) {
        try {
            Path dataDir = Path.of(props.getDataDir());
            Files.createDirectories(dataDir);

            String masterKey = props.getMasterKey();
            if (masterKey == null || masterKey.isBlank()) {
                masterKey = System.getenv("CLP_MASTER_KEY");
            }

            CloudDriveConfig.Builder builder = CloudDriveConfig.builder(dataDir)
                    .probeIntervalMillis(props.getProbeIntervalMillis());
            if (masterKey != null && !masterKey.isBlank()) {
                CredentialCipher cipher = AesGcmCredentialCipher.fromMasterKey(masterKey);
                builder.cipher(cipher);
            } else {
                log.warn("[CloudDrive] 未配置 game-platform.cloud-drive.master-key 或 CLP_MASTER_KEY，"
                        + "SDK 数据目录内凭证将明文落盘（仅限本地试用）");
            }
            CloudDriveClient client = CloudDriveClient.create(builder.build());
            log.info("[CloudDrive] CloudDriveClient 已启动，providers={}, dataDir={}",
                    client.providerTypes(), dataDir.toAbsolutePath());
            return client;
        } catch (Exception e) {
            throw new IllegalStateException("CloudDriveClient 初始化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 宿主 ExtensionClient（ADR-0024 宿主化）：绑定保留命名空间 platform，
     * SHARED 策略模型落全局 extensions 表；插件绑定的 pluginId 与之互斥，双向隔离。
     */
    @Bean
    public ExtensionClient hostExtensionClient() {
        return new ExtensionClientImpl(jdbcTemplate, extensionRouter, HOST_NAMESPACE,
                extensionQueryDialect, objectMapper, Set.of(), idGenerator);
    }
}
