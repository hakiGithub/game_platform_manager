package com.gameplatform.patch;

import com.gameplatform.plugin.patch.HostCapabilities;

/**
 * 补丁安装决策引擎（ADR-0006 决策 5）
 *
 * <p>纯函数：由探测结果（工具集）、补丁格式与 isLanHost 机械推导执行策略，
 * 无人工猜测分支。矩阵：</p>
 * <pre>
 * 能下载 &amp;&amp; 能解压            → TARGET_DOWNLOAD_TARGET_EXTRACT
 * !能解压（无论能否下载）且 LAN   → PLATFORM_DOWNLOAD_PLATFORM_EXTRACT
 * 能解压 &amp;&amp; !能下载 且 LAN     → PLATFORM_DOWNLOAD_TARGET_EXTRACT
 * WAN 且不能自治                 → ERROR_WAN_NOT_SELF_SUFFICIENT
 * 非压缩包：解压能力不参与判定
 * </pre>
 */
public class PatchDecisionEngine {

    /**
     * 判定执行策略。
     *
     * @param caps      宿主机能力探测结果
     * @param format    补丁格式
     * @param isLanHost 目标主机是否为局域网（平台代劳门控，ADR-0004）
     */
    public PatchStrategy decide(HostCapabilities caps, PatchFormat format, boolean isLanHost) {
        boolean canDownload = canDownload(caps);

        if (!format.isArchive()) {
            // 非压缩包只需下载
            if (canDownload) {
                return PatchStrategy.TARGET_DOWNLOAD_TARGET_EXTRACT;
            }
            return isLanHost
                    ? PatchStrategy.PLATFORM_DOWNLOAD_PLATFORM_EXTRACT
                    : PatchStrategy.ERROR_WAN_NOT_SELF_SUFFICIENT;
        }

        boolean canExtract = canExtract(caps, format);
        if (canDownload && canExtract) {
            return PatchStrategy.TARGET_DOWNLOAD_TARGET_EXTRACT;
        }
        if (!isLanHost) {
            return PatchStrategy.ERROR_WAN_NOT_SELF_SUFFICIENT;
        }
        // LAN：平台可代劳
        if (canExtract) {
            // 目标能解压但不能下载：平台下载 + 推压缩包 + 远程解压
            return PatchStrategy.PLATFORM_DOWNLOAD_TARGET_EXTRACT;
        }
        // 目标不能解压（含不能下载）：平台下载 + 解压 + 推散文件
        return PatchStrategy.PLATFORM_DOWNLOAD_PLATFORM_EXTRACT;
    }

    /** 下载能力：curl 或 wget 存在即认为可尝试（实际失败再回退，ADR-0006 决策 5） */
    public boolean canDownload(HostCapabilities caps) {
        return caps.hasTool("curl") || caps.hasTool("wget");
    }

    /** 解压能力：按格式匹配 tar/unzip/bsdtar 与压缩工具 */
    public boolean canExtract(HostCapabilities caps, PatchFormat format) {
        if (!format.isArchive()) {
            return true;
        }
        boolean tar = caps.hasTool("tar");
        return switch (format) {
            case TAR_GZ -> tar && caps.hasTool("gzip");
            case TAR_BZ2 -> tar && caps.hasTool("bzip2");
            case TAR_XZ -> tar && caps.hasTool("xz");
            case ZIP -> caps.hasTool("unzip") || caps.hasTool("bsdtar");
            case GZ -> caps.hasTool("gzip");
            case BZ2 -> caps.hasTool("bzip2");
            case XZ -> caps.hasTool("xz");
            case PLAIN -> true;
        };
    }
}
