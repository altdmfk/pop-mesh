package com.pop.gateway.model;

import java.io.Serializable;

public record CompositePolicyKey(String spiffeId, String userId, String resource, String action) implements Serializable {
    public String toHashedKey() {
        String canonical = spiffeId.length() + ":" + spiffeId + "|"
                         + userId.length() + ":" + userId + "|"
                         + resource.length() + ":" + resource + "|"
                         + action.length() + ":" + action;
        return com.pop.gateway.util.HashUtil.sha256Hex(canonical);
    }
    
    @Override
    public String toString() {
        return spiffeId + ":" + userId + ":" + resource + ":" + action;
    }
}
