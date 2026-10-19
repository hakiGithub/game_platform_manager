package com.gameplatform.hostaccess;

import com.gameplatform.common.exception.BusinessException;

/** 主机传输层不可用（连接、认证、会话失效或 I/O 失败）。 */
public class HostAccessException extends BusinessException {

    public HostAccessException(String message) {
        super(message);
    }

    public HostAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
