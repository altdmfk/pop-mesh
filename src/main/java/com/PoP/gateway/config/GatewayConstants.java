package com.pop.gateway.config;

import java.time.Duration;

public final class GatewayConstants {

    private GatewayConstants() {}

    public static final String CONTEXT_KEY = "security_context_exchange";
    public static final String HEADER_INTERNAL_IDENTITY = "X-Internal-Identity";
    public static final String BEARER_PREFIX = "Bearer ";

    public static final String CHANNEL_POLICY_INVALIDATION = "policy-invalidation";
    public static final String CHANNEL_TOKEN_REVOCATION = "token:revocation";
    public static final String REDIS_POLICY_PREFIX = "policy:";

    public static final Duration DEFAULT_CLOCK_SKEW = Duration.ofSeconds(60);
    public static final Duration INTERNAL_TOKEN_TTL = Duration.ofSeconds(60);
    public static final Duration L1_CACHE_TTL = Duration.ofMinutes(5);
    public static final long L1_CACHE_MAX_SIZE = 10_000L;
    public static final Duration REVOCATION_CACHE_TTL = Duration.ofHours(24);
    public static final long REVOCATION_CACHE_MAX_SIZE = 100_000L;
    public static final Duration L2_DEFAULT_TTL = Duration.ofHours(1);

    // Exact Prometheus metric names per README / paper.md specification
    public static final String METRIC_L1_HITS = "pop.policy.cache.l1.hits";
    public static final String METRIC_L1_MISSES = "pop.policy.cache.l1.misses";
    public static final String METRIC_L2_LATENCY = "pop.policy.cache.l2.latency";
    public static final String METRIC_SINGLEFLIGHT_DEDUPLICATED = "pop.policy.singleflight.deduplicated";
    public static final String METRIC_AUTH_REJECTIONS = "pop.security.auth.rejections";
    public static final String METRIC_CONFUSED_DEPUTY_BLOCKS = "pop.security.dual_identity.confused_deputy_blocks";
}
