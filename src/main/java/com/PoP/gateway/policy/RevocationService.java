package com.pop.gateway.policy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pop.gateway.config.GatewayConstants;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.util.retry.Retry;

import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
public class RevocationService {

    private final ReactiveRedisMessageListenerContainer listenerContainer;
    private Disposable subscription;

    private final Cache<String, Boolean> revokedCache = Caffeine.newBuilder()
            .expireAfterWrite(GatewayConstants.REVOCATION_CACHE_TTL)
            .maximumSize(GatewayConstants.REVOCATION_CACHE_MAX_SIZE)
            .build();

    @PostConstruct
    public void init() {
        this.subscription = listenerContainer.receive(ChannelTopic.of(GatewayConstants.CHANNEL_TOKEN_REVOCATION))
                .map(ReactiveSubscription.Message::getMessage)
                .doOnNext(revokedId -> {
                    if (revokedId != null && !revokedId.isBlank()) {
                        revokedCache.put(revokedId, Boolean.TRUE);
                    }
                })
                .doOnError(e -> log.warn("Redis connection dropped for revocation channel, preparing to reconnect: {}", e.getMessage()))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(30)))
                .subscribe(
                        msg -> {},
                        error -> log.error("Revocation subscription terminated permanently", error),
                        () -> log.warn("Revocation subscription completed unexpectedly")
                );
    }

    public boolean isRevoked(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        return revokedCache.getIfPresent(id) != null;
    }

    @PreDestroy
    public void destroy() {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
    }
}
