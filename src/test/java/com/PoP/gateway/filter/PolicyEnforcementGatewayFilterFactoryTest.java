package com.pop.gateway.filter;

import com.pop.gateway.BaseBlockHoundTest;
import com.pop.gateway.model.CompositePolicyKey;
import com.pop.gateway.model.SecurityContextExchange;
import com.pop.gateway.model.SpiffeIdentity;
import com.pop.gateway.model.UserTokenIdentity;
import com.pop.gateway.policy.PolicyDecision;
import com.pop.gateway.policy.PolicyEngine;
import com.pop.gateway.policy.RevocationService;
import com.pop.gateway.util.DownstreamTokenMinter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

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
        PolicyEnforcementGatewayFilterFactory factory = new PolicyEnforcementGatewayFilterFactory(policyEngine, tokenMinter, revocationService, meterRegistry);
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
                new UserTokenIdentity("user123", "jti123", java.util.List.of()),
                "GET",
                "/api"
        );
        exchange.getAttributes().put(ContextExtractionGatewayFilterFactory.CONTEXT_KEY, context);

        when(policyEngine.evaluate(any(CompositePolicyKey.class))).thenReturn(Mono.just(PolicyDecision.ALLOW));
        when(tokenMinter.mintInternalToken("spiffe://service-a", "user123")).thenReturn(reactor.core.publisher.Mono.just("hmac-token-123"));
        when(revocationService.isRevoked(anyString())).thenReturn(false);

        GatewayFilterChain chain = ex -> {
            // Assertions inside chain to verify mutated request
            assertFalse(ex.getRequest().getHeaders().containsKey(HttpHeaders.AUTHORIZATION));
            assertEquals("hmac-token-123", ex.getRequest().getHeaders().getFirst("X-Internal-Identity"));
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
                new UserTokenIdentity("user123", "jti123", java.util.List.of()),
                "GET",
                "/api"
        );
        exchange.getAttributes().put(ContextExtractionGatewayFilterFactory.CONTEXT_KEY, context);

        when(policyEngine.evaluate(any(CompositePolicyKey.class))).thenReturn(Mono.just(PolicyDecision.DENY));
        when(revocationService.isRevoked(anyString())).thenReturn(false);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertEquals(HttpStatus.FORBIDDEN, exchange.getResponse().getStatusCode());
    }
}
