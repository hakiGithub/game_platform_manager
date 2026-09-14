package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.extension.ExtensionClient;
import com.gameplatform.plugin.l4d2.extension.PluginConfigResource;
import com.gameplatform.plugin.l4d2.parser.SourceModCfgParser;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.vo.CandidatePathVO;
import com.gameplatform.plugin.l4d2.vo.PluginMeta;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 插件配置候选路径回归测试：meta 声明的 config_files 优先。
 *
 * <p>缺陷背景：候选路径仅按"插件显示名"推导（cfg/sourcemod/{显示名}.cfg），
 * 而插件实际 cfg 文件名通常取自 .smx 文件名——中文显示名插件（如
 * "自选-击杀特感和女巫奖励血量(v1.1.4)(豆瓣酱な)" → l4d2_health_rewards.cfg）
 * 永远找不到配置文件，配置页误报"无配置项"。
 */
@ExtendWith(MockitoExtension.class)
class SourceModCfgServiceCandidatesTest {

    @Mock
    private InstanceQueryService instanceQueryService;
    @Mock
    private InstanceFileService instanceFileService;
    @Mock
    private L4D2PathResolver pathResolver;
    @Mock
    private L4D2RconService rconService;
    @Mock
    private PluginConfigAuditService auditService;
    @Mock
    private PluginMetaService pluginMetaService;
    @Mock
    private SourceModCfgParser cfgParser;
    @Mock
    private ExtensionClient extensionClient;

    private SourceModCfgService service;

    private static final String PLUGIN_NAME = "自选-击杀特感和女巫奖励血量(v1.1.4)(豆瓣酱な)";
    private static final String REAL_CFG = "cfg/sourcemod/l4d2_health_rewards.cfg";

    @BeforeEach
    void setUp() {
        service = new SourceModCfgService(instanceQueryService, instanceFileService,
                extensionClient, cfgParser, new L4D2PathResolver(), rconService,
                auditService, pluginMetaService);
    }

    private void mockInstance() {
        InstanceVO instance = new InstanceVO();
        instance.setId(9L);
        instance.setHostId(2L);
        when(instanceQueryService.getInstanceById(9L)).thenReturn(instance);
    }

    private PluginMeta metaWithCfg() {
        PluginMeta meta = new PluginMeta();
        meta.setName(PLUGIN_NAME);
        meta.setConfigFiles(List.of(REAL_CFG));
        return meta;
    }

    @Test
    void 候选路径_meta声明的config_files应排在最前() {
        when(pluginMetaService.load(9L, PLUGIN_NAME)).thenReturn(metaWithCfg());

        List<String> paths = service.getCandidatePaths(9L, PLUGIN_NAME);

        // 主候选 = meta 声明的真实 cfg 路径；次候选 = 按显示名推导的兜底
        assertThat(paths).contains(REAL_CFG, "cfg/sourcemod/" + PLUGIN_NAME + ".cfg");
        assertThat(paths.indexOf(REAL_CFG)).isZero();
    }

    @Test
    void 候选路径_无meta时回退按插件名推导() {
        when(pluginMetaService.load(9L, PLUGIN_NAME)).thenReturn(null);

        List<String> paths = service.getCandidatePaths(9L, PLUGIN_NAME);

        assertThat(paths).contains("cfg/sourcemod/" + PLUGIN_NAME + ".cfg");
        assertThat(paths).allMatch(p -> p.endsWith(".cfg"));
    }

    @Test
    void 读取配置_中文显示名插件应通过meta找到真实cfg并解析() throws Exception {
        mockInstance();
        when(pluginMetaService.load(9L, PLUGIN_NAME)).thenReturn(metaWithCfg());
        // meta 声明的 cfg 在游戏目录真实存在（其余候选路径的 exists 不需打桩——
        // 命中 meta 候选后循环即返回，多余的打桩会触发 Mockito 严格模式报错）
        when(instanceFileService.exists(eq(9L), contains("l4d2_health_rewards.cfg"))).thenReturn(true);
        String cfgContent = "// 击杀特感和女巫奖励血量\nsm_cvar health_reward_enable 1\n";
        when(instanceFileService.readTextFile(eq(9L), contains("l4d2_health_rewards.cfg"), any(java.nio.charset.Charset.class)))
                .thenReturn(cfgContent);
        com.gameplatform.plugin.l4d2.vo.config.ConfigItem item =
                new com.gameplatform.plugin.l4d2.vo.config.ConfigItem();
        item.setKey("health_reward_enable");
        item.setValue("1");
        when(cfgParser.parse(cfgContent)).thenReturn(List.of(item));
        // 扩展资源持久化：不存在则创建
        when(extensionClient.get(eq(com.gameplatform.plugin.l4d2.extension.PluginConfigResource.class), anyString()))
                .thenReturn(Optional.empty());

        PluginConfigResource resource = service.getConfig(9L, PLUGIN_NAME);

        assertThat(resource).isNotNull();
        assertThat(resource.getSpec().getConfigPath()).isEqualTo(REAL_CFG); // 相对 left4dead2 目录
        assertThat(resource.getSpec().getItems()).extracting("key").containsExactly("health_reward_enable");
    }
}
