package com.pop.gateway.policy;

import com.pop.gateway.BaseBlockHoundTest;
import com.pop.gateway.model.CompositePolicyKey;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PolicyEngineStampedeTest extends BaseBlockHoundTest {

    private PolicyEngine policyEngine;
    private Cache<String, PolicyDecision> l1Cache;
    private L2CacheService l2CacheService;
    private AtomicInteger l2CallCount;
    private io.micrometer.core.instrument.simple.SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        l1Cache = Caffeine.newBuilder().build();
        l2CallCount = new AtomicInteger(0);

        l2CacheService = mock(L2CacheService.class);
        when(l2CacheService.getPolicyDecision(anyString())).thenAnswer(invocation -> {
            l2CallCount.incrementAndGet();
            // Simulate network delay to allow concurrent subscribers to pile up
            return Mono.just(PolicyDecision.ALLOW).delayElement(Duration.ofMillis(100));
        });

        meterRegistry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        policyEngine = new PolicyEngine(l1Cache, l2CacheService, meterRegistry);
    }

    @Test
    void testCacheStampedeProtection() {
        CompositePolicyKey key = new CompositePolicyKey("spiffe://service-a", "user1", "/api", "GET");

        // Fire 100 concurrent requests simultaneously for the same key
        Flux<PolicyDecision> concurrentRequests = Flux.range(1, 100)
                .flatMap(i -> policyEngine.evaluate(key));

        StepVerifier.create(concurrentRequests)
                .expectNextCount(100) // All 100 should resolve successfully
                .verifyComplete();

        // Single-Flight pattern should guarantee only ONE call was made to L2
        assertEquals(1, l2CallCount.get(), "L2 Cache should only be called once during a stampede");
        // And L1 should now be populated for subsequent calls
        assertEquals(PolicyDecision.ALLOW, l1Cache.getIfPresent(key.toHashedKey()));

        double deduplicated = meterRegistry.counter("pop.policy.singleflight.deduplicated").count();
        assertEquals(99.0, deduplicated, "Single-flight should deduplicate 99 out of 100 concurrent requests");
    }
}
