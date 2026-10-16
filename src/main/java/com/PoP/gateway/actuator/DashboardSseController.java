package com.pop.gateway.actuator;

import com.pop.gateway.config.GatewayConstants;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

@RestController
public class DashboardSseController {

    private static final Duration STREAM_INTERVAL = Duration.ofSeconds(1);
    private static final String EVENT_NAME = "metric-update";
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final MeterRegistry registry;

    public DashboardSseController(MeterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping(value = "/actuator/metrics-stream", produces = "text/event-stream")
    public Flux<ServerSentEvent<MetricDto>> streamMetrics() {
        return Flux.interval(STREAM_INTERVAL)
                .map(sequence -> ServerSentEvent.<MetricDto>builder()
                        .id(String.valueOf(sequence))
                        .event(EVENT_NAME)
                        .data(gatherCurrentMetrics())
                        .build());
    }

    private MetricDto gatherCurrentMetrics() {
        double incoming = 0;
        try {
            incoming = registry.find("http.server.requests").timers().stream()
                    .mapToDouble(Timer::count)
                    .sum();
        } catch (Exception ignored) {
        }

        double coalesced = registry.counter(GatewayConstants.METRIC_SINGLEFLIGHT_DEDUPLICATED).count();
        double upstream = Math.max(1, incoming - coalesced);
        double efficiency = incoming > 0 ? (coalesced / incoming) * 100.0 : 100.0;

        return new MetricDto(
                LocalTime.now().format(TIME_FORMATTER),
                (int) incoming,
                (int) upstream,
                efficiency,
                (long) coalesced
        );
    }

    public record MetricDto(String time, int incomingRps, int upstreamRps, double efficiency, long coalescedCount) {}
}
