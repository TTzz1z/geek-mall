package com.geekmall.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "geek.auth")
public record AuthProperties(String jwtSecret, String issuer, Duration accessTtl, Duration refreshTtl) {
}
