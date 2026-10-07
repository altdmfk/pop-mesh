package com.pop.gateway.filter;

import com.pop.gateway.model.CompositePolicyKey;
import com.pop.gateway.model.SecurityContextExchange;
import com.pop.gateway.model.SpiffeIdentity;
import com.pop.gateway.model.UserTokenIdentity;
import com.pop.gateway.policy.PolicyDecision;
import com.pop.gateway.policy.PolicyEngine;
import com.pop.gateway.policy.RevocationService;
import com.pop.gateway.util.DownstreamTokenMinter;
import com.pop.gateway.util.ErrorResponseUtil;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public class PolicyEnforcementGatewayFilterFactory extends AbstractGatewayFilterFactory<PolicyEnforcementGatewayFilterFactory.Config> {

    private final PolicyEngine policyEngine;
    private final DownstreamTokenMinter tokenMinter;
    private final RevocationService revocationService;
    private final MeterRegistry meterRegistry;

    public PolicyEnforcementGatewayFilterFactory(PolicyEngine policyEngine, DownstreamTokenMinter tokenMinter, RevocationService revocationService, MeterRegistry meterRegistry) {
        super(Config.class);
        this.policyEngine = policyEngine;
        this.tokenMinter = tokenMinter;
        this.revocationService = revocationService;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            SecurityContextExchange context = exchange.getAttribute(ContextExtractionGatewayFilterFactory.CONTEXT_KEY);
            
            if (context == null) {
                return ErrorResponseUtil.writeProblemResponse(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "Security Context Not Found");
            }

            final String spiffeId;
            if (context.serviceIdentity() instanceof SpiffeIdentity(var id, var ns, var sa)) {
                spiffeId = id;
            } else if (context.serviceIdentity() == null) {
                return Mono.error(new IllegalStateException("Service Identity missing"));
            } else {
                return Mono.error(new IllegalStateException("Unexpected identity type: " + context.serviceIdentity().getClass()));
            }

            final String userId;
            final String jti;
            if (context.userIdentity() instanceof UserTokenIdentity(var sub, var j, var roles)) {
                userId = sub;
                jti = j;
            } else if (context.userIdentity() == null) {
                return Mono.error(new IllegalStateException("User Identity missing"));
            } else {
                return Mono.error(new IllegalStateException("Unexpected user identity type: " + context.userIdentity().getClass()));
            }

            if (revocationService.isRevoked(spiffeId) || (jti != null && revocationService.isRevoked(jti))) {
                meterRegistry.counter("pop.security.auth.rejections", "reason", "revoked").increment();
                return ErrorResponseUtil.writeProblemResponse(exchange, HttpStatus.UNAUTHORIZED, "Identity or Token has been revoked");
            }

            CompositePolicyKey policyKey = new CompositePolicyKey(
                    spiffeId,
                    userId,
                    context.path(),
                    context.method()
            );

            return policyEngine.evaluate(policyKey)
                    .flatMap(decision -> {
                        if (decision == PolicyDecision.ALLOW) {
                            return tokenMinter.mintInternalToken(spiffeId, userId)
                                .flatMap(internalToken -> {
                                    ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                                            .headers(headers -> headers.remove(HttpHeaders.AUTHORIZATION))
                                            .header("X-Internal-Identity", internalToken)
                                            .build();
                                    
                                    return chain.filter(exchange.mutate().request(mutatedRequest).build());
                                });
                        } else {
                            meterRegistry.counter("pop.security.auth.rejections", "reason", "policy_deny").increment();
                            meterRegistry.counter("pop.security.dual_identity.confused_deputy_blocks").increment();
                            return ErrorResponseUtil.writeProblemResponse(exchange, HttpStatus.FORBIDDEN, "Access Denied by Policy");
                        }
                    });
        };
    }

    public static class Config {}
}
