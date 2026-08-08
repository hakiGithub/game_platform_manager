package com.gameplatform.plugin.extension.exception;

/**
 * 扩展资源存储异常基类（运行时异常）。
 * <p>
 * 所有 ExtensionClient 抛出的存储相关异常均继承此类。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public class ExtensionStoreException extends RuntimeException {

    public ExtensionStoreException(String message) {
        super(message);
    }

    public ExtensionStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
