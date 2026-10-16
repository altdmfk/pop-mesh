package com.pop.gateway.policy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pop.gateway.config.GatewayConstants;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class L1CacheConfig {

    @Bean
    public Cache<String, PolicyDecision> l1PolicyCache() {
        return Caffeine.newBuilder()
                .maximumSize(GatewayConstants.L1_CACHE_MAX_SIZE)
                .expireAfterWrite(GatewayConstants.L1_CACHE_TTL)
                .build();
    }
}
