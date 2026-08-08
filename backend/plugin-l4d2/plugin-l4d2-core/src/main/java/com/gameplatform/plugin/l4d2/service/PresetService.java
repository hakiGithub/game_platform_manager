package com.gameplatform.plugin.l4d2.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.gameplatform.plugin.l4d2.config.PresetConfig;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.l4d2.vo.PresetDetailVO;
import com.gameplatform.plugin.l4d2.vo.PresetPlugin;
import com.gameplatform.plugin.l4d2.vo.PresetPluginConfig;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * L4D2 预设服务：加载 classpath:preset.yaml，提供预设列表/详情/应用。
 *
 * <p>应用流程（对齐 l4d2-server-next ApplyPreset）：
 * <ol>
 *   <li>应用前自动创建备份（失败不阻塞）</li>
 *   <li>预校验所有插件存在（任一不存在立即抛异常，避免半启用状态）</li>
 *   <li>禁用所有插件</li>
 *   <li>启用平台插件（必装，根据实例部署类型解析；失败抛异常中止）</li>
 *   <li>启用预设中其他插件（失败仅警告，继续）</li>
 *   <li>应用 cfg 配置覆盖</li>
 * </ol>
 *
 * @author GamePlatform
 * @version 1.1.0
 */
@Slf4j
@Service
public class PresetService {

    private final PluginInstallService pluginInstallService;
    private final SourceModCfgService cfgService;
    private final BackupService backupService;
    private final InstanceQueryService instanceQueryService;
    private final PlatformPluginInstaller platformPluginInstaller;

    private PresetConfig presetConfig;
    private List<PresetDetailVO> presets;

    public PresetService(PluginInstallService pluginInstallService, SourceModCfgService cfgService,
                         BackupService backupService, InstanceQueryService instanceQueryService,
                         PlatformPluginInstaller platformPluginInstaller) {
        this.pluginInstallService = pluginInstallService;
        this.cfgService = cfgService;
        this.backupService = backupService;
        this.instanceQueryService = instanceQueryService;
        this.platformPluginInstaller = platformPluginInstaller;
    }

    /**
     * 启动时加载 preset.yaml，失败时记日志并使用空列表，避免阻塞插件启动。
     */
    @PostConstruct
    public void loadPresetYaml() {
        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("preset.yaml")) {
            if (is == null) {
                log.error("preset.yaml not found in classpath");
                presetConfig = new PresetConfig();
                presets = Collections.emptyList();
                return;
            }
            presetConfig = mapper.readValue(is, PresetConfig.class);
            presets = presetConfig.getPresets() != null ? presetConfig.getPresets() : Collections.emptyList();
            log.info("Loaded {} presets from preset.yaml", presets.size());
        } catch (Exception e) {
            log.error("Failed to load preset.yaml", e);
            presetConfig = new PresetConfig();
            presets = Collections.emptyList();
        }
    }

    /**
     * 返回所有预设。
     */
    public List<PresetDetailVO> list() {
        return presets;
    }

    /**
     * 根据 presetId 返回预设详情；不存在返回 null。
     */
    public PresetDetailVO detail(String presetId) {
        return presets.stream()
                .filter(p -> presetId.equals(p.getId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * 应用预设：预校验 → 禁用所有 → 启用平台插件 → 启用其他插件 → 应用 cfg 覆盖。
     *
     * <p>对齐 l4d2-server-next ApplyPreset：纯文件操作，不调 RCON。
     * 启用插件时通过 enableAndLoad（含 RCON load），但 cfg 覆盖仅写文件，
     * 需要用户重启服务器或手动 sm plugins reload 才能让 cfg 生效。
     */
    public void apply(Long instanceId, String presetId) {
        PresetDetailVO preset = detail(presetId);
        if (preset == null) {
            throw new L4D2PluginException(L4D2PluginException.BUSINESS, "预设不存在: " + presetId);
        }
        log.info("Applying preset {} to instance {}", presetId, instanceId);

        // 1. 应用前自动创建备份（失败不阻塞）
        try {
            backupService.create(instanceId,
                    "preset-apply-" + presetId + "-" + System.currentTimeMillis(),
                    "应用预设前自动备份");
            log.info("应用预设前已创建备份: instanceId={}, preset={}", instanceId, presetId);
        } catch (Exception e) {
            log.warn("应用预设前创建备份失败（继续应用）: {}", e.getMessage());
        }

        // 2. 预校验所有插件存在（对齐 preset.go:96-107）
        String platformPlugin = resolvePlatformPlugin(instanceId);
        if (platformPlugin != null && !platformPlugin.isBlank()) {
            if (!pluginInstallService.pluginExists(instanceId, platformPlugin)) {
                // 平台插件缺失时尝试从内置 ZIP 自动安装（仅支持 PLATFORM_PLUGIN_NAME）
                if (PlatformPluginInstaller.PLATFORM_PLUGIN_NAME.equals(platformPlugin)) {
                    log.info("平台插件缺失，自动从内置 ZIP 安装: instanceId={}", instanceId);
                    platformPluginInstaller.install(instanceId);
                } else {
                    throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                            "平台插件不存在: " + platformPlugin + "，请先通过商店或上传安装");
                }
            }
        }
        if (preset.getPlugins() != null) {
            for (PresetPlugin pp : preset.getPlugins()) {
                if (!pluginInstallService.pluginExists(instanceId, pp.getName())) {
                    throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                            "预设插件不存在: " + pp.getName() + "，请先通过商店或上传安装");
                }
            }
        }

        // 3. 禁用所有插件
        pluginInstallService.disableAllPlugins(instanceId);

        // 4. 优先启用平台插件（必装，失败抛异常中止，对齐 preset.go:130-135）
        if (platformPlugin != null && !platformPlugin.isBlank()) {
            try {
                pluginInstallService.enableAndLoad(instanceId, platformPlugin);
                log.info("已启用平台插件: instanceId={}, plugin={}", instanceId, platformPlugin);
            } catch (Exception e) {
                throw new L4D2PluginException(L4D2PluginException.BUSINESS,
                        "启用平台插件失败: " + platformPlugin + "，预设应用中止: " + e.getMessage(), e);
            }
        }

        // 5. 启用预设中其他插件（跳过平台插件，失败仅警告继续）
        if (preset.getPlugins() != null) {
            for (PresetPlugin pp : preset.getPlugins()) {
                if (pp.getName().equals(platformPlugin)) {
                    continue; // 已在步骤 4 启用
                }
                try {
                    pluginInstallService.enableAndLoad(instanceId, pp.getName());
                } catch (Exception e) {
                    log.warn("Failed to enable plugin {}: {}", pp.getName(), e.getMessage());
                }
            }
        }

        // 6. 应用 cfg 覆盖（不调 RCON，仅写文件）
        if (preset.getPlugins() != null) {
            for (PresetPlugin pp : preset.getPlugins()) {
                if (pp.getConfigs() == null || pp.getConfigs().isEmpty()) continue;
                for (PresetPluginConfig cfg : pp.getConfigs()) {
                    applyPluginConfig(instanceId, pp.getName(), cfg);
                }
            }
        }
    }

    /**
     * 解析当前实例对应的平台插件名。
     *
     * <p>对齐开源 preset.go:74-82 的 runtime.GOOS 判断，但适配本项目架构：
     * <ul>
     *   <li>Docker 类部署（docker/docker-compose/linuxgsm-docker）→ 游戏服务器运行在 Linux 容器中，返回 platform.linux</li>
     *   <li>Native 部署 → 使用后端 OS 判断</li>
     *   <li>无法确定 → 返回 null（跳过平台插件启用）</li>
     * </ul>
     *
     * @param instanceId 实例 ID
     * @return 平台插件名，或 null
     */
    private String resolvePlatformPlugin(Long instanceId) {
        if (presetConfig == null) {
            return null;
        }
        Map<String, String> platform = presetConfig.getPlatform();
        if (platform == null || platform.isEmpty()) {
            return null;
        }

        // 查询实例以确定部署类型
        String deployType = null;
        try {
            InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
            if (instance != null) {
                deployType = instance.getDeployType();
            }
        } catch (Exception e) {
            log.warn("查询实例 {} 失败，使用后端 OS 判断平台: {}", instanceId, e.getMessage());
        }

        // Docker 类部署 → Linux
        if (deployType != null) {
            String lower = deployType.toLowerCase();
            if (lower.contains("docker")) {
                return platform.get("linux");
            }
        }

        // Native 或未知 → 使用后端 OS 判断
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("windows")) {
            return platform.get("windows");
        }
        if (osName.contains("linux")) {
            return platform.get("linux");
        }
        return null;
    }

    private void applyPluginConfig(Long instanceId, String pluginName, PresetPluginConfig cfg) {
        try {
            cfgService.updateOrCreateConfig(instanceId, pluginName, cfg.getName(), cfg.getValues());
        } catch (Exception e) {
            log.warn("Failed to apply config {} for plugin {}: {}",
                    cfg.getName(), pluginName, e.getMessage());
        }
    }
}
