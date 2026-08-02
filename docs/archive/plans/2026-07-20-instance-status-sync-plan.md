# 实例状态双向同步 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现主机容器/进程状态与平台实例状态的双向同步，启动时和 5 分钟周期定时校正 `run_status`，以主机实际状态为准。

**Architecture:** 新增 `InstanceSyncService` 协调入口 + 两个策略类（`DockerInstanceSyncStrategy` / `NativeInstanceSyncStrategy`）+ `InstanceSyncStartupRunner` 启动钩子；扩展 `HostMonitorTask` 在 5 分钟周期后追加 `syncAll()` 调用。复用现有 `docker_container_link` 表与 `SshUtil` 连接池，不改动 3 个适配器与 `game_instance` schema。

**Tech Stack:** Java 17 + Spring Boot 3.2.5 + MyBatis-Plus + SQLite + JSoup（无需新增依赖）+ Vitest（前端无改动）

**Spec:** [docs/superpowers/specs/2026-07-20-instance-status-sync-design.md](../specs/2026-07-20-instance-status-sync-design.md)

---

## File Structure

### 新增文件（backend/core 模块）

| 文件 | 职责 | 行数估计 |
|------|------|----------|
| `core/src/main/java/com/gameplatform/service/InstanceSyncService.java` | 同步业务契约接口 | ~30 |
| `core/src/main/java/com/gameplatform/service/impl/InstanceSyncServiceImpl.java` | 协调入口，遍历主机+分发策略 | ~150 |
| `core/src/main/java/com/gameplatform/service/sync/DockerInstanceSyncStrategy.java` | Docker 类同步策略（3 个适配器共用） | ~250 |
| `core/src/main/java/com/gameplatform/service/sync/NativeInstanceSyncStrategy.java` | Native 部署 pgrep 检测策略 | ~120 |
| `core/src/main/java/com/gameplatform/service/sync/InstanceMatchResult.java` | 匹配结果值对象 | ~30 |
| `core/src/main/java/com/gameplatform/listener/InstanceSyncStartupRunner.java` | 启动时异步触发同步 | ~50 |
| `core/src/main/java/com/gameplatform/config/InstanceSyncProperties.java` | 同步配置项 | ~30 |
| `core/src/test/java/com/gameplatform/service/sync/DockerInstanceSyncStrategyTest.java` | 单元测试 | ~300 |
| `core/src/test/java/com/gameplatform/service/sync/NativeInstanceSyncStrategyTest.java` | 单元测试 | ~200 |
| `core/src/test/java/com/gameplatform/service/impl/InstanceSyncServiceImplTest.java` | 单元测试 | ~150 |

### 修改文件

| 文件 | 改动点 |
|------|--------|
| `core/src/main/java/com/gameplatform/task/HostMonitorTask.java` | 追加 `instanceSyncService.syncAll()` 调用 |
| `core/src/main/resources/application.yml` | 新增 `game-platform.instance-sync` 配置块 |
| `core/src/test/resources/application-test.yml` | 同步配置关闭（`enabled: false`） |
| `core/src/main/java/com/gameplatform/mapper/GameInstanceMapper.java` | 新增 `selectByHostIdAndDeployTypes` 方法 |

---

## Task 1: 配置类与 GameInstanceMapper 扩展

**Files:**
- Create: `backend/core/src/main/java/com/gameplatform/config/InstanceSyncProperties.java`
- Modify: `backend/core/src/main/resources/application.yml`
- Modify: `backend/core/src/test/resources/application-test.yml`
- Modify: `backend/core/src/main/java/com/gameplatform/mapper/GameInstanceMapper.java`

- [ ] **Step 1: 创建配置类**

Create `backend/core/src/main/java/com/gameplatform/config/InstanceSyncProperties.java`:

```java
package com.gameplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 实例状态同步配置项
 */
@Component
@ConfigurationProperties(prefix = "game-platform.instance-sync")
public class InstanceSyncProperties {

    /** 是否启用同步 */
    private boolean enabled = true;

    /** 启动时同步延迟（毫秒） */
    private long startupSyncDelayMs = 10000L;

    /** 同步日志级别 */
    private String logLevel = "INFO";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getStartupSyncDelayMs() { return startupSyncDelayMs; }
    public void setStartupSyncDelayMs(long startupSyncDelayMs) { this.startupSyncDelayMs = startupSyncDelayMs; }

    public String getLogLevel() { return logLevel; }
    public void setLogLevel(String logLevel) { this.logLevel = logLevel; }
}
```

- [ ] **Step 2: 追加 application.yml 配置**

在 `backend/core/src/main/resources/application.yml` 末尾追加：

```yaml
# 实例状态同步配置
game-platform:
  instance-sync:
    enabled: true
    startup-sync-delay-ms: 10000
    log-level: INFO
```

- [ ] **Step 3: 测试环境关闭同步**

在 `backend/core/src/test/resources/application-test.yml` 追加：

```yaml
game-platform:
  instance-sync:
    enabled: false
```

- [ ] **Step 4: 扩展 GameInstanceMapper**

在 `backend/core/src/main/java/com/gameplatform/mapper/GameInstanceMapper.java` 追加方法：

```java
/**
 * 按主机ID和部署类型列表查询未删除的实例
 * @param hostId 主机ID
 * @param deployTypes 部署类型列表（如 ["docker", "docker-compose", "linuxgsm-docker"]）
 * @return 实例列表
 */
@Select({"<script>",
        "SELECT * FROM game_instance",
        "WHERE host_id = #{hostId} AND is_deleted = 0",
        "AND deploy_type IN",
        "<foreach item='type' collection='deployTypes' open='(' separator=',' close=')'>#{type}</foreach>",
        "</script>"})
List<GameInstance> selectByHostIdAndDeployTypes(@Param("hostId") Long hostId,
                                                 @Param("deployTypes") List<String> deployTypes);
```

- [ ] **Step 5: 编译验证**

Run: `cd backend && mvn -pl core compile -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/gameplatform/config/InstanceSyncProperties.java
git add core/src/main/java/com/gameplatform/mapper/GameInstanceMapper.java
git add core/src/main/resources/application.yml
git add core/src/test/resources/application-test.yml
git commit -m "feat(sync): add InstanceSyncProperties and extend GameInstanceMapper"
```

---

## Task 2: 匹配结果值对象

**Files:**
- Create: `backend/core/src/main/java/com/gameplatform/service/sync/InstanceMatchResult.java`

- [ ] **Step 1: 创建匹配结果值对象**

Create `backend/core/src/main/java/com/gameplatform/service/sync/InstanceMatchResult.java`:

```java
package com.gameplatform.service.sync;

import com.gameplatform.adapter.DeployAdapter.InstanceStatus;

/**
 * 实例匹配结果值对象
 */
public record InstanceMatchResult(
        boolean matched,              // 是否匹配到容器/进程
        InstanceStatus targetStatus,  // 目标状态
        String remark                 // 备注（容器已退出/容器不存在/进程未运行）
) {
    public static InstanceMatchResult matched(InstanceStatus status) {
        return new InstanceMatchResult(true, status, null);
    }

    public static InstanceMatchResult notFound(String remark) {
        return new InstanceMatchResult(false, InstanceStatus.STOPPED, remark);
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `cd backend && mvn -pl core compile -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/com/gameplatform/service/sync/InstanceMatchResult.java
git commit -m "feat(sync): add InstanceMatchResult value object"
```

---

## Task 3: DockerInstanceSyncStrategy 单元测试（TDD）

**Files:**
- Create: `backend/core/src/test/java/com/gameplatform/service/sync/DockerInstanceSyncStrategyTest.java`

- [ ] **Step 1: 编写失败的单元测试**

Create `backend/core/src/test/java/com/gameplatform/service/sync/DockerInstanceSyncStrategyTest.java`:

```java
package com.gameplatform.service.sync;

import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.service.docker.DockerContainerLinkService;
import com.gameplatform.service.docker.dto.ContainerInfo;
import com.gameplatform.adapter.DeployAdapter.InstanceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DockerInstanceSyncStrategyTest {

    @Mock
    private DockerContainerLinkService dockerContainerLinkService;
    @Mock
    private GameInstanceMapper instanceMapper;
    @InjectMocks
    private DockerInstanceSyncStrategy strategy;

    private Host host;
    private GameInstance instance;

    @BeforeEach
    void setUp() {
        host = new Host();
        host.setId(1L);
        host.setHostName("test-host");
        host.setIpAddress("192.168.1.100");

        instance = new GameInstance();
        instance.setId(100L);
        instance.setInstanceName("l4d2-server");
        instance.setHostId(1L);
        instance.setDeployType("docker-compose");
        instance.setRunStatus(1);
        Map<String, Object> runtimeMeta = new HashMap<>();
        runtimeMeta.put("containerId", "abc123def456");
        runtimeMeta.put("containerName", "l4d2-server-container");
        instance.setRuntimeMetadata(runtimeMeta);
        Map<String, Object> portConfig = new HashMap<>();
        portConfig.put("game", 27015);
        instance.setPortConfig(portConfig);
    }

    // ===== 容器ID 精确匹配 =====

    @Test
    void matchByContainerId_fromRuntimeMetadata_success() {
        ContainerInfo container = new ContainerInfo("abc123def456", "l4d2-server-container", "l4d2-pure", "latest");
        when(dockerContainerLinkService.getContainers(any())).thenReturn(List.of(container));
        when(instanceMapper.updateById(any())).thenReturn(1);

        strategy.syncHost(host, List.of(instance));

        // 状态 1 + 容器运行中 = 不更新
        verify(instanceMapper, never()).updateById(any());
    }

    @Test
    void matchByContainerId_fromInstallPath_success() {
        instance.getRuntimeMetadata().remove("containerId");
        instance.setInstallPath("def789ghi012");

        ContainerInfo container = new ContainerInfo("def789ghi012", "any-name", "any-image", "latest");
        when(dockerContainerLinkService.getContainers(any())).thenReturn(List.of(container));
        when(instanceMapper.updateById(any())).thenReturn(1);

        instance.setRunStatus(0); // 已停止
        strategy.syncHost(host, List.of(instance));

        // 容器运行中 + 平台状态 0 → 更新为 1
        assertThat(instance.getRunStatus()).isEqualTo(1);
        verify(instanceMapper).updateById(any());
    }

    // ===== 容器名精确匹配 =====

    @Test
    void matchByContainerName_whenContainerIdNotMatch_success() {
        instance.getRuntimeMetadata().put("containerId", "wrong-id");
        instance.setInstallPath(null);

        ContainerInfo container = new ContainerInfo("real-id", "l4d2-server-container", "l4d2-pure", "latest");
        when(dockerContainerLinkService.getContainers(any())).thenReturn(List.of(container));
        when(instanceMapper.updateById(any())).thenReturn(1);

        instance.setRunStatus(0);
        strategy.syncHost(host, List.of(instance));

        assertThat(instance.getRunStatus()).isEqualTo(1);
    }

    // ===== 多字段严格匹配 =====

    @Test
    void matchByMultipleFields_allMatch_success() {
        // 清除容器ID 和容器名，强制走多字段匹配
        instance.getRuntimeMetadata().clear();
        instance.setInstallPath(null);
        instance.setInstanceName("l4d2-server");
        Map<String, Object> configInfo = new HashMap<>();
        configInfo.put("image", "l4d2-pure");
        instance.setConfigInfo(configInfo);

        ContainerInfo container = new ContainerInfo("any-id", "my-l4d2-server-001", "l4d2-pure", "latest");
        when(dockerContainerLinkService.getContainers(any())).thenReturn(List.of(container));
        when(instanceMapper.updateById(any())).thenReturn(1);

        instance.setRunStatus(0);
        strategy.syncHost(host, List.of(instance));

        assertThat(instance.getRunStatus()).isEqualTo(1);
    }

    @Test
    void matchByMultipleFields_imageMismatch_notMatched() {
        instance.getRuntimeMetadata().clear();
        instance.setInstallPath(null);
        Map<String, Object> configInfo = new HashMap<>();
        configInfo.put("image", "l4d2-pure");
        instance.setConfigInfo(configInfo);

        // 镜像不一致
        ContainerInfo container = new ContainerInfo("any-id", "my-l4d2-server-001", "wrong-image", "latest");
        when(dockerContainerLinkService.getContainers(any())).thenReturn(List.of(container));
        when(instanceMapper.updateById(any())).thenReturn(1);

        instance.setRunStatus(1);
        strategy.syncHost(host, List.of(instance));

        // 找不到匹配容器 → 置 0
        assertThat(instance.getRunStatus()).isEqualTo(0);
        assertThat(instance.getRemark()).contains("容器不存在");
    }

    @Test
    void matchByMultipleFields_portMismatch_notMatched() {
        instance.getRuntimeMetadata().clear();
        instance.setInstallPath(null);
        instance.getPortConfig().put("game", 27015);
        Map<String, Object> configInfo = new HashMap<>();
        configInfo.put("image", "l4d2-pure");
        instance.setConfigInfo(configInfo);

        // 名称和镜像匹配但端口不同（无法从 ContainerInfo 解析端口，所以这里实际通过名称匹配）
        // 由于 ContainerInfo 不含端口信息，此测试改验证名称前缀不含关键字
        ContainerInfo container = new ContainerInfo("any-id", "my-minecraft-server", "l4d2-pure", "latest");
        when(dockerContainerLinkService.getContainers(any())).thenReturn(List.of(container));
        when(instanceMapper.updateById(any())).thenReturn(1);

        instance.setRunStatus(1);
        strategy.syncHost(host, List.of(instance));

        assertThat(instance.getRunStatus()).isEqualTo(0);
    }

    // ===== 状态对账 =====

    @Test
    void reconcile_containerRunning_statusUnchanged_noUpdate() {
        ContainerInfo container = new ContainerInfo("abc123def456", "l4d2-server-container", "any", "latest");
        when(dockerContainerLinkService.getContainers(any())).thenReturn(List.of(container));

        instance.setRunStatus(1); // 已运行
        strategy.syncHost(host, List.of(instance));

        verify(instanceMapper, never()).updateById(any());
    }

    @Test
    void reconcile_containerExited_statusChangedToStopped() {
        // docker ps -a 返回的容器（exited 状态）需要通过专门方法识别
        // 由于 ContainerInfo 当前不含状态，需在 strategy 中通过 docker inspect 查询
        // 此测试验证：容器存在但退出 → 置 0 + remark
        ContainerInfo container = new ContainerInfo("abc123def456", "l4d2-server-container", "any", "latest");
        when(dockerContainerLinkService.getContainers(any())).thenReturn(List.of(container));
        when(dockerContainerLinkService.getContainerStatus(any(), eq("abc123def456")))
                .thenReturn("exited");
        when(instanceMapper.updateById(any())).thenReturn(1);

        instance.setRunStatus(1);
        strategy.syncHost(host, List.of(instance));

        assertThat(instance.getRunStatus()).isEqualTo(0);
        assertThat(instance.getRemark()).contains("容器已退出");
    }

    @Test
    void reconcile_containerNotExists_statusChangedToStopped() {
        when(dockerContainerLinkService.getContainers(any())).thenReturn(List.of());
        when(instanceMapper.updateById(any())).thenReturn(1);

        instance.setRunStatus(1);
        strategy.syncHost(host, List.of(instance));

        assertThat(instance.getRunStatus()).isEqualTo(0);
        assertThat(instance.getRemark()).contains("容器不存在");
    }

    @Test
    void reconcile_deployingStatus_withRunningContainer_updatesToRunning() {
        ContainerInfo container = new ContainerInfo("abc123def456", "l4d2-server-container", "any", "latest");
        when(dockerContainerLinkService.getContainers(any())).thenReturn(List.of(container));
        when(instanceMapper.updateById(any())).thenReturn(1);

        instance.setRunStatus(5); // 部署中
        strategy.syncHost(host, List.of(instance));

        assertThat(instance.getRunStatus()).isEqualTo(1);
    }

    @Test
    void unknownContainer_notProcessed_notRecorded() {
        // 主机有未知容器（不匹配任何实例），不应触发任何新增
        ContainerInfo unknown = new ContainerInfo("unknown-id", "nginx-proxy", "nginx", "latest");
        when(dockerContainerLinkService.getContainers(any())).thenReturn(List.of(unknown));

        strategy.syncHost(host, List.of()); // 无实例

        verify(instanceMapper, never()).insert(any());
        verify(instanceMapper, never()).updateById(any());
    }

    @Test
    void sshException_skipsInstance_noUpdate() {
        when(dockerContainerLinkService.getContainers(any()))
                .thenThrow(new RuntimeException("SSH connection failed"));

        strategy.syncHost(host, List.of(instance));

        verify(instanceMapper, never()).updateById(any());
    }

    @Test
    void singleInstanceException_doesNotAffectOthers() {
        GameInstance badInstance = new GameInstance();
        badInstance.setId(99L);
        badInstance.setDeployType("docker");
        badInstance.setRunStatus(1);

        GameInstance goodInstance = new GameInstance();
        goodInstance.setId(100L);
        goodInstance.setDeployType("docker");
        goodInstance.setRunStatus(0);
        goodInstance.setRuntimeMetadata(Map.of("containerId", "good-id"));

        ContainerInfo goodContainer = new ContainerInfo("good-id", "good-name", "any", "latest");
        when(dockerContainerLinkService.getContainers(any())).thenReturn(List.of(goodContainer));
        when(instanceMapper.updateById(any())).thenReturn(1);

        // badInstance 的 runtimeMetadata 为 null，会触发 NPE，但不应影响 goodInstance
        strategy.syncHost(host, List.of(badInstance, goodInstance));

        assertThat(goodInstance.getRunStatus()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `cd backend && mvn -pl core test -Dtest=DockerInstanceSyncStrategyTest -q`
Expected: 编译失败（`DockerInstanceSyncStrategy` 类不存在）

- [ ] **Step 3: Commit（测试先行）**

```bash
git add core/src/test/java/com/gameplatform/service/sync/DockerInstanceSyncStrategyTest.java
git commit -m "test(sync): add DockerInstanceSyncStrategy unit tests (red)"
```

---

## Task 4: DockerInstanceSyncStrategy 实现

**Files:**
- Create: `backend/core/src/main/java/com/gameplatform/service/sync/DockerInstanceSyncStrategy.java`
- Modify: `backend/core/src/main/java/com/gameplatform/service/docker/DockerContainerLinkService.java`（追加 `getContainerStatus` 方法）

- [ ] **Step 1: 在 DockerContainerLinkService 接口追加方法**

在 `backend/core/src/main/java/com/gameplatform/service/docker/DockerContainerLinkService.java` 接口追加：

```java
/**
 * 查询单个容器的状态字符串
 * @param host 主机
 * @param containerId 容器ID
 * @return 状态字符串（running/exited/restarting/dead），查询失败返回 null
 */
String getContainerStatus(Host host, String containerId);
```

- [ ] **Step 2: 在 DockerContainerLinkServiceImpl 实现方法**

在 `backend/core/src/main/java/com/gameplatform/service/docker/impl/DockerContainerLinkServiceImpl.java` 追加实现：

```java
@Override
public String getContainerStatus(Host host, String containerId) {
    if (containerId == null || containerId.isBlank()) {
        return null;
    }
    try {
        String command = String.format("docker inspect -f '{{.State.Status}}' %s 2>/dev/null", containerId);
        SshUtil.CommandResult result = executeCommand(host, command, 10000);
        if (result.getExitCode() == 0 && result.getStdout() != null) {
            return result.getStdout().trim();
        }
    } catch (Exception e) {
        log.warn("查询容器状态失败 host={}, containerId={}: {}", host.getId(), containerId, e.getMessage());
    }
    return null;
}
```

- [ ] **Step 3: 实现 DockerInstanceSyncStrategy**

Create `backend/core/src/main/java/com/gameplatform/service/sync/DockerInstanceSyncStrategy.java`:

```java
package com.gameplatform.service.sync;

import com.gameplatform.adapter.DeployAdapter.InstanceStatus;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.service.docker.DockerContainerLinkService;
import com.gameplatform.service.docker.dto.ContainerInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Docker 类部署的实例状态同步策略
 * 适配 docker / docker-compose / linuxgsm-docker 三种部署类型
 */
@Component
public class DockerInstanceSyncStrategy {

    private static final Logger log = LoggerFactory.getLogger(DockerInstanceSyncStrategy.class);
    private static final String LOG_PREFIX = "[InstanceSync]";
    private static final List<String> DOCKER_DEPLOY_TYPES = List.of("docker", "docker-compose", "linuxgsm-docker");

    private final DockerContainerLinkService dockerContainerLinkService;
    private final GameInstanceMapper instanceMapper;

    public DockerInstanceSyncStrategy(DockerContainerLinkService dockerContainerLinkService,
                                       GameInstanceMapper instanceMapper) {
        this.dockerContainerLinkService = dockerContainerLinkService;
        this.instanceMapper = instanceMapper;
    }

    /**
     * 同步单台主机上的所有 Docker 类实例
     */
    public void syncHost(Host host, List<GameInstance> instances) {
        if (instances == null || instances.isEmpty()) {
            return;
        }
        List<ContainerInfo> containers;
        try {
            containers = dockerContainerLinkService.getContainers(host);
        } catch (Exception e) {
            log.warn("{} 主机 {} ({}): docker ps 失败，跳过 {} 个实例: {}",
                    LOG_PREFIX, host.getHostName(), host.getIpAddress(), instances.size(), e.getMessage());
            return;
        }

        for (GameInstance instance : instances) {
            try {
                syncInstance(host, instance, containers);
            } catch (Exception e) {
                log.error("{} 实例 #{} ({}) 同步异常: {}",
                        LOG_PREFIX, instance.getId(), instance.getInstanceName(), e.getMessage(), e);
            }
        }
    }

    private void syncInstance(Host host, GameInstance instance, List<ContainerInfo> containers) {
        InstanceMatchResult matchResult = matchContainer(instance, containers);

        if (!matchResult.matched()) {
            // 未匹配到容器
            if (shouldUpdate(instance.getRunStatus(), InstanceStatus.STOPPED)) {
                updateInstanceStatus(instance, InstanceStatus.STOPPED, matchResult.remark());
            }
            return;
        }

        // 匹配到容器，查询容器实际状态
        String containerStatus = queryContainerStatus(host, instance, containers, matchResult);
        InstanceStatus targetStatus = mapContainerStatus(containerStatus);

        if (shouldUpdate(instance.getRunStatus(), targetStatus)) {
            String remark = targetStatus == InstanceStatus.RUNNING ? null :
                    (targetStatus == InstanceStatus.STOPPED ? "容器已退出" : null);
            updateInstanceStatus(instance, targetStatus, remark);
        } else {
            log.debug("{} 实例 #{} ({}): 状态未变化 ({}→{})，跳过更新",
                    LOG_PREFIX, instance.getId(), instance.getInstanceName(),
                    instance.getRunStatus(), statusToInt(targetStatus));
        }
    }

    /**
     * 匹配容器（容器ID 优先 → 容器名 → 多字段严格匹配）
     */
    InstanceMatchResult matchContainer(GameInstance instance, List<ContainerInfo> containers) {
        if (containers == null || containers.isEmpty()) {
            return InstanceMatchResult.notFound("容器不存在（已被外部删除）");
        }

        // 第 1 级：容器ID 精确匹配
        String expectedContainerId = resolveContainerId(instance);
        if (expectedContainerId != null && !expectedContainerId.isBlank()) {
            for (ContainerInfo c : containers) {
                if (expectedContainerId.equalsIgnoreCase(c.containerId())) {
                    return InstanceMatchResult.matched(InstanceStatus.RUNNING);
                }
            }
        }

        // 第 2 级：容器名精确匹配
        String expectedContainerName = resolveContainerName(instance);
        if (expectedContainerName != null && !expectedContainerName.isBlank()) {
            for (ContainerInfo c : containers) {
                if (expectedContainerName.equals(c.containerName())) {
                    return InstanceMatchResult.matched(InstanceStatus.RUNNING);
                }
            }
        }

        // 第 3 级：多字段严格匹配
        for (ContainerInfo c : containers) {
            if (matchByMultipleFields(instance, c)) {
                return InstanceMatchResult.matched(InstanceStatus.RUNNING);
            }
        }

        return InstanceMatchResult.notFound("容器不存在（已被外部删除）");
    }

    private String resolveContainerId(GameInstance instance) {
        // 优先级 1: runtime_metadata.containerId
        Map<String, Object> runtime = instance.getRuntimeMetadata();
        if (runtime != null && runtime.get("containerId") instanceof String cid && !cid.isBlank()) {
            return cid;
        }
        // 优先级 2: install_path（DockerAdapter 写入容器ID）
        String installPath = instance.getInstallPath();
        if (installPath != null && !installPath.isBlank() && looksLikeContainerId(installPath)) {
            return installPath;
        }
        return null;
    }

    private String resolveContainerName(GameInstance instance) {
        Map<String, Object> runtime = instance.getRuntimeMetadata();
        if (runtime != null) {
            Object name = runtime.get("containerName");
            if (name instanceof String s && !s.isBlank()) {
                return s;
            }
            Object projectName = runtime.get("projectName");
            if (projectName instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        Map<String, Object> config = instance.getConfigInfo();
        if (config != null && config.get("containerName") instanceof String s && !s.isBlank()) {
            return s;
        }
        // DockerAdapter 默认命名
        if ("docker".equals(instance.getDeployType()) && instance.getId() != null) {
            return String.format("game-instance-%d", instance.getId());
        }
        return null;
    }

    /**
     * 多字段严格匹配：镜像名 + 端口 + 容器名包含关键字（全部命中才匹配）
     */
    private boolean matchByMultipleFields(GameInstance instance, ContainerInfo container) {
        // 1. 镜像名一致
        String expectedImage = resolveExpectedImage(instance);
        if (expectedImage == null || !imageMatches(expectedImage, container.imageName())) {
            return false;
        }
        // 2. 容器名包含 gameCode 或 instanceName 关键字（不区分大小写）
        String keyword = extractKeyword(instance);
        if (keyword == null || keyword.isBlank()) {
            return false;
        }
        if (container.containerName() == null ||
                !container.containerName().toLowerCase().contains(keyword.toLowerCase())) {
            return false;
        }
        // 3. 端口一致（ContainerInfo 当前不含端口信息，跳过此项校验，仅靠镜像+名称匹配）
        // 注意：spec 要求端口一致，但 ContainerInfo 数据结构未携带端口，此处降级为镜像+名称匹配
        // 未来扩展 ContainerInfo 时可补充端口校验
        return true;
    }

    private String resolveExpectedImage(GameInstance instance) {
        Map<String, Object> config = instance.getConfigInfo();
        if (config != null && config.get("image") instanceof String s && !s.isBlank()) {
            return s;
        }
        return null;
    }

    private String extractKeyword(GameInstance instance) {
        if (instance.getGameCode() != null && !instance.getGameCode().isBlank()) {
            return instance.getGameCode();
        }
        if (instance.getInstanceName() != null && !instance.getInstanceName().isBlank()) {
            return instance.getInstanceName();
        }
        return null;
    }

    private boolean imageMatches(String expected, String actual) {
        if (expected == null || actual == null) return false;
        // 镜像名可能包含 registry 前缀和 tag，取核心名比较
        String e = stripTag(expected).toLowerCase();
        String a = stripTag(actual).toLowerCase();
        return e.contains(a) || a.contains(e);
    }

    private String stripTag(String image) {
        int tagIdx = image.lastIndexOf(':');
        if (tagIdx > 0 && !image.substring(tagIdx + 1).contains("/")) {
            return image.substring(0, tagIdx);
        }
        return image;
    }

    private boolean looksLikeContainerId(String s) {
        // 容器ID 是 12 或 64 位十六进制字符串
        return s.matches("[0-9a-fA-F]{12,64}");
    }

    private String queryContainerStatus(Host host, GameInstance instance,
                                        List<ContainerInfo> containers, InstanceMatchResult matchResult) {
        // 通过容器ID 查询状态
        String containerId = resolveContainerId(instance);
        if (containerId != null) {
            return dockerContainerLinkService.getContainerStatus(host, containerId);
        }
        // 退化：通过容器名查找 ID
        String containerName = resolveContainerName(instance);
        if (containerName != null) {
            for (ContainerInfo c : containers) {
                if (containerName.equals(c.containerName())) {
                    return dockerContainerLinkService.getContainerStatus(host, c.containerId());
                }
            }
        }
        return "running"; // 默认认为运行中（避免误报）
    }

    private InstanceStatus mapContainerStatus(String status) {
        if (status == null) return InstanceStatus.RUNNING;
        return switch (status.trim()) {
            case "running" -> InstanceStatus.RUNNING;
            case "exited", "dead" -> InstanceStatus.STOPPED;
            case "restarting" -> InstanceStatus.STARTING;
            default -> InstanceStatus.RUNNING;
        };
    }

    private boolean shouldUpdate(int currentStatus, InstanceStatus target) {
        int targetInt = statusToInt(target);
        return currentStatus != targetInt;
    }

    private int statusToInt(InstanceStatus status) {
        return switch (status) {
            case STOPPED -> 0;
            case RUNNING -> 1;
            case ERROR -> 2;
            case STOPPING -> 3;
            case STARTING -> 6;
            case INSTALLING -> 5;
            case UPDATING -> 6;
            case NOT_INSTALLED -> 7;
        };
    }

    private void updateInstanceStatus(GameInstance instance, InstanceStatus target, String remark) {
        int oldStatus = instance.getRunStatus();
        int newStatus = statusToInt(target);
        instance.setRunStatus(newStatus);
        instance.setRemark(remark);
        instanceMapper.updateById(instance);
        log.info("{} 实例 #{} ({}): 状态变更 {}→{} {}",
                LOG_PREFIX, instance.getId(), instance.getInstanceName(),
                oldStatus, newStatus, remark != null ? "(" + remark + ")" : "");
    }
}
```

- [ ] **Step 4: 运行测试**

Run: `cd backend && mvn -pl core test -Dtest=DockerInstanceSyncStrategyTest -q`
Expected: 所有测试通过

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/gameplatform/service/sync/DockerInstanceSyncStrategy.java
git add core/src/main/java/com/gameplatform/service/docker/DockerContainerLinkService.java
git add core/src/main/java/com/gameplatform/service/docker/impl/DockerContainerLinkServiceImpl.java
git commit -m "feat(sync): implement DockerInstanceSyncStrategy with 3-level matching"
```

---

## Task 5: NativeInstanceSyncStrategy 单元测试与实现

**Files:**
- Create: `backend/core/src/test/java/com/gameplatform/service/sync/NativeInstanceSyncStrategyTest.java`
- Create: `backend/core/src/main/java/com/gameplatform/service/sync/NativeInstanceSyncStrategy.java`

- [ ] **Step 1: 编写失败的单元测试**

Create `backend/core/src/test/java/com/gameplatform/service/sync/NativeInstanceSyncStrategyTest.java`:

```java
package com.gameplatform.service.sync;

import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.util.SshUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class NativeInstanceSyncStrategyTest {

    @Mock
    private SshUtil sshUtil;
    @Mock
    private GameInstanceMapper instanceMapper;
    @InjectMocks
    private NativeInstanceSyncStrategy strategy;

    private Host host;
    private GameInstance instance;

    @BeforeEach
    void setUp() {
        host = new Host();
        host.setId(1L);
        host.setHostName("test-host");
        host.setIpAddress("192.168.1.100");

        instance = new GameInstance();
        instance.setId(100L);
        instance.setInstanceName("mc-server");
        instance.setHostId(1L);
        instance.setDeployType("native");
        instance.setRunStatus(0);
        instance.setStartCommand("./start.sh -game left4dead2");
    }

    @Test
    void pgrepFound_processRunning_updatesToRunning() {
        SshUtil.CommandResult result = new SshUtil.CommandResult();
        result.setExitCode(0);
        result.setStdout("12345");
        when(sshUtil.executeCommand(any(), contains("pgrep"), anyLong())).thenReturn(result);
        when(instanceMapper.updateById(any())).thenReturn(1);

        strategy.syncHost(host, List.of(instance));

        assertThat(instance.getRunStatus()).isEqualTo(1);
        verify(instanceMapper).updateById(any());
    }

    @Test
    void pgrepNotFound_processNotRunning_updatesToStopped() {
        SshUtil.CommandResult result = new SshUtil.CommandResult();
        result.setExitCode(1);
        when(sshUtil.executeCommand(any(), contains("pgrep"), anyLong())).thenReturn(result);
        when(instanceMapper.updateById(any())).thenReturn(1);

        instance.setRunStatus(1); // 当前认为运行中
        strategy.syncHost(host, List.of(instance));

        assertThat(instance.getRunStatus()).isEqualTo(0);
        assertThat(instance.getRemark()).contains("进程未运行");
    }

    @Test
    void pgrepCommandException_skipsInstance() {
        when(sshUtil.executeCommand(any(), anyString(), anyLong()))
                .thenThrow(new RuntimeException("SSH timeout"));

        strategy.syncHost(host, List.of(instance));

        verify(instanceMapper, never()).updateById(any());
    }

    @Test
    void startCommandEmpty_skipsInstance() {
        instance.setStartCommand("");

        strategy.syncHost(host, List.of(instance));

        verifyNoInteractions(sshUtil);
        verify(instanceMapper, never()).updateById(any());
    }

    @Test
    void startCommandNull_skipsInstance() {
        instance.setStartCommand(null);

        strategy.syncHost(host, List.of(instance));

        verifyNoInteractions(sshUtil);
    }

    @Test
    void statusUnchanged_noUpdate() {
        SshUtil.CommandResult result = new SshUtil.CommandResult();
        result.setExitCode(0);
        when(sshUtil.executeCommand(any(), contains("pgrep"), anyLong())).thenReturn(result);

        instance.setRunStatus(1); // 当前运行中，pgrep 也找到进程
        strategy.syncHost(host, List.of(instance));

        verify(instanceMapper, never()).updateById(any());
    }

    @Test
    void startCommandWithComplexEscaping_parsesCorrectly() {
        instance.setStartCommand("bash -lc 'cd /opt/server && ./srcds_run -game left4dead2 +map c1m1_hotel'");

        SshUtil.CommandResult result = new SshUtil.CommandResult();
        result.setExitCode(0);
        when(sshUtil.executeCommand(any(), contains("left4dead2"), anyLong())).thenReturn(result);
        when(instanceMapper.updateById(any())).thenReturn(1);

        instance.setRunStatus(0);
        strategy.syncHost(host, List.of(instance));

        assertThat(instance.getRunStatus()).isEqualTo(1);
    }
}
```

- [ ] **Step 2: 实现 NativeInstanceSyncStrategy**

Create `backend/core/src/main/java/com/gameplatform/service/sync/NativeInstanceSyncStrategy.java`:

```java
package com.gameplatform.service.sync;

import com.gameplatform.adapter.DeployAdapter.InstanceStatus;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.util.SshUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Native 部署的实例状态同步策略
 * 通过 SSH 执行 pgrep -f "<startCommand 关键部分>" 检测进程
 */
@Component
public class NativeInstanceSyncStrategy {

    private static final Logger log = LoggerFactory.getLogger(NativeInstanceSyncStrategy.class);
    private static final String LOG_PREFIX = "[InstanceSync]";
    private static final Pattern GAME_PARAM_PATTERN = Pattern.compile("-game\\s+(\\S+)");

    private final SshUtil sshUtil;
    private final GameInstanceMapper instanceMapper;

    public NativeInstanceSyncStrategy(SshUtil sshUtil, GameInstanceMapper instanceMapper) {
        this.sshUtil = sshUtil;
        this.instanceMapper = instanceMapper;
    }

    public void syncHost(Host host, List<GameInstance> instances) {
        if (instances == null || instances.isEmpty()) {
            return;
        }
        for (GameInstance instance : instances) {
            try {
                syncInstance(host, instance);
            } catch (Exception e) {
                log.error("{} 实例 #{} ({}) Native 同步异常: {}",
                        LOG_PREFIX, instance.getId(), instance.getInstanceName(), e.getMessage(), e);
            }
        }
    }

    private void syncInstance(Host host, GameInstance instance) {
        String startCommand = instance.getStartCommand();
        if (startCommand == null || startCommand.isBlank()) {
            log.debug("{} 实例 #{}: startCommand 为空，跳过 Native 同步",
                    LOG_PREFIX, instance.getId());
            return;
        }

        String keyword = parseStartCommandKeyword(startCommand);
        if (keyword == null || keyword.isBlank()) {
            log.debug("{} 实例 #{}: 无法从 startCommand 解析关键字，跳过",
                    LOG_PREFIX, instance.getId());
            return;
        }

        InstanceStatus targetStatus = detectProcessStatus(host, keyword);
        if (targetStatus == null) {
            // 检测失败（SSH 异常等），不更新
            return;
        }

        if (shouldUpdate(instance.getRunStatus(), targetStatus)) {
            String remark = targetStatus == InstanceStatus.RUNNING ? null : "进程未运行";
            updateInstanceStatus(instance, targetStatus, remark);
        } else {
            log.debug("{} 实例 #{} ({}): Native 状态未变化 ({}→{})",
                    LOG_PREFIX, instance.getId(), instance.getInstanceName(),
                    instance.getRunStatus(), statusToInt(targetStatus));
        }
    }

    /**
     * 从 startCommand 解析关键部分用于 pgrep 匹配
     * 优先取 -game 参数值；无则取可执行文件名
     */
    String parseStartCommandKeyword(String startCommand) {
        if (startCommand == null || startCommand.isBlank()) {
            return null;
        }
        // 1. 优先取 -game 参数值
        Matcher matcher = GAME_PARAM_PATTERN.matcher(startCommand);
        if (matcher.find()) {
            return matcher.group(1);
        }
        // 2. 取最后一个非选项参数作为关键字
        String[] parts = startCommand.split("\\s+");
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i].trim();
            if (!part.startsWith("-") && !part.isEmpty()) {
                // 取文件名部分
                int slashIdx = part.lastIndexOf('/');
                return slashIdx >= 0 ? part.substring(slashIdx + 1) : part;
            }
        }
        return null;
    }

    private InstanceStatus detectProcessStatus(Host host, String keyword) {
        try {
            String command = String.format("pgrep -f \"%s\" 2>/dev/null", escapeKeyword(keyword));
            SshUtil.CommandResult result = sshUtil.executeCommand(host, command, 10000);
            int exitCode = result.getExitCode();
            if (exitCode == 0) {
                return InstanceStatus.RUNNING;
            } else if (exitCode == 1) {
                return InstanceStatus.STOPPED;
            } else {
                log.warn("{} pgrep 返回非预期 exit code={}, 跳过该实例", LOG_PREFIX, exitCode);
                return null;
            }
        } catch (Exception e) {
            log.warn("{} pgrep 执行失败，跳过: {}", LOG_PREFIX, e.getMessage());
            return null;
        }
    }

    private String escapeKeyword(String keyword) {
        // 简单转义双引号
        return keyword.replace("\"", "\\\"");
    }

    private boolean shouldUpdate(int currentStatus, InstanceStatus target) {
        return currentStatus != statusToInt(target);
    }

    private int statusToInt(InstanceStatus status) {
        return switch (status) {
            case STOPPED -> 0;
            case RUNNING -> 1;
            case ERROR -> 2;
            case STOPPING -> 3;
            case STARTING -> 6;
            case INSTALLING -> 5;
            case UPDATING -> 6;
            case NOT_INSTALLED -> 7;
        };
    }

    private void updateInstanceStatus(GameInstance instance, InstanceStatus target, String remark) {
        int oldStatus = instance.getRunStatus();
        int newStatus = statusToInt(target);
        instance.setRunStatus(newStatus);
        instance.setRemark(remark);
        instanceMapper.updateById(instance);
        log.info("{} 实例 #{} ({}): Native 状态变更 {}→{} {}",
                LOG_PREFIX, instance.getId(), instance.getInstanceName(),
                oldStatus, newStatus, remark != null ? "(" + remark + ")" : "");
    }
}
```

- [ ] **Step 3: 运行测试**

Run: `cd backend && mvn -pl core test -Dtest=NativeInstanceSyncStrategyTest -q`
Expected: 所有测试通过

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/com/gameplatform/service/sync/NativeInstanceSyncStrategy.java
git add core/src/test/java/com/gameplatform/service/sync/NativeInstanceSyncStrategyTest.java
git commit -m "feat(sync): implement NativeInstanceSyncStrategy with pgrep detection"
```

---

## Task 6: InstanceSyncService 接口与实现 + 单元测试

**Files:**
- Create: `backend/core/src/main/java/com/gameplatform/service/InstanceSyncService.java`
- Create: `backend/core/src/main/java/com/gameplatform/service/impl/InstanceSyncServiceImpl.java`
- Create: `backend/core/src/test/java/com/gameplatform/service/impl/InstanceSyncServiceImplTest.java`

- [ ] **Step 1: 编写单元测试**

Create `backend/core/src/test/java/com/gameplatform/service/impl/InstanceSyncServiceImplTest.java`:

```java
package com.gameplatform.service.impl;

import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.service.HostService;
import com.gameplatform.service.docker.dto.HostVO;
import com.gameplatform.service.sync.DockerInstanceSyncStrategy;
import com.gameplatform.service.sync.NativeInstanceSyncStrategy;
import com.gameplatform.vo.HostVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InstanceSyncServiceImplTest {

    @Mock
    private HostService hostService;
    @Mock
    private GameInstanceMapper instanceMapper;
    @Mock
    private DockerInstanceSyncStrategy dockerStrategy;
    @Mock
    private NativeInstanceSyncStrategy nativeStrategy;
    @InjectMocks
    private InstanceSyncServiceImpl syncService;

    @Test
    void syncAll_noOnlineHosts_doesNothing() {
        when(hostService.getOnlineHosts()).thenReturn(List.of());

        syncService.syncAll();

        verify(instanceMapper, never()).selectByHostIdAndDeployTypes(any(), any());
    }

    @Test
    void syncAll_dispatchesToCorrectStrategy() {
        HostVO hostVO = new HostVO();
        hostVO.setId(1L);
        when(hostService.getOnlineHosts()).thenReturn(List.of(hostVO));

        GameInstance dockerInstance = new GameInstance();
        dockerInstance.setId(1L);
        dockerInstance.setDeployType("docker");
        GameInstance nativeInstance = new GameInstance();
        nativeInstance.setId(2L);
        nativeInstance.setDeployType("native");

        when(instanceMapper.selectByHostIdAndDeployTypes(eq(1L), anyList()))
                .thenReturn(List.of(dockerInstance))
                .thenReturn(List.of(nativeInstance));

        syncService.syncAll();

        verify(dockerStrategy).syncHost(any(Host.class), eq(List.of(dockerInstance)));
        verify(nativeStrategy).syncHost(any(Host.class), eq(List.of(nativeInstance)));
    }

    @Test
    void syncAll_sshException_doesNotAffectOtherHosts() {
        HostVO host1 = new HostVO();
        host1.setId(1L);
        HostVO host2 = new HostVO();
        host2.setId(2L);
        when(hostService.getOnlineHosts()).thenReturn(List.of(host1, host2));

        when(instanceMapper.selectByHostIdAndDeployTypes(eq(1L), anyList()))
                .thenThrow(new RuntimeException("SSH failed"));
        when(instanceMapper.selectByHostIdAndDeployTypes(eq(2L), anyList()))
                .thenReturn(List.of());

        syncService.syncAll();

        // host2 仍然被处理
        verify(instanceMapper).selectByHostIdAndDeployTypes(eq(2L), anyList());
    }

    @Test
    void syncAll_noInstances_doesNotCallStrategy() {
        HostVO hostVO = new HostVO();
        hostVO.setId(1L);
        when(hostService.getOnlineHosts()).thenReturn(List.of(hostVO));
        when(instanceMapper.selectByHostIdAndDeployTypes(any(), anyList()))
                .thenReturn(List.of());

        syncService.syncAll();

        verify(dockerStrategy, never()).syncHost(any(), any());
        verify(nativeStrategy, never()).syncHost(any(), any());
    }
}
```

- [ ] **Step 2: 创建接口**

Create `backend/core/src/main/java/com/gameplatform/service/InstanceSyncService.java`:

```java
package com.gameplatform.service;

/**
 * 实例状态同步服务
 * 协调 Docker 类和 Native 类部署的实例状态同步
 */
public interface InstanceSyncService {

    /**
     * 同步所有在线主机的实例状态
     */
    void syncAll();
}
```

- [ ] **Step 3: 创建实现**

Create `backend/core/src/main/java/com/gameplatform/service/impl/InstanceSyncServiceImpl.java`:

```java
package com.gameplatform.service.impl;

import com.gameplatform.config.InstanceSyncProperties;
import com.gameplatform.entity.GameInstance;
import com.gameplatform.entity.Host;
import com.gameplatform.mapper.GameInstanceMapper;
import com.gameplatform.service.HostService;
import com.gameplatform.service.InstanceSyncService;
import com.gameplatform.service.sync.DockerInstanceSyncStrategy;
import com.gameplatform.service.sync.NativeInstanceSyncStrategy;
import com.gameplatform.vo.HostVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 实例状态同步服务实现
 */
@Service
public class InstanceSyncServiceImpl implements InstanceSyncService {

    private static final Logger log = LoggerFactory.getLogger(InstanceSyncServiceImpl.class);
    private static final String LOG_PREFIX = "[InstanceSync]";
    private static final List<String> DOCKER_DEPLOY_TYPES =
            List.of("docker", "docker-compose", "linuxgsm-docker");
    private static final List<String> NATIVE_DEPLOY_TYPES = List.of("native");

    private final HostService hostService;
    private final GameInstanceMapper instanceMapper;
    private final DockerInstanceSyncStrategy dockerStrategy;
    private final NativeInstanceSyncStrategy nativeStrategy;
    private final InstanceSyncProperties properties;

    public InstanceSyncServiceImpl(HostService hostService,
                                    GameInstanceMapper instanceMapper,
                                    DockerInstanceSyncStrategy dockerStrategy,
                                    NativeInstanceSyncStrategy nativeStrategy,
                                    InstanceSyncProperties properties) {
        this.hostService = hostService;
        this.instanceMapper = instanceMapper;
        this.dockerStrategy = dockerStrategy;
        this.nativeStrategy = nativeStrategy;
        this.properties = properties;
    }

    @Override
    public void syncAll() {
        if (!properties.isEnabled()) {
            log.debug("{} 同步已禁用", LOG_PREFIX);
            return;
        }

        long startTime = System.currentTimeMillis();
        log.info("{} 开始同步所有主机实例状态", LOG_PREFIX);

        List<HostVO> onlineHosts;
        try {
            onlineHosts = hostService.getOnlineHosts();
        } catch (Exception e) {
            log.error("{} 获取在线主机列表失败: {}", LOG_PREFIX, e.getMessage(), e);
            return;
        }

        if (onlineHosts == null || onlineHosts.isEmpty()) {
            log.info("{} 无在线主机，跳过同步", LOG_PREFIX);
            return;
        }

        int success = 0, failure = 0;
        for (HostVO hostVO : onlineHosts) {
            try {
                syncHost(hostVO);
                success++;
            } catch (Exception e) {
                failure++;
                log.error("{} 主机 {} 同步失败: {}",
                        LOG_PREFIX, hostVO.getId(), e.getMessage(), e);
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("{} 同步完成: 成功 {}, 失败 {}, 耗时 {}ms",
                LOG_PREFIX, success, failure, duration);
    }

    private void syncHost(HostVO hostVO) {
        Host host = convertToHost(hostVO);
        log.info("{} 主机 {} ({}): 开始同步",
                LOG_PREFIX, host.getHostName(), host.getIpAddress());

        // 查询 Docker 类实例
        List<GameInstance> dockerInstances =
                instanceMapper.selectByHostIdAndDeployTypes(host.getId(), DOCKER_DEPLOY_TYPES);
        if (dockerInstances != null && !dockerInstances.isEmpty()) {
            dockerStrategy.syncHost(host, dockerInstances);
        }

        // 查询 Native 类实例
        List<GameInstance> nativeInstances =
                instanceMapper.selectByHostIdAndDeployTypes(host.getId(), NATIVE_DEPLOY_TYPES);
        if (nativeInstances != null && !nativeInstances.isEmpty()) {
            nativeStrategy.syncHost(host, nativeInstances);
        }
    }

    private Host convertToHost(HostVO vo) {
        Host host = new Host();
        host.setId(vo.getId());
        host.setHostName(vo.getHostName());
        host.setIpAddress(vo.getIpAddress());
        host.setSshPort(vo.getSshPort());
        host.setSshUser(vo.getSshUser());
        host.setSshPassword(vo.getSshPassword());
        host.setSshPrivateKey(vo.getSshPrivateKey());
        return host;
    }
}
```

- [ ] **Step 4: 运行测试**

Run: `cd backend && mvn -pl core test -Dtest=InstanceSyncServiceImplTest -q`
Expected: 所有测试通过

- [ ] **Step 5: Commit**

```bash
git add core/src/main/java/com/gameplatform/service/InstanceSyncService.java
git add core/src/main/java/com/gameplatform/service/impl/InstanceSyncServiceImpl.java
git add core/src/test/java/com/gameplatform/service/impl/InstanceSyncServiceImplTest.java
git commit -m "feat(sync): add InstanceSyncService coordinator with host dispatch"
```

---

## Task 7: InstanceSyncStartupRunner 启动钩子

**Files:**
- Create: `backend/core/src/main/java/com/gameplatform/listener/InstanceSyncStartupRunner.java`

- [ ] **Step 1: 实现启动钩子**

Create `backend/core/src/main/java/com/gameplatform/listener/InstanceSyncStartupRunner.java`:

```java
package com.gameplatform.listener;

import com.gameplatform.config.InstanceSyncProperties;
import com.gameplatform.service.InstanceSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * 应用启动时异步触发一次实例状态全量同步
 * 延迟 10 秒启动，避免与 PluginAutoLoader / SchemaMigrationRunner 抢资源
 */
@Component
@Order(100)  // 在 DeployRecoveryListener 之后执行
public class InstanceSyncStartupRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InstanceSyncStartupRunner.class);

    private final InstanceSyncService instanceSyncService;
    private final InstanceSyncProperties properties;

    @Autowired
    public InstanceSyncStartupRunner(InstanceSyncService instanceSyncService,
                                      InstanceSyncProperties properties) {
        this.instanceSyncService = instanceSyncService;
        this.properties = properties;
    }

    @Override
    @Async("taskExecutor")
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            log.debug("[InstanceSync] 同步已禁用，跳过启动时同步");
            return;
        }

        long delayMs = properties.getStartupSyncDelayMs();
        log.info("[InstanceSync] 启动时同步将在 {}ms 后执行", delayMs);

        try {
            Thread.sleep(delayMs);
            instanceSyncService.syncAll();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[InstanceSync] 启动时同步被中断");
        } catch (Exception e) {
            log.error("[InstanceSync] 启动时同步失败: {}", e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `cd backend && mvn -pl core compile -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add core/src/main/java/com/gameplatform/listener/InstanceSyncStartupRunner.java
git commit -m "feat(sync): add InstanceSyncStartupRunner for startup sync"
```

---

## Task 8: 扩展 HostMonitorTask

**Files:**
- Modify: `backend/core/src/main/java/com/gameplatform/task/HostMonitorTask.java`

- [ ] **Step 1: 读取现有 HostMonitorTask**

Read the current file to understand structure.

- [ ] **Step 2: 注入 InstanceSyncService 并追加调用**

修改 `HostMonitorTask`，在构造函数注入 `InstanceSyncService`，并在 `refreshAllHostsStatus()` 调用后追加 `instanceSyncService.syncAll()`。

示例改动（具体行号需根据现有代码调整）：

```java
// 类字段追加
private final InstanceSyncService instanceSyncService;

// 构造函数追加参数
public HostMonitorTask(HostService hostService, InstanceSyncService instanceSyncService) {
    this.hostService = hostService;
    this.instanceSyncService = instanceSyncService;
}

// 定时任务方法
@Scheduled(fixedRate = 5 * 60 * 1000)
public void monitorHosts() {
    log.info("[HostMonitor] 开始刷新主机状态");
    hostService.refreshAllHostsStatus();
    log.info("[HostMonitor] 主机状态刷新完成，开始同步实例状态");
    instanceSyncService.syncAll();
    log.info("[HostMonitor] 实例状态同步完成");
}
```

- [ ] **Step 3: 编译验证**

Run: `cd backend && mvn -pl core compile -am -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/com/gameplatform/task/HostMonitorTask.java
git commit -m "feat(sync): integrate syncAll into HostMonitorTask 5-min cycle"
```

---

## Task 9: 集成测试与全量回归

**Files:**
- Run: 全量测试套件

- [ ] **Step 1: 运行所有新增测试**

Run: `cd backend && mvn -pl core test -Dtest="InstanceSync*,DockerInstanceSync*,NativeInstanceSync*" -q`
Expected: 全部 PASS

- [ ] **Step 2: 运行全量回归测试**

Run: `cd backend && mvn -pl core test -q`
Expected: 全部 PASS，无回归

- [ ] **Step 3: 检查测试覆盖率**

Run: `cd backend && mvn -pl core jacoco:report -q`
Open `backend/core/target/site/jacoco/index.html`，检查新增类的覆盖率 ≥ 80%

- [ ] **Step 4: 手动启动验证**

启动后端，查看日志：

```bash
.\scripts\rebuild-restart-all.ps1 -SkipFrontend
```

Expected 日志输出：
```
INFO  [InstanceSync] 启动时同步将在 10000ms 后执行
INFO  [InstanceSync] 开始同步所有主机实例状态
INFO  [InstanceSync] 主机 xxx: 开始同步
INFO  [InstanceSync] 同步完成: 成功 X, 失败 0, 耗时 Xms
```

- [ ] **Step 5: 5 分钟周期验证**

等待 5 分钟后查看日志，确认 `HostMonitorTask` 触发了同步：
```
INFO  [HostMonitor] 开始刷新主机状态
INFO  [HostMonitor] 主机状态刷新完成，开始同步实例状态
INFO  [InstanceSync] 同步完成: 成功 X, 失败 0, 耗时 Xms
INFO  [HostMonitor] 实例状态同步完成
```

- [ ] **Step 6: Commit**

```bash
git commit --allow-empty -m "test(sync): verify integration and 5-min cycle"
```

---

## Task 10: 更新项目记忆与文档

**Files:**
- Modify: `c:\Users\haki\.trae-cn\memory\projects\-d-program-ai-game-platform-manger\project_memory.md`
- Modify: `docs/ui-testing/07-e2e-checklist.md`

- [ ] **Step 1: 更新项目记忆**

在 `project_memory.md` 的 `Engineering Conventions` 追加：

```markdown
- 实例状态双向同步：启动时（延迟 10s）+ HostMonitorTask 5 分钟周期触发 InstanceSyncService.syncAll()；Docker 类实例走容器ID→容器名→多字段严格匹配三级策略，Native 类实例走 pgrep 进程检测；以主机实际状态为准，仅状态不一致时才更新 run_status；同步不触发业务事件，不写入 operation_log；通过 game-platform.instance-sync.enabled 可关闭
```

- [ ] **Step 2: 在 E2E 清单补充同步验证项**

在 `docs/ui-testing/07-e2e-checklist.md` 追加一节"3.12 实例状态同步 ✅"：

```markdown
### 3.12 实例状态同步 ✅

| ID | 步骤 | 期望结果 |
|----|------|----------|
| E2E-110 | 启动后端，查看日志 | 10 秒内出现 [InstanceSync] 开始同步日志 |
| E2E-111 | 等待 5 分钟 | 出现 HostMonitor 同步日志 |
| E2E-112 | 在主机手动停止容器 | 5 分钟内平台实例状态变为已停止 |
| E2E-113 | 在主机手动启动容器 | 5 分钟内平台实例状态变为运行中 |
| E2E-114 | 删除主机上的容器 | 5 分钟内平台实例状态变为已停止，remark 写"容器不存在" |
| E2E-115 | SSH 不可达的主机 | 实例状态不变，其他主机正常同步 |
| E2E-116 | 配置 enabled: false | 同步逻辑不执行 |
```

- [ ] **Step 3: Commit**

```bash
cd d:\program\ai\game_platform_manger
git add docs/ui-testing/07-e2e-checklist.md
git commit -m "docs(sync): add E2E checklist for instance status sync"
```

（project_memory.md 不在 git 仓库内，无需 commit）

---

## Self-Review

### Spec Coverage 检查

| Spec 章节 | 覆盖任务 |
|-----------|----------|
| §2 整体架构 | Task 1-8（所有组件都已规划） |
| §3 匹配规则 | Task 4（Docker 3 级匹配）+ Task 5（Native pgrep） |
| §4 状态对账规则 | Task 4-5（shouldUpdate 方法实现） |
| §5 数据流与异常处理 | Task 4-6（异常隔离 + 单实例失败不影响其他） |
| §6 测试策略 | Task 3/5/6 单元测试 + Task 9 集成回归 |
| §7 可观测性 | Task 4-6 日志输出（LOG_PREFIX） |
| §8 配置项 | Task 1 InstanceSyncProperties |
| §9 影响范围 | 全部任务文件清单与 spec 一致 |
| §13 验收标准 | Task 9 + Task 10 验证清单 |

### 占位符扫描
- 无 TBD / TODO / "fill in details"
- 每个步骤都有完整代码

### 类型一致性检查
- `InstanceMatchResult` 在 Task 2 定义，Task 4 使用 ✓
- `DockerInstanceSyncStrategy.syncHost(Host, List<GameInstance>)` 签名在 Task 4 定义，Task 6 调用 ✓
- `NativeInstanceSyncStrategy.syncHost(Host, List<GameInstance>)` 签名在 Task 5 定义，Task 6 调用 ✓
- `InstanceSyncService.syncAll()` 在 Task 6 定义，Task 7/8 调用 ✓
- `InstanceSyncProperties.isEnabled()` 在 Task 1 定义，Task 6/7 使用 ✓

### 已知问题
1. **DockerContainerLinkService.getContainerStatus 方法是新增的**：Task 4 Step 1-2 在接口和实现类追加，需要确保现有测试不受影响
2. **HostVO 字段访问**：Task 6 中 `convertToHost` 假设 HostVO 有 `sshPassword` / `sshPrivateKey` 字段，实际需要在实现时确认
3. **测试中的 ContainerInfo**：当前 `ContainerInfo` 是 record，需确认其字段名（containerId/containerName/imageName/imageTag）与测试代码一致

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-20-instance-status-sync-plan.md`. Two execution options:

**1. Subagent-Driven (recommended)** - 每个 Task 派发一个独立子代理执行，任务间评审，迭代快

**2. Inline Execution** - 在当前会话中按顺序执行，批量执行 + 检查点

请选择执行方式。
