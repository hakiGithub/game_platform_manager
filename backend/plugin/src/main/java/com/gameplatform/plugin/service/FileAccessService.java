package com.gameplatform.plugin.service;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 远程文件访问服务。
 * <p>
 * 提供给插件使用的基于 SFTP 的远程文件操作能力，涵盖文件读写、上传下载、
 * 目录操作及文件信息查询。
 * <p>
 * 实现由宿主核心模块提供，通过插件 Spring 子容器注入。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface FileAccessService {

    // ===== 文件读写 =====

    /**
     * 读取远程文本文件内容。
     *
     * @param hostId     主机 ID
     * @param remotePath 远程文件路径
     * @return 文件文本内容
     */
    String readTextFile(Long hostId, String remotePath);

    /**
     * 写入远程文本文件（覆盖写入）。
     *
     * @param hostId     主机 ID
     * @param remotePath 远程文件路径
     * @param content    文本内容
     */
    void writeTextFile(Long hostId, String remotePath, String content);

    /**
     * 下载远程文件到内存。
     *
     * @param hostId     主机 ID
     * @param remotePath 远程文件路径
     * @return 文件字节数组
     */
    byte[] downloadFileToMemory(Long hostId, String remotePath);

    // ===== 文件上传/下载/删除/移动 =====

    /**
     * 上传 MultipartFile 到远程路径。
     *
     * @param hostId     主机 ID
     * @param remotePath 远程目标路径
     * @param file       待上传的 Spring multipart 文件
     */
    void uploadFile(Long hostId, String remotePath, MultipartFile file);

    /**
     * 上传本地文件到远程路径。
     *
     * @param hostId     主机 ID
     * @param remotePath 远程目标路径
     * @param localPath  本地源文件路径
     */
    void uploadLocalFile(Long hostId, String remotePath, String localPath);

    /**
     * 下载远程文件到本地路径。
     *
     * @param hostId     主机 ID
     * @param remotePath 远程源文件路径
     * @param localPath  本地目标路径
     */
    void downloadFile(Long hostId, String remotePath, String localPath);

    /**
     * 删除远程文件。
     *
     * @param hostId     主机 ID
     * @param remotePath 远程文件路径
     */
    void deleteFile(Long hostId, String remotePath);

    /**
     * 移动或重命名远程文件。
     *
     * @param hostId  主机 ID
     * @param oldPath 原路径
     * @param newPath 新路径
     */
    void moveFile(Long hostId, String oldPath, String newPath);

    // ===== 目录操作 =====

    /**
     * 列出远程目录下的文件与子目录。
     *
     * @param hostId     主机 ID
     * @param remotePath 远程目录路径
     * @return 文件信息列表；目录为空时返回空列表
     */
    List<FileInfo> listFiles(Long hostId, String remotePath);

    /**
     * 创建远程目录。
     *
     * @param hostId     主机 ID
     * @param remotePath 远程目录路径
     */
    void createDirectory(Long hostId, String remotePath);

    /**
     * 删除远程目录。
     *
     * @param hostId     主机 ID
     * @param remotePath 远程目录路径
     * @param recursive  是否递归删除非空目录
     */
    void deleteDirectory(Long hostId, String remotePath, boolean recursive);

    // ===== 查询 =====

    /**
     * 检查远程文件或目录是否存在。
     *
     * @param hostId     主机 ID
     * @param remotePath 远程路径
     * @return 存在返回 true，否则 false
     */
    boolean exists(Long hostId, String remotePath);

    /**
     * 获取远程文件/目录的元信息。
     *
     * @param hostId     主机 ID
     * @param remotePath 远程路径
     * @return 文件信息；不存在返回 null
     */
    FileInfo getFileInfo(Long hostId, String remotePath);

    // ===== 扩展能力（v1.1+） =====

    /**
     * 按指定编码读取远程文本文件。
     *
     * @param hostId     主机 ID
     * @param remotePath 远程文件路径
     * @param charset    文件编码
     * @return 文件文本内容
     */
    String readTextFile(Long hostId, String remotePath, java.nio.charset.Charset charset);

    /**
     * 读取远程文件指定字节范围。
     *
     * @param hostId     主机 ID
     * @param remotePath 远程文件路径
     * @param offset     起始字节偏移（&lt;0 表示从文件末尾反向计算）
     * @param length     读取字节数（&lt;=0 表示读到文件末尾）
     * @return 字节数组；offset 超出文件大小时返回空数组
     */
    byte[] getFileBytes(Long hostId, String remotePath, long offset, long length);

    /**
     * 远程文件 tail：从 offset 处读取增量字节并以字符串行回调。
     *
     * @param hostId     主机 ID
     * @param remotePath 远程文件路径
     * @param offset     起始字节偏移（首次传 0 或文件大小）
     * @param charset    文件编码
     * @param lineConsumer 行回调（每行调用一次）
     * @return 读取后的新 offset（下次调用传入）
     */
    long tailFile(Long hostId, String remotePath, long offset,
                  java.nio.charset.Charset charset, java.util.function.Consumer<String> lineConsumer);

    /**
     * 文件信息载体。
     * <p>
     * 描述远程文件/目录的元数据，包含名称、路径、类型、大小、修改时间、权限与所有者。
     */
    @Data
    class FileInfo {
        /** 文件名 */
        private String name;
        /** 完整路径 */
        private String path;
        /** 是否为目录 */
        private boolean directory;
        /** 文件大小（字节） */
        private long size;
        /** 最后修改时间（毫秒时间戳） */
        private long lastModified;
        /** 文件权限（如 rwxr-xr-x） */
        private String permissions;
        /** 文件所有者 */
        private String owner;
    }

    /**
     * 远程命令执行结果。
     */
    @Data
    class CommandResult {
        /** 是否成功（exitCode == 0） */
        private boolean success;
        /** 退出码 */
        private int exitCode;
        /** 标准输出 */
        private String output = "";
        /** 标准错误输出 */
        private String error = "";
    }

    /**
     * 在指定主机上执行远程命令（通过 SSH）。
     *
     * @param hostId    主机 ID
     * @param command   待执行命令
     * @param timeoutMs 超时时间（毫秒）
     * @return 命令执行结果
     */
    CommandResult executeCommand(Long hostId, String command, long timeoutMs);

    /**
     * 在指定主机上执行远程命令（使用默认超时）。
     *
     * @param hostId  主机 ID
     * @param command 待执行命令
     * @return 命令执行结果
     */
    default CommandResult executeCommand(Long hostId, String command) {
        return executeCommand(hostId, command, 30_000L);
    }
}
