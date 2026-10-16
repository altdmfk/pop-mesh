package com.pop.gateway.util;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.pop.gateway.config.GatewayConstants;
import com.pop.gateway.config.GatewaySecurityProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class DownstreamTokenMinter {

    private static final String DEFAULT_KID = "v1";
    private static final String AUDIENCE = "pop-internal-mesh";
    private static final String CLAIM_SPIFFE_ID = "spiffe_id";

    private final AtomicReference<ActiveKey> activeKeyRef = new AtomicReference<>();

    public DownstreamTokenMinter(@Value("${gateway.security.secret-key}") String initialSecret) throws Exception {
        rotateSecret(DEFAULT_KID, initialSecret);
    }

    public DownstreamTokenMinter(GatewaySecurityProperties properties) throws Exception {
        this(properties != null ? properties.secretKey() : "YourSuperSecretKeyForHmacGenerationMakeItLongAndSecure");
    }

    public void rotateSecret(String kid, String secretKey) throws Exception {
        if (secretKey == null || secretKey.length() < 32) {
            throw new IllegalArgumentException("Secret key must be at least 256 bits for HS256");
        }
        Objects.requireNonNull(kid, "Key ID (kid) must not be null");
        activeKeyRef.set(new ActiveKey(kid, new MACSigner(secretKey.getBytes(StandardCharsets.UTF_8))));
    }

    public Mono<String> mintInternalToken(String spiffeId, String userId) {
        return Mono.fromCallable(() -> {
            ActiveKey currentKey = activeKeyRef.get();
            if (currentKey == null) {
                throw new IllegalStateException("Secret key state is uninitialized. Deny by default.");
            }

            long now = System.currentTimeMillis();
            JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                    .subject(userId)
                    .claim(CLAIM_SPIFFE_ID, spiffeId)
                    .jwtID(UUID.randomUUID().toString())
                    .audience(AUDIENCE)
                    .issueTime(new Date(now))
                    .expirationTime(new Date(now + GatewayConstants.INTERNAL_TOKEN_TTL.toMillis()))
                    .build();

            JWSHeader header = new JWSHeader.Builder(JWSAlgorithm.HS256)
                    .keyID(currentKey.kid())
                    .build();

            SignedJWT signedJWT = new SignedJWT(header, claimsSet);
            signedJWT.sign(currentKey.signer());

            return signedJWT.serialize();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private record ActiveKey(String kid, MACSigner signer) {
        ActiveKey {
            Objects.requireNonNull(kid, "kid must not be null");
            Objects.requireNonNull(signer, "signer must not be null");
        }
    }
}
