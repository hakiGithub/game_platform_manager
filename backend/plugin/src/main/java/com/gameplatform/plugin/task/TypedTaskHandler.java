package com.gameplatform.plugin.task;

/**
 * Opt-in base for handlers that consume a typed task input.
 *
 * <p>Existing {@link TaskHandler} implementations that consume the raw
 * {@link TaskPayload} remain fully supported.
 *
 * @param <T> task input type
 */
public interface TypedTaskHandler<T> extends TaskHandler {

    TaskPayloadCodec<T> getPayloadCodec();

    TaskResult executeTyped(TaskContext context, T spec) throws Exception;

    @Override
    default TaskResult execute(TaskContext context, TaskPayload payload) throws Exception {
        return executeTyped(context, getPayloadCodec().decode(payload));
    }
}
