package com.gameplatform.plugin.l4d2.service;

import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.dto.ServerConfigUpdateDTO;
import com.gameplatform.plugin.l4d2.resolver.L4D2PathResolver;
import com.gameplatform.plugin.l4d2.vo.ServerConfigVO;
import com.gameplatform.plugin.service.InstanceFileService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * server.cfg 起始地图行回归测试。
 *
 * <p>严重缺陷背景：保存配置曾把 map 写成有效指令，而 srcds 在每次加载地图后
 * 会重新执行 server.cfg → map 再次触发换图 → 无限换图循环（实测每分钟百余次
 * Host_NewGame），导致运行时间恒为 0、RCON 反复断连、服务器不可用。
 *
 * <p>修复契约：
 * 1. 写入的 server.cfg 中不得存在有效 map 指令（以 // map 注释记录起始地图）；
 * 2. 注释形式的 map 行在解析时回显到 mapName（保存→读取往返一致）；
 * 3. 兼容旧文件中的有效 map 行解析。
 */
@ExtendWith(MockitoExtension.class)
class ServerConfigServiceMapLineTest {

    @Mock
    private InstanceQueryService instanceQueryService;
    @Mock
    private InstanceFileService instanceFileService;
    @Mock
    private L4D2PathResolver pathResolver;
    @Mock
    private L4D2RconService rconService;

    private ServerConfigService service;

    @BeforeEach
    void setUp() {
        service = new ServerConfigService(instanceQueryService, instanceFileService,
                pathResolver, new L4D2Config(), rconService);
    }

    private void mockInstance() {
        InstanceVO instance = new InstanceVO();
        instance.setId(9L);
        when(instanceQueryService.getInstanceById(9L)).thenReturn(instance);
    }

    private ServerConfigUpdateDTO dto(String difficulty, String mapName) {
        ServerConfigUpdateDTO dto = new ServerConfigUpdateDTO();
        dto.setInstanceId(9L);
        dto.setHostname("test-server");
        dto.setRconPassword("123456");
        dto.setSvPassword("");
        dto.setMaxPlayers(8);
        dto.setVisibleMaxPlayers(8);
        dto.setMapName(mapName);
        dto.setGameMode("coop");
        dto.setDifficulty(difficulty);
        return dto;
    }

    @Test
    void 保存配置_不得写入有效map指令_防止无限换图循环() {
        mockInstance();
        when(pathResolver.getCfgPath()).thenReturn("left4dead2/cfg");
        when(instanceFileService.exists(anyLong(), anyString())).thenReturn(false);

        ServerConfigUpdateDTO dto = dto("hard", "c1m1_hotel");
        service.updateServerConfig(9L, dto);

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(instanceFileService)
                .writeTextFile(eq(9L), anyString(), content.capture());
        // 关键契约：不存在有效 map 指令（否则 srcds 每次加载地图后重执行 cfg → 无限换图）
        assertThat(content.getValue()).doesNotMatch("(?m)^map\\s.*");
        // 起始地图以注释形式记录
        assertThat(content.getValue()).contains("// map c1m1_hotel");
    }

    @Test
    void 读取配置_注释形式的map行应回显到mapName() {
        mockInstance();
        when(pathResolver.getCfgPath()).thenReturn("left4dead2/cfg");
        String saved = String.join("\n",
                "hostname \"test-server\"",
                "rcon_password \"123456\"",
                "sv_maxplayers 8",
                "sv_visiblemaxplayers 8",
                "// map c1m1_hotel",
                "mp_gamemode coop",
                "z_difficulty hard",
                "");
        when(instanceFileService.readTextFile(eq(9L), anyString())).thenReturn(saved);

        ServerConfigVO vo = service.getServerConfig(9L);
        assertThat(vo.getMapName()).isEqualTo("c1m1_hotel");
        assertThat(vo.getDifficulty()).isEqualTo("hard");
        assertThat(vo.getMaxPlayers()).isEqualTo(8);
        // 注释行不应泄漏进额外配置
        if (vo.getExtraConfig() != null) {
            assertThat(vo.getExtraConfig()).doesNotContainKey("// map");
        }
    }

    @Test
    void 读取配置_兼容旧文件中的有效map行() {
        mockInstance();
        when(pathResolver.getCfgPath()).thenReturn("left4dead2/cfg");
        String legacy = "map c2m1_fairfields\nz_difficulty normal\n";
        when(instanceFileService.readTextFile(eq(9L), anyString())).thenReturn(legacy);

        ServerConfigVO vo = service.getServerConfig(9L);
        assertThat(vo.getMapName()).isEqualTo("c2m1_fairfields");
    }
}
