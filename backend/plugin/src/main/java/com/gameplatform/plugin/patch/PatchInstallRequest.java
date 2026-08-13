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
}
