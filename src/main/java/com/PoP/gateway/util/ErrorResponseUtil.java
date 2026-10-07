package com.pop.gateway.util;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

public final class ErrorResponseUtil {

    private ErrorResponseUtil() {}

    public static Mono<Void> writeProblemResponse(ServerWebExchange exchange, HttpStatus status, String detail) {
        // Prevent writing to a response that's already committed or when client disconnects
        if (exchange.getResponse().isCommitted()) {
            return Mono.empty();
        }

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        
        String title = status.getReasonPhrase() != null ? status.getReasonPhrase() : "Error";

        String json = """
                {
                    "title": "%s",
                    "status": %d,
                    "detail": "%s"
                }
                """.formatted(title, status.value(), detail.replace("\"", "\\\""));
                
        return exchange.getResponse().writeWith(Mono.fromCallable(() -> {
            byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return exchange.getResponse().bufferFactory().wrap(bytes);
        }));
    }
}
