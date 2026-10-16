package com.pop.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway.security")
public record GatewaySecurityProperties(
        String secretKey,
        String policyAllowlist
) {
    public GatewaySecurityProperties {
        if (secretKey == null || secretKey.isBlank()) {
            secretKey = "YourSuperSecretKeyForHmacGenerationMakeItLongAndSecure";
        }
        if (policyAllowlist == null) {
            policyAllowlist = "";
        }
    }
}
