package com.gameplatform.plugin.service;

import com.gameplatform.plugin.service.FileAccessService.FileInfo;
import java.nio.charset.Charset;
import java.util.List;
import java.util.function.Consumer;

/**
 * 实例感知的文件访问 SPI。
 *
 * 调用方传 (instanceId, relativePath)，实现层根据实例 deployType
 * 自动路由到 SFTP（Native）或 docker exec/cp（Docker 类）。
 *
 * relativePath 语义：相对于实例"游戏数据根目录"的路径，使用正斜杠。
 * - Native/LinuxGSM：根目录 = instance.installPath
 * - Docker/Compose/LinuxGsmDocker：根目录 = 容器内工作目录
 *   （由部署适配器写入 runtimeMetadata.containerWorkDir）
 *
 * 路径安全：禁止使用 .. 跳出根目录（实现层校验，越界抛 IllegalArgumentException）。
 */
public interface InstanceFileService {

    // ===== 文本读写 =====
    String readTextFile(long instanceId, String relativePath);
    String readTextFile(long instanceId, String relativePath, Charset charset);
    void   writeTextFile(long instanceId, String relativePath, String content);
    void   writeTextFile(long instanceId, String relativePath, String content, Charset charset);

    // ===== 二进制读写 =====
    byte[] downloadFileToMemory(long instanceId, String relativePath);
    byte[] getFileBytes(long instanceId, String relativePath, long offset, long length);

    // ===== 上传/下载 =====
    void uploadLocalFile(long instanceId, String relativePath, String localPath);
    void downloadFile(long instanceId, String relativePath, String localPath);

    // ===== 文件管理 =====
    void deleteFile(long instanceId, String relativePath);
    void moveFile(long instanceId, String oldRelativePath, String newRelativePath);
    void copyFile(long instanceId, String srcRelativePath, String dstRelativePath);
    boolean exists(long instanceId, String relativePath);
    FileInfo getFileInfo(long instanceId, String relativePath);

    // ===== 目录管理 =====
    List<FileInfo> listFiles(long instanceId, String relativePath);
    void createDirectory(long instanceId, String relativePath);
    void deleteDirectory(long instanceId, String relativePath, boolean recursive);

    /**
     * 递归复制远程目录。
     *
     * <p>Native 实现通过 SSH 执行 {@code cp -r}；Docker 实现通过 {@code docker exec cp -r}
     * 在容器内复制。目标目录会先被清空，确保与源目录一致。
     */
    void copyDirectory(long instanceId, String srcRelativePath, String dstRelativePath);

    // ===== 流式增量（SourceMod 日志用）=====
    long tailFile(long instanceId, String relativePath, long offset,
                  Charset charset, Consumer<String> lineConsumer);

    // ===== 文件摘要 =====
    /**
     * 计算远程文件摘要。
     *
     * @param algorithm 算法名，如 "MD5"、"SHA-1"、"SHA-256"（MessageDigest 支持的标准名）
     * @return 摘要的十六进制小写字符串
     */
    String computeDigest(long instanceId, String relativePath, String algorithm);

    /** MD5 快捷方法，等价于 computeDigest(instanceId, relativePath, "MD5") */
    default String md5(long instanceId, String relativePath) {
        return computeDigest(instanceId, relativePath, "MD5");
    }
}
