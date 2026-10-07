package com.pop.gateway.policy;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;

@Service
public class L2CacheService {

    private static final org.springframework.util.AntPathMatcher PATH_MATCHER = new org.springframework.util.AntPathMatcher(":");
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final java.util.Set<String> policyAllowlist;

    public L2CacheService(
            @Qualifier("reactiveRedisTemplate") ReactiveRedisTemplate<String, String> redisTemplate,
            @org.springframework.beans.factory.annotation.Value("${gateway.security.policy-allowlist:}") String allowlistRaw) {
        this.redisTemplate = redisTemplate;
        this.policyAllowlist = java.util.Arrays.stream(allowlistRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public Mono<PolicyDecision> getPolicyDecision(String rawKeyStr) {
        return redisTemplate.opsForValue().get("policy:" + rawKeyStr)
                .map(val -> {
                    try {
                        return PolicyDecision.valueOf(val);
                    } catch (IllegalArgumentException e) {
                        return PolicyDecision.DENY;
                    }
                })
                .switchIfEmpty(Mono.defer(() -> {
                    boolean allowed = policyAllowlist.stream().anyMatch(pattern -> 
                        PATH_MATCHER.match(pattern, rawKeyStr)
                    );
                    if (allowed) {
                        return Mono.just(PolicyDecision.ALLOW);
                    }
                    return Mono.just(PolicyDecision.DENY);
                }));
    }

    public Mono<Boolean> setPolicyDecision(String rawKeyStr, PolicyDecision decision) {
        String hashedKey = com.pop.gateway.util.HashUtil.sha256Hex(rawKeyStr);
        return redisTemplate.opsForValue().set("policy:" + rawKeyStr, decision.name(), Duration.ofHours(1))
                .flatMap(success -> redisTemplate.convertAndSend("policy-invalidation", hashedKey).thenReturn(success));
    }


}
