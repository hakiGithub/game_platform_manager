package com.gameplatform.plugin.task;

import com.gameplatform.common.result.PageResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChildTaskFollowerTest {

    @Test
    void mapsProgressAndCompletesThroughTheSharedCadence() throws Exception {
        FakeTaskService taskService = new FakeTaskService(
                task("RUNNING", 50),
                task("COMPLETED", 100));
        List<Long> sleeps = new ArrayList<>();
        List<ChildTaskProgress> progress = new ArrayList<>();

        ChildTaskOutcome outcome = ChildTaskFollower.follow(
                taskService, "child-1", new ProgressWindow(20, 60),
                ChildTaskCancellation.none(), progress::add,
                ChildTaskFollower.DEFAULT_POLL_INTERVAL, sleeps::add);

        assertTrue(outcome.completedSuccessfully());
        assertEquals(List.of(2000L), sleeps);
        assertEquals(List.of(40, 60), progress.stream()
                .map(ChildTaskProgress::mappedPercent).toList());
        assertEquals(0, taskService.cancelCalls);
    }

    @Test
    void returnsFailureMessageWithoutThrowing() throws Exception {
        TaskVO failed = task("FAILED", 35);
        failed.setErrorMessage("download rejected");
        FakeTaskService taskService = new FakeTaskService(failed);

        ChildTaskOutcome outcome = ChildTaskFollower.follow(
                taskService, "child-1", ProgressWindow.full(),
                ChildTaskCancellation.none(), ignored -> {
                }, Duration.ZERO, ignored -> {
                });

        assertTrue(!outcome.completedSuccessfully());
        assertEquals("download rejected", outcome.message());
    }

    @Test
    void parentCancellationCancelsChildAndPropagatesStandardSignal() {
        FakeTaskService taskService = new FakeTaskService();
        ChildTaskCancellation cancelled = new ChildTaskCancellation() {
            @Override
            public boolean isCancelled() {
                return true;
            }
        };

        ChildTaskCancelledException error = assertThrows(ChildTaskCancelledException.class,
                () -> ChildTaskFollower.follow(
                        taskService, "child-1", ProgressWindow.full(), cancelled,
                        ignored -> {
                        }, Duration.ZERO, ignored -> {
                        }));

        assertEquals("任务已取消", error.getMessage());
        assertEquals(1, taskService.cancelCalls);
    }

    @Test
    void childCancellationUsesSameSignal() {
        FakeTaskService taskService = new FakeTaskService(task("CANCELLED", 10));

        ChildTaskCancelledException error = assertThrows(ChildTaskCancelledException.class,
                () -> ChildTaskFollower.follow(
                        taskService, "child-1", ProgressWindow.full(),
                        ChildTaskCancellation.none(), ignored -> {
                        }, Duration.ZERO, ignored -> {
                        }));

        assertEquals("任务已取消", error.getMessage());
    }

    @Test
    void parentTimeoutUsesFailedSignalAndCancelsChild() {
        FakeTaskService taskService = new FakeTaskService();
        ChildTaskCancellation timedOut = new ChildTaskCancellation() {
            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public boolean isTimedOut() {
                return true;
            }
        };

        ChildTaskTimeoutException error = assertThrows(ChildTaskTimeoutException.class,
                () -> ChildTaskFollower.follow(
                        taskService, "child-1", ProgressWindow.full(), timedOut,
                        ignored -> {
                        }, Duration.ZERO, ignored -> {
                        }));

        assertEquals("任务已超时", error.getMessage());
        assertEquals(1, taskService.cancelCalls);
    }

    @Test
    void repeatedUnknownStatusFailsAfterBoundedRetries() throws Exception {
        FakeTaskService taskService = new FakeTaskService(
                task("UNKNOWN", 0),
                task("UNKNOWN", 0),
                task("UNKNOWN", 0));

        ChildTaskOutcome outcome = ChildTaskFollower.follow(
                taskService, "child-1", ProgressWindow.full(),
                ChildTaskCancellation.none(), ignored -> {
                }, Duration.ZERO, ignored -> {
                });

        assertTrue(!outcome.completedSuccessfully());
        assertEquals("子任务状态未知", outcome.message());
        assertEquals(3, taskService.getTaskCalls);
    }

    @Test
    void knownStatusResetsUnknownRetryCount() throws Exception {
        FakeTaskService taskService = new FakeTaskService(
                task("UNKNOWN", 0),
                task("RUNNING", 20),
                task("UNKNOWN", 20),
                task("UNKNOWN", 20),
                task("COMPLETED", 100));

        ChildTaskOutcome outcome = ChildTaskFollower.follow(
                taskService, "child-1", ProgressWindow.full(),
                ChildTaskCancellation.none(), ignored -> {
                }, Duration.ZERO, ignored -> {
                });

        assertTrue(outcome.completedSuccessfully());
        assertEquals(5, taskService.getTaskCalls);
    }

    @Test
    void missingTaskIsBoundedLikeUnknownStatus() throws Exception {
        FakeTaskService taskService = new FakeTaskService();

        ChildTaskOutcome outcome = ChildTaskFollower.follow(
                taskService, "child-1", ProgressWindow.full(),
                ChildTaskCancellation.none(), ignored -> {
                }, Duration.ZERO, ignored -> {
                });

        assertTrue(!outcome.completedSuccessfully());
        assertEquals("子任务状态未知", outcome.message());
        assertEquals(3, taskService.getTaskCalls);
    }

    private static TaskVO task(String status, int progress) {
        TaskVO task = new TaskVO();
        task.setStatus(status);
        task.setProgress(progress);
        return task;
    }

    private static final class FakeTaskService implements TaskService {

        private final List<TaskVO> responses = new ArrayList<>();
        private int responseIndex;
        private int cancelCalls;
        private int getTaskCalls;

        private FakeTaskService(TaskVO... responses) {
            for (TaskVO response : responses) {
                this.responses.add(response);
            }
        }

        @Override
        public String submit(TaskSubmitRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskVO getTask(String taskId) {
            getTaskCalls++;
            return responseIndex < responses.size() ? responses.get(responseIndex++) : null;
        }

        @Override
        public PageResult<TaskVO> listTasks(TaskQuery query) {
            return null;
        }

        @Override
        public List<TaskLog> getTaskLogs(String taskId) {
            return List.of();
        }

        @Override
        public boolean cancelMyOwn(String taskId) {
            cancelCalls++;
            return true;
        }
    }
}
