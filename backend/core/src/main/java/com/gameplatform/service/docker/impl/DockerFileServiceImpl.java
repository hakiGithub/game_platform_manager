package com.gameplatform.service.docker.impl;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.common.result.ResultCode;
import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.deploy.HostCredentials;
import com.gameplatform.dto.docker.FileContentUpdateDTO;
import com.gameplatform.dto.docker.FileCopyDTO;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.service.docker.DockerFileService;
import com.gameplatform.util.SshUtil;
import com.gameplatform.vo.docker.ContainerFileInfoVO;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Docker文件管理服务实现
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DockerFileServiceImpl implements DockerFileService {

    private final HostMapper hostMapper;
    private final SshUtil sshUtil;
    private final DeploymentAccess deployAccess;
    
    private static final long MAX_PREVIEW_SIZE = 10 * 1024 * 1024; // 10MB
    private static final long MAX_EDIT_SIZE = 1 * 1024 * 1024; // 1MB
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    public FileListResult listFiles(Long hostId, String containerId, String path, Boolean showHidden) {
        Host host = getHost(hostId);
        
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        
        // 使用 stat 输出，兼容 BusyBox 等精简 ls 实现
        StringBuilder command = new StringBuilder("docker exec ");
        command.append(containerId);
        command.append(" sh -c 'cd \"$1\" && ls -A | while IFS= read -r f; do ");
        if (!Boolean.TRUE.equals(showHidden)) {
            command.append("case \"$f\" in .*) continue;; esac; ");
        }
        command.append("([ -e \"$f\" ] || [ -L \"$f\" ]) && stat -c \"%A %h %U %G %s %Y %n\" \"$f\"; done' _ ");
        command.append(escapePath(path));

        SshUtil.CommandResult result = executeCommand(host, command.toString(), 30000);
        
        if (!result.isSuccess()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "获取文件列表失败: " + result.getError());
        }
        
        List<ContainerFileInfoVO> files = parseFileList(result.getOutput());
        
        return new FileListResult(path, files);
    }

    @Override
    public FileContentResult getFileContent(Long hostId, String containerId, String path, String encoding, Integer lines) {
        Host host = getHost(hostId);
        
        if (encoding == null || encoding.isEmpty()) {
            encoding = "UTF-8";
        }
        
        // 先检查文件大小
        String sizeCommand = String.format("docker exec %s stat -c %%s %s 2>/dev/null", 
                containerId, escapePath(path));
        SshUtil.CommandResult sizeResult = executeCommand(host, sizeCommand, 10000);
        
        long fileSize = 0;
        boolean truncated = false;
        
        if (sizeResult.isSuccess()) {
            try {
                fileSize = Long.parseLong(sizeResult.getOutput().trim());
                if (fileSize > MAX_PREVIEW_SIZE) {
                    throw new BusinessException(ResultCode.FAILED, "文件过大，超过预览限制(10MB)");
                }
            } catch (NumberFormatException e) {
                log.warn("解析文件大小失败: {}", sizeResult.getOutput());
            }
        }
        
        // 读取文件内容
        StringBuilder command = new StringBuilder("docker exec ");
        command.append(containerId);
        command.append(" cat ");
        
        if (lines != null && lines > 0) {
            // 使用 head 限制行数
            command = new StringBuilder("docker exec ")
                    .append(containerId)
                    .append(" sh -c 'head -n ")
                    .append(lines)
                    .append(" ")
                    .append(escapePath(path))
                    .append("'");
        } else {
            command.append(escapePath(path));
        }
        
        SshUtil.CommandResult result = executeCommand(host, command.toString(), 60000);
        
        if (!result.isSuccess()) {
            throw new BusinessException(ResultCode.NOT_FOUND, "读取文件失败: " + result.getError());
        }
        
        String content = result.getOutput();
        
        // 获取文件名
        String fileName = path;
        if (path.contains("/")) {
            fileName = path.substring(path.lastIndexOf("/") + 1);
        }
        
        // 获取修改时间
        LocalDateTime modifiedTime = getFileModifiedTime(host, containerId, path);
        
        return new FileContentResult(
                path,
                fileName,
                content,
                fileSize,
                encoding,
                modifiedTime,
                truncated
        );
    }

    @Override
    public FileUpdateResult updateFileContent(Long hostId, String containerId, FileContentUpdateDTO dto) {
        Host host = getHost(hostId);
        
        String path = dto.getPath();
        String content = dto.getContent();
        String encoding = dto.getEncoding() != null ? dto.getEncoding() : "UTF-8";
        boolean backup = Boolean.TRUE.equals(dto.getBackup());
        
        String backupPath = null;
        
        // 备份原文件
        if (backup) {
            backupPath = path + ".bak." + System.currentTimeMillis();
            String backupCommand = String.format("docker exec %s cp %s %s",
                    containerId, escapePath(path), escapePath(backupPath));
            SshUtil.CommandResult backupResult = executeCommand(host, backupCommand, 10000);
            
            if (!backupResult.isSuccess()) {
                log.warn("备份文件失败: {}", backupResult.getError());
                backupPath = null;
            }
        }
        
        // 写入新内容
        // 使用 base64 编码避免特殊字符问题
        String base64Content = Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
        String writeCommand = String.format(
                "docker exec %s sh -c 'echo %s | base64 -d > %s'",
                containerId, base64Content, escapePath(path)
        );
        
        SshUtil.CommandResult result = executeCommand(host, writeCommand, 30000);
        
        if (!result.isSuccess()) {
            throw new BusinessException(ResultCode.FAILED, "写入文件失败: " + result.getError());
        }
        
        long newSize = content.getBytes(StandardCharsets.UTF_8).length;
        
        return new FileUpdateResult(true, path, newSize, backupPath);
    }

    @Override
    public FileDeleteResult deleteFile(Long hostId, String containerId, String path) {
        Host host = getHost(hostId);
        
        String command = String.format("docker exec %s rm -rf %s", containerId, escapePath(path));
        SshUtil.CommandResult result = executeCommand(host, command, 30000);
        
        if (!result.isSuccess()) {
            throw new BusinessException(ResultCode.FAILED, "删除文件失败: " + result.getError());
        }
        
        return new FileDeleteResult(true, path);
    }

    @Override
    public FileUploadResult uploadFile(Long hostId, String containerId, MultipartFile file, String path, Boolean overwrite) {
        Host host = getHost(hostId);
        
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        
        String fileName = file.getOriginalFilename();
        String targetPath = path.endsWith("/") ? path + fileName : path + "/" + fileName;
        
        try {
            // 先上传到主机临时目录
            Path tempFile = Files.createTempFile("docker-upload-", ".tmp");
            file.transferTo(tempFile);
            
            // 使用 docker cp 将文件复制到容器
            String copyCommand = String.format("docker cp %s %s:%s",
                    tempFile.toString(), containerId, escapePath(targetPath));
            
            SshUtil.CommandResult result = executeCommand(host, copyCommand, 60000);
            
            // 删除临时文件
            Files.deleteIfExists(tempFile);
            
            if (!result.isSuccess()) {
                return new FileUploadResult(false, fileName, targetPath, 0L);
            }
            
            return new FileUploadResult(true, fileName, targetPath, file.getSize());
            
        } catch (Exception e) {
            log.error("上传文件失败: {}", e.getMessage(), e);
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED, "上传文件失败: " + e.getMessage());
        }
    }

    @Override
    public void downloadFile(Long hostId, String containerId, String path, HttpServletResponse response) {
        Host host = getHost(hostId);
        
        try {
            // 获取文件名
            String fileName = path;
            if (path.contains("/")) {
                fileName = path.substring(path.lastIndexOf("/") + 1);
            }
            
            // 设置响应头
            response.setContentType("application/octet-stream");
            response.setHeader("Content-Disposition", "attachment; filename=\"" + fileName + "\"");
            
            // 使用 docker cp 将文件从容器复制到主机临时目录，然后读取
            Path tempFile = Files.createTempFile("docker-download-", ".tmp");
            String copyCommand = String.format("docker cp %s:%s %s",
                    containerId, escapePath(path), tempFile.toString());
            
            SshUtil.CommandResult result = executeCommand(host, copyCommand, 60000);
            
            if (result.isSuccess()) {
                // 读取文件并写入响应
                Files.copy(tempFile, response.getOutputStream());
            }
            
            // 删除临时文件
            Files.deleteIfExists(tempFile);
            
        } catch (Exception e) {
            log.error("下载文件失败: {}", e.getMessage(), e);
            throw new BusinessException(ResultCode.FILE_DOWNLOAD_FAILED, "下载文件失败: " + e.getMessage());
        }
    }

    @Override
    public FileCopyResult copyFile(Long hostId, String containerId, FileCopyDTO dto) {
        Host host = getHost(hostId);
        
        String direction = dto.getDirection();
        String sourcePath = dto.getSourcePath();
        String destPath = dto.getDestinationPath();
        boolean overwrite = Boolean.TRUE.equals(dto.getOverwrite());
        
        String command;
        
        if ("toContainer".equals(direction)) {
            // 主机到容器
            command = String.format("docker cp %s %s:%s",
                    escapePath(sourcePath), containerId, escapePath(destPath));
        } else if ("fromContainer".equals(direction)) {
            // 容器到主机
            command = String.format("docker cp %s:%s %s",
                    containerId, escapePath(sourcePath), escapePath(destPath));
        } else {
            throw new BusinessException(ResultCode.VALIDATE_FAILED, "无效的拷贝方向: " + direction);
        }
        
        SshUtil.CommandResult result = executeCommand(host, command, 60000);
        
        if (!result.isSuccess()) {
            throw new BusinessException(ResultCode.FAILED, "拷贝文件失败: " + result.getError());
        }
        
        return new FileCopyResult(true, direction, sourcePath, destPath, 1);
    }

    // ========== 私有方法 ==========

    private Host getHost(Long hostId) {
        Host host = hostMapper.selectById(hostId);
        if (host == null) {
            throw new BusinessException(ResultCode.HOST_NOT_FOUND);
        }
        return host;
    }

    private SshUtil.CommandResult executeCommand(Host host, String command, int timeout) {
        try {
            HostCredentials conn = deployAccess.credentials(host);
            return sshUtil.executeCommand(
                    conn.host(), conn.port(), conn.username(), null, conn.password(),
                    command,
                    timeout
            );
        } catch (Exception e) {
            log.error("执行SSH命令失败: {}", e.getMessage(), e);
            SshUtil.CommandResult errorResult = new SshUtil.CommandResult();
            errorResult.setExitCode(1);
            errorResult.setOutput("");
            errorResult.setError(e.getMessage());
            errorResult.setSuccess(false);
            return errorResult;
        }
    }

    private List<ContainerFileInfoVO> parseFileList(String output) {
        List<ContainerFileInfoVO> files = new ArrayList<>();
        String[] lines = output.split("\n");
        
        // 跳过第一行 total
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            
            ContainerFileInfoVO file = parseFileLine(line);
            if (file != null) {
                files.add(file);
            }
        }
        
        // 排序：目录在前，文件在后
        files.sort((a, b) -> {
            if (a.getIsDirectory() && !b.getIsDirectory()) return -1;
            if (!a.getIsDirectory() && b.getIsDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        
        return files;
    }

    private ContainerFileInfoVO parseFileLine(String line) {
        // 格式: drwxr-xr-x 2 root root 4096 1711184800 dirname
        // 格式: -rw-r--r-- 1 root root 1234 1711184800 filename
        
        Pattern pattern = Pattern.compile(
                "^([dls-][rwx-]{9})\\s+(\\d+)\\s+(\\S+)\\s+(\\S+)\\s+(\\d+)\\s+(\\d+)\\s+(.+)$"
        );
        
        Matcher matcher = pattern.matcher(line);
        
        if (matcher.find()) {
            ContainerFileInfoVO file = new ContainerFileInfoVO();
            
            String permissions = matcher.group(1);
            file.setPermissions(permissions);
            file.setIsDirectory(permissions.startsWith("d"));
            
            file.setOwner(matcher.group(3));
            file.setGroup(matcher.group(4));
            file.setSize(Long.parseLong(matcher.group(5)));
            
            try {
                long timestamp = Long.parseLong(matcher.group(6));
                file.setModifiedTime(LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneId.systemDefault()));
            } catch (Exception e) {
                file.setModifiedTime(LocalDateTime.now());
            }
            
            file.setName(matcher.group(7));
            
            return file;
        }
        
        return null;
    }

    private LocalDateTime getFileModifiedTime(Host host, String containerId, String path) {
        String command = String.format("docker exec %s stat -c %%Y %s 2>/dev/null",
                containerId, escapePath(path));
        SshUtil.CommandResult result = executeCommand(host, command, 10000);
        
        if (result.isSuccess()) {
            try {
                long timestamp = Long.parseLong(result.getOutput().trim());
                return LocalDateTime.ofInstant(Instant.ofEpochSecond(timestamp), ZoneId.systemDefault());
            } catch (NumberFormatException e) {
                log.warn("解析修改时间失败: {}", result.getOutput());
            }
        }
        
        return LocalDateTime.now();
    }

    private String escapePath(String path) {
        if (path == null) return "";
        // 简单的路径转义
        return path.replace(" ", "\\ ").replace("\"", "\\\"");
    }
}
