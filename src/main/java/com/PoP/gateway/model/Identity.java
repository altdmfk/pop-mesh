package com.pop.gateway.model;

public sealed interface Identity permits SpiffeIdentity, UserTokenIdentity {
}
