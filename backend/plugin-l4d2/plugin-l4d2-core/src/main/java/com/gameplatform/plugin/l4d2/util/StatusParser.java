package com.gameplatform.plugin.l4d2.util;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RCON status 输出解析工具。
 * <p>
 * 对齐源项目 {@code logic/rcon_status.go}：
 * <ul>
 *   <li>{@link #parse(String)}：解析 {@code status} 命令输出（hostname/map/players 行 + 玩家行）</li>
 *   <li>{@link #parseUser(String)}：解析单个玩家行（10 字段正则）</li>
 *   <li>{@link #parseDifficulty(String)}：难度英文→中文（easy→简单/normal→普通/hard→高级/impossible→专家）</li>
 *   <li>{@link #parseGameMode(String)}：游戏模式英文→中文（含 [SM] 与标准 cvar 两种格式）</li>
 *   <li>{@link #translateGameMode(String)}：游戏模式翻译表（19 突变 + 6 社区模式）</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Component
public class StatusParser {

    /** hostname 行：{@code hostname: xxx} */
    private static final Pattern HOSTNAME_PATTERN = Pattern.compile("hostname:\\s*(.+)");

    /** map 行：{@code map     : xxx} */
    private static final Pattern MAP_PATTERN = Pattern.compile("map\\s*:\\s*(.+)");

    /** 玩家数行的唯一正则定义：{@code players : 4 humans, 0 bots (30 max)}；RCON 语义层直接复用。 */
    public static final Pattern PLAYERS_PATTERN =
            Pattern.compile("(\\d+)\\s+humans,\\s+\\d+\\s+bots\\s+\\((\\d+)\\s+max\\)");

    /** 玩家行 10 字段正则（对齐 rcon_status.go:187） */
    private static final Pattern USER_PATTERN = Pattern.compile(
            "^#\\s*(\\d+)\\s+(\\d+)\\s+\"([^\"]+)\"\\s+([A-Z_:0-9]+)\\s+(\\d+(?::\\d+)+)\\s+(\\d+)\\s+(\\d+)\\s+(\\w+)\\s+(\\d+)\\s+([0-9.]+:\\d+)"
    );

    /** z_difficulty cvar 输出：{@code "z_difficulty" = "hard"} */
    private static final Pattern DIFFICULTY_PATTERN = Pattern.compile("\"z_difficulty\"\\s*[=:]\\s*\"([^\"]+)\"");

    /** [SM] 查询与标准 cvar 查询共用：{@code [SM] ... "mp_gamemode": "coop"} 或 {@code "mp_gamemode" = "coop"} */
    private static final Pattern GAME_MODE_PATTERN = Pattern.compile("\"mp_gamemode\"\\s*[=:]\\s*\"([^\"]+)\"");

    private static final Map<String, String> DIFFICULTY_LABELS = orderedMap(
            "easy", "简单",
            "normal", "普通",
            "hard", "高级",
            "impossible", "专家"
    );

    private static final Map<String, String> GAME_MODE_LABELS = orderedMap(
            "coop", "合作",
            "realism", "写实",
            "survival", "生存",
            "versus", "对抗",
            "scavenge", "拾荒",
            "holdout", "坚守",
            "mutation1", "地球上最后一人",
            "mutation2", "爆头！",
            "mutation3", "血流不止",
            "mutation4", "绝境求生",
            "mutation5", "四剑客",
            "mutation7", "链锯屠杀",
            "mutation8", "铁人",
            "mutation9", "地球上最后侏儒",
            "mutation10", "仅容一人",
            "mutation11", "医疗末日",
            "mutation12", "写实对抗",
            "mutation13", "跟随公升",
            "mutation14", "碎尸盛宴",
            "mutation15", "对抗生存",
            "mutation16", "猎杀派对",
            "mutation17", "孤胆枪手",
            "mutation18", "失血对抗",
            "mutation19", "无尽坦克！",
            "mutation20", "治疗侏儒",
            "community1", "特感速递",
            "community2", "流感季节",
            "community3", "骑乘派对",
            "community4", "梦魇",
            "community5", "死亡之门",
            "community6", "Confogl"
    );

    /**
     * 解析 RCON {@code status} 命令输出。
     *
     * @param statusText status 命令原始输出
     * @return 解析结果（永不返回 null）
     */
    public Status parse(String statusText) {
        Status status = new Status();
        if (statusText == null || statusText.isEmpty()) {
            return status;
        }
        List<User> users = new ArrayList<>();
        String[] lines = statusText.split("\n");
        for (String raw : lines) {
            String line = raw == null ? "" : raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            // hostname
            Matcher hostnameMatcher = HOSTNAME_PATTERN.matcher(line);
            if (hostnameMatcher.find()) {
                status.setHostname(hostnameMatcher.group(1).trim());
                continue;
            }
            // map
            Matcher mapMatcher = MAP_PATTERN.matcher(line);
            if (mapMatcher.find()) {
                status.setMap(mapMatcher.group(1).trim());
                continue;
            }
            // players
            Matcher playersMatcher = PLAYERS_PATTERN.matcher(line);
            if (playersMatcher.find()) {
                int current = parseIntSafe(playersMatcher.group(1), 0);
                int max = parseIntSafe(playersMatcher.group(2), 0);
                status.setPlayerCount(current);
                status.setMaxPlayers(max);
                continue;
            }
            // user line
            if (line.startsWith("# ") && !line.contains("userid name") && !line.contains("end")) {
                User user = parseUser(line);
                if (user != null) {
                    users.add(user);
                }
            }
        }
        status.setUsers(users);
        return status;
    }

    /**
     * 解析单个玩家行。
     *
     * @param line 玩家行（如 {@code # 2 2 "Player" STEAM_1:0:123 1:23:45 30 0 active 5 1.2.3.4:27005})
     * @return User 对象；不匹配返回 null
     */
    public User parseUser(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        Matcher m = USER_PATTERN.matcher(line);
        if (!m.find()) {
            return null;
        }
        User user = new User();
        user.setId(parseIntSafe(m.group(1), 0));
        user.setName(m.group(3));
        user.setSteamId(m.group(4));
        user.setDuration(m.group(5));
        user.setDelay(parseIntSafe(m.group(6), 0));
        user.setLoss(parseIntSafe(m.group(7), 0));
        user.setStatus(m.group(8));
        user.setLinkRate(parseIntSafe(m.group(9), 0));
        user.setIp(m.group(10));
        // location 由调用方填充（依赖 GeoIpService）
        user.setLocation("");
        return user;
    }

    /**
     * 解析难度（z_difficulty 命令输出）。
     *
     * @param difficultyText z_difficulty 命令原始输出
     * @return 中文难度名（无法解析返回 "未知"）
     */
    public String parseDifficulty(String difficultyText) {
        if (difficultyText == null || difficultyText.isEmpty()) {
            return "未知";
        }
        Matcher matcher = DIFFICULTY_PATTERN.matcher(difficultyText);
        if (!matcher.find()) {
            return "未知";
        }
        return difficultyLabel(matcher.group(1));
    }

    /**
     * 解析游戏模式（sm_cvar mp_gamemode 或 cvar 命令输出）。
     * <p>
     * 先尝试 [SM] 格式，再尝试标准 cvar 格式。
     *
     * @param gameModeText 命令原始输出
     * @return 中文模式名（无法解析返回 "未知"）
     */
    public String parseGameMode(String gameModeText) {
        if (gameModeText == null || gameModeText.isEmpty()) {
            return "未知";
        }
        Matcher matcher = GAME_MODE_PATTERN.matcher(gameModeText);
        if (matcher.find()) {
            return gameModeLabel(matcher.group(1));
        }
        return "未知";
    }

    /**
     * 翻译游戏模式英文→中文（19 突变 + 6 社区模式）。
     */
    public String translateGameMode(String gameMode) {
        return gameModeLabel(gameMode);
    }

    /** 原始难度值或中文标签 → 规范中文标签；未知值原样返回。 */
    public String difficultyLabel(String difficulty) {
        if (difficulty == null || difficulty.isBlank()) {
            return "未知";
        }
        String normalized = difficulty.trim();
        if ("困难".equals(normalized)) {
            return "高级";
        }
        return DIFFICULTY_LABELS.getOrDefault(normalized.toLowerCase(), normalized);
    }

    /** 原始模式值或中文标签 → 规范中文标签；未知值原样返回。 */
    public String gameModeLabel(String gameMode) {
        if (gameMode == null || gameMode.isBlank()) {
            return "未知";
        }
        String normalized = gameMode.trim();
        if (normalized.endsWith("模式")) {
            normalized = normalized.substring(0, normalized.length() - "模式".length());
        }
        if ("清道夫".equals(normalized)) {
            return "拾荒";
        }
        if (GAME_MODE_LABELS.containsKey(normalized.toLowerCase())) {
            return GAME_MODE_LABELS.get(normalized.toLowerCase());
        }
        for (String label : GAME_MODE_LABELS.values()) {
            if (label.equals(normalized)) {
                return label;
            }
        }
        return normalized;
    }

    /** 中文标签或英文 code → RCON 使用的规范英文 code；未知值抛业务参数异常。 */
    public String difficultyCode(String difficulty) {
        String code = reverseLookup(DIFFICULTY_LABELS, difficultyLabel(difficulty), "难度");
        return capitalize(code);
    }

    /** 中文标签或英文 code → RCON 使用的规范英文 code；未知值抛业务参数异常。 */
    public String gameModeCode(String gameMode) {
        return reverseLookup(GAME_MODE_LABELS, gameModeLabel(gameMode), "游戏模式");
    }

    private String reverseLookup(Map<String, String> labels, String value, String domain) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("无效的" + domain + "值: " + value);
        }
        String normalized = value.trim();
        for (Map.Entry<String, String> entry : labels.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(normalized) || entry.getValue().equals(normalized)) {
                return entry.getKey();
            }
        }
        throw new IllegalArgumentException("无效的" + domain + "值: " + value);
    }

    private static String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static Map<String, String> orderedMap(String... entries) {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("词汇表必须成对定义");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            map.put(entries[i], entries[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(map);
    }

    private int parseIntSafe(String s, int defaultValue) {
        if (s == null || s.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    /**
     * 解析后的服务器状态。
     */
    @lombok.Data
    public static class Status {
        /** 主机名 */
        private String hostname;
        /** 当前地图 */
        private String map;
        /** 当前玩家数 */
        private int playerCount;
        /** 最大玩家数 */
        private int maxPlayers;
        /** 玩家列表 */
        private List<User> users = new ArrayList<>();
    }

    /**
     * 解析后的玩家信息。
     */
    @lombok.Data
    public static class User {
        /** userid */
        private int id;
        /** 玩家名 */
        private String name;
        /** SteamID（STEAM_X:Y:Z） */
        private String steamId;
        /** IP（IP:port） */
        private String ip;
        /** 归属地（由调用方填充） */
        private String location;
        /** 状态（active/spawning） */
        private String status;
        /** 延迟（ms） */
        private int delay;
        /** 丢包率（%） */
        private int loss;
        /** 在线时长（如 1:23:45） */
        private String duration;
        /** 连接速率 */
        private int linkRate;
    }
}
