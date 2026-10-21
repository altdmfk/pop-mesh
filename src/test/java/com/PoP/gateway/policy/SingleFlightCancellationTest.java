package com.pop.gateway.policy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pop.gateway.BaseBlockHoundTest;
import com.pop.gateway.config.GatewayConstants;
import com.pop.gateway.model.CompositePolicyKey;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.blockhound.BlockingOperationError;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SingleFlightCancellationTest extends BaseBlockHoundTest {

    private PolicyEngine policyEngine;
    private Cache<String, PolicyDecision> l1Cache;
    private L2CacheService l2CacheService;
    private SimpleMeterRegistry meterRegistry;
    private AtomicInteger l2CallCount;
    private AtomicInteger inFlightRemoveCount;
    private ConcurrentMap<String, Mono<PolicyDecision>> trackingMap;
    private AtomicReference<Sinks.One<PolicyDecision>> currentRedisSink;

    @BeforeEach
    void setUp() {
        l1Cache = Caffeine.newBuilder().build();
        l2CallCount = new AtomicInteger(0);
        inFlightRemoveCount = new AtomicInteger(0);
        currentRedisSink = new AtomicReference<>(Sinks.one());

        // Instrument inFlight map to track removal count and verify single-removal guarantees
        trackingMap = new ConcurrentHashMap<>() {
            @Override
            public Mono<PolicyDecision> remove(Object key) {
                inFlightRemoveCount.incrementAndGet();
                return super.remove(key);
            }

            @Override
            public boolean remove(Object key, Object value) {
                inFlightRemoveCount.incrementAndGet();
                return super.remove(key, value);
            }
        };

        l2CacheService = mock(L2CacheService.class);
        when(l2CacheService.getPolicyDecision(anyString())).thenAnswer(inv -> {
            l2CallCount.incrementAndGet();
            return currentRedisSink.get().asMono();
        });

        meterRegistry = new SimpleMeterRegistry();
        policyEngine = new PolicyEngine(l1Cache, l2CacheService, meterRegistry, trackingMap);
    }

    @Test
    @DisplayName("Leader cancellation mid-flight: followers succeed, map entry cleaned up exactly once, and fresh query runs on subsequent call")
    void testLeaderCancellationMidFlight_followersSucceed_andMapEntryCleanedUpExactlyOnce() {
        CompositePolicyKey key = new CompositePolicyKey("spiffe://service-a", "user-lead", "/api/order", "POST");
        String hashedKey = key.toHashedKey();

        // 1. Leader subscribes first (triggers computeIfAbsent and L2 query)
        List<PolicyDecision> leaderResults = new CopyOnWriteArrayList<>();
        List<Throwable> leaderErrors = new CopyOnWriteArrayList<>();
        Mono<PolicyDecision> leaderMono = policyEngine.evaluate(key);
        Disposable leaderSub = leaderMono.subscribe(leaderResults::add, leaderErrors::add);

        // Verify leader started the L2 query and registered in-flight entry
        assertEquals(1, l2CallCount.get(), "Leader must trigger the L2 query");
        assertTrue(trackingMap.containsKey(hashedKey), "inFlight map must contain key for in-progress request");
        assertEquals(0, inFlightRemoveCount.get(), "remove() should not have been called yet");

        // 2. Three follower subscribers join the same in-flight Mono
        List<PolicyDecision> f1Results = new CopyOnWriteArrayList<>();
        List<PolicyDecision> f2Results = new CopyOnWriteArrayList<>();
        List<PolicyDecision> f3Results = new CopyOnWriteArrayList<>();

        Disposable f1Sub = policyEngine.evaluate(key).subscribe(f1Results::add);
        Disposable f2Sub = policyEngine.evaluate(key).subscribe(f2Results::add);
        Disposable f3Sub = policyEngine.evaluate(key).subscribe(f3Results::add);

        // Confirm single-flight deduplication coalesced all followers into the leader's query
        assertEquals(1, l2CallCount.get(), "Followers must coalesce into existing flight; L2 query count remains 1");
        double deduplicatedCount = meterRegistry.counter(GatewayConstants.METRIC_SINGLEFLIGHT_DEDUPLICATED).count();
        assertEquals(3.0, deduplicatedCount, "MeterRegistry must record 3 deduplicated follower requests");

        // 3. Leader cancels mid-flight (e.g. client disconnects before Redis responds)
        leaderSub.dispose();

        // Critical Invariant: upstream is NOT cancelled because followers are still waiting!
        // The inFlight map entry must NOT be prematurely evicted.
        assertTrue(trackingMap.containsKey(hashedKey),
                "Leader cancellation MUST NOT remove inFlight entry while followers are still waiting");
        assertEquals(0, inFlightRemoveCount.get(),
                "inFlight.remove() MUST NOT be called prematurely on leader cancellation");

        // 4. Redis response arrives
        currentRedisSink.get().tryEmitValue(PolicyDecision.ALLOW);

        // Requirement 2a: Confirm followers still receive correct result via .cache() multicasting
        assertEquals(List.of(PolicyDecision.ALLOW), f1Results, "Follower 1 must receive ALLOW");
        assertEquals(List.of(PolicyDecision.ALLOW), f2Results, "Follower 2 must receive ALLOW");
        assertEquals(List.of(PolicyDecision.ALLOW), f3Results, "Follower 3 must receive ALLOW");

        // Leader received nothing due to cancellation
        assertTrue(leaderResults.isEmpty(), "Cancelled leader must not receive items");
        assertTrue(leaderErrors.isEmpty(), "Cancelled leader must not receive errors");

        // Requirement 2b: Confirm inFlight map entry is cleaned up exactly once (no leak, no double removal)
        assertTrue(trackingMap.isEmpty(), "inFlight map must be empty after all subscribers complete");
        assertEquals(1, inFlightRemoveCount.get(),
                "inFlight entry must be cleaned up EXACTLY ONCE upon upstream completion");

        // L1 cache was populated with the result
        assertEquals(PolicyDecision.ALLOW, l1Cache.getIfPresent(hashedKey));

        // Requirement 2c: Subsequent request for the same key triggers a FRESH Redis query (not stuck/stale)
        // Clear L1 cache to force L2 resolution
        l1Cache.invalidate(hashedKey);

        Sinks.One<PolicyDecision> secondSink = Sinks.one();
        currentRedisSink.set(secondSink);

        List<PolicyDecision> subsequentResults = new CopyOnWriteArrayList<>();
        Disposable subsequentSub = policyEngine.evaluate(key).subscribe(subsequentResults::add);

        // Assert fresh Redis query was triggered
        assertEquals(2, l2CallCount.get(), "Subsequent request after completion must trigger a fresh Redis query");
        assertTrue(trackingMap.containsKey(hashedKey), "New in-flight query must be registered in inFlight map");

        // Complete second query
        secondSink.tryEmitValue(PolicyDecision.ALLOW);
        assertEquals(List.of(PolicyDecision.ALLOW), subsequentResults, "Subsequent request receives correct decision");
        assertTrue(trackingMap.isEmpty(), "inFlight map must be empty again after second query completes");
        assertEquals(2, inFlightRemoveCount.get(), "inFlight entry cleaned up exactly once per query (total 2)");

        f1Sub.dispose();
        f2Sub.dispose();
        f3Sub.dispose();
        subsequentSub.dispose();
    }

    @Test
    @DisplayName("Solo subscriber cancels mid-flight: inFlight entry remains protected until upstream completes, then cleanly purged")
    void testSoloSubscriberCancels_entryCleanedUpOnCompletion() {
        CompositePolicyKey key = new CompositePolicyKey("spiffe://service-x", "user-solo", "/api/item", "GET");
        String hashedKey = key.toHashedKey();

        Mono<PolicyDecision> soloMono = policyEngine.evaluate(key);
        Disposable soloSub = soloMono.subscribe();

        assertEquals(1, l2CallCount.get());
        assertTrue(trackingMap.containsKey(hashedKey));
        assertEquals(0, inFlightRemoveCount.get());

        // Cancel subscriber mid-flight
        soloSub.dispose();

        // Upstream is not prematurely aborted by downstream cancel; entry remains in inFlight
        assertTrue(trackingMap.containsKey(hashedKey),
                "inFlight entry must remain protected while upstream query completes");
        assertEquals(0, inFlightRemoveCount.get(),
                "remove() should not have been called prematurely on cancel");

        // When upstream completes, doFinally executes and cleans up the map
        currentRedisSink.get().tryEmitValue(PolicyDecision.ALLOW);
        assertTrue(trackingMap.isEmpty(), "inFlight map must be empty after upstream completion");
        assertEquals(1, inFlightRemoveCount.get(), "inFlight entry must be cleaned up exactly once");
    }

    @Test
    @DisplayName("Reproduction of failure mode: .cache() before .doFinally() causes premature eviction and multiple removals")
    void testBuggyOrdering_cacheBeforeDoFinally_causesPrematureEvictionAndDoubleRemoval() {
        AtomicInteger faultyRemoveCount = new AtomicInteger(0);
        ConcurrentMap<String, Mono<PolicyDecision>> faultyInFlight = new ConcurrentHashMap<>();
        Sinks.One<PolicyDecision> mockRedisSink = Sinks.one();
        String testKey = "faulty-order-key";

        // Reproduce incorrect ordering: .cache() placed BEFORE .doFinally()
        Mono<PolicyDecision> faultyMono = faultyInFlight.computeIfAbsent(testKey, k ->
                mockRedisSink.asMono()
                        .cache()
                        .doFinally(signal -> {
                            faultyRemoveCount.incrementAndGet();
                            faultyInFlight.remove(testKey);
                        })
        );

        // Leader subscribes
        List<PolicyDecision> leaderOut = new CopyOnWriteArrayList<>();
        Disposable leaderSub = faultyMono.subscribe(leaderOut::add);

        // Followers subscribe
        List<PolicyDecision> f1Out = new CopyOnWriteArrayList<>();
        List<PolicyDecision> f2Out = new CopyOnWriteArrayList<>();
        Disposable f1Sub = faultyMono.subscribe(f1Out::add);
        Disposable f2Sub = faultyMono.subscribe(f2Out::add);

        assertEquals(0, faultyRemoveCount.get());
        assertTrue(faultyInFlight.containsKey(testKey));

        // Leader cancels mid-flight
        leaderSub.dispose();

        // FAILURE MODE 1: Premature Eviction & Single-Flight Stampede Breach
        // Because .doFinally() is downstream of .cache(), leader cancellation immediately executes doFinally!
        assertEquals(1, faultyRemoveCount.get(),
                "Buggy ordering prematurely executes doFinally when leader cancels, before Redis has responded!");
        assertFalse(faultyInFlight.containsKey(testKey),
                "Buggy ordering prematurely evicted the map entry while followers are still waiting!");

        // Redis responds
        mockRedisSink.tryEmitValue(PolicyDecision.ALLOW);

        // Followers get the value via cache()
        assertEquals(List.of(PolicyDecision.ALLOW), f1Out);
        assertEquals(List.of(PolicyDecision.ALLOW), f2Out);

        // FAILURE MODE 2: Double/Triple Removal Race
        // Each follower triggers its own doFinally upon completion
        assertEquals(3, faultyRemoveCount.get(),
                "Buggy ordering triggers doFinally 3 times (1 on leader cancel + 2 on follower completions)");

        f1Sub.dispose();
        f2Sub.dispose();
    }

    @Test
    @DisplayName("BlockHound verification: Single-flight execution introduces no blocking calls on Reactor/Netty event loop")
    void testSingleFlightPath_noBlockingCallsOnReactorEventLoop() {
        CompositePolicyKey key = new CompositePolicyKey("spiffe://service-bh", "user-bh", "/api/eventloop", "GET");

        // Prepare L2 mock with a non-blocking delay on Schedulers.parallel()
        doAnswer(inv -> {
            l2CallCount.incrementAndGet();
            return Mono.just(PolicyDecision.ALLOW).delayElement(Duration.ofMillis(100), Schedulers.parallel());
        }).when(l2CacheService).getPolicyDecision(anyString());
        l2CallCount.set(0);

        // 1. Execute single-flight stampede: all concurrent requests coalesce into exactly 1 L2 query.
        // Under BlockHound, all operators executing on Schedulers.parallel() are actively monitored.
        Flux<PolicyDecision> concurrentRequests = Flux.range(1, 50)
                .flatMap(i -> policyEngine.evaluate(key));

        StepVerifier.create(concurrentRequests)
                .expectNextCount(50)
                .verifyComplete();

        assertEquals(1, l2CallCount.get(), "Single-flight coalesced all 50 calls into 1 non-blocking L2 query");
        assertEquals(PolicyDecision.ALLOW, l1Cache.getIfPresent(key.toHashedKey()));

        // 2. Explicitly invoke evaluate() directly inside a Schedulers.parallel() worker thread
        // to guarantee that the entire pipeline assembly, Caffeine L1 cache checks,
        // and computeIfAbsent map operations are executed on a NonBlocking thread without blocking.
        CompositePolicyKey directKey = new CompositePolicyKey("spiffe://service-bh-direct", "user-bh", "/api/direct", "GET");
        PolicyDecision directDecision = Mono.defer(() -> policyEngine.evaluate(directKey))
                .subscribeOn(Schedulers.parallel())
                .block();
        assertEquals(PolicyDecision.ALLOW, directDecision);

        // 3. Explicitly confirm BlockHound is active and catching blocking calls on Schedulers.parallel()
        StepVerifier.create(Mono.fromRunnable(() -> {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).subscribeOn(Schedulers.parallel()))
                .verifyError(BlockingOperationError.class);
    }
}
