package com.pop.gateway.model;

import java.util.List;

public record UserTokenIdentity(String subject, String jti, List<String> roles) implements Identity {
}
