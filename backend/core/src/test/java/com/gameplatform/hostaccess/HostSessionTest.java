package com.gameplatform.hostaccess;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.HostMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HostSessionTest {

    @Mock
    private HostMapper hostMapper;

    @Mock
    private RemoteHost remoteHost;

    private HostSession hostSession;
    private Host host;

    @BeforeEach
    void setUp() {
        hostSession = new HostSession(hostMapper, remoteHost);
        host = new Host();
        host.setId(1L);
        host.setHostName("host-1");
    }

    @Test
    @DisplayName("普通操作成功写在线")
    void run_successMarksOnline() {
        when(hostMapper.selectById(1L)).thenReturn(host);

        String result = hostSession.run(1L, (loaded, remote) -> "ok");

        assertThat(result).isEqualTo("ok");
        verify(hostMapper).updateOnlineStatus(1L, 1);
    }

    @Test
    @DisplayName("HostAccessException 写离线并继续抛出")
    void run_accessFailureMarksOfflineAndRethrows() {
        when(hostMapper.selectById(1L)).thenReturn(host);

        assertThatThrownBy(() -> hostSession.run(1L, (loaded, remote) -> {
            throw new HostAccessException("offline");
        })).isInstanceOf(HostAccessException.class);

        verify(hostMapper).updateOnlineStatus(1L, 0);
    }

    @Test
    @DisplayName("probe 返回不可达写离线")
    void probe_unreachableMarksOffline() {
        when(hostMapper.selectById(1L)).thenReturn(host);

        Boolean result = hostSession.probe(1L, (loaded, remote) -> false);

        assertThat(result).isFalse();
        verify(hostMapper).updateOnlineStatus(1L, 0);
    }

    @Test
    @DisplayName("probe 成功写在线")
    void probe_reachableMarksOnline() {
        when(hostMapper.selectById(1L)).thenReturn(host);

        Boolean result = hostSession.probe(1L, (loaded, remote) -> true);

        assertThat(result).isTrue();
        verify(hostMapper).updateOnlineStatus(1L, 1);
    }

    @Test
    @DisplayName("资源查询失败时写离线并抛统一异常")
    void run_resourceFailureMarksOfflineAndThrows() {
        when(hostMapper.selectById(1L)).thenReturn(host);
        ResourceInfo failed = new ResourceInfo();
        failed.setSuccess(false);
        failed.setError("all commands failed");

        assertThatThrownBy(() -> hostSession.run(1L, (loaded, remote) -> failed))
                .isInstanceOf(HostAccessException.class)
                .hasMessageContaining("all commands failed");

        verify(hostMapper).updateOnlineStatus(1L, 0);
    }

    @Test
    @DisplayName("主机不存在时抛业务异常，不写在线状态")
    void missingHostFailsFast() {
        when(hostMapper.selectById(1L)).thenReturn(null);

        assertThatThrownBy(() -> hostSession.run(1L, (loaded, remote) -> "ok"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("主机不存在");
    }
}
