package com.gameplatform.plugin.extension.exception;

/**
 * 资源不存在异常（get/update/delete 目标缺失）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public class ExtensionNotFoundException extends ExtensionStoreException {

    public ExtensionNotFoundException(String message) {
        super(message);
    }
}
