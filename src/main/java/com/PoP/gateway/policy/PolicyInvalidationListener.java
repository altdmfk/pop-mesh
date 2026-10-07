package com.pop.gateway.policy;

import com.github.benmanes.caffeine.cache.Cache;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.connection.ReactiveSubscription;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PolicyInvalidationListener {

    private final ReactiveRedisMessageListenerContainer listenerContainer;
    private final Cache<String, PolicyDecision> l1PolicyCache;

    @PostConstruct
    public void init() {
        listenerContainer.receive(ChannelTopic.of("policy-invalidation"))
                .map(ReactiveSubscription.Message::getMessage)
                .doOnNext(message -> {
                    if (message != null) {
                        if (message.endsWith("*")) {
                            String prefix = message.substring(0, message.length() - 1);
                            java.util.List<String> keys = l1PolicyCache.asMap().keySet().stream()
                                    .filter(key -> key.startsWith(prefix))
                                    .toList();
                            l1PolicyCache.invalidateAll(keys);
                        } else {
                            l1PolicyCache.invalidate(message);
                        }
                    }
                })
                .doOnError(e -> org.slf4j.LoggerFactory.getLogger(PolicyInvalidationListener.class).warn("Redis connection dropped, preparing to reconnect..."))
                .retryWhen(reactor.util.retry.Retry.backoff(Long.MAX_VALUE, java.time.Duration.ofSeconds(1)).maxBackoff(java.time.Duration.ofSeconds(30)))
                .subscribe(
                        null,
                        error -> org.slf4j.LoggerFactory.getLogger(PolicyInvalidationListener.class).error("Policy invalidation subscription died permanently", error),
                        () -> org.slf4j.LoggerFactory.getLogger(PolicyInvalidationListener.class).warn("Policy invalidation subscription completed unexpectedly")
                );
    }
}
