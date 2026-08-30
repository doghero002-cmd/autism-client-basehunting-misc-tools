package com.example.donutflipscanner.api;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public record ApiHttpResponse(
        int statusCode,
        Map<String, List<String>> headers,
        String body,
        Duration latency
) {
    public ApiHttpResponse {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        body = body == null ? "" : body;
        latency = latency == null ? Duration.ZERO : latency;
    }

    public List<String> headerValues(String name) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .findFirst()
                .map(Map.Entry::getValue)
                .orElse(List.of());
    }
}
