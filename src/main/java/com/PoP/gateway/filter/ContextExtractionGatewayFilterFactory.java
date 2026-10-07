package com.pop.gateway.filter;

import com.pop.gateway.model.SecurityContextExchange;
import com.pop.gateway.model.SpiffeIdentity;
import com.pop.gateway.model.UserTokenIdentity;
import com.pop.gateway.util.ErrorResponseUtil;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ContextExtractionGatewayFilterFactory extends AbstractGatewayFilterFactory<ContextExtractionGatewayFilterFactory.Config> {

    public static final String CONTEXT_KEY = "security_context_exchange";
    private static final Pattern SPIFFE_PATTERN = Pattern.compile("^spiffe://[^/]+/ns/([^/]+)/sa/([^/]+)$");
    private final MeterRegistry meterRegistry;
    private final String secretKey;
    private final com.pop.gateway.policy.RevocationService revocationService;

    public ContextExtractionGatewayFilterFactory(MeterRegistry meterRegistry, @org.springframework.beans.factory.annotation.Value("${gateway.security.secret-key}") String secretKey) {
        this(meterRegistry, secretKey, null);
    }

    public ContextExtractionGatewayFilterFactory(
            MeterRegistry meterRegistry,
            @org.springframework.beans.factory.annotation.Value("${gateway.security.secret-key}") String secretKey,
            @org.springframework.beans.factory.annotation.Autowired(required = false) com.pop.gateway.policy.RevocationService revocationService) {
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
                meterRegistry.counter("pop.security.auth.rejections", "reason", "missing_mtls").increment();
                return ErrorResponseUtil.writeProblemResponse(exchange, HttpStatus.UNAUTHORIZED, "Missing Service Identity");
            }

            String authHeader = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                meterRegistry.counter("pop.security.auth.rejections", "reason", "missing_jwt").increment();
                return ErrorResponseUtil.writeProblemResponse(exchange, HttpStatus.UNAUTHORIZED, "Missing User Token");
            }

            String token = authHeader.substring(7);
            return reactor.core.publisher.Mono.fromCallable(() -> {
                try {
                    // 1. Parse token (extract JTI for O(1) blacklist check to prevent Crypto DoS)
                    SignedJWT signedJWT = SignedJWT.parse(token);
                    String jti = signedJWT.getJWTClaimsSet().getJWTID();
                    
                    if (jti != null && revocationService != null && revocationService.isRevoked(jti)) {
                        return "revoked_jwt";
                    }
                    
                    // 2. Perform cryptographic signature verification with explicit UTF-8 charset
                    JWSVerifier verifier = new MACVerifier(secretKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    if (!signedJWT.verify(verifier)) {
                        return "invalid_signature";
                    }
                    
                    // 3. Strict claims validation (exp, sub, iss, aud)
                    java.util.Date exp = signedJWT.getJWTClaimsSet().getExpirationTime();
                    if (exp == null) {
                        return "invalid_jwt";
                    }
                    long now = System.currentTimeMillis();
                    long maxClockSkewMs = 60_000L;
                    if (now > exp.getTime() + maxClockSkewMs) {
                        return "expired_jwt";
                    }

                    String sub = signedJWT.getJWTClaimsSet().getSubject();
                    if (sub == null) {
                        return "missing_sub";
                    }

                    String iss = signedJWT.getJWTClaimsSet().getIssuer();
                    List<String> aud = signedJWT.getJWTClaimsSet().getAudience();
                    if (iss != null && iss.isBlank()) {
                        return "invalid_issuer";
                    }
                    if (aud != null && aud.isEmpty()) {
                        return "invalid_audience";
                    }

                    return new UserTokenIdentity(sub, jti, List.of());
                } catch (Exception e) {
                    return "invalid_jwt_exception";
                }
            })
            .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic())
            .flatMap(result -> {
                if (result instanceof String reason) {
                    if ("revoked_jwt".equals(reason)) {
                        meterRegistry.counter("pop.security.auth.rejections", "reason", "revoked_jwt").increment();
                        return ErrorResponseUtil.writeProblemResponse(exchange, HttpStatus.UNAUTHORIZED, "Token has been revoked");
                    } else if ("invalid_signature".equals(reason)) {
                        meterRegistry.counter("pop.security.auth.rejections", "reason", "invalid_signature").increment();
                        return ErrorResponseUtil.writeProblemResponse(exchange, HttpStatus.UNAUTHORIZED, "Invalid Token Signature");
                    } else if ("expired_jwt".equals(reason)) {
                        meterRegistry.counter("pop.security.auth.rejections", "reason", "expired_jwt").increment();
                        return ErrorResponseUtil.writeProblemResponse(exchange, HttpStatus.UNAUTHORIZED, "Token Expired");
                    } else if ("missing_sub".equals(reason)) {
                        meterRegistry.counter("pop.security.auth.rejections", "reason", "invalid_jwt").increment();
                        return ErrorResponseUtil.writeProblemResponse(exchange, HttpStatus.UNAUTHORIZED, "Missing User Subject in Token");
                    } else if ("invalid_issuer".equals(reason)) {
                        meterRegistry.counter("pop.security.auth.rejections", "reason", "invalid_claims").increment();
                        return ErrorResponseUtil.writeProblemResponse(exchange, HttpStatus.UNAUTHORIZED, "Invalid Token Issuer");
                    } else if ("invalid_audience".equals(reason)) {
                        meterRegistry.counter("pop.security.auth.rejections", "reason", "invalid_claims").increment();
                        return ErrorResponseUtil.writeProblemResponse(exchange, HttpStatus.UNAUTHORIZED, "Invalid Token Audience");
                    } else {
                        meterRegistry.counter("pop.security.auth.rejections", "reason", "invalid_jwt").increment();
                        return ErrorResponseUtil.writeProblemResponse(exchange, HttpStatus.UNAUTHORIZED, "Invalid User Token");
                    }
                }

                UserTokenIdentity userIdentity = (UserTokenIdentity) result;
                SecurityContextExchange securityContext = new SecurityContextExchange(
                        serviceIdentity,
                        userIdentity,
                        request.getMethod().name(),
                        request.getPath().value()
                );

                exchange.getAttributes().put(CONTEXT_KEY, securityContext);
                return chain.filter(exchange);
            });
        };
    }

    private SpiffeIdentity extractSpiffeIdentity(ServerWebExchange exchange) {
        if (exchange.getRequest().getSslInfo() == null || 
            exchange.getRequest().getSslInfo().getPeerCertificates() == null || 
            exchange.getRequest().getSslInfo().getPeerCertificates().length == 0) {
            return null;
        }

        X509Certificate clientCert = exchange.getRequest().getSslInfo().getPeerCertificates()[0];
        try {
            Collection<List<?>> subjectAlternativeNames = clientCert.getSubjectAlternativeNames();
            if (subjectAlternativeNames != null) {
                for (List<?> san : subjectAlternativeNames) {
                    if (san.size() >= 2 && san.getFirst() instanceof Integer type && type == 6) {
                        String uri = (String) san.getLast(); // getLast() fetches the value reliably
                        if (uri != null && uri.startsWith("spiffe://")) {
                            Matcher matcher = SPIFFE_PATTERN.matcher(uri);
                            if (matcher.matches()) {
                                return new SpiffeIdentity(uri, matcher.group(1), matcher.group(2));
                            }
                            continue;
                        }
                    }
                }
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(ContextExtractionGatewayFilterFactory.class).warn("Failed to extract SPIFFE identity from certificate", e);
            return null;
        }
        return null; 
    }

    public static class Config {}
}
