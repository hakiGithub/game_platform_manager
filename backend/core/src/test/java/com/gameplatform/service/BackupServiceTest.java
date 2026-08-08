package com.gameplatform.service;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.config.GamePlatformConfig;
import com.gameplatform.entity.BackupRecord;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.BackupRecordMapper;
import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.service.impl.BackupServiceImpl;
import com.gameplatform.util.AesUtil;
import com.gameplatform.util.SshUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 备份服务测试类
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
class BackupServiceTest {

    @Mock
    private BackupRecordMapper backupRecordMapper;

    @Mock
    private GameInstanceMapper gameInstanceMapper;

    @Mock
    private HostMapper hostMapper;

    @Mock
    private InstanceService instanceService;

    @Mock
    private SshUtil sshUtil;

    @Mock
    private AesUtil aesUtil;

    @Mock
    private GamePlatformConfig gamePlatformConfig;

    @InjectMocks
    private BackupServiceImpl backupService;

    private GamePlatformConfig.BackupConfig backupConfig;

    @BeforeEach
    void setUp() {
        backupConfig = new GamePlatformConfig.BackupConfig();
        backupConfig.setBackupDir(System.getProperty("java.io.tmpdir") + "/test-backups");
        backupConfig.setMaxBackups(10);
        backupConfig.setRetentionDays(30);
        backupConfig.setCompressFormat("tar.gz");
        backupConfig.setTimeoutMinutes(60);
        backupConfig.setAutoCleanup(true);

        when(gamePlatformConfig.getBackup()).thenReturn(backupConfig);

        // 初始化备份目录
        backupService.init();
    }

    private GameInstance createInstance(Long id, String name) {
        GameInstance instance = new GameInstance();
        instance.setId(id);
        instance.setInstanceName(name);
        instance.setHostId(1L);
        instance.setInstallPath("/opt/game/server");
        return instance;
    }

    private Host createHost() {
        Host host = new Host();
        host.setId(1L);
        host.setHostName("test-host");
        host.setIpAddress("192.168.1.100");
        host.setSshPort(22);
        host.setSshUser("root");
        return host;
    }

    private BackupRecord createBackupRecord(Long id, Long instanceId, String targetType, int status) {
        BackupRecord record = new BackupRecord();
        record.setId(id);
        record.setInstanceId(instanceId);
        record.setBackupName("test-backup");
        record.setBackupType(BackupRecord.BACKUP_TYPE_FULL);
        record.setTargetType(targetType);
        record.setStatus(status);
        record.setFileSize(1024L);
        record.setFilePath("/tmp/test-backup.zip");
        record.setBackupTime(LocalDateTime.now());
        return record;
    }

    @Test
    void testCreateDatabaseBackupSuccess() {
        // 准备数据
        GameInstance instance = createInstance(1L, "test-server");
        Map<String, Object> dbConfig = new HashMap<>();
        dbConfig.put("host", "localhost");
        dbConfig.put("port", 3306);
        dbConfig.put("database", "game_db");
        dbConfig.put("username", "root");
        dbConfig.put("password", "encrypted_password");
        instance.setDatabaseConfig(dbConfig);

        when(gameInstanceMapper.selectById(1L)).thenReturn(instance);
        when(backupRecordMapper.insert(any(BackupRecord.class))).thenAnswer(invocation -> {
            BackupRecord record = invocation.getArgument(0);
            record.setId(1L);
            return 1;
        });

        // 执行测试
        BackupRecord result = backupService.createDatabaseBackup(
                1L,
                "mysql-backup-2024",
                BackupRecord.BACKUP_TYPE_FULL,
                BackupRecord.DATABASE_TYPE_MYSQL,
                "Test backup"
        );

        // 验证结果
        assertNotNull(result);
        assertEquals(1L, result.getId());
        assertEquals(BackupRecord.STATUS_BACKING_UP, result.getStatus());
        assertEquals(BackupRecord.TARGET_TYPE_DATABASE, result.getTargetType());
        assertEquals(BackupRecord.DATABASE_TYPE_MYSQL, result.getDatabaseType());

        verify(backupRecordMapper).insert(any(BackupRecord.class));
    }

    @Test
    void testCreateDatabaseBackupInstanceNotFound() {
        when(gameInstanceMapper.selectById(1L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> {
            backupService.createDatabaseBackup(1L, "backup", BackupRecord.BACKUP_TYPE_FULL,
                    BackupRecord.DATABASE_TYPE_MYSQL, "");
        });
    }

    @Test
    void testCreateDatabaseBackupNoDbConfig() {
        GameInstance instance = createInstance(1L, "test-server");
        // 不设置 databaseConfig

        when(gameInstanceMapper.selectById(1L)).thenReturn(instance);

        assertThrows(BusinessException.class, () -> {
            backupService.createDatabaseBackup(1L, "backup", BackupRecord.BACKUP_TYPE_FULL,
                    BackupRecord.DATABASE_TYPE_MYSQL, "");
        });
    }

    @Test
    void testCreateFileBackupSuccess() {
        GameInstance instance = createInstance(1L, "test-server");
        Host host = createHost();

        when(gameInstanceMapper.selectById(1L)).thenReturn(instance);
        when(hostMapper.selectById(1L)).thenReturn(host);
        when(backupRecordMapper.insert(any(BackupRecord.class))).thenAnswer(invocation -> {
            BackupRecord record = invocation.getArgument(0);
            record.setId(1L);
            return 1;
        });

        BackupRecord result = backupService.createFileBackup(
                1L,
                "file-backup-2024",
                BackupRecord.BACKUP_TYPE_FULL,
                "/opt/game/saves",
                "Test file backup"
        );

        assertNotNull(result);
        assertEquals(1L, result.getId());
        assertEquals(BackupRecord.STATUS_BACKING_UP, result.getStatus());
        assertEquals(BackupRecord.TARGET_TYPE_FILES, result.getTargetType());
        assertEquals("/opt/game/saves", result.getSourcePath());

        verify(backupRecordMapper).insert(any(BackupRecord.class));
    }

    @Test
    void testCreateFileBackupUseDefaultPath() {
        GameInstance instance = createInstance(1L, "test-server");
        instance.setInstallPath("/opt/game/server");
        Host host = createHost();

        when(gameInstanceMapper.selectById(1L)).thenReturn(instance);
        when(hostMapper.selectById(1L)).thenReturn(host);
        when(backupRecordMapper.insert(any(BackupRecord.class))).thenAnswer(invocation -> {
            BackupRecord record = invocation.getArgument(0);
            record.setId(1L);
            return 1;
        });

        // 不指定源路径,应该使用安装路径
        BackupRecord result = backupService.createFileBackup(
                1L,
                "file-backup-2024",
                BackupRecord.BACKUP_TYPE_FULL,
                null,  // 不指定路径
                "Test file backup"
        );

        assertNotNull(result);
        assertEquals("/opt/game/server", result.getSourcePath());
    }

    @Test
    void testGetBackupList() {
        List<BackupRecord> expectedList = Arrays.asList(
                createBackupRecord(1L, 1L, BackupRecord.TARGET_TYPE_DATABASE, BackupRecord.STATUS_SUCCESS),
                createBackupRecord(2L, 1L, BackupRecord.TARGET_TYPE_FILES, BackupRecord.STATUS_SUCCESS)
        );

        when(backupRecordMapper.selectByInstanceId(1L)).thenReturn(expectedList);

        List<BackupRecord> result = backupService.getBackupList(1L);

        assertNotNull(result);
        assertEquals(2, result.size());
        verify(backupRecordMapper).selectByInstanceId(1L);
    }

    @Test
    void testGetBackupListByTargetType() {
        List<BackupRecord> expectedList = Arrays.asList(
                createBackupRecord(1L, 1L, BackupRecord.TARGET_TYPE_DATABASE, BackupRecord.STATUS_SUCCESS)
        );

        when(backupRecordMapper.selectByInstanceIdAndTargetType(1L, BackupRecord.TARGET_TYPE_DATABASE))
                .thenReturn(expectedList);

        List<BackupRecord> result = backupService.getBackupListByTargetType(1L, BackupRecord.TARGET_TYPE_DATABASE);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals(BackupRecord.TARGET_TYPE_DATABASE, result.get(0).getTargetType());
    }

    @Test
    void testGetBackupDetailSuccess() {
        BackupRecord expected = createBackupRecord(1L, 1L, BackupRecord.TARGET_TYPE_DATABASE, BackupRecord.STATUS_SUCCESS);

        when(backupRecordMapper.selectById(1L)).thenReturn(expected);

        BackupRecord result = backupService.getBackupDetail(1L);

        assertNotNull(result);
        assertEquals(1L, result.getId());
    }

    @Test
    void testGetBackupDetailNotFound() {
        when(backupRecordMapper.selectById(1L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> {
            backupService.getBackupDetail(1L);
        });
    }

    @Test
    void testDeleteBackupSuccess() {
        BackupRecord record = createBackupRecord(1L, 1L, BackupRecord.TARGET_TYPE_DATABASE, BackupRecord.STATUS_SUCCESS);

        when(backupRecordMapper.selectById(1L)).thenReturn(record);

        backupService.deleteBackup(1L);

        verify(backupRecordMapper).deleteById(1L);
    }

    @Test
    void testDeleteBackupNotFound() {
        when(backupRecordMapper.selectById(1L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> {
            backupService.deleteBackup(1L);
        });
    }

    @Test
    void testRestoreBackupFileNotSuccess() {
        BackupRecord record = createBackupRecord(1L, 1L, BackupRecord.TARGET_TYPE_DATABASE, BackupRecord.STATUS_FAILED);

        when(backupRecordMapper.selectById(1L)).thenReturn(record);

        assertThrows(BusinessException.class, () -> {
            backupService.restoreBackup(1L);
        });
    }

    @Test
    void testRestoreBackupNotFound() {
        when(backupRecordMapper.selectById(1L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> {
            backupService.restoreBackup(1L);
        });
    }

    @Test
    void testRestoreBackupNotSuccess() {
        BackupRecord record = createBackupRecord(1L, 1L, BackupRecord.TARGET_TYPE_DATABASE, BackupRecord.STATUS_FAILED);

        when(backupRecordMapper.selectById(1L)).thenReturn(record);

        assertThrows(BusinessException.class, () -> {
            backupService.restoreBackup(1L);
        });
    }

    @Test
    void testGetBackupProgress() {
        BackupRecord record = createBackupRecord(1L, 1L, BackupRecord.TARGET_TYPE_DATABASE, BackupRecord.STATUS_BACKING_UP);
        record.setProgress(50);

        when(backupRecordMapper.selectById(1L)).thenReturn(record);

        Integer progress = backupService.getBackupProgress(1L);

        assertEquals(50, progress);
    }

    @Test
    void testCancelBackupNotFound() {
        // 取消不存在的备份
        boolean cancelled = backupService.cancelBackup(999L);

        assertFalse(cancelled);
    }

    @Test
    void testVerifyBackupSuccess() throws Exception {
        // 创建临时测试文件
        Path tempFile = Files.createTempFile("test-backup-", ".zip");
        Files.write(tempFile, "test content".getBytes());

        BackupRecord record = createBackupRecord(1L, 1L, BackupRecord.TARGET_TYPE_DATABASE, BackupRecord.STATUS_SUCCESS);
        record.setFilePath(tempFile.toString());
        record.setFileSize(Files.size(tempFile));

        // 计算MD5
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
        byte[] hash = md.digest("test content".getBytes());
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        record.setFileMd5(sb.toString());

        when(backupRecordMapper.selectById(1L)).thenReturn(record);

        boolean valid = backupService.verifyBackup(1L);

        assertTrue(valid);

        // 清理
        Files.deleteIfExists(tempFile);
    }

    @Test
    void testVerifyBackupFileNotExists() {
        BackupRecord record = createBackupRecord(1L, 1L, BackupRecord.TARGET_TYPE_DATABASE, BackupRecord.STATUS_SUCCESS);
        record.setFilePath("/nonexistent/path/backup.zip");

        when(backupRecordMapper.selectById(1L)).thenReturn(record);

        boolean valid = backupService.verifyBackup(1L);

        assertFalse(valid);
    }

    @Test
    void testVerifyBackupNotSuccess() {
        BackupRecord record = createBackupRecord(1L, 1L, BackupRecord.TARGET_TYPE_DATABASE, BackupRecord.STATUS_FAILED);

        when(backupRecordMapper.selectById(1L)).thenReturn(record);

        boolean valid = backupService.verifyBackup(1L);

        assertFalse(valid);
    }

    @Test
    void testGetBackupFileInfo() {
        BackupRecord record = createBackupRecord(1L, 1L, BackupRecord.TARGET_TYPE_DATABASE, BackupRecord.STATUS_SUCCESS);
        record.setFilePath("/backups/test-backup.zip");
        record.setFileMd5("abc123");

        when(backupRecordMapper.selectById(1L)).thenReturn(record);

        Map<String, Object> info = backupService.getBackupFileInfo(1L);

        assertNotNull(info);
        assertEquals("test-backup.zip", info.get("filename"));
        assertEquals(1024L, info.get("size"));
        assertEquals("abc123", info.get("md5"));
    }

    @Test
    void testCleanupExpiredBackups() {
        List<BackupRecord> expiredList = Arrays.asList(
                createBackupRecord(1L, 1L, BackupRecord.TARGET_TYPE_DATABASE, BackupRecord.STATUS_SUCCESS),
                createBackupRecord(2L, 1L, BackupRecord.TARGET_TYPE_FILES, BackupRecord.STATUS_SUCCESS)
        );

        when(backupRecordMapper.selectExpiredBackups(any(LocalDateTime.class))).thenReturn(expiredList);
        when(backupRecordMapper.selectById(anyLong())).thenAnswer(invocation -> {
            Long id = invocation.getArgument(0);
            return expiredList.stream().filter(r -> r.getId().equals(id)).findFirst().orElse(null);
        });

        int count = backupService.cleanupExpiredBackups(30);

        assertEquals(2, count);
    }

    @Test
    void testDownloadBackupSuccess() throws Exception {
        // 创建临时测试文件
        Path tempFile = Files.createTempFile("test-backup-", ".zip");
        Files.write(tempFile, "test content".getBytes());

        BackupRecord record = createBackupRecord(1L, 1L, BackupRecord.TARGET_TYPE_DATABASE, BackupRecord.STATUS_SUCCESS);
        record.setFilePath(tempFile.toString());

        when(backupRecordMapper.selectById(1L)).thenReturn(record);

        InputStream is = backupService.downloadBackup(1L);

        assertNotNull(is);
        is.close();

        // 清理
        Files.deleteIfExists(tempFile);
    }

    @Test
    void testDownloadBackupNotSuccess() {
        BackupRecord record = createBackupRecord(1L, 1L, BackupRecord.TARGET_TYPE_DATABASE, BackupRecord.STATUS_FAILED);

        when(backupRecordMapper.selectById(1L)).thenReturn(record);

        assertThrows(BusinessException.class, () -> {
            backupService.downloadBackup(1L);
        });
    }

    @Test
    void testDownloadBackupFileNotFound() {
        BackupRecord record = createBackupRecord(1L, 1L, BackupRecord.TARGET_TYPE_DATABASE, BackupRecord.STATUS_SUCCESS);
        record.setFilePath("/nonexistent/path/backup.zip");

        when(backupRecordMapper.selectById(1L)).thenReturn(record);

        assertThrows(BusinessException.class, () -> {
            backupService.downloadBackup(1L);
        });
    }
}
