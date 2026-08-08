package com.gameplatform.plugin.l4d2.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class ExternalHttpClientTest {

    @Test
    void getForObject_shouldReturnJson() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ExternalHttpClient client = new ExternalHttpClient(builder);

        server.expect(requestTo("https://example.com/api"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"name\":\"test\"}", MediaType.APPLICATION_JSON));

        Map<String, ?> result = client.getForObject(
                "https://example.com/api", Map.class, Map.of());
        assertEquals("test", result.get("name"));
        server.verify();
    }
}
