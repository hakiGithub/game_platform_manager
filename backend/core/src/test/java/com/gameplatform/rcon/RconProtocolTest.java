package com.gameplatform.rcon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Source RCON 协议编解码与认证响应契约。
 */
class RconProtocolTest {

    @Test
    @DisplayName("AUTH 包按 little-endian 构造，ID/type/body/终止符正确")
    void buildAuthPacket_shouldEncodeLittleEndianFields() {
        byte[] packet = RconProtocol.buildAuthPacket(7, "secret");

        ByteBuffer buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        assertThat(buffer.getInt()).isEqualTo(4 + 4 + "secret".length() + 2);
        assertThat(buffer.getInt()).isEqualTo(7);
        assertThat(buffer.getInt()).isEqualTo(RconProtocol.PACKET_TYPE_AUTH);
        byte[] body = new byte["secret".length()];
        buffer.get(body);
        assertThat(new String(body)).isEqualTo("secret");
        assertThat(buffer.get()).isZero();
        assertThat(buffer.get()).isZero();
        assertThat(buffer.hasRemaining()).isFalse();
    }

    @Test
    @DisplayName("readPacket 拒绝非正/超长包，避免异常帧拖垮连接")
    void readPacket_shouldRejectInvalidLength() {
        assertThatThrownBy(() -> RconProtocol.readPacket(packetWithLength(0)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("无效的数据包长度: 0");
        assertThatThrownBy(() -> RconProtocol.readPacket(packetWithLength(4097)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("无效的数据包长度: 4097");
    }

    @Test
    @DisplayName("连接关闭时给出明确认证失败原因")
    void readAuthResponse_shouldExplainClosedConnection() {
        assertThatThrownBy(() -> RconProtocol.readAuthResponse(
                new ByteArrayInputStream(new byte[0]), 1))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("RCON 认证失败：服务器关闭连接");
    }

    @Test
    @DisplayName("认证 ID 不匹配时识别为密码错误")
    void readAuthResponse_shouldRejectWrongPasswordId() {
        byte[] response = packet(1, RconProtocol.PACKET_TYPE_AUTH_RESPONSE, "");
        // 偏移 4 才是 ID；偏移 0 是长度字段，写成 -1 会被 readPacket 拒绝
        ByteBuffer.wrap(response).order(ByteOrder.LITTLE_ENDIAN).putInt(4, -1);

        assertThatThrownBy(() -> RconProtocol.readAuthResponse(
                new ByteArrayInputStream(response), 1))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("RCON 认证失败：密码错误");
    }

    @Test
    @DisplayName("认证成功接受先 RESPONSE_VALUE 后 AUTH_RESPONSE 的双包响应")
    void readAuthResponse_shouldAcceptTwoPacketAuth() throws Exception {
        byte[] first = packet(1, RconProtocol.PACKET_TYPE_RESPONSE_VALUE, "");
        byte[] second = packet(1, RconProtocol.PACKET_TYPE_AUTH_RESPONSE, "");
        byte[] combined = new byte[first.length + second.length];
        System.arraycopy(first, 0, combined, 0, first.length);
        System.arraycopy(second, 0, combined, first.length, second.length);

        RconProtocol.readAuthResponse(new ByteArrayInputStream(combined), 1);
    }

    @Test
    @DisplayName("authenticate 发送 AUTH 包并消费成功响应")
    void authenticate_shouldWriteAuthPacketAndSucceed() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] response = packet(1, RconProtocol.PACKET_TYPE_AUTH_RESPONSE, "");

        RconProtocol.authenticate(new ByteArrayInputStream(response), out, "pass");

        byte[] written = out.toByteArray();
        assertThat(written.length).isGreaterThan(12);
        ByteBuffer buffer = ByteBuffer.wrap(written).order(ByteOrder.LITTLE_ENDIAN);
        buffer.getInt();
        assertThat(buffer.getInt()).isEqualTo(1);
        assertThat(buffer.getInt()).isEqualTo(RconProtocol.PACKET_TYPE_AUTH);
    }

    @Test
    @DisplayName("sendCommand 解析响应体并去除尾部终止符")
    void sendCommand_shouldReturnBody() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        InputStream in = new ByteArrayInputStream(
                packet(2, RconProtocol.PACKET_TYPE_RESPONSE_VALUE, "hello"));

        String result = RconProtocol.sendCommand(in, out, "status");

        assertThat(result).isEqualTo("hello");
        assertThat(out.toByteArray()).isNotEmpty();
    }

    private static InputStream packetWithLength(int length) {
        ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(length);
        return new ByteArrayInputStream(buffer.array());
    }

    private static byte[] packet(int id, int type, String body) {
        return RconProtocol.buildPacket(id, type, body);
    }
}
