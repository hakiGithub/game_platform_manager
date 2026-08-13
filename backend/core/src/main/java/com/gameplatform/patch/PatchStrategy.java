package com.gameplatform.patch;

/**
 * 补丁安装执行策略（ADR-0006 决策 5 的矩阵输出）
 */
public enum PatchStrategy {

    /** 目标自治：SSH 远程下载 + 远程解压（非压缩包只需远程下载） */
    TARGET_DOWNLOAD_TARGET_EXTRACT,

    /** 平台代劳下载：平台下载 + SFTP 推压缩包 + 目标远程解压（目标能解压不能下载，仅 LAN） */
    PLATFORM_DOWNLOAD_TARGET_EXTRACT,

    /** 平台代劳下载+解压：平台下载 + 平台解压 + 推散文件（目标不能解压，仅 LAN） */
    PLATFORM_DOWNLOAD_PLATFORM_EXTRACT,

    /** 公网主机不能自治（不能下载或不能解压），平台不跨公网代劳 */
    ERROR_WAN_NOT_SELF_SUFFICIENT
}
