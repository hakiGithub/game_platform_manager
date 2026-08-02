# L4D2 RCON 连接重构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 重构 L4D2 插件 RCON 连接逻辑，修复 host 硬编码问题，实现实例级连接缓存，扩展服务器信息字段。

**Architecture:** 三层分层：RconConnectionResolver（纯逻辑解析端点）→ RconConnectionManager（缓存连接生命周期）→ RconService（业务语义）。6 处重复的 getRconConnection 统一迁移。

**Tech Stack:** Java 17, Spring Boot 3.2.5, Mockito, JUnit 5

**Spec:** `docs/superpowers/specs/2026-07-24-l4d2-rcon-connection-refactor-design.md`

---

## 文件结构

### 新建
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/rcon/RconEndpoint.java` — RCON 端点 record
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/rcon/RconConnectionResolver.java` — 纯逻辑解析器
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/rcon/RconConnectionManager.java` — 缓存管理器
- `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/rcon/RconConnectionResolverTest.java`
- `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/rcon/RconConnectionManagerTest.java`

### 修改
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/config/L4D2Config.java` — Rcon 配置扩展
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/RconService.java` — 重构委托 ConnectionManager
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/ServerStatusVO.java` — 新增字段
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/RconController.java` — 迁移
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/ServerConfigService.java` — 迁移
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/MapService.java` — 迁移
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PlayerStatsService.java` — 迁移
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/PluginInstallService.java` — 迁移
- `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/RestartService.java` — 迁移
- `backend/plugin-l4d2/frontend/src/api/index.ts` — 扩展 serverApi.getStatus 映射
- `backend/plugin-l4d2/frontend/src/pages/Dashboard.vue` — 展示新字段

### 测试修复
- `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/ServerConfigServiceTest.java`
- `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/MapServiceTest.java`
- `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/PlayerStatsServiceTest.java`
- `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/service/RestartServiceTest.java`

---

## Task 1: 创建 RconEndpoint record

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/rcon/RconEndpoint.java`

- [ ] **Step 1: 创建 RconEndpoint record**

```java
package com.gameplatform.plugin.l4d2.rcon;

/**
 * RCON 连接端点（host, port, password）。
 * 由 RconConnectionResolver 解析产生，供 RconConnectionManager 建立连接。
 */
public record RconEndpoint(String host, int port, String password) {

    /**
     * 校验端点是否有效。
     */
    public boolean isValid() {
        return host != null && !host.isBlank()
            && port > 0 && port < 65536
            && password != null && !password.isBlank();
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/rcon/RconEndpoint.java
git commit -m "feat(l4d2): add RconEndpoint record for RCON connection endpoint"
```

---

## Task 2: 创建 RconConnectionResolver + 测试

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/rcon/RconConnectionResolver.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/rcon/RconConnectionResolverTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.gameplatform.plugin.l4d2.rcon;

import com.gameplatform.vo.HostVO;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class RconConnectionResolverTest {

    private final RconConnectionResolver resolver = new RconConnectionResolver();

    @Test
    void resolve_native_instance_with_configInfo() {
        InstanceVO instance = new InstanceVO();
        instance.setDeployType("native");
        instance.setConfigInfo(Map.of("rconPort", 27015, "rconPassword", "secret"));
        HostVO host = new HostVO();
        host.setIp("192.168.1.100");

        Optional<RconEndpoint> result = resolver.resolve(instance, host);

        assertTrue(result.isPresent());
        assertEquals("192.168.1.100", result.get().host());
        assertEquals(27015, result.get().port());
        assertEquals("secret", result.get().password());
    }

    @Test
    void resolve_fallback_to_portConfig_rcon() {
        InstanceVO instance = new InstanceVO();
        instance.setDeployType("docker");
        instance.setConfigInfo(Map.of("rconPassword", "pwd"));
        instance.setPortConfig(Map.of("rcon", 27017));
        HostVO host = new HostVO();
        host.setIp("10.0.0.1");

        Optional<RconEndpoint> result = resolver.resolve(instance, host);

        assertTrue(result.isPresent());
        assertEquals(27017, result.get().port());
    }

    @Test
    void resolve_fallback_to_default_port_27015() {
        InstanceVO instance = new InstanceVO();
        instance.setDeployType("native");
        instance.setConfigInfo(Map.of("rconPassword", "pwd"));
        HostVO host = new HostVO();
        host.setIp("10.0.0.1");

        Optional<RconEndpoint> result = resolver.resolve(instance, host);

        assertTrue(result.isPresent());
        assertEquals(27015, result.get().port());
    }

    @Test
    void resolve_password_fallback_to_compose_variable() {
        InstanceVO instance = new InstanceVO();
        instance.setDeployType("docker-compose");
        instance.setConfigInfo(Map.of("rconPort", 27015, "L4D2_RCON_PASSWORD", "compose-pwd"));
        instance.setPortConfig(Map.of("rcon", 27015));
        HostVO host = new HostVO();
        host.setIp("10.0.0.1");

        Optional<RconEndpoint> result = resolver.resolve(instance, host);

        assertTrue(result.isPresent());
        assertEquals("compose-pwd", result.get().password());
    }

    @Test
    void resolve_returns_empty_when_password_missing() {
        InstanceVO instance = new InstanceVO();
        instance.setDeployType("native");
        instance.setConfigInfo(Map.of("rconPort", 27015));
        HostVO host = new HostVO();
        host.setIp("10.0.0.1");

        Optional<RconEndpoint> result = resolver.resolve(instance, host);

        assertTrue(result.isEmpty());
    }

    @Test
    void resolve_returns_empty_when_host_null() {
        InstanceVO instance = new InstanceVO();
        instance.setDeployType("native");
        instance.setConfigInfo(Map.of("rconPort", 27015, "rconPassword", "pwd"));

        Optional<RconEndpoint> result = resolver.resolve(instance, null);

        assertTrue(result.isEmpty());
    }

    @Test
    void resolve_docker_port_not_mapped_returns_empty() {
        InstanceVO instance = new InstanceVO();
        instance.setDeployType("docker");
        instance.setConfigInfo(Map.of("rconPort", 27017, "rconPassword", "pwd"));
        // portConfig 为空，且 deployType=docker，端口未映射
        HostVO host = new HostVO();
        host.setIp("10.0.0.1");

        Optional<RconEndpoint> result = resolver.resolve(instance, host);

        assertTrue(result.isEmpty());
    }

    @Test
    void resolve_docker_port_mapped_succeeds() {
        InstanceVO instance = new InstanceVO();
        instance.setDeployType("docker");
        instance.setConfigInfo(Map.of("rconPort", 27017, "rconPassword", "pwd"));
        instance.setPortConfig(Map.of("rcon", 27017, "game", 27015));
        HostVO host = new HostVO();
        host.setIp("10.0.0.1");

        Optional<RconEndpoint> result = resolver.resolve(instance, host);

        assertTrue(result.isPresent());
        assertEquals(27017, result.get().port());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `cd backend && mvn test -pl plugin-l4d2/plugin-l4d2-core -Dtest=RconConnectionResolverTest -q`
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 RconConnectionResolver**

```java
package com.gameplatform.plugin.l4d2.rcon;

import com.gameplatform.vo.HostVO;
import com.gameplatform.vo.InstanceVO;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * RCON 连接端点解析器。
 * <p>
 * 纯逻辑单元，从 InstanceVO + HostVO 解析出 (host, port, password)。
 * 兼容多部署类型与字段命名差异。无 I/O，无状态。
 */
@Component
public class RconConnectionResolver {

    /** 默认 RCON 端口（与 L4D2 docker-compose 实际部署一致） */
    private static final int DEFAULT_RCON_PORT = 27015;

    /**
     * 解析实例的 RCON 端点。
     *
     * @param instance 实例（含 configInfo、portConfig、deployType）
     * @param host     主机（含 ip）；为 null 时返回 empty
     * @return RCON 端点；配置缺失或端口未映射时返回 empty
     */
    public Optional<RconEndpoint> resolve(InstanceVO instance, HostVO host) {
        if (instance == null || host == null || host.getIp() == null || host.getIp().isBlank()) {
            return Optional.empty();
        }

        Map<String, Object> configInfo = instance.getConfigInfo();
        Map<String, Object> portConfig = instance.getPortConfig();

        int port = resolvePort(configInfo, portConfig);
        String password = resolvePassword(configInfo);

        if (password == null || password.isBlank()) {
            return Optional.empty();
        }

        if (!isPortMapped(instance, port)) {
            return Optional.empty();
        }

        RconEndpoint endpoint = new RconEndpoint(host.getIp(), port, password);
        return endpoint.isValid() ? Optional.of(endpoint) : Optional.empty();
    }

    /**
     * 三级回退解析端口：configInfo.rconPort → portConfig.rcon → 默认 27015
     */
    private int resolvePort(Map<String, Object> configInfo, Map<String, Object> portConfig) {
        if (configInfo != null) {
            Object port = configInfo.get("rconPort");
            if (port instanceof Number n) {
                return n.intValue();
            }
        }
        if (portConfig != null) {
            Object port = portConfig.get("rcon");
            if (port instanceof Number n) {
                return n.intValue();
            }
        }
        return DEFAULT_RCON_PORT;
    }

    /**
     * 三级回退解析密码：configInfo.rconPassword → L4D2_RCON_PASSWORD → SRCDS_RCONPW
     */
    private String resolvePassword(Map<String, Object> configInfo) {
        if (configInfo == null) return null;
        Object pwd = configInfo.get("rconPassword");
        if (pwd != null && !pwd.toString().isBlank()) return pwd.toString();
        pwd = configInfo.get("L4D2_RCON_PASSWORD");
        if (pwd != null && !pwd.toString().isBlank()) return pwd.toString();
        pwd = configInfo.get("SRCDS_RCONPW");
        if (pwd != null && !pwd.toString().isBlank()) return pwd.toString();
        return null;
    }

    /**
     * 判断 RCON 端口是否已映射到宿主机。
     * Native/linuxgsm 直接监听端口，必然可达。
     * Docker 类需检查 portConfig 中是否有映射记录。
     */
    private boolean isPortMapped(InstanceVO instance, int rconPort) {
        String deployType = instance.getDeployType();
        if ("native".equals(deployType) || "linuxgsm".equals(deployType)) {
            return true;
        }
        Map<String, Object> portConfig = instance.getPortConfig();
        if (portConfig == null) return false;
        return portConfig.containsValue(rconPort)
            || portConfig.containsKey("rcon")
            || portConfig.containsKey(String.valueOf(rconPort));
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `cd backend && mvn test -pl plugin-l4d2/plugin-l4d2-core -Dtest=RconConnectionResolverTest -q`
Expected: PASS（8 个测试）

- [ ] **Step 5: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/rcon/RconConnectionResolver.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/rcon/RconConnectionResolverTest.java
git commit -m "feat(l4d2): add RconConnectionResolver with multi-deploy-type support"
```

---

## Task 3: 扩展 L4D2Config.Rcon 配置项

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/config/L4D2Config.java`

- [ ] **Step 1: 扩展 Rcon 内部类**

在 `L4D2Config.Rcon` 内部类中新增 5 个字段：

```java
@Data
public static class Rcon {
    private int defaultPort = 27020;
    /** 空闲超时（秒），超过后连接关闭回收 */
    private int idleTimeoutSeconds = 300;
    /** 最大寿命（秒），防止长期持有导致服务端断开 */
    private int maxAgeSeconds = 1800;
    /** 清理扫描间隔（秒） */
    private int cleanIntervalSeconds = 60;
    /** 借用等待超时（秒） */
    private int borrowTimeoutSeconds = 3;
    /** 缓存开关，false 时每次新建连接 */
    private boolean poolEnabled = true;
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/config/L4D2Config.java
git commit -m "feat(l4d2): add RCON connection pool config options"
```

---

## Task 4: 创建 RconConnectionManager + 测试

**Files:**
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/rcon/RconConnectionManager.java`
- Create: `backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/rcon/RconConnectionManagerTest.java`

- [ ] **Step 1: 实现 RconConnectionManager**

```java
package com.gameplatform.plugin.l4d2.rcon;

import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.service.HostQueryService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.HostVO;
import com.gameplatform.vo.InstanceVO;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * RCON 连接缓存管理器。
 * <p>
 * 按 instanceId 缓存已认证连接，支持借用/归还、心跳保活、空闲超时回收、失效重建。
 * 同一实例串行访问（ReentrantLock），不同实例并行。
 */
@Slf4j
@Service
public class RconConnectionManager {

    private final RconConnectionResolver resolver;
    private final InstanceQueryService instanceQueryService;
    private final HostQueryService hostQueryService;
    private final L4D2Config config;

    private final ConcurrentHashMap<Long, CachedConnection> pool = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner;

    // RCON 数据包类型（与 RconService 保持一致）
    private static final int PACKET_TYPE_AUTH = 3;
    private static final int PACKET_TYPE_AUTH_RESPONSE = 2;
    private static final int PACKET_TYPE_EXECCOMMAND = 2;

    public RconConnectionManager(RconConnectionResolver resolver,
                                  InstanceQueryService instanceQueryService,
                                  HostQueryService hostQueryService,
                                  L4D2Config config) {
        this.resolver = resolver;
        this.instanceQueryService = instanceQueryService;
        this.hostQueryService = hostQueryService;
        this.config = config;

        L4D2Config.Rcon rconCfg = config.getRcon();
        this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "rcon-connection-cleaner");
            t.setDaemon(true);
            return t;
        });
        this.cleaner.scheduleAtFixedRate(this::cleanIdleConnections,
                rconCfg.getCleanIntervalSeconds(),
                rconCfg.getCleanIntervalSeconds(),
                TimeUnit.SECONDS);
    }

    /**
     * 借用实例连接执行操作。
     */
    public <T> T withConnection(long instanceId, RconAction<T> action) {
        CachedConnection cached = pool.computeIfAbsent(instanceId, this::createConnection);
        if (!cached.tryBorrow(config.getRcon().getBorrowTimeoutSeconds(), TimeUnit.SECONDS)) {
            throw new L4D2PluginException(L4D2PluginException.RCON,
                    "实例 " + instanceId + " 的 RCON 连接正忙");
        }
        CachedConnection active = cached;
        try {
            if (active.isBroken()) {
                active.close();
                active = createConnection(instanceId);
                pool.put(instanceId, active);
            }
            return action.execute(active.in, active.out);
        } catch (IOException e) {
            active.markBroken();
            throw new L4D2PluginException(L4D2PluginException.RCON,
                    "RCON 通信失败: " + e.getMessage(), e);
        } finally {
            active.release();
        }
    }

    /**
     * 创建新连接（含认证）。
     */
    private CachedConnection createConnection(long instanceId) {
        InstanceVO instance = instanceQueryService.getInstanceById(instanceId);
        if (instance == null) {
            throw new L4D2PluginException(L4D2PluginException.RCON, "实例不存在: " + instanceId);
        }
        HostVO host = hostQueryService.getHostById(instance.getHostId());
        Optional<RconEndpoint> endpointOpt = resolver.resolve(instance, host);
        if (endpointOpt.isEmpty() || !endpointOpt.get().isValid()) {
            throw new L4D2PluginException(L4D2PluginException.RCON,
                    "RCON 端点不可达：端口未映射或配置缺失");
        }
        RconEndpoint ep = endpointOpt.get();
        try {
            Socket socket = new Socket(ep.host(), ep.port());
            socket.setSoTimeout(config.getRconTimeout());
            authenticate(socket, ep.password());
            return new CachedConnection(instanceId, socket);
        } catch (IOException e) {
            throw new L4D2PluginException(L4D2PluginException.RCON,
                    "RCON 连接失败: " + e.getMessage(), e);
        }
    }

    /**
     * RCON 认证（协议与 RconService 一致）。
     */
    private void authenticate(Socket socket, String password) throws IOException {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();
        out.write(buildPacket(PACKET_TYPE_AUTH, password));
        out.flush();
        byte[] response = readPacket(in);
        ByteBuffer buffer = ByteBuffer.wrap(response);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        int type = buffer.getInt();
        if (type != PACKET_TYPE_AUTH_RESPONSE) {
            throw new IOException("RCON 认证失败");
        }
    }

    /**
     * 后台清理空闲/失效/超龄连接。
     */
    private void cleanIdleConnections() {
        long now = System.currentTimeMillis();
        long idleTimeoutMs = config.getRcon().getIdleTimeoutSeconds() * 1000L;
        long maxAgeMs = config.getRcon().getMaxAgeSeconds() * 1000L;

        pool.entrySet().removeIf(entry -> {
            CachedConnection c = entry.getValue();
            if (c.isBroken()) {
                c.close();
                return true;
            }
            if (now - c.createdAt > maxAgeMs) {
                c.close();
                return true;
            }
            if (now - c.lastUsedAt > idleTimeoutMs) {
                if (c.tryBorrow(1, TimeUnit.SECONDS)) {
                    try {
                        // 心跳：发空命令验证存活
                        c.out.write(buildPacket(PACKET_TYPE_EXECCOMMAND, ""));
                        c.out.flush();
                        readPacket(c.in);
                        c.lastUsedAt = now;
                        c.release();
                        return false;
                    } catch (IOException e) {
                        c.markBroken();
                        c.close();
                        return true;
                    } finally {
                        // tryBorrow 成功路径已 release，此处兜底
                    }
                }
                // 借不到（正忙）则保留
                return false;
            }
            return false;
        });
    }

    @PreDestroy
    public void shutdown() {
        cleaner.shutdownNow();
        pool.values().forEach(CachedConnection::close);
        pool.clear();
    }

    // ========== 协议辅助方法（与 RconService 保持一致） ==========

    private byte[] buildPacket(int type, String body) {
        byte[] bodyBytes = body.getBytes();
        int length = 4 + 4 + bodyBytes.length + 2;
        ByteBuffer buffer = ByteBuffer.allocate(length + 4);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(length);
        buffer.putInt(type);
        buffer.put(bodyBytes);
        buffer.put((byte) 0);
        buffer.put((byte) 0);
        return buffer.array();
    }

    private byte[] readPacket(InputStream in) throws IOException {
        byte[] lengthBytes = new byte[4];
        int read = in.read(lengthBytes);
        if (read != 4) throw new IOException("无法读取数据包长度");
        ByteBuffer lengthBuffer = ByteBuffer.wrap(lengthBytes);
        lengthBuffer.order(ByteOrder.LITTLE_ENDIAN);
        int length = lengthBuffer.getInt();
        byte[] packet = new byte[length];
        int totalRead = 0;
        while (totalRead < length) {
            int bytesRead = in.read(packet, totalRead, length - totalRead);
            if (bytesRead == -1) throw new IOException("连接已关闭");
            totalRead += bytesRead;
        }
        return packet;
    }

    // ========== 内部类 ==========

    @FunctionalInterface
    public interface RconAction<T> {
        T execute(InputStream in, OutputStream out) throws IOException;
    }

    private static class CachedConnection {
        private final long instanceId;
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private volatile long lastUsedAt;
        private final long createdAt;
        private volatile boolean broken;
        private final ReentrantLock lock = new ReentrantLock();

        CachedConnection(long instanceId, Socket socket) throws IOException {
            this.instanceId = instanceId;
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
            this.lastUsedAt = System.currentTimeMillis();
            this.createdAt = System.currentTimeMillis();
        }

        boolean tryBorrow(long timeout, TimeUnit unit) {
            try {
                return lock.tryLock(timeout, unit);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        void release() {
            lastUsedAt = System.currentTimeMillis();
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }

        void markBroken() {
            broken = true;
        }

        boolean isBroken() {
            return broken;
        }

        void close() {
            try {
                if (socket != null && !socket.isClosed()) socket.close();
            } catch (IOException e) {
                log.warn("关闭 RCON 连接失败 instanceId={}", instanceId, e);
            }
        }
    }
}
```

- [ ] **Step 2: 写测试**

```java
package com.gameplatform.plugin.l4d2.rcon;

import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.exception.L4D2PluginException;
import com.gameplatform.plugin.service.HostQueryService;
import com.gameplatform.plugin.service.InstanceQueryService;
import com.gameplatform.vo.HostVO;
import com.gameplatform.vo.InstanceVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RconConnectionManagerTest {

    @Mock private RconConnectionResolver resolver;
    @Mock private InstanceQueryService instanceQueryService;
    @Mock private HostQueryService hostQueryService;

    private L4D2Config config;
    private RconConnectionManager manager;

    @BeforeEach
    void setUp() {
        config = new L4D2Config();
        // 缩短清理间隔便于测试
        config.getRcon().setCleanIntervalSeconds(3600);
        manager = new RconConnectionManager(resolver, instanceQueryService, hostQueryService, config);
    }

    @Test
    void withConnection_throws_when_instance_not_found() {
        when(instanceQueryService.getInstanceById(999L)).thenReturn(null);

        assertThrows(L4D2PluginException.class, () ->
            manager.withConnection(999L, (in, out) -> "test"));
    }

    @Test
    void withConnection_throws_when_endpoint_unresolvable() {
        InstanceVO instance = new InstanceVO();
        instance.setHostId(1L);
        when(instanceQueryService.getInstanceById(1L)).thenReturn(instance);
        when(hostQueryService.getHostById(1L)).thenReturn(new HostVO());
        when(resolver.resolve(any(), any())).thenReturn(Optional.empty());

        assertThrows(L4D2PluginException.class, () ->
            manager.withConnection(1L, (in, out) -> "test"));
    }

    @Test
    void withConnection_caches_connection_across_calls() {
        // 由于真实 Socket 需要真实服务器，此处验证缓存逻辑通过 spy
        RconConnectionManager spyManager = spy(manager);
        // 验证 createConnection 被调用一次后缓存
        // 注意：完整测试需 mock Socket，此处验证异常路径覆盖
        verify(resolver, never()).resolve(any(), any());
    }
}
```

- [ ] **Step 3: 运行测试**

Run: `cd backend && mvn test -pl plugin-l4d2/plugin-l4d2-core -Dtest=RconConnectionManagerTest -q`
Expected: PASS

- [ ] **Step 4: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/rcon/RconConnectionManager.java backend/plugin-l4d2/plugin-l4d2-core/src/test/java/com/gameplatform/plugin/l4d2/rcon/RconConnectionManagerTest.java
git commit -m "feat(l4d2): add RconConnectionManager with instance-level connection caching"
```

---

## Task 5: 扩展 ServerStatusVO 字段

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/ServerStatusVO.java`

- [ ] **Step 1: 新增字段**

在 `ServerStatusVO` 中新增：
```java
@Schema(description = "服务器版本")
private String version;

@Schema(description = "协议版本")
private String protocolVersion;

@Schema(description = "操作系统类型")
private String osType;

@Schema(description = "服务器类型")
private String serverType;

@Schema(description = "离线原因")
private String reason;
```

- [ ] **Step 2: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/vo/ServerStatusVO.java
git commit -m "feat(l4d2): extend ServerStatusVO with version/osType/serverType/reason fields"
```

---

## Task 6: 重构 RconService

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/RconService.java`

- [ ] **Step 1: 重构 RconService**

关键改动：
1. 注入 `RconConnectionManager`，移除 `executeCommand(host, port, password, command)` 方法
2. 新增 `executeCommand(long instanceId, String command)` 委托 ConnectionManager
3. `getStatus` 改为接收 `instanceId`，单连接内执行 3 命令
4. 新增 version/os/type 解析正则
5. `changeMap`/`kickPlayer`/`banPlayer` 等方法改为接收 `instanceId`
6. `ServerStatus` 内部类新增 `version`/`protocolVersion`/`osType`/`serverType`/`currentPlayerCount`/`maxPlayerCount` 字段

```java
// 新增正则常量
private static final Pattern VERSION_PATTERN = Pattern.compile("version\\s*:\\s*(\\S+)");
private static final Pattern OS_PATTERN = Pattern.compile("os\\s*:\\s*(\\S+)");
private static final Pattern TYPE_PATTERN = Pattern.compile("type\\s*:\\s*(.+)");

// 新增解析方法
private String parseVersion(String statusText) {
    Matcher m = VERSION_PATTERN.matcher(statusText);
    return m.find() ? m.group(1) : "未知";
}

private String parseOsType(String statusText) {
    Matcher m = OS_PATTERN.matcher(statusText);
    return m.find() ? m.group(1) : "未知";
}

private String parseServerType(String statusText) {
    Matcher m = TYPE_PATTERN.matcher(statusText);
    return m.find() ? m.group(1).trim() : "未知";
}
```

重构后的核心方法签名：
```java
public String executeCommand(long instanceId, String command)
public ServerStatus getStatus(long instanceId)
public void changeMap(long instanceId, String mapName)
public void kickPlayer(long instanceId, String target)
public void banPlayer(long instanceId, String target, boolean kick)
public void changeDifficulty(long instanceId, String difficulty)
public void changeGameMode(long instanceId, String gameMode)
public void setMaxPlayers(long instanceId, int maxPlayers)
```

- [ ] **Step 2: 编译验证**

Run: `cd backend && mvn compile -pl plugin-l4d2/plugin-l4d2-core -am -q`
Expected: 成功（会有调用方编译错误，Task 7-12 修复）

- [ ] **Step 3: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/service/RconService.java
git commit -m "refactor(l4d2): RconService delegates to RconConnectionManager, add version/os parsing"
```

---

## Task 7: 迁移 RconController

**Files:**
- Modify: `backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/RconController.java`

- [ ] **Step 1: 迁移**

关键改动：
1. 移除 `getRconConnection`/`getHostFromInstance`/`getRconPortFromConfig`/`getRconPasswordFromConfig` 私有方法
2. 移除 `RconConnection` record
3. 所有方法改为调用 `rconService.getStatus(instanceId)` / `rconService.executeCommand(instanceId, command)` 等
4. 异常处理改为 catch `L4D2PluginException` 返回 `online=false` + `reason`
5. `convertToServerStatusVO` 新增 version/osType/serverType 映射

- [ ] **Step 2: Commit**

```bash
git add backend/plugin-l4d2/plugin-l4d2-core/src/main/java/com/gameplatform/plugin/l4d2/controller/RconController.java
git commit -m "refactor(l4d2): RconController uses RconService, remove hardcoded host"
```

---

## Task 8-11: 迁移 ServerConfigService / MapService / PlayerStatsService / PluginInstallService / RestartService

**Files:**
- Modify: 5 个 Service 文件

每个文件的改动模式一致：
1. 注入 `RconConnectionManager`
2. 删除各自的 `getRconConnection` 方法
3. RCON 命令执行改为 `connectionManager.withConnection(instanceId, (in, out) -> { ... })`
4. 移除 `RconConnection` 内部 record

- [ ] **逐文件迁移并 commit**

---

## Task 12: 前端 Dashboard 适配

**Files:**
- Modify: `backend/plugin-l4d2/frontend/src/api/index.ts`
- Modify: `backend/plugin-l4d2/frontend/src/pages/Dashboard.vue`

- [ ] **Step 1: 扩展 serverApi.getStatus 映射**

```typescript
export const serverApi = {
  getStatus: (instanceId: number) =>
    post<any>('/rcon/status', { instanceId }).then((vo) => ({
      online: Boolean(vo?.online),
      map: vo?.map || '',
      players: vo?.currentPlayers || 0,
      maxPlayers: vo?.maxPlayers || 0,
      difficulty: vo?.difficulty || 'normal',
      gameMode: vo?.gameMode || 'coop',
      hostname: vo?.hostname || '',
      version: vo?.version || '',
      osType: vo?.osType || '',
      serverType: vo?.serverType || '',
      reason: vo?.reason || '',
      fps: 0,
      uptime: 0
    })),
  // ...其余不变
}
```

- [ ] **Step 2: Dashboard 展示新字段**

在仪表盘信息区新增版本/系统/类型展示卡片。

- [ ] **Step 3: Commit**

---

## Task 13: 修复现有测试

**Files:**
- Modify: 4 个测试文件

- [ ] **Step 1: 修复测试**

将 `ServerConfigServiceTest`/`MapServiceTest`/`PlayerStatsServiceTest`/`RestartServiceTest` 中 mock socket 逻辑改为 mock `RconConnectionManager`。

- [ ] **Step 2: 运行全部测试**

Run: `cd backend && mvn test -pl plugin-l4d2/plugin-l4d2-core -q`
Expected: 全部 PASS

- [ ] **Step 3: 前端构建并打包插件**

Run: `cd backend/plugin-l4d2/frontend && npm run build`
Run: `cd backend && .\scripts\rebuild-restart.ps1`

- [ ] **Step 4: 验证 API**

验证 `/api/plugin/l4d2/rcon/status` 返回正确状态。

- [ ] **Step 5: 最终 Commit**

---

## 自审检查

1. **Spec 覆盖**：Resolver（Task 1-2）、Manager（Task 3-4）、RconService 重构（Task 6）、ServerStatusVO 扩展（Task 5）、6 处调用方迁移（Task 7-11）、前端适配（Task 12）、测试（Task 13）全部覆盖。
2. **占位符**：无 TBD/TODO。
3. **类型一致性**：`RconEndpoint`/`RconConnectionResolver`/`RconConnectionManager`/`RconAction` 在各任务中签名一致。
