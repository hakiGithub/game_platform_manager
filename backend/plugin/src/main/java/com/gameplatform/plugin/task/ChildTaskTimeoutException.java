package com.gameplatform.plugin.task;

/**
 * Standard timeout signal for a parent task or its followed child.
 */
public class ChildTaskTimeoutException extends RuntimeException {

    public ChildTaskTimeoutException(String message) {
        super(message);
    }
}
