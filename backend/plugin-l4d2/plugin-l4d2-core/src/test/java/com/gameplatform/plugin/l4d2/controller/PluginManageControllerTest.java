package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.service.BuiltinPluginInstaller;
import com.gameplatform.plugin.l4d2.service.PlatformPluginInstaller;
import com.gameplatform.plugin.l4d2.service.PluginExportService;
import com.gameplatform.plugin.l4d2.service.PluginInstallService;
import com.gameplatform.plugin.l4d2.vo.PluginListVO;
import com.gameplatform.plugin.task.TaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * PluginManageController 单元测试。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
class PluginManageControllerTest {

    @Mock
    private PluginInstallService pluginInstallService;

    @Mock
    private PluginExportService pluginExportService;

    @Mock
    private PlatformPluginInstaller platformPluginInstaller;

    @Mock
    private BuiltinPluginInstaller builtinPluginInstaller;

    @Mock
    private TaskService taskService;

    @Mock
    private MultipartFile file;

    private PluginManageController controller;

    @BeforeEach
    void setUp() {
        controller = new PluginManageController(pluginInstallService, pluginExportService,
                platformPluginInstaller, builtinPluginInstaller, taskService);
    }

    @Test
    void upload_archive_validation_failure_returns_actionable_message() {
        when(file.getOriginalFilename()).thenReturn("e2e-broken-plugin.zip");
        when(pluginInstallService.installFromUpload(1L, file))
                .thenThrow(new L4D2PluginException(L4D2PluginException.FILE,
                        "归档导入失败；阶段=校验(VALIDATE)；原因码=ARCHIVE_REQUIRED_FILE_MISSING"
                                + "；原因=缺少关键文件: left4dead2/addons/sourcemod/plugins"
                                + "；建议=确认归档完整且关键文件存在后重试"));

        Result<PluginListVO> result = controller.upload(1L, file);

        assertEquals(400, result.getCode());
        assertTrue(result.getMessage().contains("阶段=校验(VALIDATE)"));
        assertTrue(result.getMessage().contains("原因码=ARCHIVE_REQUIRED_FILE_MISSING"));
        assertTrue(result.getMessage().contains("建议=确认归档完整且关键文件存在后重试"));
    }
}
