package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.rcon.RconConnectionManager;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.gameplatform.plugin.l4d2.rcon.RconProtocol.sendCommand;

/**
 * RCON 远程连接服务
 * <p>
 * 业务语义层：负责 status 输出解析、命令语义封装。
 * 连接管理委托 RconConnectionManager，协议层委托 RconProtocol。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
public class RconService {

    private final RconConnectionManager connectionManager;

    // 状态解析正则表达式
    private static final Pattern HOSTNAME_PATTERN = Pattern.compile("hostname:\\s*(.+)");
    private static final Pattern MAP_PATTERN = Pattern.compile("map\\s*:\\s*(.+)");
    private static final Pattern PLAYERS_PATTERN = Pattern.compile("(\\d+)\\s+humans,\\s+\\d+\\s+bots\\s+\\((\\d+)\\s+max\\)");
    private static final Pattern USER_PATTERN = Pattern.compile(
            "^#\\s*(\\d+)\\s+(\\d+)\\s+\"([^\"]+)\"\\s+([A-Z_:0-9]+)\\s+(\\d+(?::\\d+)+)\\s+(\\d+)\\s+(\\d+)\\s+(\\w+)\\s+(\\d+)\\s+([0-9.]+:\\d+)"
    );
    private static final Pattern DIFFICULTY_PATTERN = Pattern.compile("\"z_difficulty\"\\s*=\\s*\"([^\"]+)\"");
    private static final Pattern GAME_MODE_PATTERN = Pattern.compile("\"mp_gamemode\"\\s*=\\s*\"([^\"]+)\"");

    // 新增：版本/系统/类型解析
    private static final Pattern VERSION_PATTERN = Pattern.compile("version\\s*:\\s*(\\S+)");
    private static final Pattern OS_PATTERN = Pattern.compile("os\\s*:\\s*(\\S+)");
    private static final Pattern TYPE_PATTERN = Pattern.compile("type\\s*:\\s*(.+)");

    public RconService(RconConnectionManager connectionManager, L4D2Config config) {
        this.connectionManager = connectionManager;
        // config 保留以备未来扩展，当前 ConnectionManager 已持有
    }

    /**
     * 执行 RCON 命令。
     *
     * @param instanceId 实例 ID
     * @param command    要执行的命令
     * @return 命令执行结果
     */
    public String executeCommand(long instanceId, String command) {
        return connectionManager.withConnection(instanceId, (in, out) -> sendCommand(in, out, command));
    }

    /**
     * 获取服务器状态。单连接内执行 status + z_difficulty + sm_cvar mp_gamemode 三条命令。
     *
     * @param instanceId 实例 ID
     * @return 服务器状态信息
     */
    public ServerStatus getStatus(long instanceId) {
        return connectionManager.withConnection(instanceId, (in, out) -> {
            String statusText = sendCommand(in, out, "status");
            ServerStatus status = parseStatus(statusText);

            // 获取难度
            try {
                String difficultyText = sendCommand(in, out, "z_difficulty");
                status.setDifficulty(parseDifficulty(difficultyText));
            } catch (Exception e) {
                log.warn("获取游戏难度失败", e);
                status.setDifficulty("未知");
            }

            // 获取游戏模式
            try {
                String gameModeText = sendCommand(in, out, "sm_cvar mp_gamemode");
                status.setGameMode(parseGameMode(gameModeText));
            } catch (Exception e) {
                log.warn("获取游戏模式失败", e);
                status.setGameMode("未知");
            }

            // 版本/系统/类型（从 status 输出解析，无需额外命令）
            status.setVersion(parseVersion(statusText));
            status.setOsType(parseOsType(statusText));
            status.setServerType(parseServerType(statusText));

            return status;
        });
    }

    /**
     * 切换地图
     */
    public void changeMap(long instanceId, String mapName) {
        executeCommand(instanceId, "changelevel " + mapName);
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
        String englishDifficulty = translateDifficultyToEnglish(difficulty);
        executeCommand(instanceId, "z_difficulty " + englishDifficulty);
    }

    /**
     * 切换游戏模式
     */
    public void changeGameMode(long instanceId, String gameMode) {
        String englishMode = translateGameModeToEnglish(gameMode);
        executeCommand(instanceId, "sm_cvar mp_gamemode " + englishMode);
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

            Matcher playersMatcher = PLAYERS_PATTERN.matcher(line);
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
     * 解析难度
     */
    private String parseDifficulty(String difficultyText) {
        Matcher matcher = DIFFICULTY_PATTERN.matcher(difficultyText);
        if (matcher.find()) {
            String difficulty = matcher.group(1);
            return translateDifficultyToChinese(difficulty);
        }
        return "未知";
    }

    /**
     * 解析游戏模式
     */
    private String parseGameMode(String gameModeText) {
        Matcher matcher = GAME_MODE_PATTERN.matcher(gameModeText);
        if (matcher.find()) {
            String gameMode = matcher.group(1);
            return translateGameModeToChinese(gameMode);
        }
        return "未知";
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

    /**
     * 难度英文转中文
     */
    private String translateDifficultyToChinese(String difficulty) {
        return switch (difficulty.toLowerCase()) {
            case "easy" -> "简单";
            case "normal" -> "普通";
            case "hard" -> "高级";
            case "impossible" -> "专家";
            default -> difficulty;
        };
    }

    /**
     * 难度中文转英文
     */
    private String translateDifficultyToEnglish(String difficulty) {
        return switch (difficulty) {
            case "简单" -> "Easy";
            case "普通" -> "Normal";
            case "高级" -> "Hard";
            case "专家" -> "Impossible";
            default -> throw new IllegalArgumentException("无效的难度值: " + difficulty);
        };
    }

    /**
     * 游戏模式英文转中文
     */
    private String translateGameModeToChinese(String gameMode) {
        return switch (gameMode.toLowerCase()) {
            case "coop" -> "合作";
            case "realism" -> "写实";
            case "survival" -> "生存";
            case "versus" -> "对抗";
            case "scavenge" -> "拾荒";
            case "holdout" -> "坚守";
            case "mutation1" -> "地球上最后一人";
            case "mutation2" -> "爆头！";
            case "mutation3" -> "血流不止";
            case "mutation4" -> "绝境求生";
            case "mutation5" -> "四剑客";
            case "mutation7" -> "链锯屠杀";
            case "mutation8" -> "铁人";
            case "mutation9" -> "地球上最后侏儒";
            case "mutation10" -> "仅容一人";
            case "mutation11" -> "医疗末日";
            case "mutation12" -> "写实对抗";
            case "mutation13" -> "跟随公升";
            case "mutation14" -> "碎尸盛宴";
            case "mutation15" -> "对抗生存";
            case "mutation16" -> "猎杀派对";
            case "mutation17" -> "孤胆枪手";
            case "mutation18" -> "失血对抗";
            case "mutation19" -> "无尽坦克！";
            case "mutation20" -> "治疗侏儒";
            case "community1" -> "特感速递";
            case "community2" -> "流感季节";
            case "community3" -> "骑乘派对";
            case "community4" -> "梦魇";
            case "community5" -> "死亡之门";
            case "community6" -> "Confogl";
            default -> gameMode;
        };
    }

    /**
     * 游戏模式中文转英文
     */
    private String translateGameModeToEnglish(String gameMode) {
        return switch (gameMode) {
            case "合作" -> "coop";
            case "写实" -> "realism";
            case "生存" -> "survival";
            case "对抗" -> "versus";
            case "拾荒" -> "scavenge";
            case "坚守" -> "holdout";
            case "地球上最后一人" -> "mutation1";
            case "爆头！" -> "mutation2";
            case "血流不止" -> "mutation3";
            case "绝境求生" -> "mutation4";
            case "四剑客" -> "mutation5";
            case "链锯屠杀" -> "mutation7";
            case "铁人" -> "mutation8";
            case "地球上最后侏儒" -> "mutation9";
            case "仅容一人" -> "mutation10";
            case "医疗末日" -> "mutation11";
            case "写实对抗" -> "mutation12";
            case "跟随公升" -> "mutation13";
            case "碎尸盛宴" -> "mutation14";
            case "对抗生存" -> "mutation15";
            case "猎杀派对" -> "mutation16";
            case "孤胆枪手" -> "mutation17";
            case "失血对抗" -> "mutation18";
            case "无尽坦克！" -> "mutation19";
            case "治疗侏儒" -> "mutation20";
            case "特感速递" -> "community1";
            case "流感季节" -> "community2";
            case "骑乘派对" -> "community3";
            case "梦魇" -> "community4";
            case "死亡之门" -> "community5";
            case "Confogl" -> "community6";
            default -> throw new IllegalArgumentException("无效的游戏模式: " + gameMode);
        };
    }

    // ========== 内部类 ==========

    /**
     * 服务器状态
     */
    @Data
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
    @Data
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
}
