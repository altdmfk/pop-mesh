package com.pop.gateway.policy;

import com.pop.gateway.config.GatewayConstants;
import com.pop.gateway.config.GatewaySecurityProperties;
import com.pop.gateway.util.HashUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.AntPathMatcher;
import reactor.core.publisher.Mono;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
public class L2CacheService {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher(":");
    private final ReactiveRedisTemplate<String, String> redisTemplate;
    private final Set<String> policyAllowlist;

    public L2CacheService(
            @Qualifier("reactiveRedisTemplate") ReactiveRedisTemplate<String, String> redisTemplate,
            @Value("${gateway.security.policy-allowlist:}") String allowlistRaw) {
        this.redisTemplate = redisTemplate;
        this.policyAllowlist = parseAllowlist(allowlistRaw);
    }

    public L2CacheService(
            ReactiveRedisTemplate<String, String> redisTemplate,
            GatewaySecurityProperties securityProperties) {
        this(redisTemplate, securityProperties != null ? securityProperties.policyAllowlist() : "");
    }

    public Mono<PolicyDecision> getPolicyDecision(String rawKeyStr) {
        if (rawKeyStr == null || rawKeyStr.isBlank()) {
            return Mono.just(PolicyDecision.DENY);
        }

        String redisKey = GatewayConstants.REDIS_POLICY_PREFIX + rawKeyStr;
        return redisTemplate.opsForValue().get(redisKey)
                .map(this::parseDecision)
                .switchIfEmpty(Mono.defer(() -> evaluateAllowlist(rawKeyStr)))
                .onErrorResume(ex -> {
                    log.warn("Redis L2 cache lookup failed for key: [{}], falling back: {}", rawKeyStr, ex.getMessage());
                    return evaluateAllowlist(rawKeyStr);
                });
    }

    public Mono<Boolean> setPolicyDecision(String rawKeyStr, PolicyDecision decision) {
        if (rawKeyStr == null || rawKeyStr.isBlank() || decision == null) {
            return Mono.just(false);
        }

        String redisKey = GatewayConstants.REDIS_POLICY_PREFIX + rawKeyStr;
        String hashedKey = HashUtil.sha256Hex(rawKeyStr);

        return redisTemplate.opsForValue().set(redisKey, decision.name(), GatewayConstants.L2_DEFAULT_TTL)
                .flatMap(success -> redisTemplate.convertAndSend(GatewayConstants.CHANNEL_POLICY_INVALIDATION, hashedKey)
                        .thenReturn(success));
    }

    private PolicyDecision parseDecision(String val) {
        try {
            return PolicyDecision.valueOf(val);
        } catch (IllegalArgumentException e) {
            return PolicyDecision.DENY;
        }
    }

    private Mono<PolicyDecision> evaluateAllowlist(String rawKeyStr) {
        boolean allowed = policyAllowlist.stream()
                .anyMatch(pattern -> PATH_MATCHER.match(pattern, rawKeyStr));
        return Mono.just(allowed ? PolicyDecision.ALLOW : PolicyDecision.DENY);
    }

    private static Set<String> parseAllowlist(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }
}
