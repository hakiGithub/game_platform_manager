package com.gameplatform.enums;

import java.util.Optional;

/**
 * API 令牌作用域（ADR-0029）。
 *
 * <p>脏数据（未知 code）必须识别为 {@link Optional#empty()} 以便认证路径 fail-closed
 * 成 INVALID，绝不能当作 write。</p>
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public enum TokenScope {

    READ("read"),
    WRITE("write");

    private final String code;

    TokenScope(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /**
     * 按 code 解析作用域；未知或空值返回 empty（调用方据此 fail-closed）。
     */
    public static Optional<TokenScope> fromCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        for (TokenScope scope : values()) {
            if (scope.code.equals(code)) {
                return Optional.of(scope);
            }
        }
        return Optional.empty();
    }

}
