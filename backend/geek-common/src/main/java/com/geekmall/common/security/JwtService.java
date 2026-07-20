package com.geekmall.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class JwtService {
    private final SecretKey key;
    private final String issuer;

    public JwtService(String secret, String issuer) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("JWT secret must contain at least 32 UTF-8 bytes");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.issuer = issuer;
    }

    public String issue(AuthPrincipal principal, Duration ttl) {
        Instant now = Instant.now();
        return Jwts.builder()
                .issuer(issuer)
                .subject(Long.toString(principal.userId()))
                .audience().add(principal.audience()).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .claims(Map.of(
                        "username", principal.username(),
                        "merchantId", principal.merchantId() == null ? 0L : principal.merchantId(),
                        "shopId", principal.shopId() == null ? 0L : principal.shopId(),
                        "roles", principal.roles().stream().sorted().toList(),
                        "permissions", principal.permissions().stream().sorted().toList()
                ))
                .signWith(key)
                .compact();
    }

    public AuthPrincipal parse(String token) {
        Claims claims = Jwts.parser().verifyWith(key).requireIssuer(issuer).build()
                .parseSignedClaims(token).getPayload();
        long merchantId = claims.get("merchantId", Long.class);
        long shopId = claims.get("shopId", Long.class);
        return new AuthPrincipal(
                Long.parseLong(claims.getSubject()),
                claims.get("username", String.class),
                claims.getAudience().iterator().next(),
                merchantId == 0 ? null : merchantId,
                shopId == 0 ? null : shopId,
                stringSet(claims.get("roles", List.class)),
                stringSet(claims.get("permissions", List.class))
        );
    }

    private static Set<String> stringSet(List<?> source) {
        Set<String> result = new HashSet<>();
        if (source != null) {
            source.forEach(value -> result.add(String.valueOf(value)));
        }
        return Set.copyOf(result);
    }
}
