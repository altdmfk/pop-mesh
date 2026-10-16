package com.pop.gateway.model;

import com.pop.gateway.util.HashUtil;

import java.io.Serializable;
import java.util.Objects;

public record CompositePolicyKey(String spiffeId, String userId, String resource, String action) implements Serializable {

    public CompositePolicyKey {
        Objects.requireNonNull(spiffeId, "spiffeId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(resource, "resource must not be null");
        Objects.requireNonNull(action, "action must not be null");
    }

    public String toHashedKey() {
        String canonical = spiffeId.length() + ":" + spiffeId + "|"
                         + userId.length() + ":" + userId + "|"
                         + resource.length() + ":" + resource + "|"
                         + action.length() + ":" + action;
        return HashUtil.sha256Hex(canonical);
    }
    
    @Override
    public String toString() {
        return spiffeId + ":" + userId + ":" + resource + ":" + action;
    }
}
