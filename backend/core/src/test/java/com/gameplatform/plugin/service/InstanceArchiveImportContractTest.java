package com.gameplatform.plugin.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("InstanceArchiveImport 契约")
class InstanceArchiveImportContractTest {

    @Test
    @DisplayName("六个阶段首尾相接并覆盖 0-100")
    void phasesCoverFullProgressRange() {
        InstanceArchiveImportService.Phase[] phases = InstanceArchiveImportService.Phase.values();
        assertEquals(6, phases.length);
        assertEquals(0, phases[0].getStartPercent());
        assertEquals(100, phases[phases.length - 1].getEndPercent());
        for (int i = 1; i < phases.length; i++) {
            assertEquals(phases[i - 1].getEndPercent(), phases[i].getStartPercent());
        }
    }

    @Test
    @DisplayName("请求默认采用 10000 条与 4GB 安全上限")
    void requestUsesSafeDefaults() {
        InstanceArchiveImportRequest request = InstanceArchiveImportRequest.builder().build();
        assertEquals(10_000, request.getMaxEntries());
        assertEquals(4L * 1024 * 1024 * 1024, request.getMaxExtractBytes());
        assertEquals(0, request.getRequiredFiles().length);
    }

    @Test
    @DisplayName("异常消息包含阶段、路径、命令类别、退出码和建议")
    void exceptionCarriesActionableDiagnostics() {
        InstanceArchiveImportException exception = new InstanceArchiveImportException(
                InstanceArchiveImportService.Phase.IMPORT,
                "/l4d2/plugins_store/.install-1",
                InstanceArchiveImportException.CommandCategory.CONTAINER_COPY,
                125,
                "检查容器是否运行",
                "docker cp 失败",
                null);

        assertEquals(InstanceArchiveImportService.Phase.IMPORT, exception.getPhase());
        assertEquals("/l4d2/plugins_store/.install-1", exception.getPath());
        assertEquals(InstanceArchiveImportException.CommandCategory.CONTAINER_COPY,
                exception.getCommandCategory());
        assertEquals(125, exception.getExitCode());
        assertTrue(exception.getMessage().contains("阶段=导入(IMPORT)"));
        assertTrue(exception.getMessage().contains("命令类别=CONTAINER_COPY"));
        assertTrue(exception.getMessage().contains("退出码=125"));
        assertTrue(exception.getMessage().contains("建议=检查容器是否运行"));
    }

    @Test
    @DisplayName("请求不暴露 Docker 命令或镜像字段")
    void requestExposesNoDockerControl() {
        assertFalse(Arrays.stream(InstanceArchiveImportRequest.class.getDeclaredFields())
                .anyMatch(field -> field.getName().toLowerCase().contains("docker")
                        || field.getName().toLowerCase().contains("image")
                        || field.getName().toLowerCase().contains("command")));
    }
}
