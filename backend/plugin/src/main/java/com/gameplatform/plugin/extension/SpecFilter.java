package com.gameplatform.plugin.extension;

/**
 * spec 字段过滤条件。
 * <p>
 * path 为 JSON 路径（如 {@code "$.userId"}），op 为操作符，value 为比较值。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public class SpecFilter {

    /** JSON 路径，如 "$.userId" */
    private final String path;

    /** 操作符：=  !=  >  <  >=  <=  like  in */
    private final String op;

    /** 比较值 */
    private final Object value;

    public SpecFilter(String path, String op, Object value) {
        this.path = path;
        this.op = op;
        this.value = value;
    }

    public String getPath() {
        return path;
    }

    public String getOp() {
        return op;
    }

    public Object getValue() {
        return value;
    }
}
