package com.gameplatform.patch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gameplatform.plugin.patch.PatchInstallRequest;
import com.gameplatform.plugin.task.TaskPayload;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PatchInstallContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void roundTripPreservesEveryRequestField() throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Cookie", "session=abc");
        headers.put("User-Agent", "GamePlatform");

        PatchInstallRequest original = PatchInstallRequest.builder()
                .instanceId(42L)
                .url("https://example.test/patch.zip")
                .targetPath("left4dead2/addons/")
                .format("zip")
                .sha256("abc123")
                .headers(headers)
                .includePattern("*.vpk")
                .build();

        String json = objectMapper.writeValueAsString(PatchInstallContract.toPayload(original));
        Map<String, Object> persisted = objectMapper.readValue(json, new TypeReference<>() {
        });
        PatchInstallRequest restored = PatchInstallContract.fromPayload(
                new TaskPayload(persisted));

        assertEquals(original.getInstanceId(), restored.getInstanceId());
        assertEquals(original.getUrl(), restored.getUrl());
        assertEquals(original.getTargetPath(), restored.getTargetPath());
        assertEquals(original.getFormat(), restored.getFormat());
        assertEquals(original.getSha256(), restored.getSha256());
        assertEquals(headers, restored.getHeaders());
        assertEquals(original.getIncludePattern(), restored.getIncludePattern());
    }

    @Test
    void roundTripKeepsOptionalFieldsAbsentWhenNotProvided() {
        PatchInstallRequest original = PatchInstallRequest.builder()
                .instanceId(7L)
                .url("https://example.test/patch.bin")
                .targetPath("data/")
                .build();

        PatchInstallRequest restored = PatchInstallContract.fromPayload(
                new TaskPayload(PatchInstallContract.toPayload(original)));

        assertNull(restored.getFormat());
        assertNull(restored.getSha256());
        assertNull(restored.getHeaders());
        assertNull(restored.getIncludePattern());
    }
}
