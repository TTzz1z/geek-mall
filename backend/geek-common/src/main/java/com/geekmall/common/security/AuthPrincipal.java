package com.geekmall.common.security;

import java.util.Set;

public record AuthPrincipal(
        long userId,
        String username,
        String audience,
        Long merchantId,
        Long shopId,
        Set<String> roles,
        Set<String> permissions
) {
    public boolean hasPermission(String permission) {
        return permissions.contains("*") || permissions.contains(permission);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
