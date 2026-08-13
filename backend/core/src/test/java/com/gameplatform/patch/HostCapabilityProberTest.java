package com.gameplatform.patch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.common.exception.BusinessException;
import com.gameplatform.plugin.patch.HostCapabilities;
import com.gameplatform.service.FileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 探测结果解析单测（ADR-0006 决策 3 的返回格式契约）
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("主机能力探测解析测试")
class HostCapabilityProberTest {

    @Mock
    private FileService fileService;

    private HostCapabilityProber prober;

    @BeforeEach
    void setUp() {
        prober = new HostCapabilityProber(fileService, new ObjectMapper());
    }

    @Test
    @DisplayName("解析探测脚本标准输出")
    void parseStandardOutput() {
        String json = "{\"osType\":\"linux\",\"hostname\":\"srv1\",\"arch\":\"x86_64\","
                + "\"currentUser\":\"steam\","
                + "\"tools\":{\"curl\":true,\"wget\":false,\"tar\":true,\"gzip\":true,"
                + "\"bzip2\":false,\"xz\":false,\"unzip\":true,\"bsdtar\":false,"
                + "\"sha256sum\":true,\"shasum\":false,\"rsync\":false},"
                + "\"tmpFreeKb\":1048576}";

        HostCapabilities caps = prober.parse(json);

        assertEquals("linux", caps.getOsType());
        assertEquals("srv1", caps.getHostname());
        assertEquals("x86_64", caps.getArch());
        assertEquals("steam", caps.getCurrentUser());
        assertTrue(caps.hasTool("curl"));
        assertFalse(caps.hasTool("wget"));
        assertTrue(caps.hasTool("tar"));
        assertTrue(caps.hasTool("gzip"));
        assertTrue(caps.hasTool("unzip"));
        assertFalse(caps.hasTool("rsync"));
        assertEquals(1048576L, caps.getTmpFreeKb());
    }

    @Test
    @DisplayName("tools 未记录的字段视为不存在")
    void missingToolsAreFalse() {
        HostCapabilities caps = prober.parse("{\"tools\":{\"curl\":true}}");
        assertTrue(caps.hasTool("curl"));
        assertFalse(caps.hasTool("tar"));
        assertFalse(caps.hasTool("unzip"));
    }

    @Test
    @DisplayName("非法输出抛 BusinessException")
    void invalidOutputThrows() {
        assertThrows(BusinessException.class, () -> prober.parse("not json"));
    }
}
