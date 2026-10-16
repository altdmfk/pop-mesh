package com.pop.gateway.util;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

public final class ErrorResponseUtil {

    private ErrorResponseUtil() {}

    public static Mono<Void> writeProblemResponse(ServerWebExchange exchange, HttpStatus status, String detail) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.empty();
        }

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        String title = status.getReasonPhrase() != null ? status.getReasonPhrase() : "Error";
        String escapedDetail = detail != null ? detail.replace("\"", "\\\"") : "";

        String json = """
                {
                    "title": "%s",
                    "status": %d,
                    "detail": "%s"
                }
                """.formatted(title, status.value(), escapedDetail);

        // Deferred allocation: Mono.fromCallable ensures DataBuffer allocation occurs only upon subscription,
        // avoiding Netty DirectByteBuf leaks if the client connection cancels before HTTP write initiation.
        return exchange.getResponse().writeWith(Mono.fromCallable(() -> {
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            return exchange.getResponse().bufferFactory().wrap(bytes);
        }));
    }
}
