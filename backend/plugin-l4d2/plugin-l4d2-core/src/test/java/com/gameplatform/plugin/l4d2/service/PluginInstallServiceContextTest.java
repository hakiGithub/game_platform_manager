package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.service.InstanceArchiveImportResult;
import com.gameplatform.plugin.service.InstanceArchiveImportService;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 插件子容器宿主服务注入回归测试。
 *
 * <p>父容器同时存在按实现类命名的 {@code instanceArchiveImportServiceImpl}，
 * 插件子容器又注册了按接口语义命名的 {@code instanceArchiveImportService}。
 * {@link PluginInstallService} 必须按限定名选择子容器代理，否则插件加载会因
 * 两个候选 Bean 而失败。
 */
class PluginInstallServiceContextTest {

    @TempDir
    Path tempDir;

    @Test
    void childContextQualifierSelectsPluginScopedArchiveService() throws Exception {
        InstanceArchiveImportService parentService = mock(InstanceArchiveImportService.class);
        InstanceArchiveImportService childService = mock(InstanceArchiveImportService.class);
        InstanceQueryService instanceQueryService = mock(InstanceQueryService.class);
        InstanceFileService instanceFileService = mock(InstanceFileService.class);
        FileRefsService fileRefsService = mock(FileRefsService.class);
        PluginMetaService pluginMetaService = mock(PluginMetaService.class);
        EnabledPluginsService enabledPluginsService = mock(EnabledPluginsService.class);

        InstanceVO instance = new InstanceVO();
        instance.setId(42L);
        when(instanceQueryService.getInstanceById(42L)).thenReturn(instance);
        when(childService.importArchive(any(), any())).thenReturn(
                InstanceArchiveImportResult.builder()
                        .pluginName("context-plugin")
                        .fileCount(1)
                        .totalBytes(7)
                        .relativePaths(List.of(
                                "left4dead2/addons/sourcemod/plugins/context-plugin.smx"))
                        .build());

        try (AnnotationConfigApplicationContext parent =
                     new AnnotationConfigApplicationContext()) {
            parent.registerBean("instanceArchiveImportServiceImpl",
                    InstanceArchiveImportService.class, () -> parentService);
            parent.refresh();

            try (AnnotationConfigApplicationContext child =
                         new AnnotationConfigApplicationContext()) {
                child.setParent(parent);
                child.registerBean("instanceQueryService",
                        InstanceQueryService.class, () -> instanceQueryService);
                child.registerBean("instanceFileService",
                        InstanceFileService.class, () -> instanceFileService);
                child.registerBean("fileRefsService",
                        FileRefsService.class, () -> fileRefsService);
                child.registerBean("l4d2PathResolver",
                        L4D2PathResolver.class, L4D2PathResolver::new);
                child.registerBean("pluginMetaService",
                        PluginMetaService.class, () -> pluginMetaService);
                child.registerBean("enabledPluginsService",
                        EnabledPluginsService.class, () -> enabledPluginsService);
                child.registerBean("instanceArchiveImportService",
                        InstanceArchiveImportService.class, () -> childService);
                child.registerBean(PluginInstallService.class);
                child.refresh();

                PluginInstallService service = child.getBean(PluginInstallService.class);
                Path zip = tempDir.resolve("context-plugin.zip");
                Files.write(zip, new byte[]{0x50, 0x4b, 0x03, 0x04});

                service.installFromLocalFile(42L, zip.toFile());

                verify(childService).importArchive(any(), any());
                verify(parentService, never()).importArchive(any(), any());
                assertThat(service).isNotNull();
            }
        }
    }
}
