package com.gameplatform.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.gameplatform.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.ini4j.Ini;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * 配置文件管理服务
 * 支持多种配置格式：properties、yaml、json、ini
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Slf4j
@Service
public class ConfigService {

    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;

    public ConfigService() {
        this.jsonMapper = new ObjectMapper();
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
    }

    /**
     * 配置文件格式枚举
     */
    public enum ConfigFormat {
        PROPERTIES("properties", "Properties配置文件"),
        YAML("yaml", "YAML配置文件"),
        JSON("json", "JSON配置文件"),
        INI("ini", "INI配置文件");

        private final String extension;
        private final String description;

        ConfigFormat(String extension, String description) {
            this.extension = extension;
            this.description = description;
        }

        public String getExtension() {
            return extension;
        }

        public String getDescription() {
            return description;
        }

        public static ConfigFormat fromExtension(String ext) {
            for (ConfigFormat format : values()) {
                if (format.extension.equalsIgnoreCase(ext)) {
                    return format;
                }
            }
            return null;
        }

        public static ConfigFormat detect(String filename) {
            if (filename == null || !filename.contains(".")) {
                return null;
            }
            String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
            // 处理yml特殊情况
            if ("yml".equals(ext)) {
                return YAML;
            }
            return fromExtension(ext);
        }
    }

    /**
     * 读取配置文件
     *
     * @param filePath 文件路径
     * @return 配置内容（键值对形式）
     */
    public Map<String, Object> readConfig(String filePath) {
        log.info("读取配置文件: {}", filePath);

        ConfigFormat format = ConfigFormat.detect(filePath);
        if (format == null) {
            throw new BusinessException("不支持的配置文件格式");
        }

        try {
            String content = Files.readString(Paths.get(filePath), StandardCharsets.UTF_8);
            return parseConfig(content, format);
        } catch (IOException e) {
            log.error("读取配置文件失败: {}", filePath, e);
            throw new BusinessException("读取配置文件失败: " + e.getMessage());
        }
    }

    /**
     * 从字符串解析配置
     *
     * @param content 配置内容
     * @param format  配置格式
     * @return 配置内容（键值对形式）
     */
    public Map<String, Object> parseConfig(String content, ConfigFormat format) {
        if (content == null || content.trim().isEmpty()) {
            return new LinkedHashMap<>();
        }

        try {
            switch (format) {
                case PROPERTIES:
                    return parseProperties(content);
                case YAML:
                    return parseYaml(content);
                case JSON:
                    return parseJson(content);
                case INI:
                    return parseIni(content);
                default:
                    throw new BusinessException("不支持的配置格式: " + format);
            }
        } catch (Exception e) {
            log.error("解析配置失败: {}", format, e);
            throw new BusinessException("解析配置失败: " + e.getMessage());
        }
    }

    /**
     * 写入配置文件
     *
     * @param filePath 文件路径
     * @param config   配置内容
     */
    public void writeConfig(String filePath, Map<String, Object> config) {
        log.info("写入配置文件: {}", filePath);

        ConfigFormat format = ConfigFormat.detect(filePath);
        if (format == null) {
            throw new BusinessException("不支持的配置文件格式");
        }

        try {
            String content = formatConfig(config, format);
            Path path = Paths.get(filePath);
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
            log.info("配置文件写入成功: {}", filePath);
        } catch (IOException e) {
            log.error("写入配置文件失败: {}", filePath, e);
            throw new BusinessException("写入配置文件失败: " + e.getMessage());
        }
    }

    /**
     * 格式化配置为字符串
     *
     * @param config 配置内容
     * @param format 配置格式
     * @return 格式化后的字符串
     */
    public String formatConfig(Map<String, Object> config, ConfigFormat format) {
        try {
            switch (format) {
                case PROPERTIES:
                    return formatProperties(config);
                case YAML:
                    return formatYaml(config);
                case JSON:
                    return formatJson(config);
                case INI:
                    return formatIni(config);
                default:
                    throw new BusinessException("不支持的配置格式: " + format);
            }
        } catch (Exception e) {
            log.error("格式化配置失败: {}", format, e);
            throw new BusinessException("格式化配置失败: " + e.getMessage());
        }
    }

    /**
     * 更新配置项
     *
     * @param filePath 文件路径
     * @param key      配置键
     * @param value    配置值
     */
    public void updateConfigValue(String filePath, String key, Object value) {
        Map<String, Object> config = readConfig(filePath);
        setNestedValue(config, key, value);
        writeConfig(filePath, config);
    }

    /**
     * 批量更新配置项
     *
     * @param filePath   文件路径
     * @param updates    更新的键值对
     */
    public void batchUpdateConfig(String filePath, Map<String, Object> updates) {
        Map<String, Object> config = readConfig(filePath);
        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            setNestedValue(config, entry.getKey(), entry.getValue());
        }
        writeConfig(filePath, config);
    }

    /**
     * 获取配置项值
     *
     * @param filePath 文件路径
     * @param key      配置键（支持嵌套，如 "server.port"）
     * @return 配置值
     */
    public Object getConfigValue(String filePath, String key) {
        Map<String, Object> config = readConfig(filePath);
        return getNestedValue(config, key);
    }

    /**
     * 验证配置文件
     *
     * @param content 配置内容
     * @param format  配置格式
     * @return 是否有效
     */
    public boolean validateConfig(String content, ConfigFormat format) {
        try {
            parseConfig(content, format);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 转换配置格式
     *
     * @param content     原配置内容
     * @param fromFormat  原格式
     * @param toFormat    目标格式
     * @return 转换后的配置内容
     */
    public String convertFormat(String content, ConfigFormat fromFormat, ConfigFormat toFormat) {
        Map<String, Object> config = parseConfig(content, fromFormat);
        return formatConfig(config, toFormat);
    }

    // ==================== 私有方法 ====================

    /**
     * 解析Properties格式
     */
    private Map<String, Object> parseProperties(String content) throws IOException {
        Properties properties = new Properties();
        properties.load(new StringReader(content));

        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames()) {
            setNestedValue(result, key, properties.getProperty(key));
        }
        return result;
    }

    /**
     * 格式化Properties格式
     */
    private String formatProperties(Map<String, Object> config) {
        StringBuilder sb = new StringBuilder();
        flattenMap(config, "", sb);
        return sb.toString();
    }

    /**
     * 扁平化Map为Properties格式
     */
    private void flattenMap(Map<String, Object> map, String prefix, StringBuilder sb) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();

            if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedMap = (Map<String, Object>) value;
                flattenMap(nestedMap, key, sb);
            } else {
                sb.append(key).append("=").append(value).append("\n");
            }
        }
    }

    /**
     * 解析YAML格式
     */
    private Map<String, Object> parseYaml(String content) throws IOException {
        return yamlMapper.readValue(content, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    /**
     * 格式化YAML格式
     */
    private String formatYaml(Map<String, Object> config) throws IOException {
        return yamlMapper.writeValueAsString(config);
    }

    /**
     * 解析JSON格式
     */
    private Map<String, Object> parseJson(String content) throws IOException {
        return jsonMapper.readValue(content, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    /**
     * 格式化JSON格式
     */
    private String formatJson(Map<String, Object> config) throws IOException {
        return jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
    }

    /**
     * 解析INI格式
     */
    private Map<String, Object> parseIni(String content) throws IOException {
        Ini ini = new Ini(new StringReader(content));
        Map<String, Object> result = new LinkedHashMap<>();

        for (String sectionName : ini.keySet()) {
            Map<String, String> section = new LinkedHashMap<>();
            ini.get(sectionName).forEach((key, value) -> section.put(key, value));
            result.put(sectionName, section);
        }

        return result;
    }

    /**
     * 格式化INI格式
     */
    @SuppressWarnings("unchecked")
    private String formatIni(Map<String, Object> config) {
        StringBuilder sb = new StringBuilder();

        // 先处理没有section的键值对
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            if (!(entry.getValue() instanceof Map)) {
                sb.append(entry.getKey()).append("=").append(entry.getValue()).append("\n");
            }
        }

        // 处理有section的键值对
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            if (entry.getValue() instanceof Map) {
                sb.append("\n[").append(entry.getKey()).append("]\n");
                Map<String, Object> section = (Map<String, Object>) entry.getValue();
                for (Map.Entry<String, Object> sectionEntry : section.entrySet()) {
                    sb.append(sectionEntry.getKey()).append("=").append(sectionEntry.getValue()).append("\n");
                }
            }
        }

        return sb.toString();
    }

    /**
     * 获取嵌套值
     */
    @SuppressWarnings("unchecked")
    private Object getNestedValue(Map<String, Object> map, String key) {
        String[] parts = key.split("\\.");
        Object current = map;

        for (String part : parts) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(part);
                if (current == null) {
                    return null;
                }
            } else {
                return null;
            }
        }

        return current;
    }

    /**
     * 设置嵌套值
     */
    @SuppressWarnings("unchecked")
    private void setNestedValue(Map<String, Object> map, String key, Object value) {
        String[] parts = key.split("\\.");
        Map<String, Object> current = map;

        for (int i = 0; i < parts.length - 1; i++) {
            Object next = current.get(parts[i]);
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<String, Object>();
                current.put(parts[i], next);
            }
            current = (Map<String, Object>) next;
        }

        current.put(parts[parts.length - 1], value);
    }
}
