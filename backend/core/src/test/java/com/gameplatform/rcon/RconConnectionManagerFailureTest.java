package com.gameplatform.rcon;

import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.plugin.service.HostQueryService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.HostVO;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * RCON 连接管理器失败矩阵：端口/配置缺失、实例不存在、拒绝、超时、
 * 认证失败、非 RCON 服务与同实例 busy。
 */
@ExtendWith(MockitoExtension.class)
class RconConnectionManagerFailureTest {

    @Mock
    private RconConnectionResolver resolver;
    @Mock
    private InstanceQueryService instanceQueryService;
    @Mock
    private HostQueryService hostQueryService;

    private RconConnectionManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) manager.shutdown();
    }

    @BeforeEach
    void setUp() {
        RconProperties properties = new RconProperties();
        properties.setAuthRetryCount(1);
        properties.setConnectTimeoutMs(250);
        properties.setReadTimeoutMs(250);
        properties.getPool().setBorrowTimeoutSeconds(1);
        manager = new RconConnectionManager(
                resolver, instanceQueryService, hostQueryService, properties);
    }

    @Test
    @DisplayName("实例不存在时返回明确原因")
    void instanceMissing_shouldReturnExplicitReason() {
        when(instanceQueryService.getInstanceById(404L)).thenReturn(null);

        assertThatThrownBy(() -> manager.withConnection(404L,
                (in, out) -> RconProtocol.sendCommand(in, out, "status")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("实例不存在: 404");
    }

    @Test
    @DisplayName("密码或端口映射缺失时返回端点不可达")
    void missingEndpointConfig_shouldReturnEndpointUnreachable() {
        InstanceVO instance = instance(1L, "docker");
        HostVO host = host();
        when(instanceQueryService.getInstanceById(1L)).thenReturn(instance);
        when(hostQueryService.getHostById(2L)).thenReturn(host);
        when(resolver.resolve(instance, host)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> manager.withConnection(1L,
                (in, out) -> RconProtocol.sendCommand(in, out, "status")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("RCON 端点不可达：端口未映射或配置缺失");
    }

    @Test
    @DisplayName("端口拒绝连接时最终原因保留 Connection refused")
    void connectionRefused_shouldPreserveReason() throws Exception {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        stubEndpoint(freePort);

        assertThatThrownBy(() -> manager.withConnection(1L,
                (in, out) -> RconProtocol.sendCommand(in, out, "status")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("RCON 连接失败")
                .hasMessageContaining("Connection refused");
    }

    @Test
    @DisplayName("认证响应超时后返回连接失败并说明超时")
    void authTimeout_shouldReturnTimeoutReason() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread acceptThread = new Thread(() -> {
                try (Socket ignored = server.accept()) {
                    Thread.sleep(2_000);
                } catch (Exception ignored) {
                }
            }, "rcon-timeout-server");
            acceptThread.setDaemon(true);
            acceptThread.start();
            stubEndpoint(server.getLocalPort());

            assertThatThrownBy(() -> manager.withConnection(1L,
                    (in, out) -> RconProtocol.sendCommand(in, out, "status")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("RCON 连接失败")
                    .hasMessageContaining("读取响应超时");
        }
    }

    @Test
    @DisplayName("密码错误时返回认证失败原因")
    void wrongPassword_shouldReturnAuthFailure() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            startServer(server, (socket, in, out) -> {
                readPacket(in);
                writePacket(out, -1, RconProtocol.PACKET_TYPE_AUTH_RESPONSE, "");
            });
            stubEndpoint(server.getLocalPort());

            assertThatThrownBy(() -> manager.withConnection(1L,
                    (in, out) -> RconProtocol.sendCommand(in, out, "status")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("RCON 认证失败")
                    .hasMessageContaining("密码错误");
        }
    }

    @Test
    @DisplayName("非 RCON 服务返回无效包长而不是静默挂起")
    void nonRconServer_shouldReturnInvalidPacketLength() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            startServer(server, (socket, in, out) -> {
                readPacket(in);
                out.write(new byte[]{0, 0, 0, 0});
                out.flush();
            });
            stubEndpoint(server.getLocalPort());

            assertThatThrownBy(() -> manager.withConnection(1L,
                    (in, out) -> RconProtocol.sendCommand(in, out, "status")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("RCON 认证失败")
                    .hasMessageContaining("无效的数据包长度");
        }
    }

    @Test
    @DisplayName("同实例连接被占用时 borrow 超时返回连接正忙")
    void concurrentBorrow_shouldReturnBusy() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (ServerSocket server = new ServerSocket(0)) {
            startServer(server, (socket, in, out) -> {
                readPacket(in);
                writePacket(out, 1, RconProtocol.PACKET_TYPE_AUTH_RESPONSE, "");
                readPacket(in);
                writePacket(out, 2, RconProtocol.PACKET_TYPE_RESPONSE_VALUE, "first");
            });
            stubEndpoint(server.getLocalPort());

            Thread first = new Thread(() -> {
                try {
                    manager.withConnection(1L, (in, out) -> {
                        entered.countDown();
                        try {
                            release.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return RconProtocol.sendCommand(in, out, "first");
                    });
                } catch (RuntimeException ignored) {
                    // 测试只关心第二路 borrow 是否在占用期间超时。
                }
            }, "rcon-busy-first");
            first.setDaemon(true);
            first.start();
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> manager.withConnection(1L,
                    (in, out) -> RconProtocol.sendCommand(in, out, "second")))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("RCON 连接正忙");
            release.countDown();
            first.join(5_000);
        }
    }

    private void stubEndpoint(int port) {
        InstanceVO instance = instance(1L, "docker");
        HostVO host = host();
        when(instanceQueryService.getInstanceById(1L)).thenReturn(instance);
        when(hostQueryService.getHostById(2L)).thenReturn(host);
        when(resolver.resolve(any(), any()))
                .thenReturn(Optional.of(new RconEndpoint("127.0.0.1", port, "pass")));
    }

    private static InstanceVO instance(long id, String deployType) {
        InstanceVO instance = new InstanceVO();
        instance.setId(id);
        instance.setHostId(2L);
        instance.setDeployType(deployType);
        instance.setConfigInfo(Map.of(
                "rconPort", 27015,
                "rconPassword", "pass"));
        return instance;
    }

    private static HostVO host() {
        HostVO host = new HostVO();
        host.setId(2L);
        host.setIp("127.0.0.1");
        return host;
    }

    private interface Handler {
        void handle(Socket socket, DataInputStream in, OutputStream out) throws Exception;
    }

    private static void startServer(ServerSocket server, Handler handler) {
        Thread thread = new Thread(() -> {
            try (Socket socket = server.accept()) {
                socket.setTcpNoDelay(true);
                DataInputStream in = new DataInputStream(
                        new BufferedInputStream(socket.getInputStream()));
                handler.handle(socket, in, socket.getOutputStream());
            } catch (Exception ignored) {
            }
        }, "fake-rcon-failure-server");
        thread.setDaemon(true);
        thread.start();
    }

    private static byte[] readFully(DataInputStream in, int length) throws Exception {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = in.read(data, offset, length - offset);
            if (read < 0) throw new java.io.EOFException("closed");
            offset += read;
        }
        return data;
    }

    private static void readPacket(DataInputStream in) throws Exception {
        byte[] lengthBytes = readFully(in, 4);
        int length = (lengthBytes[0] & 0xFF)
                | ((lengthBytes[1] & 0xFF) << 8)
                | ((lengthBytes[2] & 0xFF) << 16)
                | ((lengthBytes[3] & 0xFF) << 24);
        readFully(in, length);
    }

    private static void writePacket(OutputStream out, int id, int type, String body)
            throws Exception {
        byte[] bodyBytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int length = 4 + 4 + bodyBytes.length + 2;
        out.write(new byte[]{
                (byte) length,
                (byte) (length >> 8),
                (byte) (length >> 16),
                (byte) (length >> 24)});
        out.write(new byte[]{
                (byte) id,
                (byte) (id >> 8),
                (byte) (id >> 16),
                (byte) (id >> 24)});
        out.write(new byte[]{
                (byte) type,
                (byte) (type >> 8),
                (byte) (type >> 16),
                (byte) (type >> 24)});
        out.write(bodyBytes);
        out.write(new byte[]{0, 0});
        out.flush();
    }
}
