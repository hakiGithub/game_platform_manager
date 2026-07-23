# L4D2 RCON 连接与服务器信息获取重构设计

> 日期：2026-07-24
> 范围：plugin-l4d2-core 后端 + 前端 Dashboard 适配
> 方案：分层服务（方案 A）

## 1. 背景与问题

### 1.1 现状缺陷

当前 L4D2 插件中 6 个服务各自实现 `getRconConnection` 解析逻辑，存在以下严重问题：

1. **Host 硬编码**：`RconController.getHostFromInstance`、`ServerConfigService.getRconConnection` 等全部硬编码返回 `127.0.0.1`，导致 RCON 实际连不上远程主机上的实例。
2. **端口解析单源**：仅读 `configInfo.rconPort`，无 `portConfig.rcon` 回退，无默认端口兜底。
3. **密码解析不兼容**：仅读 `configInfo.rconPassword`，不兼容 docker-compose 变量名 `L4D2_RCON_PASSWORD` 和 docker 变量名 `SRCDS_RCONPW`。
4. **无连接复用**：每次命令都新建 TCP + 认证，`getStatus` 执行 3 条命令需 3 次握手 ≈ 10s。
5. **服务器信息不全**：`ServerStatusVO` 缺少 `version`、`osType`、`serverType` 等字段。
6. **降级缺失**：Docker 实例端口未映射时直接抛异常，前端收到 500。

### 1.2 涉及文件

| 文件 | 现状 |
|------|------|
| `RconController.java` | 硬编码 host + 自带 `getRconConnection` |
| `ServerConfigService.java` | 硬编码 host + 自带 `getRconConnection` |
| `MapService.java` | 内联解析端口密码 |
| `PlayerStatsService.java` | 内联解析端口密码 |
| `PluginInstallService.java` | 内联解析端口密码 |
| `RestartService.java` | 内联解析端口密码 |
| `RconService.java` | 每次命令新建 Socket，无复用 |
| `ServerStatusVO.java` | 缺少 version/osType 等字段 |

## 2. 架构设计

### 2.1 分层架构

```
RconController (REST)
       │ 依赖
       ▼
RconService (业务逻辑：status 解析、命令语义)
       │ 借还连接
       ▼
RconConnectionManager (实例级缓存：Map<instanceId, CachedConn>)
       │ 委托解析
       ▼
RconConnectionResolver (纯逻辑：InstanceVO + HostVO → RconEndpoint)
       │ 依赖 SPI
       ▼
InstanceQueryService / HostQueryService (宿主能力)
```

### 2.2 三个单元的职责边界

| 单元 | 职责 | 输入 | 输出 | 依赖 |
|------|------|------|------|------|
| **RconConnectionResolver** | 解析实例的 RCON 端点 `(host, port, password)`，兼容多部署类型与字段命名 | `InstanceVO`, `HostVO` | `Optional<RconEndpoint>` | 无 I/O，纯函数 |
| **RconConnectionManager** | 管理 socket 生命周期：缓存、心跳、超时回收、失效重建 | `instanceId` | `RconLease`（借用句柄） | `RconConnectionResolver`, `InstanceQueryService`, `HostQueryService` |
| **RconService**（重构） | 业务语义：`getStatus`/`executeCommand`/`changeMap` 等；解析 `status` 输出 | `instanceId`, 命令 | `ServerStatus` / 命令输出 | `RconConnectionManager` |

### 2.3 Docker 端口未映射降级

`RconConnectionResolver` 解析失败时返回 `Optional.empty()`，`RconService` 收到空端点时返回 `ServerStatus{online=false, reason="RCON_PORT_NOT_MAPPED"}`。

**不实现 docker exec 回退**：RCON 命令（`status`/`z_difficulty`）无法通过 `docker exec` 执行，只能解析日志文件，信息不完整且实现复杂。端口未映射属于部署配置问题，应引导用户修正部署。

## 3. RconConnectionResolver 详细设计

### 3.1 解析优先级

#### Host 解析
```
host = HostVO.ip  （通过 HostQueryService.getHostById(instance.hostId) 获取）
```
移除所有 `127.0.0.1` 硬编码。

#### Port 解析（三级回退）
```
1. configInfo.rconPort          （camelCase，部署时写入，最可靠）
2. portConfig.rcon              （游戏元数据端口配置）
3. 默认端口 27015                （与实际 docker-compose 部署一致）
```

#### Password 解析（三级回退）
```
1. configInfo.rconPassword      （camelCase，部署时写入）
2. configInfo.L4D2_RCON_PASSWORD （docker-compose 变量名）
3. configInfo.SRCDS_RCONPW       （docker 部署变量名）
```
三级均无 → 返回 `Optional.empty()`。

### 3.2 Docker 端口映射检测

```java
boolean isPortMapped(InstanceVO instance, int rconPort) {
    String deployType = instance.getDeployType();
    if ("native".equals(deployType) || "linuxgsm".equals(deployType)) {
        return true;  // Native 直接监听端口
    }
    Map<String, Object> portConfig = instance.getPortConfig();
    if (portConfig == null) return false;
    return portConfig.containsValue(rconPort)
        || portConfig.containsKey("rcon")
        || portConfig.containsKey(String.valueOf(rconPort));
}
```

### 3.3 对外接口

```java
public class RconConnectionResolver {
    public Optional<RconEndpoint> resolve(InstanceVO instance, HostVO host) { ... }
}

public record RconEndpoint(String host, int port, String password) {
    public boolean isValid() {
        return host != null && !host.isBlank()
            && port > 0 && port < 65536
            && password != null && !password.isBlank();
    }
}
```

### 3.4 约束

- 无 I/O：不调用任何 SPI，不查数据库。
- 幂等：相同输入返回相同输出。
- 失败不抛异常：用 `Optional.empty()` 表示无法解析。
- 无状态：不缓存解析结果。

## 4. RconConnectionManager 详细设计

### 4.1 核心数据结构

```java
@Service
public class RconConnectionManager {
    private final RconConnectionResolver resolver;
    private final InstanceQueryService instanceQueryService;
    private final HostQueryService hostQueryService;
    private final L4D2Config config;

    private final ConcurrentHashMap<Long, CachedConnection> pool = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner;
}
```

### 4.2 CachedConnection

```java
private static class CachedConnection {
    private final long instanceId;
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private volatile long lastUsedAt;
    private volatile long createdAt;
    private volatile boolean broken;
    private final ReentrantLock lock;

    boolean tryBorrow(long timeout, TimeUnit unit);
    void release();
    void heartbeat();
    void close();
    void markBroken();
}
```

### 4.3 借用/归还流程

```java
public <T> T withConnection(long instanceId, RconAction<T> action) {
    CachedConnection cached = pool.computeIfAbsent(instanceId, this::createConnection);
    if (!cached.tryBorrow(borrowTimeout, TimeUnit.SECONDS)) {
        throw new L4D2PluginException(RCON, "实例 RCON 连接正忙");
    }
    CachedConnection active = cached;
    try {
        if (active.isBroken()) {
            active.close();
            active = createConnection(instanceId);  // 重建并替换 pool 中的旧连接
            pool.put(instanceId, active);
            active.tryBorrow(0, TimeUnit.SECONDS);   // 重建后立即获取锁
        }
        return action.execute(active.getIn(), active.getOut());
    } catch (IOException e) {
        active.markBroken();
        throw new L4D2PluginException(RCON, "RCON 通信失败", e);
    } finally {
        active.release();
    }
}

@FunctionalInterface
public interface RconAction<T> {
    T execute(InputStream in, OutputStream out) throws IOException;
}
```

### 4.4 心跳保活

后台守护线程每 60 秒扫描：
- `broken` 的连接：关闭并移除。
- 超过最大寿命（30 分钟）的连接：关闭并移除。
- 空闲超过 5 分钟的连接：尝试心跳一次，失败则关闭移除。

心跳命令：发送空字符串命令，仅验证连接存活。

### 4.5 配置项（L4D2Config.Rcon 扩展）

```java
@Data
public static class Rcon {
    private int defaultPort = 27020;
    private int idleTimeoutSeconds = 300;       // 空闲超时
    private int maxAgeSeconds = 1800;            // 最大寿命
    private int cleanIntervalSeconds = 60;       // 清理扫描间隔
    private int borrowTimeoutSeconds = 3;        // 借用等待超时
    private boolean poolEnabled = true;          // 缓存开关
}
```

### 4.6 并发与线程安全

- 同一实例串行：`ReentrantLock` 确保同一 instanceId 连接同一时刻只被一个线程使用。
- 不同实例并行：不同 instanceId 连接独立锁，互不阻塞。
- 清理线程安全：`ConcurrentHashMap.removeIf` 原子操作，清理时 `tryBorrow` 失败则跳过。

### 4.7 优雅关闭

```java
@PreDestroy
public void shutdown() {
    cleaner.shutdownNow();
    pool.values().forEach(CachedConnection::close);
    pool.clear();
}
```

### 4.8 性能预期

| 场景 | 现状 | 优化后 |
|------|------|--------|
| `getStatus`（3 命令） | 3 次 TCP+认证 ≈ 10s | 1 次 TCP+认证 + 3 命令 ≈ 3.5s |
| 连续刷新仪表盘 | 每次重新认证 | 复用连接 ≈ 1s |
| 实例停止后查询 | 抛异常 500 | `markBroken` + `online=false` |

## 5. RconService 重构与 ServerStatus 扩展

### 5.1 RconService 重构

移除内部 socket 管理，委托 `RconConnectionManager`：

```java
public String executeCommand(long instanceId, String command) {
    return connectionManager.withConnection(instanceId, (in, out) -> {
        sendCommand(in, out, command);
        return readResponse(in);
    });
}

public ServerStatus getStatus(long instanceId) {
    return connectionManager.withConnection(instanceId, (in, out) -> {
        String statusText = sendAndRead(in, out, "status");
        String difficulty = sendAndRead(in, out, "z_difficulty");
        String gameMode = sendAndRead(in, out, "sm_cvar mp_gamemode");
        ServerStatus status = parseStatus(statusText);
        status.setDifficulty(parseDifficulty(difficulty));
        status.setGameMode(parseGameMode(gameMode));
        status.setVersion(parseVersion(statusText));
        return status;
    });
}
```

`changeMap`/`kickPlayer`/`banPlayer`/`changeDifficulty`/`changeGameMode`/`setMaxPlayers` 等方法签名改为接收 `instanceId`，内部委托 `executeCommand`。

### 5.2 ServerStatus 扩展字段

```java
@Data
public static class ServerStatus {
    private String hostname;
    private String map;
    private String players;
    private String difficulty;
    private String gameMode;
    private List<PlayerInfo> users;
    // 新增
    private String version;
    private String protocolVersion;
    private String osType;
    private String serverType;
    private Integer currentPlayerCount;
    private Integer maxPlayerCount;
}
```

### 5.3 status 输出新增解析

L4D2 status 输出示例：
```
version : 2.2.1.3/24 5xxx secure
os      :  Linux
type    :  community dedicated
```

新增正则：
```java
private static final Pattern VERSION_PATTERN = Pattern.compile("version\\s*:\\s*(\\S+)");
private static final Pattern OS_PATTERN = Pattern.compile("os\\s*:\\s*(\\S+)");
private static final Pattern TYPE_PATTERN = Pattern.compile("type\\s*:\\s*(.+)");
```

### 5.4 ServerStatusVO 同步扩展

```java
public class ServerStatusVO {
    // 现有字段...
    // 新增
    private String version;
    private String protocolVersion;
    private String osType;
    private String serverType;
    private String reason;  // 离线原因
}
```

### 5.5 前端 Dashboard 适配

`serverApi.getStatus` 的 `.then()` 映射新增 `version`/`osType`/`serverType` 字段，仪表盘新增信息展示卡片。

## 6. 调用方迁移

### 6.1 6 处重复代码统一迁移

| 文件 | 现状 | 迁移后 |
|------|------|--------|
| `RconController` | 自带 `getRconConnection` + 硬编码 host | 注入 `RconService` |
| `ServerConfigService` | `getRconConnection` + 硬编码 | 注入 `RconConnectionManager` |
| `MapService` | 内联解析 | 注入 `RconConnectionManager` |
| `PlayerStatsService` | 内联解析 | 注入 `RconConnectionManager` |
| `PluginInstallService` | 内联解析 | 注入 `RconConnectionManager` |
| `RestartService` | 内联解析 | 注入 `RconConnectionManager` |

### 6.2 错误处理统一

```java
try {
    ServerStatus status = rconService.getStatus(instanceId);
    return Result.success(convertToVO(status));
} catch (L4D2PluginException e) {
    log.warn("RCON 不可达 instanceId={}, msg={}", instanceId, e.getMessage());
    ServerStatusVO vo = new ServerStatusVO();
    vo.setOnline(false);
    vo.setReason(e.getMessage());
    return Result.success(vo);
}
```

## 7. 测试策略

### 7.1 单元测试

- **RconConnectionResolver**：纯逻辑单测，覆盖 Native/Docker/docker-compose 多部署类型、端口映射检测、三级回退、空配置降级。
- **RconConnectionManager**：mock `InstanceQueryService`/`HostQueryService`/`Socket`，验证缓存命中、失效重建、并发借用串行化、空闲超时回收。
- **RconService**：mock `RconConnectionManager`，验证 status 多命令编排与解析逻辑（含新增 version/os 解析）。

### 7.2 集成测试

现有 `PlayerStatsServiceTest`/`ServerConfigServiceTest`/`MapServiceTest`/`RestartServiceTest` 改为 mock `RconConnectionManager`，删除各自 mock socket 逻辑。

## 8. 不做的事（YAGNI）

- 不实现 docker exec 回退（RCON 命令无法通过 exec 执行）。
- 不引入连接池框架（Commons-Pool 过重，`ConcurrentHashMap` + `ReentrantLock` 足够）。
- 不实现 RCON 异步命令队列（L4D2 单实例操作频率低）。
- 不修改 SPI 接口（`InstanceQueryService`/`HostQueryService` 无需变更）。
- 不重构 `RconService` 内部的 Source RCON 协议解析（`buildPacket`/`readPacket` 保持现状）。
