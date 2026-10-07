package com.pop.gateway.model;

public record SecurityContextExchange(Identity serviceIdentity, Identity userIdentity, String method, String path) {
}
