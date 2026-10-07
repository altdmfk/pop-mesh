package com.pop.gateway.model;

public record SpiffeIdentity(String spiffeId, String namespace, String serviceAccount) implements Identity {
}
