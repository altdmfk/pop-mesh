package com.pop.gateway.policy;

import com.github.benmanes.caffeine.cache.Cache;
import com.pop.gateway.config.GatewayConstants;
import com.pop.gateway.model.CompositePolicyKey;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class PolicyEngine {

    private final Cache<String, PolicyDecision> l1PolicyCache;
    private final L2CacheService l2CacheService;
    private final MeterRegistry meterRegistry;
    private final Counter l1HitsCounter;
    private final Counter l1MissesCounter;
    private final Counter singleFlightDeduplicatedCounter;
    private final Timer l2LatencyTimer;
    private final ConcurrentMap<String, Mono<PolicyDecision>> inFlight = new ConcurrentHashMap<>();

    public PolicyEngine(Cache<String, PolicyDecision> l1PolicyCache,
                        L2CacheService l2CacheService,
                        MeterRegistry meterRegistry) {
        this.l1PolicyCache = l1PolicyCache;
        this.l2CacheService = l2CacheService;
        this.meterRegistry = meterRegistry;
        this.l1HitsCounter = meterRegistry.counter(GatewayConstants.METRIC_L1_HITS);
        this.l1MissesCounter = meterRegistry.counter(GatewayConstants.METRIC_L1_MISSES);
        this.singleFlightDeduplicatedCounter = meterRegistry.counter(GatewayConstants.METRIC_SINGLEFLIGHT_DEDUPLICATED);
        this.l2LatencyTimer = meterRegistry.timer(GatewayConstants.METRIC_L2_LATENCY);
    }

    public Mono<PolicyDecision> evaluate(CompositePolicyKey key) {
        String hashedKey = key.toHashedKey();
        String rawKey = key.toString();

        return Mono.defer(() -> {
            PolicyDecision cached = l1PolicyCache.getIfPresent(hashedKey);
            if (cached != null) {
                l1HitsCounter.increment();
                return Mono.just(cached);
            }

            l1MissesCounter.increment();
            boolean[] deduplicated = { true };

            Mono<PolicyDecision> result = inFlight.computeIfAbsent(hashedKey, k -> {
                deduplicated[0] = false;
                return l2CacheService.getPolicyDecision(rawKey)
                        .defaultIfEmpty(PolicyDecision.DENY)
                        .doOnNext(decision -> l1PolicyCache.put(hashedKey, decision))
                        .transformDeferred(mono -> Mono.defer(() -> {
                            Timer.Sample sample = Timer.start(meterRegistry);
                            return mono.doFinally(signal -> sample.stop(l2LatencyTimer));
                        }))
                        // Ordering invariant: .doFinally must precede .cache() so cancellation or completion
                        // immediately purges the in-flight entry rather than being swallowed inside cache multicasting.
                        .doFinally(signal -> inFlight.remove(hashedKey))
                        .cache();
            });

            if (deduplicated[0]) {
                singleFlightDeduplicatedCounter.increment();
            }
            return result;
        });
    }
}
