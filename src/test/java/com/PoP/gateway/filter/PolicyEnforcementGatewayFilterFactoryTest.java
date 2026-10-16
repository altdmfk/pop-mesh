package com.pop.gateway.filter;

import com.pop.gateway.BaseBlockHoundTest;
import com.pop.gateway.config.GatewayConstants;
import com.pop.gateway.model.CompositePolicyKey;
import com.pop.gateway.model.SecurityContextExchange;
import com.pop.gateway.model.SpiffeIdentity;
import com.pop.gateway.model.UserTokenIdentity;
import com.pop.gateway.policy.PolicyDecision;
import com.pop.gateway.policy.PolicyEngine;
import com.pop.gateway.policy.RevocationService;
import com.pop.gateway.util.DownstreamTokenMinter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PolicyEnforcementGatewayFilterFactoryTest extends BaseBlockHoundTest {

    private PolicyEngine policyEngine;
    private DownstreamTokenMinter tokenMinter;
    private RevocationService revocationService;
    private SimpleMeterRegistry meterRegistry;
    private GatewayFilter filter;

    @BeforeEach
    void setUp() {
        policyEngine = mock(PolicyEngine.class);
        tokenMinter = mock(DownstreamTokenMinter.class);
        revocationService = mock(RevocationService.class);
        meterRegistry = new SimpleMeterRegistry();
        PolicyEnforcementGatewayFilterFactory factory =
                new PolicyEnforcementGatewayFilterFactory(policyEngine, tokenMinter, revocationService, meterRegistry);
        filter = factory.apply(new PolicyEnforcementGatewayFilterFactory.Config());
    }

    @Test
    void testAllowPolicy_MintsDownstreamHeaderAndStripsAuth() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api")
                .header(HttpHeaders.AUTHORIZATION, "Bearer some-token")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        SecurityContextExchange context = new SecurityContextExchange(
                new SpiffeIdentity("spiffe://service-a", "default", "service-a"),
                new UserTokenIdentity("user123", "jti123", List.of()),
                "GET",
                "/api"
        );
        exchange.getAttributes().put(GatewayConstants.CONTEXT_KEY, context);

        when(policyEngine.evaluate(any(CompositePolicyKey.class))).thenReturn(Mono.just(PolicyDecision.ALLOW));
        when(tokenMinter.mintInternalToken("spiffe://service-a", "user123")).thenReturn(Mono.just("hmac-token-123"));
        when(revocationService.isRevoked(anyString())).thenReturn(false);

        GatewayFilterChain chain = ex -> {
            assertFalse(ex.getRequest().getHeaders().containsKey(HttpHeaders.AUTHORIZATION));
            assertEquals("hmac-token-123", ex.getRequest().getHeaders().getFirst(GatewayConstants.HEADER_INTERNAL_IDENTITY));
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    void testDenyPolicy_ReturnsForbidden() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        SecurityContextExchange context = new SecurityContextExchange(
                new SpiffeIdentity("spiffe://service-a", "default", "service-a"),
                new UserTokenIdentity("user123", "jti123", List.of()),
                "GET",
                "/api"
        );
        exchange.getAttributes().put(GatewayConstants.CONTEXT_KEY, context);

        when(policyEngine.evaluate(any(CompositePolicyKey.class))).thenReturn(Mono.just(PolicyDecision.DENY));
        when(revocationService.isRevoked(anyString())).thenReturn(false);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
        assertEquals(1.0, meterRegistry.counter(GatewayConstants.METRIC_CONFUSED_DEPUTY_BLOCKS).count());
    }
}
