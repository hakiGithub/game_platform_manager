package com.gameplatform.service.impl;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.config.GamePlatformConfig;
import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.deploy.HostCredentials;
import com.gameplatform.entity.BackupRecord;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.BackupRecordMapper;
import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.service.BackupService;
import com.gameplatform.service.InstanceService;
import com.gameplatform.util.AesUtil;
import com.gameplatform.util.SshUtil;
import lombok.extern.slf4j.Slf4j;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * 备份服务实现类
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
public class BackupServiceImpl implements BackupService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final int MAX_RETRY_COUNT = 1;
    private static final Map<Long, AtomicBoolean> backupCancellationMap = new ConcurrentHashMap<>();

    @Autowired
    private BackupRecordMapper backupRecordMapper;

    @Autowired
    private GameInstanceMapper gameInstanceMapper;

    @Autowired
    private HostMapper hostMapper;

    @Autowired
    private InstanceService instanceService;

    @Autowired
    private SshUtil sshUtil;

    @Autowired
    private AesUtil aesUtil;

    @Autowired
    private DeploymentAccess deployAccess;

    @Autowired
    private GamePlatformConfig gamePlatformConfig;

    private String backupBasePath;

    @PostConstruct
    public void init() {
        this.backupBasePath = gamePlatformConfig.getBackup().getBackupDir();
        // 确保备份目录存在
        try {
            Files.createDirectories(Paths.get(backupBasePath));
        } catch (IOException e) {
            log.error("创建备份目录失败: {}", backupBasePath, e);
        }
    }

    @Override
    @Transactional
    public BackupRecord createDatabaseBackup(Long instanceId, String backupName, String backupType,
                                              String databaseType, String description) {
        // 验证实例存在
        GameInstance instance = gameInstanceMapper.selectById(instanceId);
        if (instance == null) {
            throw new BusinessException("实例不存在");
        }

        // 验证数据库配置
        Map<String, Object> dbConfig = instance.getDatabaseConfig();
        if (dbConfig == null || dbConfig.isEmpty()) {
            throw new BusinessException("实例未配置数据库信息");
        }

        // 创建备份记录
        BackupRecord record = new BackupRecord();
        record.setInstanceId(instanceId);
        record.setBackupName(backupName);
        record.setBackupType(backupType);
        record.setTargetType(BackupRecord.TARGET_TYPE_DATABASE);
        record.setDatabaseType(databaseType);
        record.setDescription(description);
        record.setStatus(BackupRecord.STATUS_BACKING_UP);
        record.setProgress(0);
        record.setRetryCount(0);
        record.setBackupTime(LocalDateTime.now());
        backupRecordMapper.insert(record);

        // 异步执行备份
        Long backupId = record.getId();
        backupCancellationMap.put(backupId, new AtomicBoolean(false));
        performDatabaseBackupAsync(backupId, instance, dbConfig, databaseType);

        return record;
    }

    @Override
    @Transactional
    public BackupRecord createFileBackup(Long instanceId, String backupName, String backupType,
                                          String sourcePath, String description) {
        // 验证实例存在
        GameInstance instance = gameInstanceMapper.selectById(instanceId);
        if (instance == null) {
            throw new BusinessException("实例不存在");
        }

        // 验证主机存在
        Host host = hostMapper.selectById(instance.getHostId());
        if (host == null) {
            throw new BusinessException("主机不存在");
        }

        // 如果未指定源路径,使用实例安装路径
        if (sourcePath == null || sourcePath.isEmpty()) {
            sourcePath = instance.getInstallPath();
        }
        if (sourcePath == null || sourcePath.isEmpty()) {
            throw new BusinessException("未指定备份源路径");
        }

        // 创建备份记录
        BackupRecord record = new BackupRecord();
        record.setInstanceId(instanceId);
        record.setBackupName(backupName);
        record.setBackupType(backupType);
        record.setTargetType(BackupRecord.TARGET_TYPE_FILES);
        record.setSourcePath(sourcePath);
        record.setDescription(description);
        record.setStatus(BackupRecord.STATUS_BACKING_UP);
        record.setProgress(0);
        record.setRetryCount(0);
        record.setBackupTime(LocalDateTime.now());
        backupRecordMapper.insert(record);

        // 异步执行备份
        Long backupId = record.getId();
        backupCancellationMap.put(backupId, new AtomicBoolean(false));
        performFileBackupAsync(backupId, instance, host, sourcePath);

        return record;
    }

    /**
     * 异步执行数据库备份
     */
    @Async
    protected void performDatabaseBackupAsync(Long backupId, GameInstance instance,
                                               Map<String, Object> dbConfig, String databaseType) {
        try {
            performDatabaseBackup(backupId, instance, dbConfig, databaseType);
        } catch (Exception e) {
            log.error("数据库备份失败: backupId={}, error={}", backupId, e.getMessage(), e);
            handleBackupFailure(backupId, e.getMessage());
        }
    }

    /**
     * 执行数据库备份
     */
    private void performDatabaseBackup(Long backupId, GameInstance instance,
                                        Map<String, Object> dbConfig, String databaseType) throws Exception {
        AtomicBoolean cancelled = backupCancellationMap.get(backupId);

        // 更新进度
        updateProgress(backupId, 10);

        // 检查是否取消
        if (cancelled != null && cancelled.get()) {
            throw new InterruptedException("备份已取消");
        }

        // 构建备份文件名
        String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
        String backupFileName = String.format("db_backup_%s_%s_%s.sql",
                instance.getInstanceName(), databaseType.toLowerCase(), timestamp);
        String compressedFileName = backupFileName + ".zip";

        // 创建临时目录
        Path tempDir = Files.createTempDirectory("backup_" + backupId);
        Path sqlFilePath = tempDir.resolve(backupFileName);
        Path zipFilePath = tempDir.resolve(compressedFileName);

        try {
            updateProgress(backupId, 20);

            // 根据数据库类型执行导出
            switch (databaseType.toUpperCase()) {
                case BackupRecord.DATABASE_TYPE_MYSQL:
                    exportMySqlDatabase(dbConfig, sqlFilePath.toString());
                    break;
                case BackupRecord.DATABASE_TYPE_POSTGRESQL:
                    exportPostgreSqlDatabase(dbConfig, sqlFilePath.toString());
                    break;
                case BackupRecord.DATABASE_TYPE_SQLITE:
                    exportSqliteDatabase(dbConfig, sqlFilePath.toString());
                    break;
                default:
                    throw new BusinessException("不支持的数据库类型: " + databaseType);
            }

            updateProgress(backupId, 60);

            // 检查是否取消
            if (cancelled != null && cancelled.get()) {
                throw new InterruptedException("备份已取消");
            }

            // 压缩文件
            compressFile(sqlFilePath.toString(), zipFilePath.toString());

            updateProgress(backupId, 80);

            // 移动备份文件到存储目录
            String finalBackupPath = moveBackupFile(zipFilePath.toString(), compressedFileName);

            updateProgress(backupId, 90);

            // 计算MD5
            String fileMd5 = calculateFileMd5(finalBackupPath);
            long fileSize = new File(finalBackupPath).length();

            updateProgress(backupId, 100);

            // 更新备份记录
            backupRecordMapper.updateCompleteInfo(backupId, BackupRecord.STATUS_SUCCESS,
                    fileSize, fileMd5, null);

            // 更新实例最后备份时间
            instance.setLastBackupTime(LocalDateTime.now());
            gameInstanceMapper.updateById(instance);

            log.info("数据库备份成功: backupId={}, file={}", backupId, finalBackupPath);

        } finally {
            // 清理临时文件
            cleanupTempFiles(tempDir.toFile());
            backupCancellationMap.remove(backupId);
        }
    }

    /**
     * 导出MySQL数据库
     */
    private void exportMySqlDatabase(Map<String, Object> dbConfig, String outputPath) throws Exception {
        String host = (String) dbConfig.getOrDefault("host", "localhost");
        int port = (int) dbConfig.getOrDefault("port", 3306);
        String database = (String) dbConfig.get("database");
        String username = (String) dbConfig.get("username");
        String password = (String) dbConfig.get("password");

        if (database == null || username == null) {
            throw new BusinessException("MySQL数据库配置不完整");
        }

        // 解密密码
        if (password != null && !password.isEmpty()) {
            password = aesUtil.decrypt(password);
        }

        // 构建mysqldump命令
        StringBuilder command = new StringBuilder("mysqldump");
        command.append(" -h").append(host);
        command.append(" -P").append(port);
        command.append(" -u").append(username);
        if (password != null && !password.isEmpty()) {
            command.append(" -p").append(password);
        }
        command.append(" --single-transaction");
        command.append(" --routines");
        command.append(" --triggers");
        command.append(" ").append(database);
        command.append(" > ").append(outputPath);

        executeCommand(command.toString());
    }

    /**
     * 导出PostgreSQL数据库
     */
    private void exportPostgreSqlDatabase(Map<String, Object> dbConfig, String outputPath) throws Exception {
        String host = (String) dbConfig.getOrDefault("host", "localhost");
        int port = (int) dbConfig.getOrDefault("port", 5432);
        String database = (String) dbConfig.get("database");
        String username = (String) dbConfig.get("username");
        String password = (String) dbConfig.get("password");

        if (database == null || username == null) {
            throw new BusinessException("PostgreSQL数据库配置不完整");
        }

        // 解密密码
        if (password != null && !password.isEmpty()) {
            password = aesUtil.decrypt(password);
        }

        // 构建pg_dump命令
        StringBuilder command = new StringBuilder("pg_dump");
        command.append(" -h ").append(host);
        command.append(" -p ").append(port);
        command.append(" -U ").append(username);
        command.append(" -F p"); // 纯文本格式
        command.append(" -f ").append(outputPath);
        command.append(" ").append(database);

        // 设置环境变量PGPASSWORD
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command.toString());
        if (password != null && !password.isEmpty()) {
            pb.environment().put("PGPASSWORD", password);
        }

        executeProcess(pb);
    }

    /**
     * 导出SQLite数据库
     */
    private void exportSqliteDatabase(Map<String, Object> dbConfig, String outputPath) throws Exception {
        String dbPath = (String) dbConfig.get("path");
        if (dbPath == null) {
            throw new BusinessException("SQLite数据库路径未配置");
        }

        // SQLite直接复制文件或使用.dump命令
        String command = String.format("sqlite3 %s .dump > %s", dbPath, outputPath);
        executeCommand(command);
    }

    /**
     * 异步执行文件备份
     */
    @Async
    protected void performFileBackupAsync(Long backupId, GameInstance instance, Host host, String sourcePath) {
        try {
            performFileBackup(backupId, instance, host, sourcePath);
        } catch (Exception e) {
            log.error("文件备份失败: backupId={}, error={}", backupId, e.getMessage(), e);
            handleBackupFailure(backupId, e.getMessage());
        }
    }

    /**
     * 执行文件备份
     */
    private void performFileBackup(Long backupId, GameInstance instance, Host host, String sourcePath) throws Exception {
        AtomicBoolean cancelled = backupCancellationMap.get(backupId);

        // 更新进度
        updateProgress(backupId, 10);

        // 检查是否取消
        if (cancelled != null && cancelled.get()) {
            throw new InterruptedException("备份已取消");
        }

        // 构建备份文件名
        String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
        String backupFileName = String.format("file_backup_%s_%s.tar.gz",
                instance.getInstanceName(), timestamp);

        // 创建临时目录
        Path tempDir = Files.createTempDirectory("backup_" + backupId);
        Path tarFilePath = tempDir.resolve(backupFileName);

        try {
            updateProgress(backupId, 20);

            // 凭据解析统一走 DeploymentAccess
            HostCredentials conn = deployAccess.credentials(host);

            updateProgress(backupId, 30);

            // 在远程主机上打包文件
            String remoteTarPath = "/tmp/" + backupFileName;
            String tarCommand = String.format("tar -czf %s -C %s .", remoteTarPath, sourcePath);

            SshUtil.CommandResult result = sshUtil.executeCommand(
                    conn.host(),
                    conn.port(),
                    conn.username(),
                    conn.privateKey(),
                    null,
                    tarCommand
            );

            if (!result.isSuccess()) {
                throw new BusinessException("远程打包失败: " + result.getError());
            }

            updateProgress(backupId, 60);

            // 检查是否取消
            if (cancelled != null && cancelled.get()) {
                // 清理远程临时文件
                sshUtil.executeCommand(conn.host(), conn.port(), conn.username(),
                        conn.privateKey(), null, "rm -f " + remoteTarPath);
                throw new InterruptedException("备份已取消");
            }

            updateProgress(backupId, 70);

            // 下载打包文件
            boolean downloadSuccess = sshUtil.downloadFile(
                    conn.host(),
                    conn.port(),
                    conn.username(),
                    conn.privateKey(),
                    null,
                    remoteTarPath,
                    tarFilePath.toString()
            );

            if (!downloadSuccess) {
                throw new BusinessException("下载备份文件失败");
            }

            updateProgress(backupId, 80);

            // 清理远程临时文件
            sshUtil.executeCommand(conn.host(), conn.port(), conn.username(),
                    conn.privateKey(), null, "rm -f " + remoteTarPath);

            updateProgress(backupId, 90);

            // 移动备份文件到存储目录
            String finalBackupPath = moveBackupFile(tarFilePath.toString(), backupFileName);

            // 计算MD5
            String fileMd5 = calculateFileMd5(finalBackupPath);
            long fileSize = new File(finalBackupPath).length();

            updateProgress(backupId, 100);

            // 更新备份记录
            backupRecordMapper.updateCompleteInfo(backupId, BackupRecord.STATUS_SUCCESS,
                    fileSize, fileMd5, null);

            // 更新实例最后备份时间
            instance.setLastBackupTime(LocalDateTime.now());
            gameInstanceMapper.updateById(instance);

            log.info("文件备份成功: backupId={}, file={}", backupId, finalBackupPath);

        } finally {
            // 清理临时文件
            cleanupTempFiles(tempDir.toFile());
            backupCancellationMap.remove(backupId);
        }
    }

    /**
     * 处理备份失败
     */
    private void handleBackupFailure(Long backupId, String errorMessage) {
        BackupRecord record = backupRecordMapper.selectById(backupId);
        if (record == null) {
            return;
        }

        // 检查是否需要重试
        if (record.getRetryCount() < MAX_RETRY_COUNT) {
            backupRecordMapper.incrementRetryCount(backupId);
            log.info("备份失败,准备重试: backupId={}, retryCount={}", backupId, record.getRetryCount() + 1);
            // 这里可以实现重试逻辑
        } else {
            backupRecordMapper.updateCompleteInfo(backupId, BackupRecord.STATUS_FAILED, 0L, null, errorMessage);
        }

        backupCancellationMap.remove(backupId);
    }

    @Override
    @Transactional
    public void restoreBackup(Long backupId) {
        BackupRecord record = backupRecordMapper.selectById(backupId);
        if (record == null) {
            throw new BusinessException("备份记录不存在");
        }

        if (!BackupRecord.STATUS_SUCCESS.equals(record.getStatus())) {
            throw new BusinessException("备份文件不可用");
        }

        GameInstance instance = gameInstanceMapper.selectById(record.getInstanceId());
        if (instance == null) {
            throw new BusinessException("实例不存在");
        }

        Host host = hostMapper.selectById(instance.getHostId());
        if (host == null) {
            throw new BusinessException("主机不存在");
        }

        // 异步执行还原
        performRestoreAsync(backupId, record, instance, host);
    }

    /**
     * 异步执行还原
     */
    @Async
    protected void performRestoreAsync(Long backupId, BackupRecord record, GameInstance instance, Host host) {
        try {
            if (BackupRecord.TARGET_TYPE_DATABASE.equals(record.getTargetType())) {
                restoreDatabase(backupId, record, instance);
            } else {
                restoreFiles(backupId, record, instance, host);
            }
        } catch (Exception e) {
            log.error("还原失败: backupId={}, error={}", backupId, e.getMessage(), e);
            throw new BusinessException("还原失败: " + e.getMessage());
        }
    }

    /**
     * 还原数据库
     */
    private void restoreDatabase(Long backupId, BackupRecord record, GameInstance instance) throws Exception {
        Map<String, Object> dbConfig = instance.getDatabaseConfig();
        if (dbConfig == null) {
            throw new BusinessException("实例未配置数据库信息");
        }

        String databaseType = record.getDatabaseType();
        String backupFilePath = record.getFilePath();

        // 解压备份文件
        Path tempDir = Files.createTempDirectory("restore_" + backupId);
        Path sqlFilePath = tempDir.resolve("restore.sql");

        try {
            // 解压
            decompressFile(backupFilePath, sqlFilePath.toString());

            // 根据数据库类型执行导入
            switch (databaseType) {
                case BackupRecord.DATABASE_TYPE_MYSQL:
                    importMySqlDatabase(dbConfig, sqlFilePath.toString());
                    break;
                case BackupRecord.DATABASE_TYPE_POSTGRESQL:
                    importPostgreSqlDatabase(dbConfig, sqlFilePath.toString());
                    break;
                case BackupRecord.DATABASE_TYPE_SQLITE:
                    importSqliteDatabase(dbConfig, sqlFilePath.toString());
                    break;
                default:
                    throw new BusinessException("不支持的数据库类型: " + databaseType);
            }

            log.info("数据库还原成功: backupId={}", backupId);

        } finally {
            cleanupTempFiles(tempDir.toFile());
        }
    }

    /**
     * 导入MySQL数据库
     */
    private void importMySqlDatabase(Map<String, Object> dbConfig, String sqlFilePath) throws Exception {
        String host = (String) dbConfig.getOrDefault("host", "localhost");
        int port = (int) dbConfig.getOrDefault("port", 3306);
        String database = (String) dbConfig.get("database");
        String username = (String) dbConfig.get("username");
        String password = (String) dbConfig.get("password");

        if (database == null || username == null) {
            throw new BusinessException("MySQL数据库配置不完整");
        }

        // 解密密码
        if (password != null && !password.isEmpty()) {
            password = aesUtil.decrypt(password);
        }

        // 构建mysql命令
        StringBuilder command = new StringBuilder("mysql");
        command.append(" -h").append(host);
        command.append(" -P").append(port);
        command.append(" -u").append(username);
        if (password != null && !password.isEmpty()) {
            command.append(" -p").append(password);
        }
        command.append(" ").append(database);
        command.append(" < ").append(sqlFilePath);

        executeCommand(command.toString());
    }

    /**
     * 导入PostgreSQL数据库
     */
    private void importPostgreSqlDatabase(Map<String, Object> dbConfig, String sqlFilePath) throws Exception {
        String host = (String) dbConfig.getOrDefault("host", "localhost");
        int port = (int) dbConfig.getOrDefault("port", 5432);
        String database = (String) dbConfig.get("database");
        String username = (String) dbConfig.get("username");
        String password = (String) dbConfig.get("password");

        if (database == null || username == null) {
            throw new BusinessException("PostgreSQL数据库配置不完整");
        }

        // 解密密码
        if (password != null && !password.isEmpty()) {
            password = aesUtil.decrypt(password);
        }

        // 构建psql命令
        StringBuilder command = new StringBuilder("psql");
        command.append(" -h ").append(host);
        command.append(" -p ").append(port);
        command.append(" -U ").append(username);
        command.append(" -d ").append(database);
        command.append(" -f ").append(sqlFilePath);

        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command.toString());
        if (password != null && !password.isEmpty()) {
            pb.environment().put("PGPASSWORD", password);
        }

        executeProcess(pb);
    }

    /**
     * 导入SQLite数据库
     */
    private void importSqliteDatabase(Map<String, Object> dbConfig, String sqlFilePath) throws Exception {
        String dbPath = (String) dbConfig.get("path");
        if (dbPath == null) {
            throw new BusinessException("SQLite数据库路径未配置");
        }

        String command = String.format("sqlite3 %s < %s", dbPath, sqlFilePath);
        executeCommand(command);
    }

    /**
     * 还原文件
     */
    private void restoreFiles(Long backupId, BackupRecord record, GameInstance instance, Host host) throws Exception {
        String backupFilePath = record.getFilePath();
        String restorePath = record.getSourcePath();

        if (restorePath == null || restorePath.isEmpty()) {
            restorePath = instance.getInstallPath();
        }

        // 凭据解析统一走 DeploymentAccess
        HostCredentials conn = deployAccess.credentials(host);

        // 停止实例
        instanceService.stopInstance(instance.getId());

        try {
            // 上传备份文件到远程主机
            String remoteBackupPath = "/tmp/restore_" + backupId + ".tar.gz";
            boolean uploadSuccess = sshUtil.uploadFile(
                    conn.host(),
                    conn.port(),
                    conn.username(),
                    conn.privateKey(),
                    null,
                    backupFilePath,
                    remoteBackupPath
            );

            if (!uploadSuccess) {
                throw new BusinessException("上传备份文件失败");
            }

            // 解压并还原
            String restoreCommand = String.format("cd %s && tar -xzf %s --overwrite",
                    restorePath, remoteBackupPath);

            SshUtil.CommandResult result = sshUtil.executeCommand(
                    conn.host(),
                    conn.port(),
                    conn.username(),
                    conn.privateKey(),
                    null,
                    restoreCommand
            );

            if (!result.isSuccess()) {
                throw new BusinessException("还原文件失败: " + result.getError());
            }

            // 清理远程临时文件
            sshUtil.executeCommand(conn.host(), conn.port(), conn.username(),
                    conn.privateKey(), null, "rm -f " + remoteBackupPath);

            log.info("文件还原成功: backupId={}", backupId);

        } finally {
            // 启动实例
            instanceService.startInstance(instance.getId());
        }
    }

    @Override
    @Transactional
    public void deleteBackup(Long backupId) {
        BackupRecord record = backupRecordMapper.selectById(backupId);
        if (record == null) {
            throw new BusinessException("备份记录不存在");
        }

        // 删除备份文件
        if (record.getFilePath() != null) {
            try {
                Files.deleteIfExists(Paths.get(record.getFilePath()));
            } catch (IOException e) {
                log.warn("删除备份文件失败: {}", record.getFilePath(), e);
            }
        }

        // 逻辑删除记录
        backupRecordMapper.deleteById(backupId);

        log.info("备份删除成功: backupId={}", backupId);
    }

    @Override
    public List<BackupRecord> getBackupList(Long instanceId) {
        return backupRecordMapper.selectByInstanceId(instanceId);
    }

    @Override
    public List<BackupRecord> getBackupListByTargetType(Long instanceId, String targetType) {
        return backupRecordMapper.selectByInstanceIdAndTargetType(instanceId, targetType);
    }

    @Override
    public BackupRecord getBackupDetail(Long backupId) {
        BackupRecord record = backupRecordMapper.selectById(backupId);
        if (record == null) {
            throw new BusinessException("备份记录不存在");
        }
        return record;
    }

    @Override
    public InputStream downloadBackup(Long backupId) {
        BackupRecord record = backupRecordMapper.selectById(backupId);
        if (record == null) {
            throw new BusinessException("备份记录不存在");
        }

        if (!BackupRecord.STATUS_SUCCESS.equals(record.getStatus())) {
            throw new BusinessException("备份文件不可用");
        }

        try {
            return new FileInputStream(record.getFilePath());
        } catch (FileNotFoundException e) {
            throw new BusinessException("备份文件不存在");
        }
    }

    @Override
    public Map<String, Object> getBackupFileInfo(Long backupId) {
        BackupRecord record = backupRecordMapper.selectById(backupId);
        if (record == null) {
            throw new BusinessException("备份记录不存在");
        }

        Map<String, Object> info = new HashMap<>();
        info.put("filename", Paths.get(record.getFilePath()).getFileName().toString());
        info.put("size", record.getFileSize());
        info.put("contentType", getContentType(record.getFilePath()));
        info.put("md5", record.getFileMd5());
        return info;
    }

    @Override
    @Transactional
    public int cleanupExpiredBackups(int retentionDays) {
        LocalDateTime expireTime = LocalDateTime.now().minusDays(retentionDays);
        List<BackupRecord> expiredBackups = backupRecordMapper.selectExpiredBackups(expireTime);

        int count = 0;
        for (BackupRecord record : expiredBackups) {
            try {
                deleteBackup(record.getId());
                count++;
            } catch (Exception e) {
                log.error("清理过期备份失败: backupId={}", record.getId(), e);
            }
        }

        log.info("清理过期备份完成: 共清理{}个备份", count);
        return count;
    }

    @Override
    public Integer getBackupProgress(Long backupId) {
        BackupRecord record = backupRecordMapper.selectById(backupId);
        if (record == null) {
            throw new BusinessException("备份记录不存在");
        }
        return record.getProgress();
    }

    @Override
    public boolean cancelBackup(Long backupId) {
        AtomicBoolean cancelled = backupCancellationMap.get(backupId);
        if (cancelled != null) {
            cancelled.set(true);
            backupRecordMapper.updateStatus(backupId, BackupRecord.STATUS_FAILED, "用户取消");
            return true;
        }
        return false;
    }

    @Override
    public boolean verifyBackup(Long backupId) {
        BackupRecord record = backupRecordMapper.selectById(backupId);
        if (record == null) {
            throw new BusinessException("备份记录不存在");
        }

        if (!BackupRecord.STATUS_SUCCESS.equals(record.getStatus())) {
            return false;
        }

        // 验证文件存在
        File file = new File(record.getFilePath());
        if (!file.exists()) {
            return false;
        }

        // 验证文件大小
        if (file.length() != record.getFileSize()) {
            return false;
        }

        // 验证MD5
        if (record.getFileMd5() != null) {
            try {
                String currentMd5 = calculateFileMd5(record.getFilePath());
                return currentMd5.equalsIgnoreCase(record.getFileMd5());
            } catch (Exception e) {
                log.error("验证备份文件MD5失败: backupId={}", backupId, e);
                return false;
            }
        }

        return true;
    }

    // ========== 私有辅助方法 ==========

    /**
     * 更新备份进度
     */
    private void updateProgress(Long backupId, int progress) {
        backupRecordMapper.updateProgress(backupId, progress);
    }

    /**
     * 压缩文件
     */
    private void compressFile(String sourcePath, String zipPath) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(zipPath);
             ZipOutputStream zos = new ZipOutputStream(fos)) {

            File sourceFile = new File(sourcePath);
            ZipEntry zipEntry = new ZipEntry(sourceFile.getName());
            zos.putNextEntry(zipEntry);

            try (FileInputStream fis = new FileInputStream(sourceFile)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = fis.read(buffer)) > 0) {
                    zos.write(buffer, 0, len);
                }
            }

            zos.closeEntry();
        }
    }

    /**
     * 解压文件
     */
    private void decompressFile(String zipPath, String outputPath) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipPath))) {
            ZipEntry entry = zis.getNextEntry();
            if (entry != null) {
                try (FileOutputStream fos = new FileOutputStream(outputPath)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = zis.read(buffer)) > 0) {
                        fos.write(buffer, 0, len);
                    }
                }
            }
            zis.closeEntry();
        }
    }

    /**
     * 移动备份文件到存储目录
     */
    private String moveBackupFile(String tempPath, String fileName) throws IOException {
        Path targetDir = Paths.get(backupBasePath);
        if (!Files.exists(targetDir)) {
            Files.createDirectories(targetDir);
        }

        Path targetPath = targetDir.resolve(fileName);
        Files.move(Paths.get(tempPath), targetPath);
        return targetPath.toString();
    }

    /**
     * 计算文件MD5
     */
    private String calculateFileMd5(String filePath) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (FileInputStream fis = new FileInputStream(filePath)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = fis.read(buffer)) > 0) {
                md.update(buffer, 0, len);
            }
        }

        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * 清理临时文件
     */
    private void cleanupTempFiles(File tempDir) {
        if (tempDir != null && tempDir.exists()) {
            deleteDirectory(tempDir);
        }
    }

    /**
     * 递归删除目录
     */
    private void deleteDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        directory.delete();
    }

    /**
     * 执行命令
     */
    private void executeCommand(String command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
        executeProcess(pb);
    }

    /**
     * 执行进程
     */
    private void executeProcess(ProcessBuilder pb) throws Exception {
        pb.redirectErrorStream(true);
        Process process = pb.start();

        // 读取输出
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new Exception("命令执行失败,退出码: " + exitCode + ", 输出: " + output.toString());
        }
    }

    /**
     * 获取文件Content-Type
     */
    private String getContentType(String filePath) {
        String fileName = filePath.toLowerCase();
        if (fileName.endsWith(".zip")) {
            return "application/zip";
        } else if (fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz")) {
            return "application/gzip";
        } else if (fileName.endsWith(".sql")) {
            return "application/sql";
        }
        return "application/octet-stream";
    }

}
