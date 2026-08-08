package com.gameplatform.service;

import com.gameplatform.service.ConfigService.ConfigFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 配置文件管理服务测试类
 *
 * @author GamePlatform
 * @version 1.0.0
 */
class ConfigServiceTest {

    private ConfigService configService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        configService = new ConfigService();
    }

    @Test
    void testDetectFormat() {
        assertEquals(ConfigFormat.PROPERTIES, ConfigFormat.detect("config.properties"));
        assertEquals(ConfigFormat.YAML, ConfigFormat.detect("config.yaml"));
        assertEquals(ConfigFormat.YAML, ConfigFormat.detect("config.yml"));
        assertEquals(ConfigFormat.JSON, ConfigFormat.detect("config.json"));
        assertEquals(ConfigFormat.INI, ConfigFormat.detect("config.ini"));
        assertNull(ConfigFormat.detect("config.txt"));
        assertNull(ConfigFormat.detect("config"));
    }

    @Test
    void testParseAndFormatProperties() {
        String propertiesContent = "server.port=8080\n" +
                "server.host=localhost\n" +
                "database.url=jdbc:mysql://localhost:3306/test";

        Map<String, Object> config = configService.parseConfig(propertiesContent, ConfigFormat.PROPERTIES);

        // Properties格式会扁平化为嵌套Map
        @SuppressWarnings("unchecked")
        Map<String, Object> server = (Map<String, Object>) config.get("server");
        assertNotNull(server);
        assertEquals("8080", server.get("port"));
        assertEquals("localhost", server.get("host"));

        String formatted = configService.formatConfig(config, ConfigFormat.PROPERTIES);
        assertNotNull(formatted);
        assertTrue(formatted.contains("server.port=8080") || formatted.contains("server=8080"));
    }

    @Test
    void testParseAndFormatJson() throws IOException {
        String jsonContent = "{\n" +
                "  \"server\" : {\n" +
                "    \"port\" : 8080,\n" +
                "    \"host\" : \"localhost\"\n" +
                "  },\n" +
                "  \"database\" : {\n" +
                "    \"url\" : \"jdbc:mysql://localhost:3306/test\"\n" +
                "  }\n" +
                "}";

        Map<String, Object> config = configService.parseConfig(jsonContent, ConfigFormat.JSON);

        @SuppressWarnings("unchecked")
        Map<String, Object> server = (Map<String, Object>) config.get("server");
        assertNotNull(server);
        assertEquals(8080, server.get("port"));
        assertEquals("localhost", server.get("host"));

        String formatted = configService.formatConfig(config, ConfigFormat.JSON);
        assertNotNull(formatted);
        assertTrue(formatted.contains("\"port\" : 8080"));
    }

    @Test
    void testParseAndFormatYaml() throws IOException {
        String yamlContent = "server:\n" +
                "  port: 8080\n" +
                "  host: localhost\n" +
                "database:\n" +
                "  url: jdbc:mysql://localhost:3306/test";

        Map<String, Object> config = configService.parseConfig(yamlContent, ConfigFormat.YAML);

        @SuppressWarnings("unchecked")
        Map<String, Object> server = (Map<String, Object>) config.get("server");
        assertNotNull(server);
        assertEquals(8080, server.get("port"));

        String formatted = configService.formatConfig(config, ConfigFormat.YAML);
        assertNotNull(formatted);
    }

    @Test
    void testParseAndFormatIni() throws IOException {
        String iniContent = "[server]\n" +
                "port=8080\n" +
                "host=localhost\n" +
                "[database]\n" +
                "url=jdbc:mysql://localhost:3306/test";

        Map<String, Object> config = configService.parseConfig(iniContent, ConfigFormat.INI);

        @SuppressWarnings("unchecked")
        Map<String, String> server = (Map<String, String>) config.get("server");
        assertNotNull(server);
        assertEquals("8080", server.get("port"));
        assertEquals("localhost", server.get("host"));

        String formatted = configService.formatConfig(config, ConfigFormat.INI);
        assertNotNull(formatted);
        assertTrue(formatted.contains("[server]"));
    }

    @Test
    void testReadAndWriteConfig() throws IOException {
        // 创建测试文件
        Path configFile = tempDir.resolve("test-config.json");
        String jsonContent = "{\"server\":{\"port\":8080}}";
        Files.writeString(configFile, jsonContent);

        // 读取配置
        Map<String, Object> config = configService.readConfig(configFile.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> server = (Map<String, Object>) config.get("server");
        assertEquals(8080, server.get("port"));

        // 修改配置
        server.put("port", 9090);

        // 写入配置
        configService.writeConfig(configFile.toString(), config);

        // 重新读取验证
        Map<String, Object> newConfig = configService.readConfig(configFile.toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> newServer = (Map<String, Object>) newConfig.get("server");
        assertEquals(9090, newServer.get("port"));
    }

    @Test
    void testValidateConfig() {
        String validJson = "{\"key\": \"value\"}";
        String invalidJson = "{\"key\": \"value\"";

        assertTrue(configService.validateConfig(validJson, ConfigFormat.JSON));
        assertFalse(configService.validateConfig(invalidJson, ConfigFormat.JSON));
    }

    @Test
    void testConvertFormat() throws IOException {
        String jsonContent = "{\"server\":{\"port\":8080}}";

        String yamlContent = configService.convertFormat(jsonContent, ConfigFormat.JSON, ConfigFormat.YAML);
        assertNotNull(yamlContent);
        assertTrue(yamlContent.contains("server:") || yamlContent.contains("port:"));

        // 转换回JSON验证
        String convertedJson = configService.convertFormat(yamlContent, ConfigFormat.YAML, ConfigFormat.JSON);
        assertNotNull(convertedJson);
    }

    @Test
    void testEmptyConfig() {
        Map<String, Object> config = configService.parseConfig("", ConfigFormat.JSON);
        assertTrue(config.isEmpty());

        config = configService.parseConfig("   ", ConfigFormat.PROPERTIES);
        assertTrue(config.isEmpty());
    }
}
