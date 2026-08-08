package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.l4d2.dto.*;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.rcon.RconProtocol;
import com.gameplatform.plugin.l4d2.service.RconService;
import com.gameplatform.plugin.l4d2.vo.PlayerInfoVO;
import com.gameplatform.plugin.l4d2.vo.RconResultVO;
import com.gameplatform.plugin.l4d2.vo.ServerStatusVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RCON 远程连接控制器
 * 提供基于 Source RCON 协议的远程管理功能
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Tag(name = "L4D2 RCON 管理", description = "L4D2 服务器 RCON 远程管理接口")
@RestController
@RequestMapping("/api/plugin/l4d2/rcon")
@RequiredArgsConstructor
@Validated
public class RconController {

    private final RconService rconService;

    /**
     * 获取服务器状态
     */
    @Operation(summary = "获取服务器状态", description = "通过 RCON 获取 L4D2 服务器当前状态")
    @PostMapping("/status")
    public Result<ServerStatusVO> getStatus(@Valid @RequestBody InstanceIdDTO dto) {
        log.info("获取服务器状态, instanceId: {}", dto.getInstanceId());

        ServerStatusVO vo = new ServerStatusVO();
        vo.setOnline(false);

        try {
            RconService.ServerStatus status = rconService.getStatus(dto.getInstanceId());
            vo = convertToServerStatusVO(status);
            vo.setOnline(true);
        } catch (L4D2PluginException e) {
            // RCON 不可达（端口未映射/配置缺失/连接失败）→ 返回 online=false + reason
            log.warn("获取服务器状态失败（RCON 不可达）: instanceId={}, msg={}",
                    dto.getInstanceId(), e.getMessage());
            vo.setReason(e.getMessage());
        } catch (Exception e) {
            log.warn("获取服务器状态失败（服务器可能离线）: instanceId={}, msg={}",
                    dto.getInstanceId(), e.getMessage());
            vo.setReason(e.getMessage());
        }

        return Result.success(vo);
    }

    /**
     * 执行 RCON 命令
     */
    @Operation(summary = "执行 RCON 命令", description = "执行任意 RCON 命令")
    @PostMapping("/execute")
    public Result<RconResultVO> executeCommand(@Valid @RequestBody RconCommandDTO dto) {
        log.info("执行 RCON 命令, instanceId: {}, command: {}", dto.getInstanceId(), dto.getCommand());

        long startTime = System.currentTimeMillis();
        RconResultVO result = new RconResultVO();
        try {
            String output = rconService.executeCommand(dto.getInstanceId(), dto.getCommand());
            result.setSuccess(true);
            result.setOutput(output);
        } catch (Exception e) {
            log.error("执行 RCON 命令失败", e);
            result.setSuccess(false);
            result.setError(e.getMessage());
        }

        result.setExecutionTime(System.currentTimeMillis() - startTime);
        return Result.success(result);
    }

    /**
     * 切换地图
     */
    @Operation(summary = "切换地图", description = "切换服务器地图")
    @PostMapping("/change-map")
    public Result<Void> changeMap(@Valid @RequestBody ChangeMapDTO dto) {
        log.info("切换地图, instanceId: {}, mapName: {}", dto.getInstanceId(), dto.getMapName());
        rconService.changeMap(dto.getInstanceId(), dto.getMapName());
        return Result.success();
    }

    /**
     * 踢出玩家
     */
    @Operation(summary = "踢出玩家", description = "从服务器踢出指定玩家")
    @PostMapping("/kick")
    public Result<Void> kickPlayer(@Valid @RequestBody KickPlayerDTO dto) {
        log.info("踢出玩家, instanceId: {}, target: {}, reason: {}",
                dto.getInstanceId(), dto.getTarget(), dto.getReason());
        rconService.kickPlayer(dto.getInstanceId(), dto.getTarget());
        return Result.success();
    }

    /**
     * 封禁玩家
     */
    @Operation(summary = "封禁玩家", description = "封禁指定玩家")
    @PostMapping("/ban")
    public Result<Void> banPlayer(@Valid @RequestBody BanPlayerDTO dto) {
        log.info("封禁玩家, instanceId: {}, target: {}, kick: {}, reason: {}",
                dto.getInstanceId(), dto.getTarget(), dto.getKick(), dto.getReason());
        rconService.banPlayer(dto.getInstanceId(), dto.getTarget(),
                dto.getKick() != null ? dto.getKick() : true);
        return Result.success();
    }

    /**
     * 切换难度
     */
    @Operation(summary = "切换难度", description = "切换游戏难度")
    @PostMapping("/change-difficulty")
    public Result<Void> changeDifficulty(@Valid @RequestBody ChangeDifficultyDTO dto) {
        log.info("切换难度, instanceId: {}, difficulty: {}", dto.getInstanceId(), dto.getDifficulty());
        rconService.changeDifficulty(dto.getInstanceId(), dto.getDifficulty());
        return Result.success();
    }

    /**
     * 切换游戏模式
     */
    @Operation(summary = "切换游戏模式", description = "切换游戏模式")
    @PostMapping("/change-gamemode")
    public Result<Void> changeGameMode(@Valid @RequestBody ChangeGameModeDTO dto) {
        log.info("切换游戏模式, instanceId: {}, gameMode: {}", dto.getInstanceId(), dto.getGameMode());
        rconService.changeGameMode(dto.getInstanceId(), dto.getGameMode());
        return Result.success();
    }

    /**
     * 设置最大玩家数
     */
    @Operation(summary = "设置最大玩家数", description = "设置服务器最大玩家数")
    @PostMapping("/set-max-players")
    public Result<Void> setMaxPlayers(@Valid @RequestBody SetMaxPlayersDTO dto) {
        log.info("设置最大玩家数, instanceId: {}, maxPlayers: {}", dto.getInstanceId(), dto.getMaxPlayers());
        rconService.setMaxPlayers(dto.getInstanceId(), dto.getMaxPlayers());
        return Result.success();
    }

    /**
     * 获取地图列表
     */
    @Operation(summary = "获取地图列表", description = "获取服务器支持的地图列表")
    @GetMapping("/map-list")
    public Result<List<String>> getMapList(@Parameter(description = "实例ID") @RequestParam Long instanceId) {
        log.info("获取地图列表, instanceId: {}", instanceId);
        String output = rconService.executeCommand(instanceId, "maps *");
        List<String> maps = parseMapList(output);
        return Result.success(maps);
    }

    /**
     * 诊断端点：在 Spring Boot 上下文中直接测试 RCON 连接。
     * <p>
     * 仅用于排查 RCON 连接失败问题，绕过 RconConnectionManager 和 RconConnectionResolver，
     * 直接使用 RconProtocol.authenticate() 测试目标服务器。
     * 验证 Spring Boot 环境下 Socket 行为是否与独立 Java 程序一致。
     */
    @Operation(summary = "RCON 诊断", description = "在 Spring Boot 上下文中直接测试 RCON 连接")
    @PostMapping("/diag")
    public Result<Map<String, Object>> diag(@RequestBody Map<String, Object> body) {
        String host = (String) body.getOrDefault("host", "192.168.111.253");
        int port = ((Number) body.getOrDefault("port", 27015)).intValue();
        String password = (String) body.getOrDefault("password", "123456");
        boolean useNewThread = Boolean.TRUE.equals(body.get("useNewThread"));

        Map<String, Object> result = new HashMap<>();
        result.put("host", host);
        result.put("port", port);
        result.put("passwordLength", password.length());
        result.put("callerThread", Thread.currentThread().getName());
        result.put("useNewThread", useNewThread);
        result.put("fileEncoding", System.getProperty("file.encoding"));
        result.put("jvmName", System.getProperty("java.vm.name"));
        result.put("jvmVersion", System.getProperty("java.version"));

        Runnable diagTask = () -> runDiag(host, port, password, result);
        if (useNewThread) {
            Thread t = new Thread(diagTask, "rcon-diag-thread");
            t.setDaemon(true);
            t.start();
            try {
                t.join(15000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result.put("error", "diag thread interrupted");
            }
        } else {
            diagTask.run();
        }
        return Result.success(result);
    }

    private void runDiag(String host, int port, String password, Map<String, Object> result) {
        result.put("execThread", Thread.currentThread().getName());
        result.put("contextClassLoader", String.valueOf(Thread.currentThread().getContextClassLoader()));

        byte[] authPacket = RconProtocol.buildPacket(1, RconProtocol.PACKET_TYPE_AUTH, password);
        result.put("authPacketBytes", authPacket.length);
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < Math.min(authPacket.length, 20); i++) {
            hex.append(String.format("%02x ", authPacket[i] & 0xff));
        }
        result.put("authPacketHexHead", hex.toString().trim());

        // ========== 测试 1：传统 Socket ==========
        long t1 = System.nanoTime();
        try (Socket sock = new Socket()) {
            sock.setSoTimeout(5000);
            sock.setTcpNoDelay(true);
            sock.connect(new InetSocketAddress(host, port), 5000);
            long connectMs = (System.nanoTime() - t1) / 1_000_000;
            result.put("tcpConnectMs", connectMs);
            result.put("localAddress", String.valueOf(sock.getLocalSocketAddress()));
            result.put("remoteAddress", String.valueOf(sock.getRemoteSocketAddress()));
            result.put("tcpConnectSuccess", true);

            long t2 = System.nanoTime();
            InputStream in = sock.getInputStream();
            OutputStream out = sock.getOutputStream();
            out.write(authPacket);
            out.flush();
            long writeMs = (System.nanoTime() - t2) / 1_000_000;
            result.put("writeMs", writeMs);

            long t3 = System.nanoTime();
            String authResult = "unknown";
            int responsePackets = 0;
            int lastType = -1;
            int lastId = -99;
            try {
                for (int i = 0; i < 2; i++) {
                    byte[] response = RconProtocol.readPacket(in);
                    responsePackets++;
                    lastType = RconProtocol.parseType(response);
                    lastId = RconProtocol.parseId(response);
                    if (lastType == RconProtocol.PACKET_TYPE_AUTH_RESPONSE) {
                        authResult = (lastId == 1) ? "success" : "wrong_password";
                        break;
                    }
                }
            } catch (Exception e) {
                authResult = "read_error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            long readMs = (System.nanoTime() - t3) / 1_000_000;
            result.put("authResult", authResult);
            result.put("responsePackets", responsePackets);
            result.put("lastType", lastType);
            result.put("lastId", lastId);
            result.put("readMs", readMs);
        } catch (Exception e) {
            result.put("tcpConnectSuccess", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        // ========== 测试 2：NIO SocketChannel ==========
        try (java.nio.channels.SocketChannel channel = java.nio.channels.SocketChannel.open()) {
            channel.configureBlocking(true);
            channel.socket().setSoTimeout(5000);
            channel.socket().setTcpNoDelay(true);
            long t4 = System.nanoTime();
            channel.connect(new java.net.InetSocketAddress(host, port));
            long nioConnectMs = (System.nanoTime() - t4) / 1_000_000;
            result.put("nioConnectMs", nioConnectMs);
            result.put("nioConnectSuccess", true);

            long t5 = System.nanoTime();
            java.nio.ByteBuffer authBuffer = java.nio.ByteBuffer.wrap(authPacket);
            int written = channel.write(authBuffer);
            long nioWriteMs = (System.nanoTime() - t5) / 1_000_000;
            result.put("nioWriteMs", nioWriteMs);
            result.put("nioBytesWritten", written);

            long t6 = System.nanoTime();
            String nioAuthResult = "unknown";
            int nioResponsePackets = 0;
            int nioLastType = -1;
            int nioLastId = -99;
            try {
                InputStream nioIn = channel.socket().getInputStream();
                for (int i = 0; i < 2; i++) {
                    byte[] response = RconProtocol.readPacket(nioIn);
                    nioResponsePackets++;
                    nioLastType = RconProtocol.parseType(response);
                    nioLastId = RconProtocol.parseId(response);
                    if (nioLastType == RconProtocol.PACKET_TYPE_AUTH_RESPONSE) {
                        nioAuthResult = (nioLastId == 1) ? "success" : "wrong_password";
                        break;
                    }
                }
            } catch (Exception e) {
                nioAuthResult = "read_error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            long nioReadMs = (System.nanoTime() - t6) / 1_000_000;
            result.put("nioAuthResult", nioAuthResult);
            result.put("nioResponsePackets", nioResponsePackets);
            result.put("nioLastType", nioLastType);
            result.put("nioLastId", nioLastId);
            result.put("nioReadMs", nioReadMs);
        } catch (Exception e) {
            result.put("nioConnectSuccess", false);
            result.put("nioError", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ========== 私有方法 ==========

    /**
     * 转换服务器状态
     */
    private ServerStatusVO convertToServerStatusVO(RconService.ServerStatus status) {
        ServerStatusVO vo = new ServerStatusVO();
        vo.setHostname(status.getHostname());
        vo.setMap(status.getMap());
        vo.setPlayers(status.getPlayers());
        vo.setDifficulty(status.getDifficulty());
        vo.setGameMode(status.getGameMode());
        vo.setVersion(status.getVersion());
        vo.setOsType(status.getOsType());
        vo.setServerType(status.getServerType());

        // 解析玩家数
        if (status.getCurrentPlayerCount() != null) {
            vo.setCurrentPlayers(status.getCurrentPlayerCount());
        } else if (status.getPlayers() != null) {
            String[] parts = status.getPlayers().split("/");
            if (parts.length == 2) {
                vo.setCurrentPlayers(Integer.parseInt(parts[0].trim()));
            }
        }
        if (status.getMaxPlayerCount() != null) {
            vo.setMaxPlayers(status.getMaxPlayerCount());
        } else if (status.getPlayers() != null) {
            String[] parts = status.getPlayers().split("/");
            if (parts.length == 2) {
                vo.setMaxPlayers(Integer.parseInt(parts[1].trim()));
            }
        }

        // 转换玩家列表
        List<PlayerInfoVO> players = new ArrayList<>();
        if (status.getUsers() != null) {
            for (RconService.PlayerInfo player : status.getUsers()) {
                PlayerInfoVO playerVO = new PlayerInfoVO();
                playerVO.setId(player.getId());
                playerVO.setName(player.getName());
                playerVO.setSteamId(player.getSteamId());
                playerVO.setIp(player.getIp());
                playerVO.setStatus(player.getStatus());
                playerVO.setDelay(player.getDelay());
                playerVO.setLoss(player.getLoss());
                playerVO.setDuration(player.getDuration());
                playerVO.setLinkRate(player.getLinkRate());
                players.add(playerVO);
            }
        }
        vo.setUsers(players);

        return vo;
    }

    /**
     * 解析地图列表
     */
    private List<String> parseMapList(String output) {
        List<String> maps = new ArrayList<>();
        if (output == null || output.isEmpty()) {
            return maps;
        }

        String[] lines = output.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.contains("(fs)") || line.contains(".bsp")) {
                String mapName = line.replaceAll("\\(fs\\)", "").replaceAll("\\.bsp", "").trim();
                if (!mapName.isEmpty()) {
                    maps.add(mapName);
                }
            }
        }

        return maps;
    }
}
