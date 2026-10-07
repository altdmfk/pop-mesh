package com.pop.gateway.policy;

import com.pop.gateway.BaseBlockHoundTest;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import reactor.test.StepVerifier;

import java.time.Duration;

import org.junit.jupiter.api.Assumptions;
import org.testcontainers.DockerClientFactory;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
        listener.init(); // Start subscription
    }

    @AfterAll
    static void teardown() {
        if (redisContainer != null && redisContainer.isRunning()) {
            redisContainer.stop();
        }
    }

    @Test
    void testRedisPubSubInvalidatesL1Cache() throws InterruptedException {
        String rawKey = "spiffe://test:user:resource:read";
        String hashedKey = sha256Hex(rawKey);
        
        // 1. Populate L1 Cache
        l1Cache.put(hashedKey, PolicyDecision.ALLOW);
        assertEquals(PolicyDecision.ALLOW, l1Cache.getIfPresent(hashedKey));

        // 2. Publish invalidation event
        StepVerifier.create(redisTemplate.convertAndSend("policy-invalidation", hashedKey))
                .expectNextCount(1)
                .verifyComplete();

        // 3. Assert eviction
        org.awaitility.Awaitility.await()
                .atMost(java.time.Duration.ofSeconds(2))
                .untilAsserted(() -> assertNull(l1Cache.getIfPresent(hashedKey), "L1 cache should be evicted"));
    }

    private static String sha256Hex(String input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) sb.append('0');
                sb.append(h);
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not supported", e);
        }
    }
}
