package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.dto.ServerConfigUpdateDTO;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.vo.ServerConfigVO;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 服务器配置服务
 *
 * <p>负责 L4D2 server.cfg 配置文件的读取、写入、解析与多 tick 文件同步。
 *
 * <p>多 tick 同步策略（对齐源项目 server_config.go）：
 * <ul>
 *   <li>同步文件：server.cfg.128tick / 100tick / 60tick / 30tick</li>
 *   <li>仅当目标文件已存在时才覆盖</li>
 *   <li>写入失败仅 log.warn 不抛异常</li>
 * </ul>
 *
 * <p>marker 保留策略：
 * <ul>
 *   <li>marker {@code // [L4D2-MANAGER-CUSTOM]} 之上的内容为托管字段</li>
 *   <li>marker 之下的内容为自定义配置（customConfig）</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServerConfigService {

    /**
     * 自定义配置块标记：marker 之上为托管字段，marker 之下为自定义配置。
     */
    public static final String CUSTOM_CONFIG_MARKER = "// [L4D2-MANAGER-CUSTOM]";

    /**
     * 多 tick 同步文件名列表。
     */
    private static final String[] TICK_FILES = {
            "server.cfg.128tick",
            "server.cfg.100tick",
            "server.cfg.60tick",
            "server.cfg.30tick"
    };

    private static final String SERVER_CFG = "server.cfg";

    private final InstanceQueryService instanceQueryService;
    private final InstanceFileService instanceFileService;
    private final L4D2PathResolver pathResolver;
    private final L4D2Config config;
    private final RconService rconService;

    /**
     * 获取服务器配置。
     *
     * <p>读取 {cfgPath}/server.cfg 文件内容并解析为 VO。
     * 文件不存在或读取失败时返回空 VO（仅 instanceId 有值）。
     *
     * @param instanceId 实例 ID
     * @return 服务器配置 VO
     * @throws L4D2PluginException 实例不存在时抛出
     */
    public ServerConfigVO getServerConfig(Long instanceId) {
        InstanceVO instance = requireInstance(instanceId);
        String cfgPath = pathResolver.getCfgPath();
        String serverCfgPath = cfgPath + "/" + SERVER_CFG;

        ServerConfigVO vo = new ServerConfigVO();
        vo.setInstanceId(instanceId);

        String content;
        try {
            content = instanceFileService.readTextFile(instanceId, serverCfgPath);
        } catch (Exception e) {
            log.warn("读取 server.cfg 失败 instanceId={}, path={}, err={}",
                    instanceId, serverCfgPath, e.getMessage());
            return vo;
        }

        if (content == null || content.isEmpty()) {
            return vo;
        }

        ServerConfigVO parsed = parseServerConfig(content);
        parsed.setInstanceId(instanceId);
        return parsed;
    }

    /**
     * 更新服务器配置。
     *
     * <p>按字段顺序拼装 server.cfg 文本，写入主文件，并同步到 4 个 tick 文件。
     *
     * @param instanceId 实例 ID
     * @param dto        配置更新请求
     * @throws L4D2PluginException 实例不存在或写入失败时抛出
     */
    public void updateServerConfig(Long instanceId, ServerConfigUpdateDTO dto) {
        InstanceVO instance = requireInstance(instanceId);
        String cfgPath = pathResolver.getCfgPath();
        String serverCfgPath = cfgPath + "/" + SERVER_CFG;

        String content = buildConfigContent(dto);

        try {
            instanceFileService.writeTextFile(instanceId, serverCfgPath, content);
            log.info("写入 server.cfg 成功 instanceId={}, path={}, length={}",
                    instanceId, serverCfgPath, content.length());
        } catch (Exception e) {
            log.error("写入 server.cfg 失败 instanceId={}, path={}, err={}",
                    instanceId, serverCfgPath, e.getMessage(), e);
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "写入 server.cfg 失败: " + e.getMessage(), e);
        }

        syncMultiTickConfigs(instanceId, cfgPath, content);
    }

    /**
     * 通过 RCON 重载服务器配置。
     *
     * @param instanceId 实例 ID
     * @throws L4D2PluginException 实例不存在或 RCON 执行失败时抛出
     */
    public void reloadConfig(Long instanceId) {
        requireInstance(instanceId);

        try {
            rconService.executeCommand(instanceId, "exec server.cfg");
            log.info("重载 server.cfg 成功 instanceId={}", instanceId);
        } catch (Exception e) {
            log.error("重载 server.cfg 失败 instanceId={}, err={}", instanceId, e.getMessage(), e);
            throw new L4D2PluginException(L4D2PluginException.RCON,
                    "重载配置失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取配置文件内容。
     *
     * @param instanceId 实例 ID
     * @param fileName   文件名（禁止包含 .. / \）
     * @return 文件文本内容
     * @throws IllegalArgumentException           文件名非法时抛出
     * @throws L4D2PluginException                实例不存在或读取失败时抛出
     */
    public String getFileContent(Long instanceId, String fileName) {
        validateFileName(fileName);
        InstanceVO instance = requireInstance(instanceId);
        String cfgPath = pathResolver.getCfgPath();
        String filePath = cfgPath + "/" + fileName;

        try {
            return instanceFileService.readTextFile(instanceId, filePath);
        } catch (Exception e) {
            log.error("读取配置文件失败 instanceId={}, path={}, err={}",
                    instanceId, filePath, e.getMessage(), e);
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "读取配置文件失败: " + e.getMessage(), e);
        }
    }

    /**
     * 更新配置文件内容。
     *
     * @param instanceId 实例 ID
     * @param fileName   文件名（禁止包含 .. / \）
     * @param content    文件内容
     * @throws IllegalArgumentException 文件名非法时抛出
     * @throws L4D2PluginException      实例不存在或写入失败时抛出
     */
    public void updateFileContent(Long instanceId, String fileName, String content) {
        validateFileName(fileName);
        InstanceVO instance = requireInstance(instanceId);
        String cfgPath = pathResolver.getCfgPath();
        String filePath = cfgPath + "/" + fileName;

        try {
            instanceFileService.writeTextFile(instanceId, filePath, content);
            log.info("写入配置文件成功 instanceId={}, path={}, length={}",
                    instanceId, filePath, content == null ? 0 : content.length());
        } catch (Exception e) {
            log.error("写入配置文件失败 instanceId={}, path={}, err={}",
                    instanceId, filePath, e.getMessage(), e);
            throw new L4D2PluginException(L4D2PluginException.FILE,
                    "写入配置文件失败: " + e.getMessage(), e);
        }
    }

    // ========== 私有方法 ==========

    /**
     * 拼装 server.cfg 文本内容。
     *
     * <p>字段顺序：hostname → rcon_password → sv_password → sv_maxplayers →
     * sv_visiblemaxplayers → map → mp_gamemode → z_difficulty → extraConfig。
     * 自定义配置前插入 marker。
     *
     * @param dto 配置更新请求
     * @return 拼装后的配置文本
     */
    private String buildConfigContent(ServerConfigUpdateDTO dto) {
        StringBuilder sb = new StringBuilder();

        if (dto.getHostname() != null) {
            sb.append("hostname \"").append(dto.getHostname()).append("\"\n");
        }
        if (dto.getRconPassword() != null) {
            sb.append("rcon_password \"").append(dto.getRconPassword()).append("\"\n");
        }
        if (dto.getSvPassword() != null) {
            sb.append("sv_password \"").append(dto.getSvPassword()).append("\"\n");
        }
        if (dto.getMaxPlayers() != null) {
            sb.append("sv_maxplayers ").append(dto.getMaxPlayers()).append("\n");
        }
        if (dto.getVisibleMaxPlayers() != null) {
            sb.append("sv_visiblemaxplayers ").append(dto.getVisibleMaxPlayers()).append("\n");
        }
        if (dto.getMapName() != null) {
            sb.append("map ").append(dto.getMapName()).append("\n");
        }
        if (dto.getGameMode() != null) {
            sb.append("mp_gamemode ").append(dto.getGameMode()).append("\n");
        }
        if (dto.getDifficulty() != null) {
            sb.append("z_difficulty ").append(dto.getDifficulty()).append("\n");
        }
        if (dto.getExtraConfig() != null) {
            for (Map.Entry<String, String> entry : dto.getExtraConfig().entrySet()) {
                sb.append(entry.getKey()).append(" ").append(entry.getValue()).append("\n");
            }
        }

        // 自定义配置前插入 marker
        sb.append("\n").append(CUSTOM_CONFIG_MARKER).append("\n");

        if (dto.getCustomConfig() != null && !dto.getCustomConfig().isEmpty()) {
            sb.append(dto.getCustomConfig());
            if (!dto.getCustomConfig().endsWith("\n")) {
                sb.append("\n");
            }
        }

        return sb.toString();
    }

    /**
     * 同步多 tick 配置文件。
     *
     * <p>遍历 4 个 tick 文件，仅当目标文件已存在时才覆盖，失败仅 log.warn 不抛异常。
     *
     * @param instanceId 实例 ID
     * @param cfgPath    cfg 目录路径
     * @param content    配置文本内容
     */
    private void syncMultiTickConfigs(Long instanceId, String cfgPath, String content) {
        for (String tickFile : TICK_FILES) {
            String tickPath = cfgPath + "/" + tickFile;
            try {
                if (!instanceFileService.exists(instanceId, tickPath)) {
                    log.debug("tick 文件不存在，跳过同步 path={}", tickPath);
                    continue;
                }
                instanceFileService.writeTextFile(instanceId, tickPath, content);
                log.info("同步 tick 文件成功 path={}", tickPath);
            } catch (Exception e) {
                log.warn("同步 tick 文件失败 path={}, err={}", tickPath, e.getMessage());
            }
        }
    }

    /**
     * 解析 server.cfg 内容为 VO。
     *
     * <p>解析托管字段（hostname/rcon_password 等）与非托管字段（extraConfig），
     * marker 之后的内容作为 customConfig 原样保留。
     *
     * @param content server.cfg 文本内容
     * @return 服务器配置 VO
     */
    private ServerConfigVO parseServerConfig(String content) {
        ServerConfigVO vo = new ServerConfigVO();
        if (content == null || content.isEmpty()) {
            return vo;
        }

        String[] lines = content.split("\n", -1);
        boolean inCustomBlock = false;
        StringBuilder customConfig = new StringBuilder();
        Map<String, String> extraConfig = new LinkedHashMap<>();

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.contains(CUSTOM_CONFIG_MARKER)) {
                inCustomBlock = true;
                continue;
            }

            if (inCustomBlock) {
                if (customConfig.length() > 0) {
                    customConfig.append("\n");
                }
                customConfig.append(line);
                continue;
            }

            if (trimmed.isEmpty() || trimmed.startsWith("//")) {
                continue;
            }

            String[] parts = trimmed.split("\\s+", 2);
            if (parts.length < 2) {
                continue;
            }
            String key = parts[0];
            String value = parts[1].trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }

            switch (key) {
                case "hostname":
                    vo.setHostname(value);
                    break;
                case "rcon_password":
                    vo.setRconPassword(value);
                    break;
                case "sv_password":
                    vo.setSvPassword(value);
                    break;
                case "sv_maxplayers":
                    try {
                        vo.setMaxPlayers(Integer.parseInt(value));
                    } catch (NumberFormatException e) {
                        log.warn("解析 sv_maxplayers 失败 value={}", value);
                    }
                    break;
                case "sv_visiblemaxplayers":
                    try {
                        vo.setVisibleMaxPlayers(Integer.parseInt(value));
                    } catch (NumberFormatException e) {
                        log.warn("解析 sv_visiblemaxplayers 失败 value={}", value);
                    }
                    break;
                case "map":
                    vo.setMapName(value);
                    break;
                case "mp_gamemode":
                    vo.setGameMode(value);
                    break;
                case "z_difficulty":
                    vo.setDifficulty(value);
                    break;
                default:
                    extraConfig.put(key, value);
                    break;
            }
        }

        if (!extraConfig.isEmpty()) {
            vo.setExtraConfig(extraConfig);
        }

        String custom = customConfig.toString();
        // 去除首尾空行
        custom = custom.replaceAll("^\\n+", "").replaceAll("\\n+$", "");
        if (!custom.isEmpty()) {
            vo.setCustomConfig(custom);
        }

        return vo;
    }

    /**
     * 校验文件名安全性：禁止路径穿越。
     *
     * @param fileName 文件名
     * @throws IllegalArgumentException 文件名包含 .. / \ 时抛出
     */
    private void validateFileName(String fileName) {
        if (fileName == null || fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
            throw new IllegalArgumentException("无效的文件名: " + fileName);
        }
    }

    /**
     * 校证实例存在。
     *
     * @param instanceId 实例 ID
     * @return 实例
     * @throws L4D2PluginException 实例不存在时抛出
     */
    private InstanceVO requireInstance(Long instanceId) {
        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "实例不存在: " + instanceId);
        }
        return instance;
    }

    private String getStringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private Integer getIntValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
