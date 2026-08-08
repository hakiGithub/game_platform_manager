package com.gameplatform.plugin.l4d2.util;

import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.service.ExternalHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * GitHubApiClient 单元测试。
 *
 * <p>所有 HTTP 请求通过 Mockito mock ExternalHttpClient 实现，不发起真实网络请求。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GitHubApiClientTest {

    @Mock
    private ExternalHttpClient httpClient;

    private L4D2Config config;

    private GitHubApiClient client;

    @BeforeEach
    void setUp() {
        config = new L4D2Config();
        client = new GitHubApiClient(httpClient, config);
    }

    @Test
    void isLfsPointer_shouldReturnTrueForLfsPointer() {
        String pointer = "version https://git-lfs.github.com/spec/v1\noid sha256:abc123\nsize 100";
        assertTrue(client.isLfsPointer(pointer));
    }

    @Test
    void isLfsPointer_shouldReturnTrueForExactPrefix() {
        String pointer = "version https://git-lfs.github.com/spec/v1";
        assertTrue(client.isLfsPointer(pointer));
    }

    @Test
    void isLfsPointer_shouldReturnFalseForRegularContent() {
        assertFalse(client.isLfsPointer("regular file content"));
    }

    @Test
    void isLfsPointer_shouldReturnFalseForNull() {
        assertFalse(client.isLfsPointer(null));
    }

    @Test
    void isLfsPointer_shouldReturnFalseForPartialMatch() {
        // 仅含 LFS 关键字但未以 spec 开头
        assertFalse(client.isLfsPointer("# version https://git-lfs.github.com/spec/v1"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void getTree_shouldParseTreeResponse() {
        Map<String, Object> treeResp = new HashMap<>();
        List<Map<String, Object>> tree = new ArrayList<>();
        tree.add(Map.of(
                "path", "plugin-1/README.md",
                "type", "blob",
                "sha", "readme-sha",
                "size", 100
        ));
        tree.add(Map.of(
                "path", "plugin-1/plugin.zip",
                "type", "blob",
                "sha", "zip-sha",
                "size", 5000
        ));
        tree.add(Map.of(
                "path", "plugin-1",
                "type", "tree",
                "sha", "dir-sha"
        ));
        treeResp.put("tree", tree);

        when(httpClient.getForObject(anyString(), eq(Map.class), any())).thenReturn(treeResp);

        List<GitHubApiClient.TreeEntry> result = client.getTree();

        assertEquals(3, result.size());

        GitHubApiClient.TreeEntry readme = result.stream()
                .filter(e -> "plugin-1/README.md".equals(e.path()))
                .findFirst().orElseThrow();
        assertEquals("blob", readme.type());
        assertEquals("readme-sha", readme.sha());
        assertEquals(100L, readme.size());

        GitHubApiClient.TreeEntry zip = result.stream()
                .filter(e -> "plugin-1/plugin.zip".equals(e.path()))
                .findFirst().orElseThrow();
        assertEquals("blob", zip.type());
        assertEquals("zip-sha", zip.sha());
        assertEquals(5000L, zip.size());

        GitHubApiClient.TreeEntry dir = result.stream()
                .filter(e -> "plugin-1".equals(e.path()))
                .findFirst().orElseThrow();
        assertEquals("tree", dir.type());
        assertEquals("dir-sha", dir.sha());
        assertEquals(0L, dir.size());

        verify(httpClient, times(1)).getForObject(anyString(), eq(Map.class), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void getTree_shouldReturnEmptyWhenTreeMissing() {
        Map<String, Object> emptyResp = new HashMap<>();
        when(httpClient.getForObject(anyString(), eq(Map.class), any())).thenReturn(emptyResp);

        List<GitHubApiClient.TreeEntry> result = client.getTree();
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    void getTree_shouldReturnEmptyWhenResponseNull() {
        when(httpClient.getForObject(anyString(), eq(Map.class), any())).thenReturn(null);

        List<GitHubApiClient.TreeEntry> result = client.getTree();
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    void batchLfsObjects_shouldParseBatchResponse() {
        Map<String, Object> resp = new HashMap<>();
        List<Map<String, Object>> objects = new ArrayList<>();

        Map<String, Object> obj = new HashMap<>();
        obj.put("oid", "test-oid");
        Map<String, Object> actions = new HashMap<>();
        Map<String, Object> download = new HashMap<>();
        download.put("href", "https://download.example.com/lfs/obj");
        actions.put("download", download);
        obj.put("actions", actions);
        objects.add(obj);

        resp.put("objects", objects);

        when(httpClient.postForObject(anyString(), any(), eq(Map.class))).thenReturn(resp);

        Map<String, String> result = client.batchLfsObjects(List.of("test-oid"));

        assertEquals(1, result.size());
        assertEquals("https://download.example.com/lfs/obj", result.get("test-oid"));
        verify(httpClient, times(1)).postForObject(anyString(), any(), eq(Map.class));
    }

    @SuppressWarnings("unchecked")
    @Test
    void batchLfsObjects_shouldHandleMissingDownloadUrl() {
        Map<String, Object> resp = new HashMap<>();
        List<Map<String, Object>> objects = new ArrayList<>();

        // 第一个对象：有 download URL
        Map<String, Object> obj1 = new HashMap<>();
        obj1.put("oid", "oid-1");
        Map<String, Object> actions1 = new HashMap<>();
        Map<String, Object> download1 = new HashMap<>();
        download1.put("href", "https://download.example.com/1");
        actions1.put("download", download1);
        obj1.put("actions", actions1);
        objects.add(obj1);

        // 第二个对象：不存在（返回 error，无 actions）
        Map<String, Object> obj2 = new HashMap<>();
        obj2.put("oid", "oid-2");
        obj2.put("error", Map.of("code", 404, "message", "Not found"));
        objects.add(obj2);

        resp.put("objects", objects);

        when(httpClient.postForObject(anyString(), any(), eq(Map.class))).thenReturn(resp);

        Map<String, String> result = client.batchLfsObjects(List.of("oid-1", "oid-2"));

        assertEquals(1, result.size());
        assertEquals("https://download.example.com/1", result.get("oid-1"));
        assertNull(result.get("oid-2"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void batchLfsObjects_shouldReturnEmptyWhenOidsEmpty() {
        Map<String, String> result = client.batchLfsObjects(List.of());
        assertNotNull(result);
        assertTrue(result.isEmpty());
        verifyNoInteractions(httpClient);
    }

    @SuppressWarnings("unchecked")
    @Test
    void batchLfsObjects_shouldReturnEmptyWhenResponseNull() {
        when(httpClient.postForObject(anyString(), any(), eq(Map.class))).thenReturn(null);

        Map<String, String> result = client.batchLfsObjects(List.of("oid-1"));
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    void getBlobContent_shouldDecodeBase64Content() {
        Map<String, Object> resp = new HashMap<>();
        // "version https://git-lfs.github.com/spec/v1\n" 的 base64 编码
        String original = "version https://git-lfs.github.com/spec/v1\noid sha256:abc\nsize 10";
        String base64 = java.util.Base64.getEncoder().encodeToString(original.getBytes());
        resp.put("content", base64);
        resp.put("encoding", "base64");

        when(httpClient.getForObject(anyString(), eq(Map.class), any())).thenReturn(resp);

        String result = client.getBlobContent("some-sha");

        assertEquals(original, result);
    }

    @SuppressWarnings("unchecked")
    @Test
    void getBlobContent_shouldReturnRawContentWhenNotBase64() {
        Map<String, Object> resp = new HashMap<>();
        resp.put("content", "plain text");
        resp.put("encoding", "none");

        when(httpClient.getForObject(anyString(), eq(Map.class), any())).thenReturn(resp);

        String result = client.getBlobContent("some-sha");
        assertEquals("plain text", result);
    }

    @Test
    void getBlobContent_shouldThrowWhenShaBlank() {
        assertThrows(com.gameplatform.plugin.l4d2.exception.L4D2PluginException.class,
                () -> client.getBlobContent(""));
        assertThrows(com.gameplatform.plugin.l4d2.exception.L4D2PluginException.class,
                () -> client.getBlobContent(null));
    }
}
