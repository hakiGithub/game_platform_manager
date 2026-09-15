package com.gameplatform.patch;

import com.gameplatform.plugin.patch.HostCapabilities;
import org.springframework.stereotype.Component;

/**
 * 补丁安装决策引擎（ADR-0006 决策 5；ADR-0021 决策 6 引入 Docker 代劳）
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
 *
 * <p>能力判定含 Docker 代劳（ADR-0021）：主机有 Docker 时视为既能下载也能解压
 * （工具镜像内预装 curl/wget/bsdtar 等），执行侧由 PatchInstallExecutor 负责在
 * 原生工具缺失时借 {@code docker run --rm} 完成下载/解压/校验。</p>
 */
@Component
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

    /** 下载能力：curl/wget 存在，或 Docker 可代劳（工具镜像预装下载器） */
    public boolean canDownload(HostCapabilities caps) {
        return caps.hasTool("curl") || caps.hasTool("wget") || caps.hasDocker();
    }

    /** 解压能力：原生工具齐备，或 Docker 可代劳（工具镜像预装 bsdtar 等） */
    public boolean canExtract(HostCapabilities caps, PatchFormat format) {
        return canExtractNative(caps, format) || caps.hasDocker();
    }

    /** 原生解压能力（不含 Docker 代劳）：按格式匹配 tar/unzip/bsdtar 与压缩工具 */
    public boolean canExtractNative(HostCapabilities caps, PatchFormat format) {
        if (!format.isArchive()) {
            return true;
        }
        boolean tar = caps.hasTool("tar");
        return switch (format) {
            case TAR_GZ -> tar && caps.hasTool("gzip");
            case TAR_BZ2 -> tar && caps.hasTool("bzip2");
            case TAR_XZ -> tar && caps.hasTool("xz");
            case ZIP -> caps.hasTool("unzip") || caps.hasTool("bsdtar");
            // ADR-0026：rar/7z 原生工具缺失时由 platform-tools 工具容器（unrar/7z）解压
            case RAR -> caps.hasTool("unrar") || caps.hasTool("7z");
            case SEVEN_Z -> caps.hasTool("7z");
            case GZ -> caps.hasTool("gzip");
            case BZ2 -> caps.hasTool("bzip2");
            case XZ -> caps.hasTool("xz");
            case PLAIN -> true;
        };
    }
}
