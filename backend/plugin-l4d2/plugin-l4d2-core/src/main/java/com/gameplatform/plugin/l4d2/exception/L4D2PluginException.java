package com.gameplatform.plugin.l4d2.exception;

import lombok.Getter;

/**
 * L4D2 插件统一业务异常。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
@Getter
public class L4D2PluginException extends RuntimeException {

    public static final String BUSINESS = "BUSINESS";
    public static final String RCON = "RCON";
    public static final String FILE = "FILE";
    public static final String NETWORK = "NETWORK";
    public static final String EXTERNAL_API = "EXTERNAL_API";

    private final String code;

    public L4D2PluginException(String code, String message) {
        super(message);
        this.code = code;
    }

    public L4D2PluginException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
