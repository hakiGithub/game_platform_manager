package com.gameplatform.plugin.task;

/**
 * Cancellation/timeout checks owned by the caller following a child task.
 */
public interface ChildTaskCancellation {

    boolean isCancelled();

    default boolean isTimedOut() {
        return false;
    }

    static ChildTaskCancellation none() {
        return new ChildTaskCancellation() {
            @Override
            public boolean isCancelled() {
                return false;
            }
        };
    }

    static ChildTaskCancellation from(TaskContext context) {
        return new ChildTaskCancellation() {
            @Override
            public boolean isCancelled() {
                return context.isCancelled();
            }

            @Override
            public boolean isTimedOut() {
                return context.isTimeout();
            }
        };
    }
}
