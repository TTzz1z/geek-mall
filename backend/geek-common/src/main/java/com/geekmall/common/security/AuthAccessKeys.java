package com.geekmall.common.security;

/** Redis key helpers for immediate access revocation when a user is disabled. */
public final class AuthAccessKeys {
    public static final String DISABLED_USER_PREFIX = "auth:user:disabled:";

    private AuthAccessKeys() {}

    public static String disabledUserKey(long userId) {
        return DISABLED_USER_PREFIX + userId;
    }
}
