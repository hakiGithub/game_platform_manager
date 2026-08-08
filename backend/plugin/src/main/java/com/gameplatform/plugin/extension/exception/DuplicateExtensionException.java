package com.gameplatform.plugin.extension.exception;

/**
 * 资源已存在异常（create 时 name 冲突）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public class DuplicateExtensionException extends ExtensionStoreException {

    public DuplicateExtensionException(String message) {
        super(message);
    }
}
