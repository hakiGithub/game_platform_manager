package com.gameplatform.plugin.service;

import java.util.List;

/**
 * 工具容器宿主能力（ADR-0026）：借用主应用 platform-tools 工具镜像在目标主机上
 * 执行语义化的文件处理，`docker run --rm` 临时容器、用完即销毁。
 * <p>
 * 镜像选择、命令拼装、临时目录清理、审计归主应用，插件不接触 docker 命令与镜像名；
 * 裸容器执行权不开放。后续新语义方法（hash 校验、编码转换等）逐个扩展并过 ADR。
 * <p>
 * 每次调用自动携带调用方（插件 ID）写入日志审计，来源不可伪造。
 *
 * <p>ADR-0027 增补：文件摘要与 VPK 分析以<b>实例维度相对路径</b>为参数（与
 * {@link InstanceFileService} 同一寻址语义），由主应用负责解析到主机/容器实际
 * 存储位置（Native 直接定位 installPath；Docker 经 docker cp 落到主机临时目录）。
 *
 * @author GamePlatform
 * @version 1.1.0
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

    /**
     * 计算实例侧文件的 sha-256 摘要（ADR-0027）：Native 实例直接对主机文件计算；
     * Docker 实例先 docker cp 到主机临时目录。计算通道优先主机原生
     * sha256sum/shasum，缺失时回退 platform-tools 工具容器。
     *
     * @param instanceId  实例 ID
     * @param relativePath 实例根目录下的相对路径（同 InstanceFileService 寻址）
     * @return sha-256 小写 hex（64 位）
     */
    String fileDigest(Long instanceId, String relativePath);

    /**
     * 分析实例侧 VPK 地图文件（ADR-0027）：读取 VPK 目录树与 mission preload
     * （只读文件头部，不传输文件数据区到平台），返回战役名与章节信息。
     * <p>分析脚本由平台维护并自动分发；优先主机原生 python3，缺失时回退
     * platform-tools 工具容器（镜像需含 python3）；两路都不可用抛异常。
     *
     * @param instanceId      实例 ID
     * @param relativeVpkPath VPK 在实例根目录下的相对路径（如 left4dead2/addons/x.vpk）
     * @return 摘要 + 战役标题 + 章节（章节 code 即开图命令所需地图码）
     * @throws VpkInvalidException 文件不是有效的 L4D2 地图 VPK（无 mission 信息）
     */
    VpkAnalyzeResult analyzeVpk(Long instanceId, String relativeVpkPath);
}
