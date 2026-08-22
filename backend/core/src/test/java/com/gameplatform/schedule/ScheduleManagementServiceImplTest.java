package com.gameplatform.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.entity.ScheduledTask;
import com.gameplatform.entity.ScheduledTaskRun;
import com.gameplatform.mapper.ScheduledTaskMapper;
import com.gameplatform.mapper.ScheduledTaskRunLogMapper;
import com.gameplatform.mapper.ScheduledTaskRunMapper;
import com.gameplatform.plugin.extension.ExtensionIdGenerator;
import com.gameplatform.plugin.schedule.ScheduleCreateRequest;
import com.gameplatform.plugin.schedule.ScheduleDeclaration;
import com.gameplatform.plugin.schedule.ScheduleUpdateRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * {@link ScheduleManagementServiceImpl} 管理服务测试（ADR-0011）。
 *
 * <p>覆盖：
 * <ul>
 *   <li>管理侧 create：source 归一化 + cron 校验</li>
 *   <li>插件侧 Scoped：来源隔离（越权拒绝）</li>
 *   <li>声明式 upsert 冲突语义（D5）：新建 / 用户改过跳过 / 墓碑不复活 / 未修改演进</li>
 *   <li>插件生命周期：pause / resume / purge（D8）</li>
 *   <li>管理侧 delete：取消进行中 run + 逻辑删除</li>
 * </ul>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ScheduleManagementServiceImpl 定时计划管理服务")
class ScheduleManagementServiceImplTest {

    @Mock
    private ScheduledTaskMapper scheduleMapper;
    @Mock
    private ScheduledTaskRunMapper runMapper;
    @Mock
    private ScheduledTaskRunLogMapper runLogMapper;
    @Mock
    private ScheduledTaskRegistry registry;
    @Mock
    private ScheduleTriggerEngine triggerEngine;
    @Mock
    private ScheduledTaskHandlerRegistry handlerRegistry;
    @Mock
    private ExtensionIdGenerator idGenerator;

    private ScheduleManagementServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ScheduleManagementServiceImpl(scheduleMapper, runMapper, runLogMapper,
                registry, triggerEngine, handlerRegistry, idGenerator, new ObjectMapper());
        when(idGenerator.nextId()).thenReturn("1234567890");
    }

    private ScheduledTask entity(String id, String source, String handlerKey, String cron) {
        ScheduledTask e = new ScheduledTask();
        e.setId(id);
        e.setName("测试计划");
        e.setHandlerKey(handlerKey);
        e.setCron(cron);
        e.setEnabled(1);
        e.setPaused(0);
        e.setSource(source);
        e.setUserModified(0);
        e.setDeleted(0);
        return e;
    }

    private ScheduleDeclaration declaration(String key, String cron) {
        return ScheduleDeclaration.builder()
                .key(key)
                .name("声明计划-" + key)
                .handlerKey("mapCrawl")
                .cron(cron)
                .payload(Map.of("crawlType", "increment"))
                .enabled(true)
                .build();
    }

    // ==================== 管理侧 create ====================

    @Nested
    @DisplayName("管理侧 create")
    class AdminCreate {

        @Test
        @DisplayName("合法请求：source 归一化大写、插入并注册调度")
        void createNormalizesSource() {
            ScheduleCreateRequest request = ScheduleCreateRequest.builder()
                    .name("每日爬取").handlerKey("mapCrawl").cron("0 0 4 * * ?").build();

            String id = service.create("l4d2", request, "admin");

            assertEquals("1234567890", id);
            ArgumentCaptor<ScheduledTask> captor = ArgumentCaptor.forClass(ScheduledTask.class);
            verify(scheduleMapper).insert(captor.capture());
            assertEquals("L4D2", captor.getValue().getSource());
            assertEquals("admin", captor.getValue().getCreateBy());
            verify(registry).register(any(ScheduledTask.class));
        }

        @Test
        @DisplayName("非法 cron 抛业务异常且不落库")
        void createInvalidCron() {
            ScheduleCreateRequest request = ScheduleCreateRequest.builder()
                    .name("每日爬取").handlerKey("mapCrawl").cron("not-a-cron").build();

            assertThrows(BusinessException.class, () -> service.create("MAIN", request, "admin"));
            verify(scheduleMapper, never()).insert(any(ScheduledTask.class));
        }

        @Test
        @DisplayName("source 为空抛业务异常")
        void createBlankSource() {
            ScheduleCreateRequest request = ScheduleCreateRequest.builder()
                    .name("每日爬取").handlerKey("mapCrawl").cron("0 0 4 * * ?").build();

            assertThrows(BusinessException.class, () -> service.create(" ", request, "admin"));
            verify(scheduleMapper, never()).insert(any(ScheduledTask.class));
        }
    }

    // ==================== 插件侧来源隔离 ====================

    @Nested
    @DisplayName("插件侧 Scoped 来源隔离")
    class ScopedOperations {

        @Test
        @DisplayName("updateScoped 越权（source 不匹配）拒绝操作")
        void updateScopedRejectsForeignSource() {
            ScheduledTask task = entity("s1", "L4D2", "mapCrawl", "0 0 4 * * ?");
            when(scheduleMapper.selectById("s1")).thenReturn(task);

            assertThrows(BusinessException.class,
                    () -> service.updateScoped("s1", "MAIN", new ScheduleUpdateRequest()));
            verify(scheduleMapper, never()).updateById(any(ScheduledTask.class));
        }

        @Test
        @DisplayName("updateScoped 本来源计划允许修改并置 userModified")
        void updateScopedOwnSchedule() {
            ScheduledTask task = entity("s1", "L4D2", "mapCrawl", "0 0 4 * * ?");
            when(scheduleMapper.selectById("s1")).thenReturn(task);
            ScheduleUpdateRequest request = new ScheduleUpdateRequest();
            request.setName("新名称");
            request.setCron("0 0 5 * * ?");

            service.updateScoped("s1", "L4D2", request);

            ArgumentCaptor<ScheduledTask> captor = ArgumentCaptor.forClass(ScheduledTask.class);
            verify(scheduleMapper).updateById(captor.capture());
            assertEquals(1, captor.getValue().getUserModified());
            assertEquals("新名称", captor.getValue().getName());
            verify(registry).register(any(ScheduledTask.class));
        }

        @Test
        @DisplayName("createScoped 由适配器绑定 source 与 pluginId，不取请求值")
        void createScopedBindsSource() {
            ScheduleCreateRequest request = ScheduleCreateRequest.builder()
                    .name("插件计划").handlerKey("mapCrawl").cron("0 0 4 * * ?").build();

            String id = service.createScoped("L4D2", "plugin-l4d2", request);

            assertEquals("1234567890", id);
            ArgumentCaptor<ScheduledTask> captor = ArgumentCaptor.forClass(ScheduledTask.class);
            verify(scheduleMapper).insert(captor.capture());
            assertEquals("L4D2", captor.getValue().getSource());
            assertEquals("plugin-l4d2", captor.getValue().getPluginId());
            assertEquals("plugin:plugin-l4d2", captor.getValue().getCreateBy());
        }

        @Test
        @DisplayName("triggerScoped 越权拒绝")
        void triggerScopedRejectsForeignSource() {
            when(scheduleMapper.selectById("s1")).thenReturn(entity("s1", "L4D2", "mapCrawl", "0 0 4 * * ?"));

            assertThrows(BusinessException.class, () -> service.triggerScoped("s1", "MAIN"));
            verify(triggerEngine, never()).trigger(any(ScheduledTask.class), anyBoolean());
        }
    }

    // ==================== 声明式 upsert（D5） ====================

    @Nested
    @DisplayName("声明式 upsert 冲突语义")
    class UpsertDeclarations {

        @Test
        @DisplayName("全新声明：插入并注册")
        void newDeclarationInserts() {
            when(scheduleMapper.selectByDeclarationKeyIncludingDeleted("plugin-l4d2:daily"))
                    .thenReturn(null);

            service.upsertDeclarations("plugin-l4d2", "L4D2", List.of(declaration("daily", "0 0 4 * * ?")));

            ArgumentCaptor<ScheduledTask> captor = ArgumentCaptor.forClass(ScheduledTask.class);
            verify(scheduleMapper).insert(captor.capture());
            assertEquals("plugin-l4d2:daily", captor.getValue().getDeclarationKey());
            assertEquals("L4D2", captor.getValue().getSource());
            verify(registry).register(any(ScheduledTask.class));
        }

        @Test
        @DisplayName("用户删除过的计划（墓碑）不复活")
        void deletedTombstoneNotResurrected() {
            ScheduledTask tombstone = entity("s1", "L4D2", "mapCrawl", "0 0 4 * * ?");
            tombstone.setDeleted(1);
            when(scheduleMapper.selectByDeclarationKeyIncludingDeleted("plugin-l4d2:daily"))
                    .thenReturn(tombstone);

            service.upsertDeclarations("plugin-l4d2", "L4D2", List.of(declaration("daily", "0 0 4 * * ?")));

            verify(scheduleMapper, never()).insert(any(ScheduledTask.class));
            verify(scheduleMapper, never()).updateById(any(ScheduledTask.class));
            verify(registry, never()).register(any(ScheduledTask.class));
        }

        @Test
        @DisplayName("用户修改过的计划跳过 upsert，但仍注册调度")
        void userModifiedSkipsUpdate() {
            ScheduledTask modified = entity("s1", "L4D2", "mapCrawl", "0 0 6 * * ?");
            modified.setUserModified(1);
            when(scheduleMapper.selectByDeclarationKeyIncludingDeleted("plugin-l4d2:daily"))
                    .thenReturn(modified);

            service.upsertDeclarations("plugin-l4d2", "L4D2", List.of(declaration("daily", "0 0 4 * * ?")));

            verify(scheduleMapper, never()).updateById(any(ScheduledTask.class));
            verify(registry).register(modified);
        }

        @Test
        @DisplayName("未修改过的计划随声明演进更新（enabled 保持用户意图）")
        void unmodifiedEvolveWithDeclaration() {
            ScheduledTask existing = entity("s1", "L4D2", "mapCrawl", "0 0 4 * * ?");
            existing.setEnabled(0);
            when(scheduleMapper.selectByDeclarationKeyIncludingDeleted("plugin-l4d2:daily"))
                    .thenReturn(existing);

            service.upsertDeclarations("plugin-l4d2", "L4D2", List.of(declaration("daily", "0 0 5 * * ?")));

            ArgumentCaptor<ScheduledTask> captor = ArgumentCaptor.forClass(ScheduledTask.class);
            verify(scheduleMapper).updateById(captor.capture());
            assertEquals("0 0 5 * * ?", captor.getValue().getCron());
            assertEquals(0, captor.getValue().getEnabled());
        }

        @Test
        @DisplayName("同插件声明 key 重复抛 IllegalStateException")
        void duplicateDeclarationKeyThrows() {
            assertThrows(IllegalStateException.class, () -> service.upsertDeclarations(
                    "plugin-l4d2", "L4D2",
                    List.of(declaration("daily", "0 0 4 * * ?"), declaration("daily", "0 0 5 * * ?"))));
        }

        @Test
        @DisplayName("非法 cron 的声明静默跳过（不阻断其他声明）")
        void invalidCronDeclarationSkipped() {
            when(scheduleMapper.selectByDeclarationKeyIncludingDeleted(anyString())).thenReturn(null);

            service.upsertDeclarations("plugin-l4d2", "L4D2",
                    List.of(declaration("bad", "not-a-cron"), declaration("good", "0 0 4 * * ?")));

            verify(scheduleMapper, times(1)).insert(any(ScheduledTask.class));
        }
    }

    // ==================== 插件生命周期（D8） ====================

    @Nested
    @DisplayName("插件生命周期联动")
    class PluginLifecycle {

        @Test
        @DisplayName("pauseByPlugin：未暂停计划置 paused=1 并取消调度，已暂停幂等跳过")
        void pauseByPlugin() {
            ScheduledTask active = entity("s1", "L4D2", "mapCrawl", "0 0 4 * * ?");
            ScheduledTask alreadyPaused = entity("s2", "L4D2", "clean", "0 0 5 * * ?");
            alreadyPaused.setPaused(1);
            when(scheduleMapper.selectByPluginId("plugin-l4d2")).thenReturn(List.of(active, alreadyPaused));

            service.pauseByPlugin("plugin-l4d2", "插件已停用");

            verify(scheduleMapper, times(1)).updateById(any(ScheduledTask.class));
            assertEquals(1, active.getPaused());
            assertEquals("插件已停用", active.getPauseReason());
            verify(registry).cancel("s1");
            verify(registry, never()).cancel("s2");
        }

        @Test
        @DisplayName("resumeByPlugin：暂停计划清除暂停标记并重新注册")
        void resumeByPlugin() {
            ScheduledTask paused = entity("s1", "L4D2", "mapCrawl", "0 0 4 * * ?");
            paused.setPaused(1);
            paused.setPauseReason("插件已停用");
            ScheduledTask normal = entity("s2", "L4D2", "clean", "0 0 5 * * ?");
            when(scheduleMapper.selectByPluginId("plugin-l4d2")).thenReturn(List.of(paused, normal));

            service.resumeByPlugin("plugin-l4d2");

            verify(scheduleMapper, times(1)).updateById(any(ScheduledTask.class));
            assertEquals(0, paused.getPaused());
            assertNull(paused.getPauseReason());
            verify(registry).register(paused);
        }

        @Test
        @DisplayName("purgeByPlugin：取消进行中 run → 级联删日志/记录 → 物理删计划")
        void purgeByPlugin() {
            ScheduledTask s1 = entity("s1", "L4D2", "mapCrawl", "0 0 4 * * ?");
            ScheduledTask s2 = entity("s2", "L4D2", "clean", "0 0 5 * * ?");
            when(scheduleMapper.selectByPluginId("plugin-l4d2")).thenReturn(List.of(s1, s2));

            ScheduledTaskRun run1 = new ScheduledTaskRun();
            run1.setId("r1");
            run1.setScheduleId("s1");
            ScheduledTaskRun run2 = new ScheduledTaskRun();
            run2.setId("r2");
            run2.setScheduleId("s2");
            when(runMapper.selectList(any())).thenReturn(List.of(run1, run2));

            service.purgeByPlugin("plugin-l4d2");

            verify(triggerEngine).cancelRunsBySchedule("s1");
            verify(triggerEngine).cancelRunsBySchedule("s2");
            verify(registry).cancel("s1");
            verify(registry).cancel("s2");
            verify(runLogMapper).deleteByRunIds(List.of("r1", "r2"));
            verify(runMapper).deleteByScheduleIds(List.of("s1", "s2"));
            verify(scheduleMapper).physicalDeleteByPluginId("plugin-l4d2");
        }
    }

    // ==================== 管理侧 delete ====================

    @Nested
    @DisplayName("管理侧 delete")
    class AdminDelete {

        @Test
        @DisplayName("删除计划：取消进行中 run + 逻辑删除 + 取消调度")
        void deleteCancelsAndLogicallyDeletes() {
            when(scheduleMapper.selectById("s1")).thenReturn(entity("s1", "MAIN", "backup", "0 0 4 * * ?"));

            service.delete("s1");

            verify(triggerEngine).cancelRunsBySchedule("s1");
            verify(scheduleMapper).deleteById("s1");
            verify(registry).cancel("s1");
        }

        @Test
        @DisplayName("删除不存在的计划抛业务异常")
        void deleteMissingThrows() {
            when(scheduleMapper.selectById("s404")).thenReturn(null);
            assertThrows(BusinessException.class, () -> service.delete("s404"));
        }
    }
}
