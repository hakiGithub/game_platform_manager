package com.gameplatform.plugin.patch;

import lombok.Builder;
import lombok.Data;

/**
 * 补丁安装请求（ADR-0006）
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@code targetPath} 为 safeRel 相对路径（相对实例安装路径 / 容器工作目录），
 *       与 {@code InstanceFileService} 约定一致，复用路径安全校验。</li>
 *   <li>{@code format} 可选；缺省按 URL 扩展名推断（tar.gz/tgz、tar.bz2/tbz2、tar.xz/txz、
 *       zip、gz/bz2/xz、其余视为非压缩包）。</li>
 *   <li>{@code sha256} 可选；提供则下载后校验，失败中止。</li>
 * </ul>
 */
@Data
@Builder
public class PatchInstallRequest {

    /** 目标实例 ID */
    private Long instanceId;

    /** 补丁资源 URL */
    private String url;

    /** 目标位置（safeRel 相对路径） */
    private String targetPath;

    /** 补丁格式（可选，缺省按 URL 扩展名推断） */
    private String format;

    /** 期望的 SHA-256（可选） */
    private String sha256;

    /**
     * HTTP 请求头（可选，云盘直连下载用，ADR-0025）。
     * <p>仅在目标主机远程下载路径（TARGET_DOWNLOAD_*）生效：curl 以 {@code --header @file}
     * 方式携带（临时文件 0600、用完即删，命令行不出现头内容）。远程无 curl 或
     * curl &lt; 7.55（不支持 @file）时任务以 {@code UNSUPPORTED_HEADER_FILE} 原因失败，
     * 调用方应回退平台中转。平台下载路径不支持请求头，带 headers 的请求若被决策为
     * 平台下载同样以该原因失败，不静默丢弃。
     */
    private java.util.Map<String, String> headers;
}
