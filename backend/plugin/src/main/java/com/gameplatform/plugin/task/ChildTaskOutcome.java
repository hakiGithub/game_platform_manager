package com.gameplatform.plugin.task;

/**
 * Non-cancelled terminal outcome of a followed child task.
 *
 * @param status  COMPLETED or FAILED
 * @param message failure message for FAILED outcomes
 */
public record ChildTaskOutcome(Status status, String message) {

    public enum Status {
        COMPLETED,
        FAILED
    }

    public static ChildTaskOutcome completed() {
        return new ChildTaskOutcome(Status.COMPLETED, null);
    }

    public static ChildTaskOutcome failed(String message) {
        return new ChildTaskOutcome(Status.FAILED, message);
    }

    public boolean completedSuccessfully() {
        return status == Status.COMPLETED;
    }
}
