package com.gameplatform.plugin.l4d2.controller;

import com.gameplatform.common.result.Result;
import com.gameplatform.plugin.l4d2.dto.*;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.service.L4D2RconService;
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

import java.util.ArrayList;
import java.util.List;

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

    private final L4D2RconService rconService;

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
            L4D2RconService.ServerStatus status = rconService.getStatus(dto.getInstanceId());
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

    // ========== 私有方法 ==========

    /**
     * 转换服务器状态
     */
    private ServerStatusVO convertToServerStatusVO(L4D2RconService.ServerStatus status) {
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
            for (L4D2RconService.PlayerInfo player : status.getUsers()) {
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
