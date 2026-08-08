package com.gameplatform.service.docker;

import com.gameplatform.dto.docker.FileContentUpdateDTO;
import com.gameplatform.dto.docker.FileCopyDTO;
import com.gameplatform.vo.docker.ContainerFileInfoVO;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;

/**
 * Docker文件管理服务接口
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public interface DockerFileService {

    /**
     * 获取文件列表
     *
     * @param hostId      主机ID
     * @param containerId 容器ID
     * @param path        目录路径
     * @param showHidden  是否显示隐藏文件
     * @return 文件列表
     */
    FileListResult listFiles(Long hostId, String containerId, String path, Boolean showHidden);

    /**
     * 获取文件内容
     *
     * @param hostId      主机ID
     * @param containerId 容器ID
     * @param path        文件路径
     * @param encoding    文件编码
     * @param lines       读取行数限制
     * @return 文件内容
     */
    FileContentResult getFileContent(Long hostId, String containerId, String path, String encoding, Integer lines);

    /**
     * 更新文件内容
     *
     * @param hostId      主机ID
     * @param containerId 容器ID
     * @param dto         文件内容更新DTO
     * @return 更新结果
     */
    FileUpdateResult updateFileContent(Long hostId, String containerId, FileContentUpdateDTO dto);

    /**
     * 删除文件
     *
     * @param hostId      主机ID
     * @param containerId 容器ID
     * @param path        文件路径
     * @return 删除结果
     */
    FileDeleteResult deleteFile(Long hostId, String containerId, String path);

    /**
     * 上传文件
     *
     * @param hostId      主机ID
     * @param containerId 容器ID
     * @param file        文件
     * @param path        目标目录路径
     * @param overwrite   是否覆盖
     * @return 上传结果
     */
    FileUploadResult uploadFile(Long hostId, String containerId, MultipartFile file, String path, Boolean overwrite);

    /**
     * 下载文件
     *
     * @param hostId      主机ID
     * @param containerId 容器ID
     * @param path        文件路径
     * @param response    HTTP响应
     */
    void downloadFile(Long hostId, String containerId, String path, HttpServletResponse response);

    /**
     * 拷贝文件
     *
     * @param hostId      主机ID
     * @param containerId 容器ID
     * @param dto         拷贝参数
     * @return 拷贝结果
     */
    FileCopyResult copyFile(Long hostId, String containerId, FileCopyDTO dto);

    /**
     * 文件列表结果
     */
    record FileListResult(
            String currentPath,
            List<ContainerFileInfoVO> files
    ) {}

    /**
     * 文件内容结果
     */
    record FileContentResult(
            String path,
            String name,
            String content,
            Long size,
            String encoding,
            java.time.LocalDateTime modifiedTime,
            Boolean truncated
    ) {}

    /**
     * 文件更新结果
     */
    record FileUpdateResult(
            Boolean success,
            String path,
            Long size,
            String backupPath
    ) {}

    /**
     * 文件删除结果
     */
    record FileDeleteResult(
            Boolean success,
            String path
    ) {}

    /**
     * 文件上传结果
     */
    record FileUploadResult(
            Boolean success,
            String fileName,
            String filePath,
            Long size
    ) {}

    /**
     * 文件拷贝结果
     */
    record FileCopyResult(
            Boolean success,
            String direction,
            String sourcePath,
            String destinationPath,
            Integer filesCopied
    ) {}
}
