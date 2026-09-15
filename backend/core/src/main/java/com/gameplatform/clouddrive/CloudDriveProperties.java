package com.gameplatform.clouddrive;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 云盘宿主能力配置（ADR-0024）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
@Component
@ConfigurationProperties(prefix = "game-platform.cloud-drive")
public class CloudDriveProperties {

    /** SDK 数据目录（账号/挂载/作业记录落盘，宿主扩展表之外的引擎态） */
    private String dataDir = "./data/cloud-drive";

    /**
     * 凭证加密主密钥（任意口令，SDK 内 SHA-256 派生）。
     * 空时回退读环境变量 CLP_MASTER_KEY；两者皆空则 SDK 明文落盘并告警（仅限本地试用）。
     * 密钥丢失 = SDK 数据目录内凭证不可解密，需逐账号重贴。
     */
    private String masterKey;

    /** 同步转存默认超时（毫秒） */
    private long transferTimeoutMillis = 600_000L;

    /** 健康探针周期（毫秒），<=0 关闭 SDK 定时探针 */
    private long probeIntervalMillis = 1_800_000L;
}
