package com.gameplatform.adapter;

import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.deploy.HostCredentials;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.util.SshUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 部署资源限制生效链路测试（ADR-0010）。
 * <p>
 * 覆盖：resources 嵌套读取与单位格式化、compose override 文件生成/删除/容错、
 * docker run 命令 --memory/--cpus 注入。
 */
@ExtendWith(MockitoExtension.class)
class DeployResourceLimitTest {

    @Mock
    private SshUtil sshUtil;

    @Mock
    private HostMapper hostMapper;

    @Mock
    private GameInstanceMapper instanceMapper;

    @Mock
    private DeploymentAccess deployAccess;

    private DockerComposeAdapter composeAdapter;
    private DockerAdapter dockerAdapter;
    private Host host;

    /** dnf_tw 风格模板（含硬编码 mem_limit: 1g / cpus '1.0'，服务名 dnf-1） */
    private static final String DNF_LIKE_TEMPLATE = """
            version: "2.3"

            services:
              dnf-1:
                image: llnut/dnf:latest
                mem_limit: 1g
                cpus: '1.0'
                ports:
                  - ${MYSQL_PORT:-3000}:4000/tcp
                environment:
                  - DNF_DB_ROOT_PASSWORD=${DNF_DB_ROOT_PASSWORD:-88888888}
            """;

    @BeforeEach
    void setUp() {
        composeAdapter = new DockerComposeAdapter();
        dockerAdapter = new DockerAdapter();
        for (Object adapter : List.of(composeAdapter, dockerAdapter)) {
            injectField(adapter, "sshUtil", sshUtil);
            injectField(adapter, "hostMapper", hostMapper);
            injectField(adapter, "instanceMapper", instanceMapper);
            injectField(adapter, "deployAccess", deployAccess);
        }
        host = new Host();
        host.setId(1L);
        host.setIpAddress("127.0.0.1");
        host.setSshPort(22);
        host.setSshUser("root");
        lenient().when(deployAccess.credentials(host))
                .thenReturn(new HostCredentials("127.0.0.1", 22, "root", null, null));
    }

    private void injectField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getSuperclass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject field: " + fieldName, e);
        }
    }

    private SshUtil.CommandResult okResult() {
        SshUtil.CommandResult r = new SshUtil.CommandResult();
        r.setSuccess(true);
        r.setOutput("");
        return r;
    }

    private Map<String, Object> resources(Double cpu, Double mem) {
        Map<String, Object> resources = new HashMap<>();
        if (cpu != null) resources.put("cpuLimit", cpu);
        if (mem != null) resources.put("memoryLimit", mem);
        Map<String, Object> config = new HashMap<>();
        config.put("resources", resources);
        return config;
    }

    // ==================== getResourceLimit / 格式化 ====================

    @Test
    void testGetResourceLimitNestedNumberAndString() {
        // 前端实际提交：嵌套 Number
        assertEquals(4.0, dockerAdapter.getResourceLimit(resources(2.0, 4.0), "memoryLimit"));
        assertEquals(2.0, dockerAdapter.getResourceLimit(resources(2.0, 4.0), "cpuLimit"));
        // 字符串数字兼容
        Map<String, Object> cfg = new HashMap<>();
        cfg.put("resources", Map.of("memoryLimit", "2"));
        assertEquals(2.0, dockerAdapter.getResourceLimit(cfg, "memoryLimit"));
        // 未设置 / 非正数 / 非法值 → null
        assertNull(dockerAdapter.getResourceLimit(new HashMap<>(), "memoryLimit"));
        assertNull(dockerAdapter.getResourceLimit(resources(0.0, 0.0), "memoryLimit"));
        assertNull(dockerAdapter.getResourceLimit(resources(null, null), "memoryLimit"));
        Map<String, Object> bad = new HashMap<>();
        bad.put("resources", Map.of("memoryLimit", "abc"));
        assertNull(dockerAdapter.getResourceLimit(bad, "memoryLimit"));
        // 顶层旧路径（历史死代码）不再读取
        Map<String, Object> topLevel = new HashMap<>();
        topLevel.put("memoryLimit", "2g");
        assertNull(dockerAdapter.getResourceLimit(topLevel, "memoryLimit"));
    }

    @Test
    void testFormatMemoryAndCpu() {
        assertEquals("4g", dockerAdapter.formatMemoryG(4.0));
        assertEquals("512m", dockerAdapter.formatMemoryG(0.5));
        assertEquals("2g", dockerAdapter.formatMemoryG(2));
        assertEquals("2", dockerAdapter.formatCpu(2.0));
        assertEquals("1.5", dockerAdapter.formatCpu(1.5));
    }

    // ==================== syncResourceOverride（compose 路径核心） ====================

    @Test
    void testSyncOverrideUploadsFileWithLimits() throws Exception {
        AtomicReference<String> uploadedContent = new AtomicReference<>();
        when(sshUtil.uploadFile(anyString(), anyInt(), anyString(), any(), any(),
                anyString(), anyString())).thenAnswer(inv -> {
            // 在临时文件被删除前读取内容（显式 String 转型，避免泛型推断到 URI 重载）
            Path local = Path.of(inv.getArgument(5, String.class));
            uploadedContent.set(Files.readString(local, StandardCharsets.UTF_8));
            return true;
        });

        boolean ok = composeAdapter.syncResourceOverride(host, "/home/root/games/dnf_tw",
                resources(1.5, 2.0), DNF_LIKE_TEMPLATE);

        assertTrue(ok);
        String content = uploadedContent.get();
        assertNotNull(content);
        assertTrue(content.contains("dnf-1:"));
        assertTrue(content.contains("mem_limit: 2g"), "用户 2GB 应覆盖模板硬编码 1g");
        assertTrue(content.contains("cpus: '1.5'"), "用户 1.5 核应覆盖模板硬编码 1.0");
        assertTrue(content.startsWith("# 平台自动生成"), "文件头应有平台生成声明");
        verify(sshUtil, never()).executeCommand(anyString(), anyInt(), anyString(), any(), any(), anyString(), anyLong());
    }

    @Test
    void testSyncOverrideRemovesFileWhenResourcesCleared() {
        when(sshUtil.executeCommand(anyString(), anyInt(), anyString(), any(), any(),
                anyString(), anyLong())).thenReturn(okResult());

        // resources 均未设置（用户取消限制后更新）
        boolean ok = composeAdapter.syncResourceOverride(host, "/opt/game",
                new HashMap<>(), DNF_LIKE_TEMPLATE);

        assertTrue(ok);
        verify(sshUtil).executeCommand(anyString(), anyInt(), anyString(), any(), any(),
                eq("rm -f /opt/game/docker-compose.override.yml"), anyLong());
        verify(sshUtil, never()).uploadFile(anyString(), anyInt(), anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    void testSyncOverrideFailOpenOnUnparseableTemplate() {
        boolean ok = composeAdapter.syncResourceOverride(host, "/opt/game",
                resources(2.0, 4.0), "::: not a yaml [");

        // fail-open：不生成 override 也不报错
        assertTrue(ok);
        verify(sshUtil, never()).uploadFile(anyString(), anyInt(), anyString(), any(), any(), anyString(), anyString());
        verify(sshUtil, never()).executeCommand(anyString(), anyInt(), anyString(), any(), any(), anyString(), anyLong());
    }

    @Test
    void testSyncOverrideSkipsWhenNoTemplateContent() {
        boolean ok = composeAdapter.syncResourceOverride(host, "/opt/game",
                resources(2.0, 4.0), null);

        assertTrue(ok);
        verify(sshUtil, never()).uploadFile(anyString(), anyInt(), anyString(), any(), any(), anyString(), anyString());
    }

    @Test
    void testExtractComposeServiceNames() {
        assertEquals(List.of("dnf-1"),
                composeAdapter.extractComposeServiceNames(DNF_LIKE_TEMPLATE));
        // l4d2 模板风格（无 version，多顶级键）
        String l4d2Like = """
                volumes:
                  l4d2-data:

                services:
                  l4d2:
                    image: laoyutang/l4d2-pure:latest
                """;
        assertEquals(List.of("l4d2"), composeAdapter.extractComposeServiceNames(l4d2Like));
        // ${VAR} 占位符不破坏解析
        assertTrue(composeAdapter.extractComposeServiceNames(
                "services:\n  mc:\n    image: ${IMAGE_REPO:-a}:${TAG:-b}\n").contains("mc"));
    }

    // ==================== docker run 命令注入 ====================

    @Test
    void testDockerRunCommandIncludesResourceLimits() throws Exception {
        Method m = DockerAdapter.class.getDeclaredMethod("buildDockerRunCommand", String.class, Map.class);
        m.setAccessible(true);
        Map<String, Object> config = new HashMap<>();
        config.put("image", "nginx:latest");
        config.put("resources", Map.of("memoryLimit", 4, "cpuLimit", 2));

        String cmd = (String) m.invoke(dockerAdapter, "test-nginx", config);

        // 前端提交嵌套 resources（Number GB/核）→ 应正确转换为 --memory 4g / --cpus 2
        assertTrue(cmd.contains("--memory 4g"), "实际命令: " + cmd);
        assertTrue(cmd.contains("--cpus 2"), "实际命令: " + cmd);
    }

    @Test
    void testDockerRunCommandWithoutResources() throws Exception {
        Method m = DockerAdapter.class.getDeclaredMethod("buildDockerRunCommand", String.class, Map.class);
        m.setAccessible(true);
        Map<String, Object> config = new HashMap<>();
        config.put("image", "nginx:latest");

        String cmd = (String) m.invoke(dockerAdapter, "test-nginx", config);

        assertFalse(cmd.contains("--memory"), "未设置 resources 时不应有 --memory");
        assertFalse(cmd.contains("--cpus"), "未设置 resources 时不应有 --cpus");
    }
}
