package com.gameplatform.clouddrive.extension;

import lombok.Data;

import java.io.Serializable;

/**
 * 云盘账号 spec（ADR-0024）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Data
public class CloudAccountSpec implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Provider 类型：baidu / quark / uc / aliyun / cloud189 / xunlei / openlist */
    private String providerType;

    /** 展示名（缺省用 name） */
    private String displayName;

    /** ProviderSettings 全字段 JSON（AES 密文，主应用 AesUtil；接口永不回显） */
    private String settingsCipher;

    /** 凭证脱敏提示（尾号），供列表展示 */
    private String credentialHint;

    /** 备注 */
    private String remark;
}
