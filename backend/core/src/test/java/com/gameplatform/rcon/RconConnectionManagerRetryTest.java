package com.gameplatform.rcon;

import com.gameplatform.plugin.service.HostQueryService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.HostVO;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * RCON 连接池"池内连接失效 → 换新连接重试"回归测试。
 *
 * <p>缺陷背景：srcds 会静默关闭空闲 RCON 连接（本端无感知），池内僵尸连接的
 * 首次命令必然失败并把错误抛给调用方——表现为面板/接口偶发"RCON 通信失败: 连接已关闭"。
 *
 * <p>修复契约：池内既有连接执行命令失败时，自动丢弃该连接并换全新连接重试一次；
 * 全新建立的连接失败则直接上抛（端到端故障重试无意义）。
 */
@ExtendWith(MockitoExtension.class)
class RconConnectionManagerRetryTest {

    @Mock
    private RconConnectionResolver resolver;
    @Mock
    private InstanceQueryService instanceQueryService;
    @Mock
    private HostQueryService hostQueryService;

    private ServerSocket fakeServer;
    private final AtomicInteger connectionCount = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        fakeServer = new ServerSocket(0);
    }

    @AfterEach
    void tearDown() throws Exception {
        fakeServer.close();
    }

    /**
     * 假 RCON 服务端（顺序处理每个连接）：
     * zombieFirst=true 时首个连接为"僵尸"——认证应答成功，但收到命令后直接断开不回应；
     * 后续连接正常循环读命令并应答 "pong"。
     */
    private boolean zombieFirst;
    private boolean zombieDone;

    private void startFakeServer() {
        startFakeServer(true);
    }

    private void startFakeServer(boolean withZombie) {
        this.zombieFirst = withZombie;
        this.zombieDone = false;
        Thread t = new Thread(() -> {
            while (!fakeServer.isClosed()) {
                try (Socket s = fakeServer.accept()) {
                    s.setTcpNoDelay(true);
                    DataInputStream in = new DataInputStream(new BufferedInputStream(s.getInputStream()));
                    OutputStream out = s.getOutputStream();
                    readPacket(in); // AUTH 请求
                    writePacket(out, 1, RconProtocol.PACKET_TYPE_AUTH_RESPONSE, "");
                    connectionCount.incrementAndGet();
                    if (zombieFirst && !zombieDone) {
                        // 僵尸连接：等客户端发来首条命令后直接断开（模拟 srcds 掐断），
                        // 然后继续 accept 后续连接（不能 return 退出服务线程）
                        zombieDone = true;
                        readPacket(in);
                        continue;
                    }
                    // 正常连接：循环读命令并应答（复用场景）
                    while (true) {
                        readPacket(in); // 命令请求
                        writePacket(out, 2, RconProtocol.PACKET_TYPE_RESPONSE_VALUE, "pong");
                    }
                } catch (Exception ignored) {
                    return;
                }
            }
        }, "fake-rcon-server");
        t.setDaemon(true);
        t.start();
    }

    private static byte[] readFully(InputStream in, int len) throws Exception {
        byte[] data = new byte[len];
        int off = 0;
        while (off < len) {
            int r = in.read(data, off, len - off);
            if (r < 0) throw new java.io.EOFException("closed");
            off += r;
        }
        return data;
    }

    private static void readPacket(DataInputStream in) throws Exception {
        byte[] lenBuf = readFully(in, 4);
        int len = (lenBuf[0] & 0xFF) | (lenBuf[1] & 0xFF) << 8 | (lenBuf[2] & 0xFF) << 16 | (lenBuf[3] & 0xFF) << 24;
        readFully(in, len);
    }

    private static void writePacket(OutputStream out, int id, int type, String body) throws Exception {
        byte[] bodyBytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int len = 4 + 4 + bodyBytes.length + 2;
        out.write(new byte[]{(byte) len, (byte) (len >> 8), (byte) (len >> 16), (byte) (len >> 24)});
        out.write(new byte[]{(byte) id, (byte) (id >> 8), (byte) (id >> 16), (byte) (id >> 24)});
        out.write(new byte[]{(byte) type, (byte) (type >> 8), (byte) (type >> 16), (byte) (type >> 24)});
        out.write(bodyBytes);
        out.write(new byte[]{0, 0});
        out.flush();
    }

    private RconConnectionManager newManager() throws Exception {
        int port = fakeServer.getLocalPort();
        InstanceVO instance = new InstanceVO();
        instance.setId(9L);
        instance.setHostId(2L);
        when(instanceQueryService.getInstanceById(9L)).thenReturn(instance);
        HostVO host = new HostVO();
        host.setId(2L);
        when(hostQueryService.getHostById(2L)).thenReturn(host);
        when(resolver.resolve(any(), any()))
                .thenReturn(Optional.of(new RconEndpoint("127.0.0.1", port, "pass")));
        return new RconConnectionManager(resolver, instanceQueryService, hostQueryService, new RconProperties());
    }

    @Test
    void 池内连接被服务端关闭_应换新连接重试成功() throws Exception {
        startFakeServer();
        RconConnectionManager manager = newManager();

        // 首次调用命中僵尸连接：命令失败应自动换新连接重试并成功
        String result = manager.withConnection(9,
                (in, out) -> RconProtocol.sendCommand(in, out, "status"));
        assertThat(result).isEqualTo("pong");
        assertThat(connectionCount.get()).isEqualTo(2);
    }

    @Test
    void 正常连接_命令直接成功且复用连接() throws Exception {
        startFakeServer(false);
        RconConnectionManager manager = newManager();

        String first = manager.withConnection(9,
                (in, out) -> RconProtocol.sendCommand(in, out, "status"));
        String second = manager.withConnection(9,
                (in, out) -> RconProtocol.sendCommand(in, out, "status"));
        assertThat(first).isEqualTo("pong");
        assertThat(second).isEqualTo("pong");
        // 连接复用：两个命令共用同一个连接
        assertThat(connectionCount.get()).isEqualTo(1);
    }
}
