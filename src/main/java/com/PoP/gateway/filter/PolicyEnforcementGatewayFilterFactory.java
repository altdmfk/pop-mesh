package com.pop.gateway.filter;

import com.pop.gateway.config.GatewayConstants;
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
import reactor.core.publisher.Mono;

@Component
public class PolicyEnforcementGatewayFilterFactory extends AbstractGatewayFilterFactory<PolicyEnforcementGatewayFilterFactory.Config> {

    private final PolicyEngine policyEngine;
    private final DownstreamTokenMinter tokenMinter;
    private final RevocationService revocationService;
    private final MeterRegistry meterRegistry;

    public PolicyEnforcementGatewayFilterFactory(PolicyEngine policyEngine,
                                                 DownstreamTokenMinter tokenMinter,
                                                 RevocationService revocationService,
                                                 MeterRegistry meterRegistry) {
        super(Config.class);
        this.policyEngine = policyEngine;
        this.tokenMinter = tokenMinter;
        this.revocationService = revocationService;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            SecurityContextExchange context = exchange.getAttribute(GatewayConstants.CONTEXT_KEY);
            if (context == null) {
                return ErrorResponseUtil.writeProblemResponse(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "Security Context Not Found");
            }

            if (!(context.serviceIdentity() instanceof SpiffeIdentity spiffe)) {
                return Mono.error(new IllegalStateException(
                        context.serviceIdentity() == null ? "Service Identity missing" : "Unexpected identity type: " + context.serviceIdentity().getClass()));
            }
            String spiffeId = spiffe.spiffeId();

            if (!(context.userIdentity() instanceof UserTokenIdentity user)) {
                return Mono.error(new IllegalStateException(
                        context.userIdentity() == null ? "User Identity missing" : "Unexpected user identity type: " + context.userIdentity().getClass()));
            }
            String userId = user.subject();
            String jti = user.jti();

            if (revocationService.isRevoked(spiffeId) || (jti != null && revocationService.isRevoked(jti))) {
                meterRegistry.counter(GatewayConstants.METRIC_AUTH_REJECTIONS, "reason", "revoked").increment();
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
                                                .header(GatewayConstants.HEADER_INTERNAL_IDENTITY, internalToken)
                                                .build();

                                        return chain.filter(exchange.mutate().request(mutatedRequest).build());
                                    });
                        }

                        meterRegistry.counter(GatewayConstants.METRIC_AUTH_REJECTIONS, "reason", "policy_deny").increment();
                        meterRegistry.counter(GatewayConstants.METRIC_CONFUSED_DEPUTY_BLOCKS).increment();
                        return ErrorResponseUtil.writeProblemResponse(exchange, HttpStatus.FORBIDDEN, "Access Denied by Policy");
                    });
        };
    }

    public static class Config {}
}
