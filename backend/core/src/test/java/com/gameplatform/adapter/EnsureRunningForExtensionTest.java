package com.gameplatform.adapter;

import com.gameplatform.deploy.DeploymentAccess;
import com.gameplatform.deploy.HostCredentials;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.mapper.HostMapper;
import com.gameplatform.util.SshUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;

/**
 * 扩展阶段收尾起回的调用面测试（design.md §14.13.2 / §14.13.3，V-27 ① 的单类内判据）。
 *
 * <p>核的是<b>命令形状</b>与<b>就绪判定</b>两列各自的取值（§14.13.3 表是唯一出处）：
 * 两类不是同一条命令（compose 带 {@code COMPOSE_HTTP_TIMEOUT=300}、lgsm-docker 不带），
 * 也不是同一套判定（compose 认 {@code ps} 的 running/Up，lgsm-docker 逐个容器探 {@code .State.Running}）。
 * 跨类实跑归 stage 4 的 V-27 完整判据。
 */
@ExtendWith(MockitoExtension.class)
class EnsureRunningForExtensionTest {

    private static final Long INSTANCE_ID = 7L;
    private static final String WORK_DIR = "/srv/app";

    @Mock
    private SshUtil sshUtil;
    @Mock
    private HostMapper hostMapper;
    @Mock
    private GameInstanceMapper instanceMapper;
    @Mock
    private DeploymentAccess deployAccess;

    private final List<String> commands = new ArrayList<>();
    private final List<Long> timeouts = new ArrayList<>();
    private Function<String, SshUtil.CommandResult> responder = command -> result(true, "", 0);

    @BeforeEach
    void wireHostChannel() {
        GameInstance instance = new GameInstance();
        instance.setId(INSTANCE_ID);
        instance.setHostId(3L);
        Host host = new Host();
        host.setId(3L);
        host.setIpAddress("10.0.0.3");
        host.setSshPort(22);
        host.setSshUser("ops");

        lenient().when(instanceMapper.selectById(INSTANCE_ID)).thenReturn(instance);
        lenient().when(hostMapper.selectById(3L)).thenReturn(host);
        lenient().when(deployAccess.credentials(host)).thenReturn(
                new HostCredentials("10.0.0.3", 22, "ops", null, null));
        // 两类适配器都同时用到「默认超时」与「显式超时」两个重载，统一路由到同一个应答器
        lenient().when(sshUtil.executeCommand(anyString(), anyInt(), anyString(), isNull(), isNull(), anyString()))
                .thenAnswer(invocation -> answer(invocation.getArgument(5), 0L));
        lenient().when(sshUtil.executeCommand(anyString(), anyInt(), anyString(), isNull(), isNull(), anyString(), anyLong()))
                .thenAnswer(invocation -> answer(invocation.getArgument(5), invocation.getArgument(6)));
    }

    private SshUtil.CommandResult answer(String command, long timeoutMs) {
        commands.add(command);
        timeouts.add(timeoutMs);
        return responder.apply(command);
    }

    /** 应答器：Compose 命令探测 + up -d 都成功，其余交给 psOutput 决定。 */
    private Function<String, SshUtil.CommandResult> composeLifecycle(String psOutput) {
        return command -> {
            if (command.contains("compose version")) {
                return result(true, "Docker Compose version v2.24.5", 0);
            }
            if (command.contains(" up -d")) {
                return result(true, "Container started", 0);
            }
            if (command.endsWith(" ps")) {
                return result(true, psOutput, 0);
            }
            return result(true, "", 0);
        };
    }

    // ==================== 默认实现 ====================

    @Nested
    @DisplayName("DeployAdapter 默认实现")
    class DefaultImplementation {

        @Test
        @DisplayName("集合外的适配器抛异常，绝不静默返回 true")
        void unsupportedAdaptersThrowInsteadOfClaimingSuccess() {
            // DockerAdapter / LinuxGsmAdapter 零改动，继承的正是「抛异常」这个默认值
            DeployAdapter docker = new DockerAdapter();
            DeployAdapter linuxGsm = new LinuxGsmAdapter();
            assertThrows(UnsupportedOperationException.class,
                    () -> docker.ensureRunningForExtension(INSTANCE_ID, Map.of()));
            assertThrows(UnsupportedOperationException.class,
                    () -> linuxGsm.ensureRunningForExtension(INSTANCE_ID, Map.of()));
        }

        @Test
        @DisplayName("是 default 方法：既有实现者不改即可编译")
        void methodIsDefaultForImplementorCompatibility() throws Exception {
            Method method = DeployAdapter.class.getMethod(
                    "ensureRunningForExtension", Long.class, Map.class);
            assertTrue(method.isDefault(), "必须带默认实现（实现者兼容性，§8.5）");
        }
    }

    // ==================== docker-compose ====================

    @Nested
    @DisplayName("docker-compose 覆写")
    class DockerCompose {

        private DockerComposeAdapter adapter;

        @BeforeEach
        void setUp() {
            adapter = new DockerComposeAdapter();
            injectIntoBase(adapter);
        }

        @Test
        @DisplayName("命令逐字照抄 deploy 在用的那条 up -d")
        void commandCopiesDeployUpDashDVerbatim() {
            responder = composeLifecycle("l4d2  running");
            assertTrue(adapter.ensureRunningForExtension(INSTANCE_ID, config()));

            String up = firstCommandContaining(" up -d");
            assertEquals("cd " + WORK_DIR + " && COMPOSE_HTTP_TIMEOUT=300 timeout 1200 docker compose -p game7 up -d", up);
            assertEquals(1200000L, timeouts.get(commands.indexOf(up)), "SSH 预算沿用 deploy 的 1200000");
        }

        @Test
        @DisplayName("就绪判定既认 V1 的 Up 也认 V2 的 running")
        void readinessAcceptsBothV1UpAndV2Running() {
            for (String status : List.of("running", "Up")) {
                commands.clear();
                responder = composeLifecycle("l4d2  " + status + "  2 minutes");
                assertTrue(adapter.ensureRunningForExtension(INSTANCE_ID, config()),
                        "compose " + status + " 状态必须判已起回");
                assertTrue(commands.stream().anyMatch(c -> c.endsWith(" ps")), "判据走 ps");
            }
        }

        @Test
        @DisplayName("未运行则判失败，并取容器日志作原因来源")
        void notRunningFailsAndPullsContainerLogs() {
            responder = command -> {
                if (command.contains("compose version")) return result(true, "Docker Compose v2", 0);
                if (command.contains(" up -d")) return result(true, "", 0);
                if (command.endsWith(" ps")) return result(true, "l4d2  exited (1)", 0);
                if (command.contains("logs --no-color --tail 50")) return result(true, "boom", 0);
                return result(true, "", 0);
            };
            assertFalse(adapter.ensureRunningForExtension(INSTANCE_ID, config()));
            assertTrue(commands.stream().anyMatch(c -> c.contains("logs --no-color --tail 50")),
                    "失败时取 logs --no-color --tail 50（镜像 deploy 的既有形状）");
        }

        @Test
        @DisplayName("up -d 失败时不再探测 ps")
        void upFailureShortCircuitsBeforePsProbe() {
            responder = command -> command.contains("compose version")
                    ? result(true, "Docker Compose v2", 0) : result(false, "", 1);
            assertFalse(adapter.ensureRunningForExtension(INSTANCE_ID, config()));
            assertTrue(commands.stream().noneMatch(c -> c.endsWith(" ps")));
        }
    }

    // ==================== linuxgsm-docker ====================

    @Nested
    @DisplayName("linuxgsm-docker 覆写")
    class LinuxGsmDocker {

        private LinuxGsmDockerAdapter adapter;

        @BeforeEach
        void setUp() {
            adapter = new LinuxGsmDockerAdapter();
            injectIntoBase(adapter);
        }

        @Test
        @DisplayName("命令照抄本类 deploy，但不带 COMPOSE_HTTP_TIMEOUT")
        void commandCopiesDeployWithoutComposeHttpTimeout() {
            responder = command -> {
                if (command.contains("compose version")) return result(true, "Docker Compose v2", 0);
                if (command.contains(" up -d")) return result(true, "", 0);
                if (command.endsWith(" ps -q")) return result(true, "aaa\n", 0);
                if (command.contains("docker inspect")) return result(true, "true", 0);
                return result(true, "", 0);
            };
            assertTrue(adapter.ensureRunningForExtension(INSTANCE_ID, config()));

            String up = firstCommandContaining(" up -d");
            assertEquals("cd " + WORK_DIR + " && timeout 1200 docker compose -p lgsm7 up -d", up);
            assertFalse(up.contains("COMPOSE_HTTP_TIMEOUT"),
                    "§14.13.3：本类的既有 up -d 不带该 env，不与其他类「统一」");
            assertEquals(1200000L, timeouts.get(commands.indexOf(up)));
        }

        @Test
        @DisplayName("逐个容器判定，不是第一个判完即返回")
        void judgesEveryContainerNotJustTheFirst() {
            responder = command -> {
                if (command.contains("compose version")) return result(true, "Docker Compose v2", 0);
                if (command.endsWith(" ps -q")) return result(true, "aaa\nbbb\n", 0);
                if (command.contains("docker inspect")) return result(true, command.contains("bbb") ? "false" : "true", 0);
                return result(true, "", 0);
            };
            assertFalse(adapter.ensureRunningForExtension(INSTANCE_ID, config()),
                    "第二个容器未运行必须判失败——private ensureContainerRunning 的「第一个判完即 return」不可复用");
        }

        @Test
        @DisplayName("全部容器 running 才判起回成功")
        void allContainersRunningSucceeds() {
            responder = command -> {
                if (command.contains("compose version")) return result(true, "Docker Compose v2", 0);
                if (command.endsWith(" ps -q")) return result(true, "aaa\nbbb\n", 0);
                if (command.contains("docker inspect")) return result(true, "true", 0);
                return result(true, "", 0);
            };
            assertTrue(adapter.ensureRunningForExtension(INSTANCE_ID, config()));
            assertTrue(commands.stream().filter(c -> c.contains("docker inspect")).count() == 2,
                    "两个容器各探一次");
        }

        @Test
        @DisplayName("容器已停止时也不回退到 compose start")
        void stoppedContainerDoesNotFallBackToComposeStart() {
            responder = command -> {
                if (command.contains("compose version")) return result(true, "Docker Compose v2", 0);
                if (command.endsWith(" ps -q")) return result(true, "aaa\n", 0);
                if (command.contains("docker inspect")) return result(true, "false", 0);
                return result(true, "", 0);
            };
            assertFalse(adapter.ensureRunningForExtension(INSTANCE_ID, config()));
            assertTrue(commands.stream().noneMatch(c -> c.endsWith(" start")),
                    "RISK-D11：start 不处理 depends_on 顺序，收尾不得走它");
        }

        @Test
        @DisplayName("ps -q 无容器时直接判失败")
        void emptyPsOutputFailsWithoutInspecting() {
            responder = command -> command.contains("compose version")
                    ? result(true, "Docker Compose v2", 0) : result(true, "", 0);
            assertFalse(adapter.ensureRunningForExtension(INSTANCE_ID, config()));
            assertTrue(commands.stream().noneMatch(c -> c.contains("docker inspect")));
        }
    }

    // ==================== 工具 ====================

    private Map<String, Object> config() {
        Map<String, Object> config = new HashMap<>();
        config.put("workDir", WORK_DIR);
        return config;
    }

    private String firstCommandContaining(String fragment) {
        return commands.stream().filter(c -> c.contains(fragment)).findFirst()
                .orElseThrow(() -> new AssertionError("未发出包含「" + fragment + "」的命令：" + commands));
    }

    private static SshUtil.CommandResult result(boolean success, String output, int exitCode) {
        SshUtil.CommandResult result = new SshUtil.CommandResult();
        result.setSuccess(success);
        result.setExitCode(exitCode);
        result.setOutput(output);
        result.setError(success ? "" : output);
        return result;
    }

    private void injectIntoBase(DeployAdapter adapter) {
        try {
            Class<?> base = adapter.getClass().getSuperclass();
            base.getDeclaredField("sshUtil").set(adapter, sshUtil);
            base.getDeclaredField("hostMapper").set(adapter, hostMapper);
            base.getDeclaredField("instanceMapper").set(adapter, instanceMapper);
            base.getDeclaredField("deployAccess").set(adapter, deployAccess);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
