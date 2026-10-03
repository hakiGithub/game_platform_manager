package com.gameplatform.controller;

import com.gameplatform.config.GamePlatformConfig;
import com.gameplatform.dto.HostCreateDTO;
import com.gameplatform.dto.HostUpdateDTO;
import com.gameplatform.service.HostService;
import com.gameplatform.util.SshUtil;
import com.gameplatform.vo.HostVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * PLUT-46：控制器层不再预加密凭据（加密职责收敛到 HostServiceImpl 单一层）。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("主机控制器凭据透传测试（PLUT-46）")
class HostControllerCredentialTest {

    private static final String PLAIN_PEM =
            "-----BEGIN OPENSSH PRIVATE KEY-----\nb3BlbnNzaC1rZXktdjEAFAKE\n-----END OPENSSH PRIVATE KEY-----\n";

    @Mock
    private HostService hostService;
    @Mock
    private GamePlatformConfig gamePlatformConfig;
    @Mock
    private SshUtil sshUtil;
    @Mock
    private com.gameplatform.service.HostsFileRefresher hostsFileRefresher;

    @InjectMocks
    private HostController hostController;

    @Test
    @DisplayName("create：DTO 私钥以明文原样传入服务层，不在控制器加密")
    void createPassesPlainKeyThrough() {
        HostCreateDTO dto = new HostCreateDTO();
        dto.setName("h");
        dto.setIp("10.0.0.1");
        dto.setSshPort(22);
        dto.setSshUsername("root");
        dto.setSshPrivateKey(PLAIN_PEM);
        when(hostService.createHost(any(HostCreateDTO.class))).thenReturn(new HostVO());

        hostController.create(dto);

        ArgumentCaptor<HostCreateDTO> captor = ArgumentCaptor.forClass(HostCreateDTO.class);
        verify(hostService).createHost(captor.capture());
        assertEquals(PLAIN_PEM, captor.getValue().getSshPrivateKey(),
                "控制器不得预加密，明文应原样透传");
    }

    @Test
    @DisplayName("update：DTO 私钥以明文原样传入服务层，不在控制器加密")
    void updatePassesPlainKeyThrough() {
        HostUpdateDTO dto = new HostUpdateDTO();
        dto.setSshPrivateKey(PLAIN_PEM);
        when(hostService.updateHost(any(HostUpdateDTO.class))).thenReturn(new HostVO());

        hostController.update(1L, dto);

        ArgumentCaptor<HostUpdateDTO> captor = ArgumentCaptor.forClass(HostUpdateDTO.class);
        verify(hostService).updateHost(captor.capture());
        assertEquals(PLAIN_PEM, captor.getValue().getSshPrivateKey(),
                "控制器不得预加密，明文应原样透传");
        assertEquals(1L, captor.getValue().getId());
    }
}
