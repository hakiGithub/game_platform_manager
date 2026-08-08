package com.gameplatform.plugin.extension;

import com.gameplatform.api.extension.AbstractExtension;
import com.gameplatform.api.extension.ExtensionMetadata;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 宽表行到 {@link AbstractExtension} 的映射器。
 * <p>
 * 用 Jackson 将 metadata 和 spec TEXT 列反序列化为强类型对象。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public class ExtensionRowMapper<T extends AbstractExtension<?>> implements RowMapper<T> {

    private final Class<T> modelClass;
    private final ObjectMapper objectMapper;

    public ExtensionRowMapper(Class<T> modelClass, ObjectMapper objectMapper) {
        this.modelClass = modelClass;
        this.objectMapper = objectMapper;
    }

    @Override
    @SuppressWarnings("unchecked")
    public T mapRow(ResultSet rs, int rowNum) throws SQLException {
        try {
            // 通过无参构造实例化
            T extension = modelClass.getDeclaredConstructor().newInstance();

            extension.setId(rs.getString("id"));
            extension.setName(rs.getString("name"));
            extension.setGroupName(rs.getString("group_name"));
            extension.setKind(rs.getString("kind"));
            int version = rs.getInt("version");
            extension.setVersion(rs.wasNull() ? null : version);
            extension.setStatus(rs.getString("status"));

            String metadataJson = rs.getString("metadata");
            if (metadataJson != null && !metadataJson.isEmpty()) {
                extension.setMetadata(objectMapper.readValue(metadataJson, ExtensionMetadata.class));
            }

            String specJson = rs.getString("spec");
            if (specJson != null && !specJson.isEmpty()) {
                // 获取 spec 字段的泛型类型
                JavaType specType = objectMapper.getTypeFactory().constructType(
                        modelClass.getGenericSuperclass() instanceof Class
                                ? Object.class
                                : ((java.lang.reflect.ParameterizedType) modelClass.getGenericSuperclass()).getActualTypeArguments()[0]);
                extension.setSpec(objectMapper.readValue(specJson, specType));
            }

            return extension;
        } catch (SQLException e) {
            throw e;
        } catch (Exception e) {
            throw new SQLException("反序列化 Extension 失败: " + modelClass.getName(), e);
        }
    }
}
