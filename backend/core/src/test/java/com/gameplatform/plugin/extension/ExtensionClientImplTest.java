package com.gameplatform.plugin.extension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.api.extension.AbstractExtension;
import com.gameplatform.api.extension.ExtensionMetadata;
import com.gameplatform.plugin.extension.exception.DuplicateExtensionException;
import com.gameplatform.plugin.extension.exception.ExtensionNotFoundException;
import com.gameplatform.plugin.extension.exception.OptimisticLockException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ExtensionClientImpl} 单元测试。
 * <p>
 * 使用 SQLite 内存库，覆盖 CRUD、乐观锁、跨插件隔离、spec/label 过滤。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@DisplayName("ExtensionClientImpl CRUD 与隔离测试")
class ExtensionClientImplTest {

    private static DataSource dataSource;
    private static JdbcTemplate jdbcTemplate;
    private static ObjectMapper objectMapper;
    private static ExtensionRouter router;
    private static ExtensionQueryDialect queryDialect;

    private ExtensionClientImpl pluginAClient;
    private ExtensionClientImpl pluginBClient;

    // ===== 测试用 Spec POJO =====
    public static class TestSpec {
        private String key;
        private String value;
        private Long instanceId;

        public TestSpec() {
        }

        public TestSpec(String key, String value) {
            this.key = key;
            this.value = value;
        }

        public TestSpec(String key, String value, Long instanceId) {
            this.key = key;
            this.value = value;
            this.instanceId = instanceId;
        }

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getValue() { return value; }
        public void setValue(String value) { this.value = value; }
        public Long getInstanceId() { return instanceId; }
        public void setInstanceId(Long instanceId) { this.instanceId = instanceId; }
    }

    // ===== 测试用 Extension 模型 =====
    @ExtensionModel(strategy = Strategy.SHARED)
    public static class SharedResource extends AbstractExtension<TestSpec> {
    }

    @ExtensionModel(strategy = Strategy.MODEL_ISOLATED)
    public static class IsolatedResource extends AbstractExtension<TestSpec> {
    }

    @BeforeAll
    static void initAll() {
        // SQLite 内存库：用 SingleConnectionDataSource 复用同一连接，确保 :memory: 库的表跨 getConnection 持久
        SingleConnectionDataSource ds = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        ds.setDriverClassName("org.sqlite.JDBC");
        dataSource = ds;
        jdbcTemplate = new JdbcTemplate(dataSource);
        objectMapper = new ObjectMapper();
        objectMapper.findAndRegisterModules();
        router = new ExtensionRouter();
        queryDialect = new SqliteQueryDialect();
    }

    @BeforeEach
    void setUp() {
        // 建全局 SHARED 表
        jdbcTemplate.execute(DdlTemplate.generate("extensions"));
        // 建插件 A/B 的 MODEL_ISOLATED 表
        jdbcTemplate.execute(DdlTemplate.generate("ext_plugin_a_isolatedresource"));
        jdbcTemplate.execute(DdlTemplate.generate("ext_plugin_b_isolatedresource"));

        pluginAClient = new ExtensionClientImpl(jdbcTemplate, router, "plugin-a",
                queryDialect, objectMapper, java.util.Set.of(), () -> "id-" + System.nanoTime());
        pluginBClient = new ExtensionClientImpl(jdbcTemplate, router, "plugin-b",
                queryDialect, objectMapper, java.util.Set.of(), () -> "id-" + System.nanoTime());
    }

    @AfterEach
    void tearDown() {
        try { jdbcTemplate.execute("DROP TABLE IF EXISTS extensions"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DROP TABLE IF EXISTS ext_plugin_a_isolatedresource"); } catch (Exception ignored) {}
        try { jdbcTemplate.execute("DROP TABLE IF EXISTS ext_plugin_b_isolatedresource"); } catch (Exception ignored) {}
    }

    // ===== CRUD 测试 =====

    @Test
    @DisplayName("create + get 往返：字段值正确")
    void createAndGet_roundTrip() {
        SharedResource res = newResource("res1", "k1", "v1", 100L);

        pluginAClient.create(res);
        Optional<SharedResource> fetched = pluginAClient.get(SharedResource.class, "res1");

        assertTrue(fetched.isPresent());
        SharedResource got = fetched.get();
        assertNotNull(got.getId(), "id 应被回填");
        assertEquals(res.getId(), got.getId(), "回填的 id 应与返回一致");
        assertEquals("res1", got.getName());
        assertEquals("plugin-a", got.getGroupName());
        assertEquals("SharedResource", got.getKind());
        assertEquals(1, got.getVersion());
        assertEquals("ACTIVE", got.getStatus());
        assertNotNull(got.getMetadata());
        assertNotNull(got.getMetadata().getCreationTimestamp());
        assertNotNull(got.getSpec());
        assertEquals("k1", got.getSpec().getKey());
        assertEquals("v1", got.getSpec().getValue());
        assertEquals(100L, got.getSpec().getInstanceId());
    }

    @Test
    @DisplayName("create 同名资源抛 DuplicateExtensionException")
    void create_duplicate_throws() {
        pluginAClient.create(newResource("dup", "k", "v", 1L));
        assertThrows(DuplicateExtensionException.class,
                () -> pluginAClient.create(newResource("dup", "k2", "v2", 2L)));
    }

    @Test
    @DisplayName("update 成功后 version 递增")
    void update_incrementsVersion() {
        SharedResource res = newResource("up1", "k", "v", 1L);
        pluginAClient.create(res);
        assertEquals(1, res.getVersion());

        res.getSpec().setValue("v2");
        pluginAClient.update(res);
        assertEquals(2, res.getVersion());

        // 再次 get 确认持久化
        SharedResource got = pluginAClient.get(SharedResource.class, "up1").orElseThrow();
        assertEquals(2, got.getVersion());
        assertEquals("v2", got.getSpec().getValue());
    }

    @Test
    @DisplayName("update 用旧 version 抛 OptimisticLockException")
    void update_withStaleVersion_throwsOptimisticLock() {
        SharedResource res = newResource("up2", "k", "v", 1L);
        pluginAClient.create(res);

        // 模拟另一个客户端已更新到 version 2
        SharedResource copy = pluginAClient.get(SharedResource.class, "up2").orElseThrow();
        copy.getSpec().setValue("v2");
        pluginAClient.update(copy); // version 现在为 2

        // 用旧的 version=1 再更新，应冲突
        res.getSpec().setValue("v3");
        // res.version 仍是 1
        assertThrows(OptimisticLockException.class, () -> pluginAClient.update(res));
    }

    @Test
    @DisplayName("update 不存在的资源抛 ExtensionNotFoundException")
    void update_nonExistent_throwsNotFound() {
        SharedResource ghost = newResource("ghost", "k", "v", 1L);
        // ghost.version 是 null，先手动设为 1
        ghost.setVersion(1);
        assertThrows(ExtensionNotFoundException.class, () -> pluginAClient.update(ghost));
    }

    @Test
    @DisplayName("delete 后资源不可 get")
    void delete_removesResource() {
        pluginAClient.create(newResource("del1", "k", "v", 1L));
        pluginAClient.delete(SharedResource.class, "del1");
        assertTrue(pluginAClient.get(SharedResource.class, "del1").isEmpty());
    }

    @Test
    @DisplayName("delete 不存在的资源抛 ExtensionNotFoundException")
    void delete_nonExistent_throwsNotFound() {
        assertThrows(ExtensionNotFoundException.class,
                () -> pluginAClient.delete(SharedResource.class, "no-such"));
    }

    // ===== 跨插件隔离测试 =====

    @Test
    @DisplayName("跨插件隔离：A 创建的资源 B 不可见（同 kind 同名）")
    void crossPlugin_isolation() {
        // 用 SHARED 表 + 同 kind，但不同 group_name
        SharedResource a = newResource("shared1", "k", "from-a", 1L);
        pluginAClient.create(a);

        // 插件 B 创建同名资源（SHARED 表内复合主键 (name, group_name, kind) 不冲突）
        SharedResource b = newResource("shared1", "k", "from-b", 2L);
        pluginBClient.create(b);

        // A 看到自己的，B 看到自己的
        SharedResource aGot = pluginAClient.get(SharedResource.class, "shared1").orElseThrow();
        SharedResource bGot = pluginBClient.get(SharedResource.class, "shared1").orElseThrow();
        assertEquals("from-a", aGot.getSpec().getValue());
        assertEquals("from-b", bGot.getSpec().getValue());
    }

    @Test
    @DisplayName("跨插件隔离：list 只返回该插件拥有的资源")
    void crossPlugin_listIsolation() {
        pluginAClient.create(newResource("a1", "k", "va1", 1L));
        pluginAClient.create(newResource("a2", "k", "va2", 2L));
        pluginBClient.create(newResource("b1", "k", "vb1", 3L));

        List<SharedResource> aList = pluginAClient.listAll(SharedResource.class);
        List<SharedResource> bList = pluginBClient.listAll(SharedResource.class);

        assertEquals(2, aList.size());
        assertEquals(1, bList.size());
        // 不依赖顺序（两次 create 间隔极短可能 creation_timestamp 相同）
        assertTrue(aList.stream().anyMatch(r -> "a1".equals(r.getName())));
        assertTrue(aList.stream().anyMatch(r -> "a2".equals(r.getName())));
        // 验证 aList 中的元素都属于 plugin-a
        assertTrue(aList.stream().allMatch(r -> "plugin-a".equals(r.getGroupName())));
        assertEquals("b1", bList.get(0).getName());
    }

    // ===== spec/label 过滤测试 =====

    @Test
    @DisplayName("list 按 spec.instanceId 内存过滤生效")
    void list_withSpecFilter() {
        pluginAClient.create(newResource("r1", "k", "v1", 100L));
        pluginAClient.create(newResource("r2", "k", "v2", 200L));
        pluginAClient.create(newResource("r3", "k", "v3", 100L));

        ListOptions opts = ListOptions.builder()
                .specFilter("$.instanceId", "=", 100)
                .build();
        List<SharedResource> filtered = pluginAClient.list(SharedResource.class, opts);

        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().allMatch(r -> r.getSpec().getInstanceId().equals(100L)));
    }

    @Test
    @DisplayName("list 按 label 内存过滤生效")
    void list_withLabelSelector() {
        SharedResource r1 = newResource("r1", "k", "v1", 1L);
        r1.getMetadata().getLabels().put("env", "prod");
        pluginAClient.create(r1);

        SharedResource r2 = newResource("r2", "k", "v2", 2L);
        r2.getMetadata().getLabels().put("env", "dev");
        pluginAClient.create(r2);

        ListOptions opts = ListOptions.builder()
                .label("env", "prod")
                .build();
        List<SharedResource> filtered = pluginAClient.list(SharedResource.class, opts);

        assertEquals(1, filtered.size());
        assertEquals("r1", filtered.get(0).getName());
    }

    @Test
    @DisplayName("count 返回正确数量")
    void count_returnsCorrectNumber() {
        pluginAClient.create(newResource("c1", "k", "v1", 1L));
        pluginAClient.create(newResource("c2", "k", "v2", 2L));
        pluginAClient.create(newResource("c3", "k", "v3", 1L));

        long total = pluginAClient.count(SharedResource.class, new ListOptions());
        assertEquals(3, total);

        // 注意：count 当带 spec/label 过滤时会拉全量内存计数（SqliteQueryDialect 的实现）
        ListOptions byInstance = ListOptions.builder()
                .specFilter("$.instanceId", "=", 1)
                .build();
        long filtered = pluginAClient.count(SharedResource.class, byInstance);
        assertEquals(2, filtered);
    }

    @Test
    @DisplayName("updateStatus 仅更新 status 列")
    void updateStatus_changesOnlyStatus() {
        pluginAClient.create(newResource("s1", "k", "v", 1L));
        SharedResource updated = pluginAClient.updateStatus(SharedResource.class, "s1", "INACTIVE");
        assertEquals("INACTIVE", updated.getStatus());

        // 验证 spec 没变
        SharedResource got = pluginAClient.get(SharedResource.class, "s1").orElseThrow();
        assertEquals("v", got.getSpec().getValue());
        assertEquals("INACTIVE", got.getStatus());
    }

    @Test
    @DisplayName("MODEL_ISOLATED 策略：不同插件的同名资源物理隔离在不同表")
    void modelIsolated_differentTables() {
        IsolatedResource a = new IsolatedResource();
        a.setName("iso1");
        a.setSpec(new TestSpec("k", "from-a", 1L));
        pluginAClient.create(a);

        IsolatedResource b = new IsolatedResource();
        b.setName("iso1");
        b.setSpec(new TestSpec("k", "from-b", 2L));
        pluginBClient.create(b);

        // 各自看到自己的
        IsolatedResource aGot = pluginAClient.get(IsolatedResource.class, "iso1").orElseThrow();
        IsolatedResource bGot = pluginBClient.get(IsolatedResource.class, "iso1").orElseThrow();
        assertEquals("from-a", aGot.getSpec().getValue());
        assertEquals("from-b", bGot.getSpec().getValue());
    }

    // ===== by-id 操作测试 =====

    @Test
    @DisplayName("getById 按 id 查询返回正确资源")
    void getById_returnsResource() {
        SharedResource res = newResource("bid1", "k", "v", 1L);
        pluginAClient.create(res);
        String id = res.getId();
        assertNotNull(id);

        Optional<SharedResource> fetched = pluginAClient.getById(SharedResource.class, id);
        assertTrue(fetched.isPresent());
        assertEquals(id, fetched.get().getId());
        assertEquals("bid1", fetched.get().getName());
    }

    @Test
    @DisplayName("deleteById 按 id 删除后不可 getById")
    void deleteById_removesResource() {
        SharedResource res = newResource("bid2", "k", "v", 1L);
        pluginAClient.create(res);
        String id = res.getId();

        pluginAClient.deleteById(SharedResource.class, id);
        assertTrue(pluginAClient.getById(SharedResource.class, id).isEmpty());
    }

    @Test
    @DisplayName("updateStatusById 按 id 更新状态")
    void updateStatusById_updatesStatus() {
        SharedResource res = newResource("bid3", "k", "v", 1L);
        pluginAClient.create(res);
        String id = res.getId();

        SharedResource updated = pluginAClient.updateStatusById(SharedResource.class, id, "INACTIVE");
        assertEquals("INACTIVE", updated.getStatus());

        SharedResource got = pluginAClient.getById(SharedResource.class, id).orElseThrow();
        assertEquals("INACTIVE", got.getStatus());
    }

    // ===== 辅助方法 =====

    private static SharedResource newResource(String name, String key, String value, Long instanceId) {
        SharedResource res = new SharedResource();
        res.setName(name);
        TestSpec spec = new TestSpec(key, value);
        spec.setInstanceId(instanceId);
        res.setSpec(spec);
        ExtensionMetadata metadata = new ExtensionMetadata();
        Map<String, String> labels = new HashMap<>();
        metadata.setLabels(labels);
        metadata.setAnnotations(new HashMap<>());
        res.setMetadata(metadata);
        return res;
    }
}
