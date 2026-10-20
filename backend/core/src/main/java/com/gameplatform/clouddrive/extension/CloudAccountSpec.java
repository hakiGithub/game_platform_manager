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

    /**
     * 宿主侧起始目录（账号内路径，{@code "/"} = 整盘）。
     * <p>与 Provider settings 的 root 字段解耦：settings 保留账号根，浏览始终按路径寻址；
     * ID 型 Provider 仅在重建挂载时把该路径解析成 Provider 原生根值。</p>
     */
    private String rootPath = "/";

    /** 凭证脱敏提示（尾号），供列表展示 */
    private String credentialHint;

    /** 备注 */
    private String remark;
}
