package com.gameplatform.plugin.task;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Polls a child task until it reaches a terminal state and translates child
 * progress into the caller's progress window.
 */
public final class ChildTaskFollower {

    static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(2);
    static final int DEFAULT_MAX_CONSECUTIVE_UNKNOWN = 3;

    private ChildTaskFollower() {
    }

    public static ChildTaskOutcome follow(TaskService taskService, String childTaskId,
                                          ProgressWindow window, TaskContext context,
                                          Consumer<ChildTaskProgress> progressListener)
            throws InterruptedException {
        return follow(taskService, childTaskId, window,
                ChildTaskCancellation.from(context), progressListener);
    }

    public static ChildTaskOutcome follow(TaskService taskService, String childTaskId,
                                          ProgressWindow window,
                                          ChildTaskCancellation cancellation,
                                          Consumer<ChildTaskProgress> progressListener)
            throws InterruptedException {
        return follow(taskService, childTaskId, window, cancellation,
                progressListener, DEFAULT_POLL_INTERVAL, Thread::sleep);
    }

    static ChildTaskOutcome follow(TaskService taskService, String childTaskId,
                                   ProgressWindow window,
                                   ChildTaskCancellation cancellation,
                                   Consumer<ChildTaskProgress> progressListener,
                                   Duration pollInterval,
                                   Sleeper sleeper) throws InterruptedException {
        Objects.requireNonNull(taskService, "taskService");
        Objects.requireNonNull(childTaskId, "childTaskId");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(cancellation, "cancellation");
        Objects.requireNonNull(progressListener, "progressListener");
        Objects.requireNonNull(pollInterval, "pollInterval");
        Objects.requireNonNull(sleeper, "sleeper");
        if (pollInterval.isNegative()) {
            throw new IllegalArgumentException("pollInterval 不能为负数");
        }

        int consecutiveUnknown = 0;
        while (true) {
            if (cancellation.isCancelled() || cancellation.isTimedOut()) {
                cancelQuietly(taskService, childTaskId);
                if (cancellation.isCancelled()) {
                    throw new ChildTaskCancelledException("任务已取消");
                }
                throw new ChildTaskTimeoutException("任务已超时");
            }

            TaskVO child = taskService.getTask(childTaskId);
            ChildTaskStatus status = statusOf(child);
            if (status == ChildTaskStatus.UNKNOWN) {
                consecutiveUnknown++;
                if (consecutiveUnknown >= DEFAULT_MAX_CONSECUTIVE_UNKNOWN) {
                    return ChildTaskOutcome.failed("子任务状态未知");
                }
            } else {
                consecutiveUnknown = 0;
                switch (status) {
                    case PENDING, RUNNING -> reportProgress(child, window, progressListener);
                    case COMPLETED -> {
                        progressListener.accept(new ChildTaskProgress(100, window.endPercent(), child));
                        return ChildTaskOutcome.completed();
                    }
                    case FAILED -> {
                        String message = child.getErrorMessage();
                        return ChildTaskOutcome.failed(
                                message == null || message.isBlank() ? "子任务失败" : message);
                    }
                    case CANCELLED -> throw new ChildTaskCancelledException("任务已取消");
                    case UNKNOWN -> throw new IllegalStateException("unreachable");
                }
            }
            sleeper.sleep(pollInterval.toMillis());
        }
    }

    private static void reportProgress(TaskVO child, ProgressWindow window,
                                       Consumer<ChildTaskProgress> progressListener) {
        int childPercent = child.getProgress() == null ? 0 : child.getProgress();
        childPercent = Math.max(0, Math.min(100, childPercent));
        progressListener.accept(new ChildTaskProgress(
                childPercent, window.map(childPercent), child));
    }

    private static ChildTaskStatus statusOf(TaskVO task) {
        if (task == null || task.getStatus() == null) {
            return ChildTaskStatus.UNKNOWN;
        }
        return switch (task.getStatus().toUpperCase(Locale.ROOT)) {
            case "PENDING" -> ChildTaskStatus.PENDING;
            case "RUNNING" -> ChildTaskStatus.RUNNING;
            case "COMPLETED" -> ChildTaskStatus.COMPLETED;
            case "FAILED" -> ChildTaskStatus.FAILED;
            case "CANCELLED" -> ChildTaskStatus.CANCELLED;
            default -> ChildTaskStatus.UNKNOWN;
        };
    }

    private static void cancelQuietly(TaskService taskService, String childTaskId) {
        try {
            taskService.cancelMyOwn(childTaskId);
        } catch (RuntimeException ignored) {
            // Cancellation is best effort; the parent must still stop promptly.
        }
    }

    private enum ChildTaskStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED,
        UNKNOWN
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }
}
