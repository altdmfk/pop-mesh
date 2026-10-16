package com.pop.gateway.filter;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.pop.gateway.BaseBlockHoundTest;
import com.pop.gateway.config.GatewayConstants;
import com.pop.gateway.model.SecurityContextExchange;
import com.pop.gateway.model.UserTokenIdentity;
import com.pop.gateway.policy.RevocationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.SslInfo;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContextExtractionGatewayFilterFactoryTest extends BaseBlockHoundTest {

    private static final String TEST_SECRET = "YourSuperSecretKeyForHmacGenerationMakeItLongAndSecure";
    private static final String SAMPLE_SPIFFE = "spiffe://cluster.local/ns/default/sa/service-a";

    private SimpleMeterRegistry meterRegistry;
    private RevocationService revocationService;
    private ContextExtractionGatewayFilterFactory factory;
    private GatewayFilter filter;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        revocationService = mock(RevocationService.class);
        factory = new ContextExtractionGatewayFilterFactory(meterRegistry, TEST_SECRET, revocationService);
        filter = factory.apply(new ContextExtractionGatewayFilterFactory.Config());
    }

    private SslInfo mockSslInfo(String spiffeUri) throws Exception {
        X509Certificate cert = mock(X509Certificate.class);
        if (spiffeUri != null) {
            when(cert.getSubjectAlternativeNames()).thenReturn(List.of(List.of(6, spiffeUri)));
        }
        SslInfo sslInfo = mock(SslInfo.class);
        when(sslInfo.getPeerCertificates()).thenReturn(new X509Certificate[]{cert});
        return sslInfo;
    }

    private MockServerHttpRequest createRequest(String path, SslInfo sslInfo, String authHeader) {
        var builder = MockServerHttpRequest.get(path);
        if (sslInfo != null) {
            builder.sslInfo(sslInfo);
        }
        if (authHeader != null) {
            builder.header(HttpHeaders.AUTHORIZATION, authHeader);
        }
        return builder.build();
    }

    private String createSignedJwt(String subject, long ttlMillis) throws Exception {
        JWSHeader header = new JWSHeader(JWSAlgorithm.HS256);
        long now = System.currentTimeMillis();
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .subject(subject)
                .expirationTime(new Date(now + ttlMillis))
                .issueTime(new Date(now))
                .build();

        SignedJWT signedJWT = new SignedJWT(header, claimsSet);
        signedJWT.sign(new MACSigner(TEST_SECRET.getBytes(StandardCharsets.UTF_8)));
        return signedJWT.serialize();
    }

    @Test
    void testMissingSslSession_ReturnsUnauthorized() {
        MockServerHttpRequest request = createRequest("/api", null, null);
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertTrue(exchange.getResponse().getHeaders().containsKey(HttpHeaders.CONTENT_TYPE));
    }

    @Test
    void testMissingUserToken_ReturnsUnauthorized() throws Exception {
        MockServerHttpRequest request = createRequest("/api", mockSslInfo(SAMPLE_SPIFFE), null);
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void testMalformedUserToken_ReturnsUnauthorized() throws Exception {
        MockServerHttpRequest request = createRequest("/api", mockSslInfo(SAMPLE_SPIFFE), "Bearer invalid.jwt.token");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void testValidContextExtraction_Success() throws Exception {
        String validToken = createSignedJwt("user123", 3600_000L);
        MockServerHttpRequest request = createRequest("/api", mockSslInfo(SAMPLE_SPIFFE), GatewayConstants.BEARER_PREFIX + validToken);
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = ex -> {
            SecurityContextExchange context = ex.getAttribute(GatewayConstants.CONTEXT_KEY);
            assertNotNull(context);
            assertEquals("/api", context.path());
            assertEquals("GET", context.method());
            assertEquals("user123", ((UserTokenIdentity) context.userIdentity()).subject());
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();
    }

    @Test
    void testTamperedToken_ReturnsUnauthorized() throws Exception {
        String validToken = createSignedJwt("user123", 3600_000L);
        String[] parts = validToken.split("\\.");
        String tamperedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"admin\"}".getBytes(StandardCharsets.UTF_8));
        String tamperedToken = parts[0] + "." + tamperedPayload + "." + parts[2];

        MockServerHttpRequest request = createRequest("/api", mockSslInfo(SAMPLE_SPIFFE), GatewayConstants.BEARER_PREFIX + tamperedToken);
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
    }

    @Test
    void testRevokedToken_ReturnsUnauthorized() throws Exception {
        JWSHeader header = new JWSHeader(JWSAlgorithm.HS256);
        long now = System.currentTimeMillis();
        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .subject("user123")
                .jwtID("revoked-jti-456")
                .expirationTime(new Date(now + 3600_000L))
                .build();
        SignedJWT signedJWT = new SignedJWT(header, claimsSet);
        signedJWT.sign(new MACSigner(TEST_SECRET.getBytes(StandardCharsets.UTF_8)));
        String revokedToken = signedJWT.serialize();

        when(revocationService.isRevoked("revoked-jti-456")).thenReturn(true);

        MockServerHttpRequest request = createRequest("/api", mockSslInfo(SAMPLE_SPIFFE), GatewayConstants.BEARER_PREFIX + revokedToken);
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        GatewayFilterChain chain = mock(GatewayFilterChain.class);

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertEquals(HttpStatus.UNAUTHORIZED, exchange.getResponse().getStatusCode());
        assertEquals(1.0, meterRegistry.counter(GatewayConstants.METRIC_AUTH_REJECTIONS, "reason", "revoked_jwt").count());
    }
}
