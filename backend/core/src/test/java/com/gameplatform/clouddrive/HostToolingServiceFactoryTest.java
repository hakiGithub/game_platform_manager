package com.gameplatform.clouddrive;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.patch.HostCapabilityProber;
import com.gameplatform.patch.PatchDecisionEngine;
import com.gameplatform.patch.PatchInstallExecutor;
import com.gameplatform.plugin.service.HostToolingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HostToolingServiceFactoryTest {

    @Mock
    private HostMapper hostMapper;
    @Mock
    private HostCapabilityProber prober;
    @Mock
    private PatchDecisionEngine decisionEngine;
    @Mock
    private PatchInstallExecutor executorBridge;
    @Mock
    private ToolScriptRunner toolScriptRunner;
    @Mock
    private ToolScriptRegistry toolScriptRegistry;

    private HostToolingServiceFactory factory;

    @BeforeEach
    void setUp() {
        factory = new HostToolingServiceFactory(hostMapper, prober, decisionEngine,
                executorBridge, toolScriptRunner, toolScriptRegistry);
    }

    @Test
    @DisplayName("未声明脚本的插件正常获得服务且不能运行任意脚本")
    void forPlugin_withoutScripts_doesNotRequireL4d2Resource() throws Exception {
        when(toolScriptRegistry.contains("plugin-other:tooling/unknown.py")).thenReturn(false);
        ClassLoader classLoader = resourceLoader(Map.of(
                "plugin.properties", "plugin.id=plugin-other\n"));
        HostToolingService service = factory.forPlugin("plugin-other", classLoader);

        BusinessException error = assertThrows(BusinessException.class,
                () -> service.runToolScript(1L, "data.bin", "tooling/unknown.py"));

        assertEquals("插件未注册工具脚本资源: tooling/unknown.py", error.getMessage());
        verify(toolScriptRegistry, never()).register(anyString(),
                org.mockito.ArgumentMatchers.isNull(), anyString());
    }

    @Test
    @DisplayName("声明的脚本被登记并可运行，未登记资源仍被拒绝")
    void forPlugin_registersOnlyDeclaredScripts() throws Exception {
        ClassLoader classLoader = resourceLoader(Map.of(
                "plugin.properties",
                "plugin.id=plugin-other\nplugin.tooling.scripts=tooling/analyze.py\n",
                "tooling/analyze.py", "print('ok')"
        ));
        when(toolScriptRunner.runToolScript(1L, "data.bin",
                "plugin-other:tooling/analyze.py")).thenReturn("ok");
        when(toolScriptRegistry.contains("plugin-other:tooling/analyze.py")).thenReturn(true);
        when(toolScriptRegistry.contains("plugin-other:tooling/other.py")).thenReturn(false);

        HostToolingService service = factory.forPlugin("plugin-other", classLoader);

        assertEquals("ok", service.runToolScript(1L, "data.bin", "tooling/analyze.py"));
        assertThrows(BusinessException.class,
                () -> service.runToolScript(1L, "data.bin", "tooling/other.py"));
        verify(toolScriptRegistry).register("plugin-other:tooling/analyze.py",
                classLoader, "tooling/analyze.py");
    }

    private ClassLoader resourceLoader(Map<String, String> resources) throws Exception {
        Path root = Files.createTempDirectory("plugin-resources-");
        for (Map.Entry<String, String> entry : resources.entrySet()) {
            Path file = root.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue(), StandardCharsets.UTF_8);
        }
        return new java.net.URLClassLoader(new java.net.URL[]{root.toUri().toURL()},
                getClass().getClassLoader());
    }
}
