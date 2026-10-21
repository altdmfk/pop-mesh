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
    private final ConcurrentMap<String, Mono<PolicyDecision>> inFlight;

    public PolicyEngine(Cache<String, PolicyDecision> l1PolicyCache,
                        L2CacheService l2CacheService,
                        MeterRegistry meterRegistry) {
        this(l1PolicyCache, l2CacheService, meterRegistry, new ConcurrentHashMap<>());
    }

    // Package-private constructor for deterministic testing and inFlight map lifecycle verification
    PolicyEngine(Cache<String, PolicyDecision> l1PolicyCache,
                 L2CacheService l2CacheService,
                 MeterRegistry meterRegistry,
                 ConcurrentMap<String, Mono<PolicyDecision>> inFlight) {
        this.l1PolicyCache = l1PolicyCache;
        this.l2CacheService = l2CacheService;
        this.meterRegistry = meterRegistry;
        this.l1HitsCounter = meterRegistry.counter(GatewayConstants.METRIC_L1_HITS);
        this.l1MissesCounter = meterRegistry.counter(GatewayConstants.METRIC_L1_MISSES);
        this.singleFlightDeduplicatedCounter = meterRegistry.counter(GatewayConstants.METRIC_SINGLEFLIGHT_DEDUPLICATED);
        this.l2LatencyTimer = meterRegistry.timer(GatewayConstants.METRIC_L2_LATENCY);
        this.inFlight = inFlight;
    }

    // Package-private accessor for state verification in single-flight tests
    ConcurrentMap<String, Mono<PolicyDecision>> getInFlight() {
        return inFlight;
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
                        // =========================================================================
                        // CRITICAL OPERATOR ORDERING INVARIANT: .doFinally() MUST PRECEDE .cache()
                        // =========================================================================
                        // Pipeline Topology: Upstream (L2 Redis) -> .doFinally(...) -> .cache()
                        //
                        // Why this ordering is mandatory:
                        // 1. Mono.cache() coordinates multiple downstream subscribers by maintaining
                        //    an internal coordinator and subscriber list. Crucially, it creates EXACTLY
                        //    ONE subscription to its upstream pipeline (.doFinally).
                        //
                        // 2. Normal Completion:
                        //    When the single upstream L2 query finishes, .doFinally() receives
                        //    SignalType.ON_COMPLETE exactly once. It removes the entry from inFlight,
                        //    and then .cache() replays/multicasts the result to all waiting subscribers
                        //    (the leader and any coalesced followers). Cleanup happens exactly once.
                        //
                        // 3. Leader Cancellation (e.g. client disconnects while leader is in flight):
                        //    If the leader subscriber cancels its subscription, Mono.cache() intercepts
                        //    the cancel signal and removes that subscriber from its coordinator list.
                        //    As long as other follower subscribers remain attached, Mono.cache() DOES NOT
                        //    propagate the cancel upstream. Therefore, upstream .doFinally() does NOT fire
                        //    prematurely. The in-flight query continues, followers still receive the
                        //    correct result, and inFlight is cleaned up exactly once upon completion.
                        //    (If ALL subscribers cancel, Mono.cache() propagates cancel upstream,
                        //    allowing .doFinally() to immediately purge inFlight to prevent leaks.)
                        //
                        // What would go wrong if .cache() were placed BEFORE .doFinally() instead?
                        // (.cache().doFinally(...)):
                        // 1. Operator Topology Inversion:
                        //    If .doFinally() is downstream of .cache(), it is instantiated PER SUBSCRIBER
                        //    (each subscriber receives its own DoFinallySubscriber wrapper).
                        //
                        // 2. On Normal Completion (Duplicate Removal Race):
                        //    When the upstream completes, .cache() emits ON_COMPLETE to all subscribers.
                        //    Every subscriber independently executes inFlight.remove(hashedKey).
                        //    For N coalesced requests, inFlight.remove() is invoked N times. If a new
                        //    subsequent request for the same key starts immediately, late-firing follower
                        //    doFinally callbacks can race and evict the NEW request's Mono from inFlight.
                        //
                        // 3. On Leader Cancellation (Premature Eviction & Thundering Herd Stampede Breach):
                        //    If the leader client disconnects mid-flight, its downstream DoFinallySubscriber
                        //    immediately receives CANCEL and executes inFlight.remove(hashedKey) RIGHT AWAY,
                        //    while the underlying L2 query is STILL IN FLIGHT and followers are still waiting!
                        //    - Single-Flight Failure: If a new concurrent request arrives at this moment,
                        //      it finds inFlight empty and initiates a redundant, concurrent L2 query,
                        //      defeating cache stampede protection.
                        //    - Double-Removal Race: When the original followers eventually complete, their
                        //      doFinally callbacks will fire, evicting newer valid in-flight entries.
                        //
                        // Therefore, placing .doFinally() upstream of .cache() ensures the cleanup hook is
                        // bound to the single shared upstream execution lifecycle rather than individual
                        // downstream subscriber lifecycles.
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
