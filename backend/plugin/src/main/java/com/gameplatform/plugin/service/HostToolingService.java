package com.gameplatform.plugin.service;

import java.util.List;

/**
 * 工具容器宿主能力（ADR-0026）：借用主应用 platform-tools 工具镜像在目标主机上
 * 执行语义化的文件处理（首版：压缩包解压），`docker run --rm` 临时容器、用完即销毁。
 * <p>
 * 镜像选择、命令拼装、临时目录清理、审计归主应用，插件不接触 docker 命令与镜像名；
 * 裸容器执行权不开放。后续新语义方法（hash 校验、编码转换等）逐个扩展并过 ADR。
 * <p>
 * 每次调用自动携带调用方（插件 ID）写入日志审计，来源不可伪造。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface HostToolingService {

    /**
     * 主机侧解压压缩包到指定目录（目录须已存在或其父目录可创建；相对主机根路径）。
     * <p>
     * 优先主机原生工具（tar/unzip/unrar/7z），缺失时回退 platform-tools 工具容器
     * （unrar/p7zip/bsdtar 预装）；两路都不可用抛异常。主机无 Docker 且无原生工具时
     * 调用方应回退平台中转。
     *
     * @param hostId         主机 ID
     * @param remoteArchive  压缩包在主机上的绝对路径
     * @param destDir        解压目标目录（主机绝对路径）
     * @param includePattern 产物筛选 glob 列表（逗号分隔、大小写不敏感，如 {@code *.vpk}）；
     *                       null/空 = 全量解压。提供时仅匹配文件平铺落位 destDir（先列清单，
     *                       空清单报错）
     * @return 实际落位文件名列表（平铺 basename）
     * @throws com.gameplatform.common.exception.BusinessException 主机不存在、压缩包不存在、
     *         无解压能力（原生+容器皆缺）、解压失败或 includePattern 筛选为空
     */
    List<String> extractArchive(Long hostId, String remoteArchive, String destDir, String includePattern);
}
