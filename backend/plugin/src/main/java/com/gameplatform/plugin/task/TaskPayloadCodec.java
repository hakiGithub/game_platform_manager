package com.gameplatform.plugin.task;

import java.util.Map;

/**
 * Encodes a task-specific input object into the persisted task payload map and
 * restores it when the handler executes.
 *
 * <p>The codec is the single field-mapping contract for a typed task family.
 * Producers and handlers should share the same codec instance/type.
 *
 * @param <T> task input type
 */
public interface TaskPayloadCodec<T> {

    Map<String, Object> encode(T spec);

    T decode(TaskPayload payload);
}
