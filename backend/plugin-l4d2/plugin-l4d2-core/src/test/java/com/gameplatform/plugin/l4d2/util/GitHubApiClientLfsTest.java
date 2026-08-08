package com.gameplatform.plugin.l4d2.util;

import com.gameplatform.plugin.l4d2.config.L4D2Config;
import com.gameplatform.plugin.l4d2.service.ExternalHttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * GitHubApiClient.parseLfsPointer 单元测试。
 *
 * <p>验证 LFS 指针解析的 OID/Size 提取与边界条件。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
class GitHubApiClientLfsTest {

    private GitHubApiClient client;

    @BeforeEach
    void setUp() {
        ExternalHttpClient http = mock(ExternalHttpClient.class);
        L4D2Config config = new L4D2Config();
        client = new GitHubApiClient(http, config);
    }

    @Test
    void parseLfsPointer_shouldReturnOidAndSize() {
        String pointer = "version https://git-lfs.github.com/spec/v1\noid sha256:abc123def456\nsize 1024\n";
        GitHubApiClient.LfsPointer parsed = client.parseLfsPointer(pointer);
        assertThat(parsed).isNotNull();
        assertThat(parsed.oid()).isEqualTo("abc123def456");
        assertThat(parsed.size()).isEqualTo(1024L);
    }

    @Test
    void parseLfsPointer_shouldReturnNullForNonLfsContent() {
        String content = "regular file content";
        GitHubApiClient.LfsPointer parsed = client.parseLfsPointer(content);
        assertThat(parsed).isNull();
    }

    @Test
    void parseLfsPointer_shouldReturnNullForNullInput() {
        assertThat(client.parseLfsPointer(null)).isNull();
    }
}
