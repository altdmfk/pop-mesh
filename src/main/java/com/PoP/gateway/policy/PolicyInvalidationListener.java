package com.pop.gateway.policy;

import com.github.benmanes.caffeine.cache.Cache;
import com.pop.gateway.config.GatewayConstants;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PolicyInvalidationListener {

    private final ReactiveRedisMessageListenerContainer listenerContainer;
    private final Cache<String, PolicyDecision> l1PolicyCache;
    private Disposable subscription;

    @PostConstruct
    public void init() {
        this.subscription = listenerContainer.receive(ChannelTopic.of(GatewayConstants.CHANNEL_POLICY_INVALIDATION))
                .map(ReactiveSubscription.Message::getMessage)
                .doOnNext(this::processInvalidation)
                .doOnError(e -> log.warn("Redis connection dropped for policy invalidation, preparing to reconnect: {}", e.getMessage()))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1)).maxBackoff(Duration.ofSeconds(30)))
                .subscribe(
                        msg -> {},
                        error -> log.error("Policy invalidation subscription terminated permanently", error),
                        () -> log.warn("Policy invalidation subscription completed unexpectedly")
                );
    }

    private void processInvalidation(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        if (message.endsWith("*")) {
            String prefix = message.substring(0, message.length() - 1);
            List<String> keys = l1PolicyCache.asMap().keySet().stream()
                    .filter(key -> key.startsWith(prefix))
                    .toList();
            l1PolicyCache.invalidateAll(keys);
        } else {
            l1PolicyCache.invalidate(message);
        }
    }

    @PreDestroy
    public void destroy() {
        if (subscription != null && !subscription.isDisposed()) {
            subscription.dispose();
        }
    }
}
