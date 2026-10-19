package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.util.RconFailureDetector;
import com.gameplatform.plugin.l4d2.util.StatusParser;
import com.gameplatform.plugin.service.RconService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * L4D2 RCON 语义层（ADR-0016）。
 * <p>
 * 传输（协议、连接池、端点解析）由宿主 {@link RconService}（按 pluginId 注入的
 * 宿主能力服务）承担；本类只承载游戏语义：status 输出解析、地图/难度/模式切换、
 * kick/ban 命令封装。
 *
 * @author GamePlatform
 * @version 1.1.0
 */
@Slf4j
@Service
public class L4D2RconService {

    /** 宿主 RCON 能力（按 pluginId 绑定审计调用方） */
    private final RconService rcon;
    /** L4D2 status/难度/模式词汇表的唯一 owner。 */
    private final StatusParser statusParser;

    // 状态解析正则表达式
    private static final Pattern HOSTNAME_PATTERN = Pattern.compile("hostname:\\s*(.+)");
    private static final Pattern MAP_PATTERN = Pattern.compile("map\\s*:\\s*(.+)");
    private static final Pattern USER_PATTERN = Pattern.compile(
            "^#\\s*(\\d+)\\s+(\\d+)\\s+\"([^\"]+)\"\\s+([A-Z_:0-9]+)\\s+(\\d+(?::\\d+)+)\\s+(\\d+)\\s+(\\d+)\\s+(\\w+)\\s+(\\d+)\\s+([0-9.]+:\\d+)"
    );

    // 版本/系统/类型解析
    private static final Pattern VERSION_PATTERN = Pattern.compile("version\\s*:\\s*(\\S+)");
    private static final Pattern OS_PATTERN = Pattern.compile("os\\s*:\\s*(\\S+)");
    private static final Pattern TYPE_PATTERN = Pattern.compile("type\\s*:\\s*(.+)");

    public L4D2RconService(RconService rcon, StatusParser statusParser) {
        this.rcon = rcon;
        this.statusParser = statusParser;
    }

    /**
     * 执行 RCON 命令。
     *
     * @param instanceId 实例 ID
     * @param command    要执行的命令
     * @return 命令执行结果
     */
    public String executeCommand(long instanceId, String command) {
        return rcon.executeCommand(instanceId, command);
    }

    /**
     * 获取服务器状态。依次执行 status + z_difficulty + sm_cvar mp_gamemode。
     *
     * @param instanceId 实例 ID
     * @return 服务器状态信息
     */
    public ServerStatus getStatus(long instanceId) {
        String statusText = rcon.executeCommand(instanceId, "status");
        ServerStatus status = parseStatus(statusText);

        // 获取难度
        try {
            String difficultyText = rcon.executeCommand(instanceId, "z_difficulty");
            status.setDifficulty(statusParser.parseDifficulty(difficultyText));
        } catch (Exception e) {
            log.warn("获取游戏难度失败", e);
            status.setDifficulty("未知");
        }

        // 获取游戏模式
        try {
            String gameModeText = rcon.executeCommand(instanceId, "sm_cvar mp_gamemode");
            status.setGameMode(statusParser.parseGameMode(gameModeText));
        } catch (Exception e) {
            log.warn("获取游戏模式失败", e);
            status.setGameMode("未知");
        }

        // 版本/系统/类型（从 status 输出解析，无需额外命令）
        status.setVersion(parseVersion(statusText));
        status.setOsType(parseOsType(statusText));
        status.setServerType(parseServerType(statusText));

        return status;
    }

    /**
     * 切换地图，并等待服务器状态确认地图变化。
     *
     * <p>只确认服务器接受了 changelevel 不够：命令可能排队或被后续状态覆盖。
     * 失败回显必须返回 accepted=false；超时则 confirmed=false 并带回最后观测地图。
     */
    public ChangeMapResult changeMap(long instanceId, String mapName) {
        return changeMap(instanceId, mapName, Duration.ofSeconds(20), Duration.ofMillis(1000));
    }

    public ChangeMapResult changeMap(long instanceId, String mapName,
                                     Duration timeout, Duration pollInterval) {
        String output = executeCommand(instanceId, "changelevel " + mapName);
        ChangeMapResult result = new ChangeMapResult(mapName, output);
        if (isChangeMapFailure(output)) {
            result.setError("服务器拒绝换图: " + firstNonBlankLine(output));
            return result;
        }
        result.setAccepted(true);

        long deadline = System.nanoTime() + timeout.toNanos();
        do {
            try {
                String currentMap = readCurrentMap(instanceId);
                result.setCurrentMap(currentMap);
                if (mapMatches(mapName, currentMap)) {
                    result.setConfirmed(true);
                    result.setError(null);
                    return result;
                }
                result.setError(null);
            } catch (Exception e) {
                result.setError("换图后读取服务器状态失败: " + e.getMessage());
            }
            if (System.nanoTime() + pollInterval.toNanos() > deadline) {
                break;
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                result.setError("等待换图结果被中断");
                return result;
            }
        } while (System.nanoTime() < deadline);

        if (result.getError() == null || result.getError().isBlank()) {
            result.setError("换图命令已发送，但未在 " + timeout.toSeconds()
                    + " 秒内确认地图切换");
        }
        return result;
    }

    /**
     * 踢出玩家
     */
    public void kickPlayer(long instanceId, String target) {
        String kickTarget = target.contains(" ") ? "\"" + target + "\"" : target;
        executeCommand(instanceId, "kick " + kickTarget);
    }

    /**
     * 封禁玩家
     */
    public void banPlayer(long instanceId, String target, boolean kick) {
        String banCmd = "banid 0 " + target;
        if (kick) {
            banCmd += " kick";
        }
        executeCommand(instanceId, banCmd);
        executeCommand(instanceId, "writeid");
    }

    /**
     * 切换难度
     */
    public void changeDifficulty(long instanceId, String difficulty) {
        String englishDifficulty = statusParser.difficultyCode(difficulty);
        // SourceMod servers expose z_difficulty as a protected replicated cvar.
        // A bare "z_difficulty <value>" can be accepted with an empty echo while
        // leaving the value unchanged, so prefer SourceMod's privileged setter.
        // Plain servers may not have SourceMod; fall back to the native setter
        // when SourceMod rejects the command.
        String setResult = executeCommand(
                instanceId, "sm_cvar z_difficulty " + englishDifficulty);
        if (RconFailureDetector.isFailed(setResult)) {
            executeCommand(instanceId, "z_difficulty " + englishDifficulty);
        }

        String actual = statusParser.parseDifficulty(
                executeCommand(instanceId, "z_difficulty"));
        String expected = statusParser.difficultyLabel(englishDifficulty);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "难度切换未生效：期望 " + expected + "，实际 " + actual);
        }
    }

    /**
     * 切换游戏模式
     */
    public void changeGameMode(long instanceId, String gameMode) {
        String englishMode = statusParser.gameModeCode(gameMode);
        executeCommand(instanceId, "sm_cvar mp_gamemode " + englishMode);
    }

    /**
     * 从 status 输出解析当前人类玩家数；无法解析返回 null（ADR-0017）。
     */
    public Integer extractPlayerCount(String statusText) {
        if (statusText == null || statusText.isBlank()) {
            return null;
        }
        Matcher m = StatusParser.PLAYERS_PATTERN.matcher(statusText);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    /**
     * 从 status 输出解析人数上限（"N max"）；无法解析返回 null（ADR-0017）。
     */
    public Integer extractMaxPlayerCount(String statusText) {
        if (statusText == null || statusText.isBlank()) {
            return null;
        }
        Matcher m = StatusParser.PLAYERS_PATTERN.matcher(statusText);
        return m.find() ? Integer.valueOf(m.group(2)) : null;
    }

    /**
     * 设置最大玩家数
     */
    public void setMaxPlayers(long instanceId, int maxPlayers) {
        if (maxPlayers < 4 || maxPlayers > 30) {
            throw new IllegalArgumentException("人数必须在 4-30 之间");
        }
        executeCommand(instanceId, "sv_visiblemaxplayers " + maxPlayers);
        executeCommand(instanceId, "sv_maxplayers " + maxPlayers);
    }

    // ========== 解析方法 ==========

    /**
     * 解析服务器状态
     */
    private ServerStatus parseStatus(String statusText) {
        ServerStatus status = new ServerStatus();
        List<PlayerInfo> players = new ArrayList<>();

        String[] lines = statusText.split("\n");
        for (String line : lines) {
            line = line.trim();

            Matcher hostnameMatcher = HOSTNAME_PATTERN.matcher(line);
            if (hostnameMatcher.find()) {
                status.setHostname(hostnameMatcher.group(1));
            }

            Matcher mapMatcher = MAP_PATTERN.matcher(line);
            if (mapMatcher.find()) {
                status.setMap(mapMatcher.group(1));
            }

            Matcher playersMatcher = StatusParser.PLAYERS_PATTERN.matcher(line);
            if (playersMatcher.find()) {
                status.setPlayers(playersMatcher.group(1) + "/" + playersMatcher.group(2));
                status.setCurrentPlayerCount(Integer.parseInt(playersMatcher.group(1)));
                status.setMaxPlayerCount(Integer.parseInt(playersMatcher.group(2)));
            }

            if (line.startsWith("# ") && !line.contains("userid name") && !line.contains("end")) {
                PlayerInfo player = parsePlayer(line);
                if (player != null) {
                    players.add(player);
                }
            }
        }

        status.setUsers(players);
        return status;
    }

    /**
     * 解析玩家信息
     */
    private PlayerInfo parsePlayer(String line) {
        Matcher matcher = USER_PATTERN.matcher(line);
        if (!matcher.find()) {
            return null;
        }

        PlayerInfo player = new PlayerInfo();
        player.setId(Integer.parseInt(matcher.group(1)));
        player.setName(matcher.group(3));
        player.setSteamId(matcher.group(4));
        player.setDuration(matcher.group(5));
        player.setDelay(Integer.parseInt(matcher.group(6)));
        player.setLoss(Integer.parseInt(matcher.group(7)));
        player.setStatus(matcher.group(8));
        player.setLinkRate(Integer.parseInt(matcher.group(9)));
        player.setIp(matcher.group(10));

        return player;
    }

    /**
     * 解析版本号
     */
    private String parseVersion(String statusText) {
        Matcher m = VERSION_PATTERN.matcher(statusText);
        return m.find() ? m.group(1) : "未知";
    }

    /**
     * 解析操作系统类型
     */
    private String parseOsType(String statusText) {
        Matcher m = OS_PATTERN.matcher(statusText);
        return m.find() ? m.group(1) : "未知";
    }

    /**
     * 解析服务器类型
     */
    private String parseServerType(String statusText) {
        Matcher m = TYPE_PATTERN.matcher(statusText);
        return m.find() ? m.group(1).trim() : "未知";
    }

    private boolean isChangeMapFailure(String output) {
        return RconFailureDetector.isChangeMapFailed(output);
    }

    private String readCurrentMap(long instanceId) {
        String statusText = rcon.executeCommand(instanceId, "status");
        return parseStatus(statusText).getMap();
    }

    private boolean mapMatches(String requested, String current) {
        if (requested == null || current == null) {
            return false;
        }
        String normalizedCurrent = current.trim().toLowerCase();
        String normalizedRequested = requested.trim().toLowerCase();
        return normalizedCurrent.equals(normalizedRequested)
                || normalizedCurrent.equals(normalizedRequested + ".bsp")
                || normalizedCurrent.replace(".bsp", "").equals(normalizedRequested);
    }

    private String firstNonBlankLine(String output) {
        if (output == null) {
            return "无 RCON 回显";
        }
        return output.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse("无 RCON 回显");
    }

    // ========== 内部类 ==========

    /**
     * 服务器状态
     */
    @lombok.Data
    public static class ServerStatus {
        private String hostname;
        private String map;
        private String players;
        private String difficulty;
        private String gameMode;
        private List<PlayerInfo> users;
        /** 服务器版本 */
        private String version;
        /** 操作系统类型 */
        private String osType;
        /** 服务器类型 */
        private String serverType;
        /** 当前玩家数 */
        private Integer currentPlayerCount;
        /** 最大玩家数 */
        private Integer maxPlayerCount;
    }

    /**
     * 玩家信息
     */
    @lombok.Data
    public static class PlayerInfo {
        private int id;
        private String name;
        private String steamId;
        private String ip;
        private String status;
        private int delay;
        private int loss;
        private String duration;
        private int linkRate;
    }

    /**
     * 换图操作结果。
     */
    @lombok.Data
    public static class ChangeMapResult {
        private final String mapName;
        private final String output;
        private Boolean accepted = false;
        private Boolean confirmed = false;
        private String error;
        private String currentMap;

        public ChangeMapResult(String mapName, String output) {
            this.mapName = mapName;
            this.output = output;
        }
    }
}
