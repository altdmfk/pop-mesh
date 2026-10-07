package com.pop.gateway.actuator;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

@RestController
public class DashboardSseController {

    private final MeterRegistry registry;

    public DashboardSseController(MeterRegistry registry) {
        this.registry = registry;
    }

    // 1초 주기로 시스템 메트릭을 클라이언트에 스트리밍
    @GetMapping(value = "/actuator/metrics-stream", produces = "text/event-stream")
    public Flux<ServerSentEvent<MetricDto>> streamMetrics() {
        return Flux.interval(Duration.ofSeconds(1))
                .map(sequence -> ServerSentEvent.<MetricDto>builder()
                        .id(String.valueOf(sequence))
                        .event("metric-update")
                        .data(gatherCurrentMetrics())
                        .build());
    }

    // Micrometer 레지스트리에서 현재 성능 지표 스냅샷 추출
    private MetricDto gatherCurrentMetrics() {
        double incoming = 0;
        try {
            incoming = registry.find("http.server.requests").timers().stream()
                    .mapToDouble(io.micrometer.core.instrument.Timer::count)
                    .sum();
        } catch (Exception e) {
            // timer may not exist initially
        }
        
        double coalesced = registry.counter("pop.policy.singleflight.deduplicated").count();
        double upstream = Math.max(1, incoming - coalesced);
        
        return new MetricDto(
            LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")),
            (int) incoming,
            (int) upstream,
            (incoming > 0) ? ((coalesced / incoming) * 100.0) : 100.0,
            (long) coalesced
        );
    }

    // DTO 레코드 (Java 16+)
    public record MetricDto(String time, int incomingRps, int upstreamRps, double efficiency, long coalescedCount) {}
}
