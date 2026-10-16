package com.pop.gateway.policy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.pop.gateway.BaseBlockHoundTest;
import com.pop.gateway.config.GatewayConstants;
import com.pop.gateway.util.HashUtil;
import com.redis.testcontainers.RedisContainer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.testcontainers.DockerClientFactory;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CacheCoherencyIntegrationTest extends BaseBlockHoundTest {

    private static RedisContainer redisContainer;
    private static Cache<String, PolicyDecision> l1Cache;
    private static ReactiveRedisTemplate<String, String> redisTemplate;
    private static PolicyInvalidationListener listener;

    @BeforeAll
    static void setup() {
        boolean dockerAvailable = false;
        try {
            dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable ignored) {
        }
        Assumptions.assumeTrue(dockerAvailable, "Docker is not available; skipping CacheCoherencyIntegrationTest");

        redisContainer = new RedisContainer("redis:7.2-alpine");
        redisContainer.start();

        ReactiveRedisConnectionFactory factory = new LettuceConnectionFactory(
                redisContainer.getHost(), redisContainer.getFirstMappedPort());
        ((LettuceConnectionFactory) factory).afterPropertiesSet();

        RedisSerializationContext<String, String> context = RedisSerializationContext
                .<String, String>newSerializationContext(new StringRedisSerializer())
                .value(new StringRedisSerializer()).build();

        redisTemplate = new ReactiveRedisTemplate<>(factory, context);
        l1Cache = Caffeine.newBuilder().build();

        ReactiveRedisMessageListenerContainer listenerContainer = new ReactiveRedisMessageListenerContainer(factory);
        listener = new PolicyInvalidationListener(listenerContainer, l1Cache);
        listener.init();
    }

    @AfterAll
    static void teardown() {
        if (listener != null) {
            listener.destroy();
        }
        if (redisContainer != null && redisContainer.isRunning()) {
            redisContainer.stop();
        }
    }

    @Test
    void testRedisPubSubInvalidatesL1Cache() {
        String rawKey = "spiffe://test:user:resource:read";
        String hashedKey = HashUtil.sha256Hex(rawKey);

        l1Cache.put(hashedKey, PolicyDecision.ALLOW);
        assertEquals(PolicyDecision.ALLOW, l1Cache.getIfPresent(hashedKey));

        StepVerifier.create(redisTemplate.convertAndSend(GatewayConstants.CHANNEL_POLICY_INVALIDATION, hashedKey))
                .expectNextCount(1)
                .verifyComplete();

        Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertNull(l1Cache.getIfPresent(hashedKey), "L1 cache should be evicted"));
    }
}
