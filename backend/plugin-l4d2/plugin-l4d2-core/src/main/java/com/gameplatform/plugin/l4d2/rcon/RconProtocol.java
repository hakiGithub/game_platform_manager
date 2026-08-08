package com.gameplatform.plugin.l4d2.rcon;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Source RCON 协议工具类。
 * <p>
 * 数据包格式（Little Endian）：
 * <pre>
 * +--------+----+------+--------+----+
 * | Size   | ID | Type | Body   | \0\0 |
 * | 4 bytes| 4  | 4    | N bytes| 2  |
 * +--------+----+------+--------+----+
 * Size = 后续内容总长度（ID + Type + Body + 2）
 * </pre>
 */
public final class RconProtocol {

    private static final Logger log = LoggerFactory.getLogger(RconProtocol.class);

    public static final int PACKET_TYPE_AUTH = 3;
    public static final int PACKET_TYPE_AUTH_RESPONSE = 2;
    public static final int PACKET_TYPE_EXECCOMMAND = 2;
    public static final int PACKET_TYPE_RESPONSE_VALUE = 0;

    private RconProtocol() {
    }

    /**
     * 构建 AUTH 数据包（用于在 connect 前预构建，避免 connect 后构建延迟）。
     *
     * @param requestId 请求 ID（通常为 1）
     * @param password  RCON 密码
     * @return AUTH 数据包字节数组
     */
    public static byte[] buildAuthPacket(int requestId, String password) {
        return buildPacket(requestId, PACKET_TYPE_AUTH, password);
    }

    /**
     * 读取并校验 RCON 认证响应。
     * <p>
     * 服务器可能返回 1~2 个包：先空 RESPONSE_VALUE(type=0)，后 AUTH_RESPONSE(type=2)。
     * AUTH_RESPONSE 的 id 与请求 id 相同表示认证成功，id=-1 表示密码错误。
     *
     * @param in        输入流
     * @param requestId 期望的请求 ID（用于校验）
     * @throws IOException 认证失败（密码错误、服务器关闭连接或协议异常）
     */
    public static void readAuthResponse(InputStream in, int requestId) throws IOException {
        int authResultId = -1;
        IOException readError = null;
        for (int i = 0; i < 2; i++) {
            try {
                byte[] response = readPacket(in);
                int type = parseType(response);
                int id = parseId(response);
                if (type == PACKET_TYPE_AUTH_RESPONSE) {
                    authResultId = id;
                    break;
                }
                // type==0（RESPONSE_VALUE）是空响应包，继续读下一个
            } catch (IOException e) {
                readError = e;
                break;
            }
        }

        if (authResultId == -1) {
            if (readError != null) {
                throw new IOException("RCON 认证失败：服务器关闭连接（可能未启用 RCON、密码错误或被 RCON 封禁）", readError);
            }
            throw new IOException("RCON 认证失败（密码错误或服务器未启用 RCON）");
        }

        if (authResultId != requestId) {
            throw new IOException("RCON 认证失败：密码错误（服务器返回 id=" + authResultId + "）");
        }

        log.debug("RCON 认证成功");
    }

    /**
     * RCON 认证（便捷方法：内部构建 AUTH 包）。
     * <p>
     * 注意：调用方应在 connect 后立即调用此方法，避免服务器空闲超时关闭连接。
     * 如需进一步优化时序，请使用 {@link #buildAuthPacket} + 直接 write/flush + {@link #readAuthResponse}。
     *
     * @throws IOException 认证失败（密码错误或协议异常）
     */
    public static void authenticate(InputStream in, OutputStream out, String password) throws IOException {
        int requestId = 1;
        byte[] authPacket = buildAuthPacket(requestId, password);
        out.write(authPacket);
        out.flush();
        readAuthResponse(in, requestId);
    }

    /**
     * 发送命令并返回响应体。
     */
    public static String sendCommand(InputStream in, OutputStream out, String command) throws IOException {
        int requestId = 2;
        byte[] commandPacket = buildPacket(requestId, PACKET_TYPE_EXECCOMMAND, command);
        out.write(commandPacket);
        out.flush();
        byte[] response = readPacket(in);
        return parseBody(response);
    }

    /**
     * 构建 RCON 数据包（id 默认 0）。
     */
    public static byte[] buildPacket(int type, String body) {
        return buildPacket(0, type, body);
    }

    /**
     * 构建 RCON 数据包。
     *
     * @param id   请求 ID
     * @param type 包类型
     * @param body 命令体
     */
    public static byte[] buildPacket(int id, int type, String body) {
        byte[] bodyBytes = body.getBytes();
        int length = 4 + 4 + bodyBytes.length + 2; // ID + Type + Body + 2 终止符
        ByteBuffer buffer = ByteBuffer.allocate(length + 4); // +4 for Size
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(length);       // Size
        buffer.putInt(id);           // ID
        buffer.putInt(type);         // Type
        buffer.put(bodyBytes);       // Body
        buffer.put((byte) 0);        // 终止符 1
        buffer.put((byte) 0);        // 终止符 2
        return buffer.array();
    }

    /**
     * 读取 RCON 数据包。返回内容不含 Size 字段（ID + Type + Body + 终止符）。
     * <p>
     * 使用循环确保完整读取 4 字节长度前缀，避免 TCP 分段导致的部分读取。
     */
    public static byte[] readPacket(InputStream in) throws IOException {
        // 循环读取 4 字节长度前缀（TCP 可能分段到达）
        byte[] lengthBytes = new byte[4];
        int totalLenRead = 0;
        while (totalLenRead < 4) {
            int bytesRead = in.read(lengthBytes, totalLenRead, 4 - totalLenRead);
            if (bytesRead == -1) {
                throw new IOException("连接已关闭（服务器可能未启动 RCON、密码错误或端口非 RCON 服务）");
            }
            totalLenRead += bytesRead;
        }

        ByteBuffer lengthBuffer = ByteBuffer.wrap(lengthBytes);
        lengthBuffer.order(ByteOrder.LITTLE_ENDIAN);
        int length = lengthBuffer.getInt();
        if (length <= 0 || length > 4096) {
            throw new IOException("无效的数据包长度: " + length);
        }

        // 循环读取数据包主体
        byte[] packet = new byte[length];
        int totalRead = 0;
        while (totalRead < length) {
            int bytesRead = in.read(packet, totalRead, length - totalRead);
            if (bytesRead == -1) {
                throw new IOException("连接已关闭（数据包读取不完整）");
            }
            totalRead += bytesRead;
        }
        return packet;
    }

    /**
     * 解析数据包 ID（第一个 int）。
     */
    public static int parseId(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        return buffer.getInt();
    }

    /**
     * 解析数据包类型（第二个 int）。
     */
    public static int parseType(byte[] data) {
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.getInt(); // 跳过 ID
        return buffer.getInt();
    }

    /**
     * 解析数据包 body（跳过 ID + Type，去除末尾 2 字节终止符）。
     */
    public static String parseBody(byte[] data) {
        if (data.length <= 8) {
            return "";
        }
        ByteBuffer buffer = ByteBuffer.wrap(data);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.getInt(); // 跳过 ID
        buffer.getInt(); // 跳过 Type
        // Body 长度 = 总长 - ID(4) - Type(4) - 终止符(2)
        int bodyLength = data.length - 4 - 4 - 2;
        if (bodyLength <= 0) {
            return "";
        }
        byte[] bodyBytes = new byte[bodyLength];
        buffer.get(bodyBytes);
        return new String(bodyBytes).trim();
    }
}
