package com.gameplatform.controller;

import com.gameplatform.plugin.service.AbstractInstanceFileService;
import com.gameplatform.plugin.service.FileAccessService.FileInfo;
import com.gameplatform.service.InstanceService;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("实例文件列表响应契约")
class InstanceControllerFilesContractTest {

    @Test
    @DisplayName("列表响应透传权限，供安装结果校验可执行文件")
    void listFilesIncludesPermissions() {
        InstanceService instanceService = mock(InstanceService.class);
        AbstractInstanceFileService fileService = mock(AbstractInstanceFileService.class);
        InstanceController controller = new InstanceController(
                instanceService, null, null, fileService, null);

        InstanceVO instance = new InstanceVO();
        instance.setId(1L);
        when(instanceService.getInstanceById(1L)).thenReturn(instance);

        FileInfo file = new FileInfo();
        file.setName("e2e-large-plugin.smx");
        file.setDirectory(false);
        file.setSize(4096L);
        file.setPermissions("rwxr-x---");
        when(fileService.listFiles(1L, "plugins"))
                .thenReturn(List.of(file));

        var result = controller.listFiles(1L, "plugins");
        @SuppressWarnings("unchecked")
        List<InstanceController.FileInfoVO> files =
                (List<InstanceController.FileInfoVO>) ((Map<String, Object>) result.getData()).get("files");

        assertEquals("rwxr-x---", files.get(0).getPermissions());
        assertEquals("plugins/e2e-large-plugin.smx", files.get(0).getPath());
    }
}
