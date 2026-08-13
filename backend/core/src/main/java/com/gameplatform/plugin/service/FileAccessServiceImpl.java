package com.gameplatform.plugin.service;

import com.gameplatform.service.FileService;
import com.gameplatform.util.SshUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 远程文件访问服务实现。
 * <p>
 * 委托转发至 {@link FileService}，不重复业务逻辑。
 * 通过 @Service 注册到主容器，再由 {@code PluginSpringContextFactory}
 * 注入到插件子容器供插件使用。
 * <p>
 * 由于 {@code FileService.FileInfo} 是 core 模块的内部类，
 * 本实现负责将其转换为插件可见的 {@link FileAccessService.FileInfo}。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileAccessServiceImpl implements FileAccessService {

    private final FileService fileService;

    @Override
    public String readTextFile(Long hostId, String remotePath) {
        return fileService.readTextFile(hostId, remotePath);
    }

    @Override
    public void writeTextFile(Long hostId, String remotePath, String content) {
        fileService.writeTextFile(hostId, remotePath, content);
    }

    @Override
    public byte[] downloadFileToMemory(Long hostId, String remotePath) {
        return fileService.downloadFileToMemory(hostId, remotePath);
    }

    @Override
    public void uploadFile(Long hostId, String remotePath, MultipartFile file) {
        fileService.uploadFile(hostId, remotePath, file);
    }

    @Override
    public void uploadLocalFile(Long hostId, String remotePath, String localPath) {
        fileService.uploadLocalFile(hostId, remotePath, localPath);
    }

    @Override
    public void downloadFile(Long hostId, String remotePath, String localPath) {
        fileService.downloadFile(hostId, remotePath, localPath);
    }

    @Override
    public void deleteFile(Long hostId, String remotePath) {
        fileService.deleteFile(hostId, remotePath);
    }

    @Override
    public void moveFile(Long hostId, String oldPath, String newPath) {
        fileService.moveFile(hostId, oldPath, newPath);
    }

    @Override
    public List<FileInfo> listFiles(Long hostId, String remotePath) {
        List<FileService.FileInfo> source = fileService.listFiles(hostId, remotePath);
        List<FileInfo> result = new ArrayList<>(source.size());
        for (FileService.FileInfo src : source) {
            result.add(convertFileInfo(src));
        }
        return result;
    }

    @Override
    public void createDirectory(Long hostId, String remotePath) {
        fileService.createDirectory(hostId, remotePath);
    }

    @Override
    public void deleteDirectory(Long hostId, String remotePath, boolean recursive) {
        fileService.deleteDirectory(hostId, remotePath, recursive);
    }

    @Override
    public boolean exists(Long hostId, String remotePath) {
        return fileService.exists(hostId, remotePath);
    }

    @Override
    public FileInfo getFileInfo(Long hostId, String remotePath) {
        FileService.FileInfo src = fileService.getFileInfo(hostId, remotePath);
        return src == null ? null : convertFileInfo(src);
    }

    @Override
    public String readTextFile(Long hostId, String remotePath, Charset charset) {
        byte[] bytes = downloadFileToMemory(hostId, remotePath);
        return new String(bytes, charset);
    }

    @Override
    public byte[] getFileBytes(Long hostId, String remotePath, long offset, long length) {
        return fileService.getFileBytes(hostId, remotePath, offset, length);
    }

    @Override
    public long tailFile(Long hostId, String remotePath, long offset, Charset charset,
                         Consumer<String> lineConsumer) {
        return fileService.tailFile(hostId, remotePath, offset, charset, lineConsumer);
    }

    @Override
    public CommandResult executeCommand(Long hostId, String command, long timeoutMs) {
        log.info("远程执行命令: hostId={}, command={}", hostId, command);
        SshUtil.CommandResult coreResult = fileService.executeCommand(hostId, command, timeoutMs);
        CommandResult result = new CommandResult();
        result.setSuccess(coreResult.isSuccess());
        result.setExitCode(coreResult.getExitCode());
        result.setOutput(coreResult.getOutput());
        result.setError(coreResult.getError());
        return result;
    }

    /**
     * 将 core 内部的 {@link FileService.FileInfo} 转换为插件可见的 {@link FileInfo}。
     *
     * @param src core 内部文件信息
     * @return 插件可见的文件信息
     */
    private static FileInfo convertFileInfo(FileService.FileInfo src) {
        FileInfo dst = new FileInfo();
        dst.setName(src.getName());
        dst.setPath(src.getPath());
        dst.setDirectory(src.isDirectory());
        dst.setSize(src.getSize());
        dst.setLastModified(src.getLastModified());
        dst.setPermissions(src.getPermissions());
        dst.setOwner(src.getOwner());
        return dst;
    }
}
