package com.gameplatform.service;

import com.gameplatform.adapter.DeployAdapter;
import com.gameplatform.adapter.DeployAdapterFactory;
import com.gameplatform.adapter.DeployProgressCallback;
import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.deploy.HostCredentials;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.mapper.HostMapper;
// BASELINE-STRIP-START（与 scripts/v10-compare-baseline.sh 成对：基线侧整块删除）
import com.gameplatform.service.deploy.DeployExtensionExecutor;
// BASELINE-STRIP-END
import com.gameplatform.util.SshUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * V-10 / AC-15 的采样器：把「无扩展声明的游戏」那次部署的五项比对面落成可比对的 JSON。
 *
 * <p>五项 = 顶层 {@code progress} 值序列 / 阶段序列 / 日志行的 {@code stage}+{@code level} 原值 /
 * 终态 / 状态转移（实例 {@code runStatus} 的写入序列）。<b>不含 CSS class 与图标名</b>（SUG-9），
 * 也不含 {@code message} 文本。</p>
 *
 * <p>本类刻意<b>不引用</b>扩展阶段的任何新类型，因此同一份文件能在改造前后两棵树上分别跑一次：
 * 两份 {@code target/evidence/v10-no-extension.json} 逐字节相同即 AC-15 的判据成立
 * （KPI-04 的分子/分母另由 {@code mvn test} 全量结果承载）。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("V-10 采样器：无扩展声明的游戏（改造前后逐值比对用）")
class DeployProgressSamplingBaselineTest {

    private static final Long INSTANCE_ID = 21L;
    private static final String EVIDENCE_DIR = "target/evidence";
    private static final String EVIDENCE_FILE = "v10-no-extension.json";

    @Mock
    private DeployAdapterFactory adapterFactory;
    @Mock
    private GameInstanceMapper instanceMapper;
    @Mock
    private HostMapper hostMapper;
    @Mock
    private SshUtil sshUtil;
    @Mock
    private DeploymentAccess deployAccess;
    @Mock
    private DeployAdapter adapter;
    // BASELINE-STRIP-START
    /** 改造后新增的接线位；基线侧随本块一起删，删的是 mock 装配不是判据。 */
    @Mock
    private DeployExtensionExecutor extensionExecutor;
    // BASELINE-STRIP-END

    @InjectMocks
    private DeployService deployService;

    private final List<Integer> progressSamples = new ArrayList<>();
    private final List<String> stageSamples = new ArrayList<>();
    private final List<Integer> runStatusWrites = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(adapterFactory.getAdapter(any(DeployAdapter.DeployType.class))).thenReturn(adapter);
        when(adapterFactory.getAdapter(anyString())).thenReturn(adapter);
        when(deployAccess.credentials(any(Host.class)))
                .thenReturn(new HostCredentials("10.0.0.1", 22, "root", null, "secret"));
        when(hostMapper.selectById(1L)).thenReturn(host());
        when(instanceMapper.selectById(INSTANCE_ID)).thenReturn(instance());
        when(sshUtil.executeCommand(anyString(), anyInt(), anyString(), any(), any(), anyString(), anyLong()))
                .thenAnswer(invocation -> {
                    String command = invocation.getArgument(5);
                    SshUtil.CommandResult result = new SshUtil.CommandResult();
                    result.setSuccess(true);
                    result.setExitCode(0);
                    result.setOutput(command.startsWith("df -h") ? "10"
                            : command.startsWith("free") ? "50.0"
                            : command.contains("--version")
                            ? "Docker version 24.0.0 / Docker Compose version v2" : "ok");
                    return result;
                });
        when(adapter.preDeploy(eq(INSTANCE_ID), any(), any())).thenAnswer(invocation -> {
            sampleNow();
            return true;
        });
        when(adapter.deploy(eq(INSTANCE_ID), any(), any())).thenAnswer(invocation -> {
            sampleNow();
            return true;
        });
        when(adapter.healthCheck(eq(INSTANCE_ID), any())).thenAnswer(invocation -> {
            sampleNow();
            return true;
        });
        // BASELINE-STRIP-START
        when(extensionExecutor.runExtensionPhase(any(), any())).thenReturn(false);
        // BASELINE-STRIP-END
        // 状态转移序列：每次实例状态写库都记一笔（V-10 的第五项，SUG-16②）
        when(instanceMapper.updateById(any(GameInstance.class))).thenAnswer(invocation -> {
            GameInstance written = invocation.getArgument(0);
            runStatusWrites.add(written.getRunStatus());
            return 1;
        });
    }

    /** 在适配器动作被调用的时刻采一次顶层值，等价于轮询样本（updateTaskStatus 不产回调也不产行）。 */
    private void sampleNow() {
        DeployService.DeployTaskStatus status = deployService.getTaskStatus(INSTANCE_ID);
        if (status != null) {
            progressSamples.add(status.getProgress());
            stageSamples.add(status.getStage());
        }
    }

    @Test
    @DisplayName("跑一次无扩展声明的部署，落五项样本")
    void sampleNoExtensionDeploy() throws Exception {
        // 本用例不断言具体数值：数值由「改造前 / 改造后两份样本逐字节相同」这一判据承载，
        // 这里只保证部署成功并取证。断言在比对脚本侧（scripts/v10-compare-baseline.sh）。
        assertTrue(deployService.deploy(context(), sampler()));

        DeployService.DeployTaskStatus status = deployService.getTaskStatus(INSTANCE_ID);
        Map<String, Object> evidence = new java.util.LinkedHashMap<>();
        evidence.put("topLevelProgressSequence", fold(progressSamples));
        evidence.put("stageSequence", fold(stageSamples));
        evidence.put("logRows", status.getLogs().stream().map(row -> {
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("stage", row.getStage());
            item.put("level", row.getLevel());
            return item;
        }).toList());
        // 顺序必须稳定：Map.of 的迭代序按盐化哈希，跨 JVM 会变，会把同一份样本比成差异
        Map<String, Object> terminal = new java.util.LinkedHashMap<>();
        terminal.put("completed", status.isCompleted());
        terminal.put("success", status.isSuccess());
        terminal.put("progress", status.getProgress());
        terminal.put("stage", status.getStage());
        terminal.put("error", status.getError() == null ? "" : status.getError());
        evidence.put("terminal", terminal);
        evidence.put("runStatusWriteSequence", runStatusWrites);

        Path dir = Path.of(EVIDENCE_DIR);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(EVIDENCE_FILE), toJson(evidence));
        assertEquals(100, status.getProgress());
    }

    private DeployService.DeployContext context() {
        return DeployService.DeployContext.builder()
                .instanceId(INSTANCE_ID)
                .hostId(1L)
                .deployType(DeployAdapter.DeployType.DOCKER_COMPOSE)
                .config(new HashMap<>(Map.of("instanceId", INSTANCE_ID)))
                .autoRollback(false)
                .autoStart(false)
                .build();
    }

    private DeployProgressCallback sampler() {
        return new DeployProgressCallback() {
            @Override
            public void onProgress(int percent, String stage, String message) {
                sample();
            }

            @Override
            public void onComplete(boolean success, String message) {
                sample();
            }

            @Override
            public void onError(String error, String stage, boolean recoverable) {
                sample();
            }

            @Override
            public void onLog(String level, String message) {
                sample();
            }

            @Override
            public void onStageStart(String stage, String description) {
                sample();
            }

            @Override
            public void onStageComplete(String stage, boolean success, String message) {
                sample();
            }

            private void sample() {
                DeployService.DeployTaskStatus status = deployService.getTaskStatus(INSTANCE_ID);
                if (status != null) {
                    progressSamples.add(status.getProgress());
                    stageSamples.add(status.getStage());
                }
            }
        };
    }

    private static <T> List<T> fold(List<T> values) {
        List<T> folded = new ArrayList<>();
        for (T value : values) {
            if (folded.isEmpty() || !folded.get(folded.size() - 1).equals(value)) {
                folded.add(value);
            }
        }
        return folded;
    }

    /** 手写最小 JSON：本类要在改造前的树上也能编译，因此不引 Jackson 之外的依赖。 */
    private static String toJson(Object value) {
        return inline(value) + "\n";
    }

    @SuppressWarnings("unchecked")
    private static String inline(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof Map) {
            return writeCompact((Map<String, Object>) value);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(DeployProgressSamplingBaselineTest::inline)
                    .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
        }
        return "\"" + String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String writeCompact(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        map.forEach((key, value) -> sb.append('"').append(key).append("\": ").append(inline(value)).append(", "));
        if (map.size() > 1) {
            sb.setLength(sb.length() - 2);
        }
        return sb.append("}").toString();
    }

    private static GameInstance instance() {
        GameInstance instance = new GameInstance();
        instance.setId(INSTANCE_ID);
        instance.setInstanceName("无扩展声明游戏");
        instance.setHostId(1L);
        // 关键：configInfo 不含 deployVersion，deployType 是 compose 类但游戏无插件声明
        instance.setGameCode("l4d2");
        instance.setDeployType("docker-compose");
        instance.setRunStatus(DeployAdapter.InstanceStatus.INSTALLING.getCode());
        instance.setConfigInfo(new HashMap<>(Map.of("port", 27015)));
        return instance;
    }

    private static Host host() {
        Host host = new Host();
        host.setId(1L);
        host.setHostName("核对主机");
        host.setIpAddress("10.0.0.1");
        host.setSshPort(22);
        host.setSshUser("root");
        return host;
    }
}
