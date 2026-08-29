package com.gameplatform.instanceinfo;

import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.plugin.extension.InstanceDynamicInfo;
import com.gameplatform.plugin.extension.InstanceInfoProvider;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * InstanceInfoService 行为测试（ADR-0017：缓存/降级/预算/落库）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InstanceInfoServiceTest {

    @Mock
    private InstanceInfoProviderRegistry registry;

    @Mock
    private GameInstanceMapper instanceMapper;

    @Mock
    private InstanceInfoProvider provider;

    private InstanceInfoProperties properties;
    private InstanceInfoService service;

    @BeforeEach
    void setUp() {
        properties = new InstanceInfoProperties();
        properties.setQueryBudgetMillis(2000);
        service = new InstanceInfoService(registry, properties, instanceMapper);
        when(registry.findBySource("L4D2")).thenReturn(Optional.of(provider));
    }

    private InstanceVO runningVo(long id, String gameCode, int onlinePlayers) {
        InstanceVO vo = new InstanceVO();
        vo.setId(id);
        vo.setGameCode(gameCode);
        vo.setRunStatus(1);
        vo.setOnlinePlayers(onlinePlayers);
        return vo;
    }

    @Test
    void nonRunning_zero_noQuery() {
        InstanceVO vo = runningVo(1L, "l4d2", 7);
        vo.setRunStatus(0);

        service.enrichInstances(List.of(vo));

        assertEquals(0, vo.getOnlinePlayers());
        verifyNoInteractions(provider);
        verify(instanceMapper, never()).updateOnlinePlayers(anyLong(), anyInt());
    }

    @Test
    void noProvider_keepsDbValue() {
        InstanceVO vo = runningVo(1L, "dst", 7);

        service.enrichInstances(List.of(vo));

        assertEquals(7, vo.getOnlinePlayers());
        verify(instanceMapper, never()).updateOnlinePlayers(anyLong(), anyInt());
    }

    @Test
    void providerSuccess_writesOnChange_andCaches() throws Exception {
        when(provider.getInstanceInfo(1L)).thenReturn(InstanceDynamicInfo.ofPlayerCount(5));
        InstanceVO vo = runningVo(1L, "l4d2", 3);

        service.enrichInstances(List.of(vo));
        assertEquals(5, vo.getOnlinePlayers());
        verify(instanceMapper).updateOnlinePlayers(1L, 5);

        // 第二次命中缓存：Provider 只被调用一次，值不再回写（无变化）
        service.enrichInstances(List.of(vo));
        assertEquals(5, vo.getOnlinePlayers());
        verify(provider, times(1)).getInstanceInfo(1L);
        verify(instanceMapper, times(1)).updateOnlinePlayers(anyLong(), anyInt());
    }

    @Test
    void providerSameValue_noWrite() throws Exception {
        when(provider.getInstanceInfo(1L)).thenReturn(InstanceDynamicInfo.ofPlayerCount(3));
        InstanceVO vo = runningVo(1L, "l4d2", 3);

        service.enrichInstances(List.of(vo));

        assertEquals(3, vo.getOnlinePlayers());
        verify(instanceMapper, never()).updateOnlinePlayers(anyLong(), anyInt());
    }

    @Test
    void providerNull_degradesAndCachesNegative() throws Exception {
        when(provider.getInstanceInfo(1L)).thenReturn(null);
        InstanceVO vo = runningVo(1L, "l4d2", 3);

        service.enrichInstances(List.of(vo));

        assertEquals(3, vo.getOnlinePlayers());
        verify(instanceMapper, never()).updateOnlinePlayers(anyLong(), anyInt());
        // null 结果同样进缓存（"本次不可知"在 TTL 内不重复打 Provider）
        service.enrichInstances(List.of(vo));
        verify(provider, times(1)).getInstanceInfo(1L);
    }

    @Test
    void providerThrows_degrades() throws Exception {
        when(provider.getInstanceInfo(1L)).thenThrow(new RuntimeException("rcon down"));
        InstanceVO vo = runningVo(1L, "l4d2", 3);

        service.enrichInstances(List.of(vo));

        assertEquals(3, vo.getOnlinePlayers());
        verify(instanceMapper, never()).updateOnlinePlayers(anyLong(), anyInt());
    }

    @Test
    void budgetExceeded_degradesWithoutBlockingLong() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        when(provider.getInstanceInfo(1L)).thenAnswer(inv -> {
            calls.incrementAndGet();
            Thread.sleep(3000);
            return InstanceDynamicInfo.ofPlayerCount(9);
        });
        properties.setQueryBudgetMillis(200);
        service = new InstanceInfoService(registry, properties, instanceMapper);
        InstanceVO vo = runningVo(1L, "l4d2", 3);

        long start = System.currentTimeMillis();
        service.enrichInstances(List.of(vo));
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(3, vo.getOnlinePlayers());
        assertTrue(elapsed < 2000, "应在预算附近返回，实际 " + elapsed + "ms");
        verify(instanceMapper, never()).updateOnlinePlayers(anyLong(), anyInt());
    }

    @Test
    void budgetExceeded_resultStillCachedAndWrittenForNextRequest() throws Exception {
        when(provider.getInstanceInfo(1L)).thenAnswer(inv -> {
            Thread.sleep(500);
            return InstanceDynamicInfo.ofPlayerCount(9);
        });
        properties.setQueryBudgetMillis(200);
        service = new InstanceInfoService(registry, properties, instanceMapper);
        InstanceVO vo = runningVo(1L, "l4d2", 3);

        service.enrichInstances(List.of(vo));
        assertEquals(3, vo.getOnlinePlayers(), "超预算本次降级");

        // 等待异步完成回调补缓存+回写（ADR-0017 决策 4）
        Thread.sleep(900);

        service.enrichInstances(List.of(vo));
        assertEquals(9, vo.getOnlinePlayers(), "下一个请求应命中缓存中的迟到结果");
        // 异步补写 + 第二次请求正常路径各写一次同值
        verify(instanceMapper, atLeastOnce()).updateOnlinePlayers(1L, 9);
    }

    @Test
    void extras_passthrough_evenWhenPlayerCountNull() throws Exception {
        when(provider.getInstanceInfo(1L))
                .thenReturn(new InstanceDynamicInfo(null, null, Map.of("mapName", "c1m1_hotel")));
        InstanceVO vo = runningVo(1L, "l4d2", 3);

        service.enrichInstances(List.of(vo));

        assertEquals(3, vo.getOnlinePlayers());
        assertNotNull(vo.getInfoExtras());
        assertEquals("c1m1_hotel", vo.getInfoExtras().get("mapName"));
        verify(instanceMapper, never()).updateOnlinePlayers(anyLong(), anyInt());
    }

    @Test
    void maxPlayerCount_passthrough_andNullKeepsOld() {
        InstanceVO vo = runningVo(1L, "l4d2", 3);
        vo.setMaxPlayerCount(8);
        when(provider.getInstanceInfo(1L))
                .thenReturn(new InstanceDynamicInfo(3, 16, Map.of()))
                .thenReturn(new InstanceDynamicInfo(4, null, Map.of()));

        service.enrichInstances(List.of(vo));
        assertEquals(16, vo.getMaxPlayerCount());

        // max 为 null 的后续结果不覆盖（降级不覆盖语义对 max 同样适用）；先失效缓存拿到新结果
        service.invalidate(1L);
        service.enrichInstances(List.of(vo));
        assertEquals(16, vo.getMaxPlayerCount());
        assertEquals(4, vo.getOnlinePlayers());
    }

    @Test
    void invalidate_forcesFreshQuery() throws Exception {
        when(provider.getInstanceInfo(anyLong())).thenReturn(InstanceDynamicInfo.ofPlayerCount(5));
        InstanceVO vo = runningVo(1L, "l4d2", 3);

        service.enrichInstances(List.of(vo));
        service.invalidate(1L);
        service.enrichInstances(List.of(vo));

        verify(provider, times(2)).getInstanceInfo(1L);
    }

    @Test
    void mixedBatch_mixedOutcomes() throws Exception {
        when(provider.getInstanceInfo(eq(1L))).thenReturn(InstanceDynamicInfo.ofPlayerCount(5));
        when(provider.getInstanceInfo(eq(2L))).thenReturn(null);
        InstanceVO a = runningVo(1L, "l4d2", 3);
        InstanceVO b = runningVo(2L, "l4d2", 4);
        InstanceVO c = new InstanceVO();
        c.setId(3L);
        c.setGameCode("l4d2");
        c.setRunStatus(0);
        c.setOnlinePlayers(9);

        service.enrichInstances(List.of(a, b, c));

        assertEquals(5, a.getOnlinePlayers());
        assertEquals(4, b.getOnlinePlayers());
        assertEquals(0, c.getOnlinePlayers());
        verify(instanceMapper).updateOnlinePlayers(1L, 5);
        verify(instanceMapper, never()).updateOnlinePlayers(eq(2L), anyInt());
    }
}
