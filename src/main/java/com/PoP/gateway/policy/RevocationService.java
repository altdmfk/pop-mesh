package com.pop.gateway.policy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class RevocationService {

    private final ReactiveRedisMessageListenerContainer listenerContainer;
    
    // Fast L1 negative-cache for revoked tokens/identities.
    // Bloom filter can also be used, but Caffeine with short/medium TTL is effective for immediate revocation sync.
    private final Cache<String, Boolean> revokedCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(24)) // Keep revoked state long enough to overlap with standard token TTL
            .maximumSize(100_000)
            .build();

    @PostConstruct
    public void init() {
        listenerContainer.receive(ChannelTopic.of("token:revocation"))
                .map(ReactiveSubscription.Message::getMessage)
                .doOnNext(revokedId -> {
                    if (revokedId != null) {
                        revokedCache.put(revokedId, Boolean.TRUE);
                    }
                })
                .doOnError(e -> org.slf4j.LoggerFactory.getLogger(RevocationService.class).warn("Redis connection dropped, preparing to reconnect..."))
                .retryWhen(reactor.util.retry.Retry.backoff(Long.MAX_VALUE, java.time.Duration.ofSeconds(1)).maxBackoff(java.time.Duration.ofSeconds(30)))
                .subscribe(
                        null,
                        error -> org.slf4j.LoggerFactory.getLogger(RevocationService.class).error("Revocation subscription died permanently", error),
                        () -> org.slf4j.LoggerFactory.getLogger(RevocationService.class).warn("Revocation subscription completed unexpectedly")
                );
    }

    public boolean isRevoked(String id) {
        if (id == null) return false;
        return revokedCache.getIfPresent(id) != null;
    }
}
