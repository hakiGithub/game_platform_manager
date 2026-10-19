package com.gameplatform.plugin.task;

/**
 * Standard cancellation signal for a parent task or its followed child.
 *
 * <p>Timeout is deliberately not represented by this exception: task-center
 * timeout remains a FAILED terminal state and uses {@link ChildTaskTimeoutException}.
 */
public class ChildTaskCancelledException extends RuntimeException {

    public ChildTaskCancelledException(String message) {
        super(message);
    }
}
