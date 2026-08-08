package com.gameplatform.plugin.extension;

import com.gameplatform.api.extension.AbstractExtension;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * SQLite 查询方言。
 * <p>
 * 按 group_name + kind + status + createdAfter 拉行，反序列化后在内存对
 * spec 字段和 label 应用过滤。避免 SQLite JSON 函数开销（且无索引）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Component
public class SqliteQueryDialect implements ExtensionQueryDialect {

    @Override
    public <T extends AbstractExtension<?>> List<T> list(ResolvedRoute route, Class<T> modelClass,
                                                          ListOptions opts, JdbcTemplate jdbcTemplate,
                                                          ObjectMapper objectMapper) {
        StringBuilder sql = new StringBuilder("SELECT * FROM " + route.table());
        List<Object> args = new ArrayList<>();
        sql.append(" WHERE group_name=? AND kind=?");
        args.add(route.group());
        args.add(route.kind());

        if (opts.getStatus() != null) {
            sql.append(" AND status=?");
            args.add(opts.getStatus());
        }
        if (opts.getCreatedAfter() != null) {
            sql.append(" AND creation_timestamp > ?");
            args.add(opts.getCreatedAfter());
        }

        String orderCol = sanitizeOrderBy(opts.getOrderBy());
        sql.append(" ORDER BY ").append(orderCol).append(" DESC");
        sql.append(" LIMIT ? OFFSET ?");
        args.add(opts.getLimit());
        args.add(opts.getOffset());

        List<T> rows = jdbcTemplate.query(sql.toString(), new ExtensionRowMapper<>(modelClass, objectMapper), args.toArray());

        // 内存过滤 spec 字段和 label
        return applyMemoryFilters(rows, opts);
    }

    @Override
    public <T extends AbstractExtension<?>> long count(ResolvedRoute route, Class<T> modelClass,
                                                        ListOptions opts, JdbcTemplate jdbcTemplate,
                                                        ObjectMapper objectMapper) {
        // 无 spec/label 过滤时直接 SQL COUNT
        if (opts.getSpecFilters().isEmpty() && opts.getLabelSelector().isEmpty()) {
            StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM " + route.table());
            List<Object> args = new ArrayList<>();
            sql.append(" WHERE group_name=? AND kind=?");
            args.add(route.group());
            args.add(route.kind());

            if (opts.getStatus() != null) {
                sql.append(" AND status=?");
                args.add(opts.getStatus());
            }
            if (opts.getCreatedAfter() != null) {
                sql.append(" AND creation_timestamp > ?");
                args.add(opts.getCreatedAfter());
            }

            Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
            return count != null ? count : 0;
        }
        // 有 spec/label 过滤时拉全量后内存计数
        ListOptions fetchAll = new ListOptions()
                .setStatus(opts.getStatus())
                .setCreatedAfter(opts.getCreatedAfter())
                .setLimit(Integer.MAX_VALUE)
                .setOffset(0);
        List<T> all = list(route, modelClass, fetchAll, jdbcTemplate, objectMapper);
        return applyMemoryFilters(all, opts).size();
    }

    /**
     * 在内存中对反序列化后的对象应用 spec 字段过滤和 label 过滤。
     */
    @SuppressWarnings("unchecked")
    private <T extends AbstractExtension<?>> List<T> applyMemoryFilters(List<T> rows, ListOptions opts) {
        if (opts.getSpecFilters().isEmpty() && opts.getLabelSelector().isEmpty()) {
            return rows;
        }
        List<T> filtered = new ArrayList<>();
        for (T row : rows) {
            if (matchesSpecFilters(row, opts) && matchesLabels(row, opts)) {
                filtered.add(row);
            }
        }
        return filtered;
    }

    @SuppressWarnings("unchecked")
    private <T extends AbstractExtension<?>> boolean matchesSpecFilters(T row, ListOptions opts) {
        if (opts.getSpecFilters().isEmpty()) {
            return true;
        }
        Object spec = row.getSpec();
        if (spec == null) {
            return false;
        }
        // 把 spec 转为 Map 以便按路径取值
        Map<String, Object> specMap;
        if (spec instanceof Map) {
            specMap = (Map<String, Object>) spec;
        } else {
            specMap = objectMapperToMap(spec);
        }
        for (SpecFilter f : opts.getSpecFilters()) {
            String key = f.getPath().replace("$.", "");
            Object fieldValue = specMap.get(key);
            if (!matchesOp(fieldValue, f.getOp(), f.getValue())) {
                return false;
            }
        }
        return true;
    }

    private <T extends AbstractExtension<?>> boolean matchesLabels(T row, ListOptions opts) {
        if (opts.getLabelSelector().isEmpty()) {
            return true;
        }
        if (row.getMetadata() == null || row.getMetadata().getLabels() == null) {
            return false;
        }
        Map<String, String> labels = row.getMetadata().getLabels();
        for (Map.Entry<String, String> entry : opts.getLabelSelector().entrySet()) {
            if (!Objects.equals(labels.get(entry.getKey()), entry.getValue())) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> objectMapperToMap(Object obj) {
        if (obj instanceof Map) {
            return (Map<String, Object>) obj;
        }
        // 用 Jackson 转换
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.convertValue(obj, Map.class);
        } catch (Exception e) {
            return java.util.Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    private boolean matchesOp(Object fieldValue, String op, Object expected) {
        if (fieldValue == null) {
            return false;
        }
        switch (op) {
            case "=":
                return Objects.equals(toString(fieldValue), toString(expected));
            case "!=":
                return !Objects.equals(toString(fieldValue), toString(expected));
            case ">":
                return compareNumbers(fieldValue, expected) > 0;
            case "<":
                return compareNumbers(fieldValue, expected) < 0;
            case ">=":
                return compareNumbers(fieldValue, expected) >= 0;
            case "<=":
                return compareNumbers(fieldValue, expected) <= 0;
            case "like":
                return toString(fieldValue).contains(toString(expected).replace("%", ""));
            default:
                return false;
        }
    }

    private String toString(Object o) {
        return o == null ? null : o.toString();
    }

    private int compareNumbers(Object a, Object b) {
        double da = toDouble(a);
        double db = toDouble(b);
        return Double.compare(da, db);
    }

    private double toDouble(Object o) {
        if (o instanceof Number) {
            return ((Number) o).doubleValue();
        }
        try {
            return Double.parseDouble(o.toString());
        } catch (Exception e) {
            return 0;
        }
    }

    private static String sanitizeOrderBy(String orderBy) {
        if (orderBy == null || orderBy.isEmpty()) {
            return "creation_timestamp";
        }
        // 白名单排序字段
        return switch (orderBy) {
            case "creation_timestamp", "update_timestamp", "name", "status" -> orderBy;
            default -> "creation_timestamp";
        };
    }
}
