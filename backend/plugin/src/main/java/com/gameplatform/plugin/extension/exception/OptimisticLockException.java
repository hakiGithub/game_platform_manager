package com.gameplatform.plugin.extension.exception;

/**
 * 乐观锁冲突异常（update 时版本号不匹配）。
 *
 * @author GamePlatform
 * @version 1.0.0
 */
public class OptimisticLockException extends ExtensionStoreException {

    public OptimisticLockException(String message) {
        super(message);
    }
}
