package com.gameplatform.patch;

import com.gameplatform.plugin.patch.PatchInstallRequest;
import com.gameplatform.plugin.task.TaskPayload;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PATCH_INSTALL 请求与任务 payload 的唯一映射入口。
 *
 * <p>服务提交与 Handler 执行必须共用这里，避免新增字段只接到一侧后在任务队列中静默丢失。
 */
public final class PatchInstallContract {

    private PatchInstallContract() {
    }

    public static Map<String, Object> toPayload(PatchInstallRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request 不能为空");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("instanceId", request.getInstanceId());
        payload.put("url", request.getUrl());
        payload.put("targetPath", request.getTargetPath());
        payload.put("format", request.getFormat());
        payload.put("sha256", request.getSha256());
        payload.put("headers", copyHeaders(request.getHeaders()));
        payload.put("includePattern", request.getIncludePattern());
        return payload;
    }

    public static PatchInstallRequest fromPayload(TaskPayload payload) {
        if (payload == null) {
            throw new IllegalArgumentException("payload 不能为空");
        }

        return PatchInstallRequest.builder()
                .instanceId(payload.getLong("instanceId"))
                .url(payload.getString("url"))
                .targetPath(payload.getString("targetPath"))
                .format(payload.getString("format"))
                .sha256(payload.getString("sha256"))
                .headers(readHeaders(payload.get("headers")))
                .includePattern(payload.getString("includePattern"))
                .build();
    }

    private static Map<String, String> copyHeaders(Map<String, String> headers) {
        return headers == null ? null : new LinkedHashMap<>(headers);
    }

    private static Map<String, String> readHeaders(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return null;
        }

        Map<String, String> headers = new LinkedHashMap<>();
        map.forEach((key, headerValue) -> {
            if (key != null) {
                headers.put(String.valueOf(key), headerValue == null ? null : String.valueOf(headerValue));
            }
        });
        return headers;
    }
}
