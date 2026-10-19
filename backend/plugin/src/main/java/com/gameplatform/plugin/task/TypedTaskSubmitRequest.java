package com.gameplatform.plugin.task;

import lombok.Builder;
import lombok.Data;

/**
 * Typed task submission request.
 *
 * <p>The codec converts {@link #spec} into the same JSON-compatible map used by
 * {@link TaskSubmitRequest}. The generic type makes producer-side codec/spec
 * mismatches a compile-time error.
 *
 * @param <T> task input type
 */
@Data
@Builder
public class TypedTaskSubmitRequest<T> {

    private String taskType;
    private String source;

    @Builder.Default
    private String scopeType = "GLOBAL";

    private String scopeKey;
    private String scopeName;
    private String submitter;
    private TaskPayloadCodec<T> codec;
    private T spec;

    /**
     * Converts this typed request to the existing wire request.
     */
    public TaskSubmitRequest toTaskSubmitRequest() {
        if (codec == null) {
            throw new IllegalArgumentException("codec 不能为空");
        }
        if (spec == null) {
            throw new IllegalArgumentException("spec 不能为空");
        }
        return TaskSubmitRequest.builder()
                .taskType(taskType)
                .source(source)
                .scopeType(scopeType)
                .scopeKey(scopeKey)
                .scopeName(scopeName)
                .submitter(submitter)
                .payload(codec.encode(spec))
                .build();
    }
}
