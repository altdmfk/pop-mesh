package com.pop.gateway.filter;

import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.pop.gateway.config.GatewayConstants;
import com.pop.gateway.config.GatewaySecurityProperties;
import com.pop.gateway.model.SecurityContextExchange;
import com.pop.gateway.model.SpiffeIdentity;
import com.pop.gateway.model.UserTokenIdentity;
import com.pop.gateway.policy.RevocationService;
import com.pop.gateway.util.ErrorResponseUtil;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.SslInfo;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class ContextExtractionGatewayFilterFactory extends AbstractGatewayFilterFactory<ContextExtractionGatewayFilterFactory.Config> {

    public static final String CONTEXT_KEY = GatewayConstants.CONTEXT_KEY;
    private static final Pattern SPIFFE_PATTERN = Pattern.compile("^spiffe://[^/]+/ns/([^/]+)/sa/([^/]+)$");
    private static final int SAN_TYPE_URI = 6;
    private static final String SPIFFE_PREFIX = "spiffe://";

    private final MeterRegistry meterRegistry;
    private final String secretKey;
    private final RevocationService revocationService;

    public ContextExtractionGatewayFilterFactory(MeterRegistry meterRegistry,
                                                 @Value("${gateway.security.secret-key}") String secretKey) {
        this(meterRegistry, secretKey, null);
    }

    public ContextExtractionGatewayFilterFactory(MeterRegistry meterRegistry,
                                                 GatewaySecurityProperties properties,
                                                 @Autowired(required = false) RevocationService revocationService) {
        this(meterRegistry, properties != null ? properties.secretKey() : "YourSuperSecretKeyForHmacGenerationMakeItLongAndSecure", revocationService);
    }

    public ContextExtractionGatewayFilterFactory(
            MeterRegistry meterRegistry,
            @Value("${gateway.security.secret-key}") String secretKey,
            @Autowired(required = false) RevocationService revocationService) {
        super(Config.class);
        this.meterRegistry = meterRegistry;
        this.secretKey = secretKey;
        this.revocationService = revocationService;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();

            SpiffeIdentity serviceIdentity = extractSpiffeIdentity(exchange);
            if (serviceIdentity == null) {
                meterRegistry.counter(GatewayConstants.METRIC_AUTH_REJECTIONS, "reason", "missing_mtls").increment();
                return ErrorResponseUtil.writeProblemResponse(exchange, HttpStatus.UNAUTHORIZED, "Missing Service Identity");
            }

            String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (authHeader == null || !authHeader.startsWith(GatewayConstants.BEARER_PREFIX)) {
                meterRegistry.counter(GatewayConstants.METRIC_AUTH_REJECTIONS, "reason", "missing_jwt").increment();
                return ErrorResponseUtil.writeProblemResponse(exchange, HttpStatus.UNAUTHORIZED, "Missing User Token");
            }

            String token = authHeader.substring(GatewayConstants.BEARER_PREFIX.length());
            return Mono.fromCallable(() -> validateToken(token))
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(result -> switch (result) {
                        case TokenValidationResult.Failure(var metricReason, var problemDetail) -> {
                            meterRegistry.counter(GatewayConstants.METRIC_AUTH_REJECTIONS, "reason", metricReason).increment();
                            yield ErrorResponseUtil.writeProblemResponse(exchange, HttpStatus.UNAUTHORIZED, problemDetail);
                        }
                        case TokenValidationResult.Success(var userIdentity) -> {
                            SecurityContextExchange securityContext = new SecurityContextExchange(
                                    serviceIdentity,
                                    userIdentity,
                                    request.getMethod().name(),
                                    request.getPath().value()
                            );
                            exchange.getAttributes().put(CONTEXT_KEY, securityContext);
                            yield chain.filter(exchange);
                        }
                    });
        };
    }

    private TokenValidationResult validateToken(String token) {
        try {
            SignedJWT signedJWT = SignedJWT.parse(token);
            String jti = signedJWT.getJWTClaimsSet().getJWTID();

            if (jti != null && revocationService != null && revocationService.isRevoked(jti)) {
                return new TokenValidationResult.Failure("revoked_jwt", "Token has been revoked");
            }

            JWSVerifier verifier = new MACVerifier(secretKey.getBytes(StandardCharsets.UTF_8));
            if (!signedJWT.verify(verifier)) {
                return new TokenValidationResult.Failure("invalid_signature", "Invalid Token Signature");
            }

            Date exp = signedJWT.getJWTClaimsSet().getExpirationTime();
            if (exp == null) {
                return new TokenValidationResult.Failure("invalid_jwt", "Invalid User Token");
            }

            long now = System.currentTimeMillis();
            if (now > exp.getTime() + GatewayConstants.DEFAULT_CLOCK_SKEW.toMillis()) {
                return new TokenValidationResult.Failure("expired_jwt", "Token Expired");
            }

            String sub = signedJWT.getJWTClaimsSet().getSubject();
            if (sub == null) {
                return new TokenValidationResult.Failure("invalid_jwt", "Missing User Subject in Token");
            }

            String iss = signedJWT.getJWTClaimsSet().getIssuer();
            List<String> aud = signedJWT.getJWTClaimsSet().getAudience();
            if (iss != null && iss.isBlank()) {
                return new TokenValidationResult.Failure("invalid_claims", "Invalid Token Issuer");
            }
            if (aud != null && aud.isEmpty()) {
                return new TokenValidationResult.Failure("invalid_claims", "Invalid Token Audience");
            }

            return new TokenValidationResult.Success(new UserTokenIdentity(sub, jti, List.of()));
        } catch (Exception e) {
            return new TokenValidationResult.Failure("invalid_jwt", "Invalid User Token");
        }
    }

    private SpiffeIdentity extractSpiffeIdentity(ServerWebExchange exchange) {
        SslInfo sslInfo = exchange.getRequest().getSslInfo();
        if (sslInfo == null || sslInfo.getPeerCertificates() == null || sslInfo.getPeerCertificates().length == 0) {
            return null;
        }

        X509Certificate clientCert = sslInfo.getPeerCertificates()[0];
        try {
            Collection<List<?>> sans = clientCert.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> san : sans) {
                    if (san.size() >= 2 && san.getFirst() instanceof Integer type && type == SAN_TYPE_URI) {
                        if (san.getLast() instanceof String uri && uri.startsWith(SPIFFE_PREFIX)) {
                            Matcher matcher = SPIFFE_PATTERN.matcher(uri);
                            if (matcher.matches()) {
                                return new SpiffeIdentity(uri, matcher.group(1), matcher.group(2));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract SPIFFE identity from certificate: {}", e.getMessage());
        }
        return null;
    }

    private sealed interface TokenValidationResult {
        record Success(UserTokenIdentity identity) implements TokenValidationResult {}
        record Failure(String metricReason, String problemDetail) implements TokenValidationResult {}
    }

    public static class Config {}
}
