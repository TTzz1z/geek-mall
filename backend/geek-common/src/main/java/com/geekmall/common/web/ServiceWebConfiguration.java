package com.geekmall.common.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.ApiResponse;
import com.geekmall.common.security.InternalServiceAccess;
import com.geekmall.common.security.JwtService;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import java.nio.charset.StandardCharsets;

@Configuration
@EnableMethodSecurity
public class ServiceWebConfiguration {
    private static final Logger log = LoggerFactory.getLogger(ServiceWebConfiguration.class);

    @Bean
    JwtService serviceJwtService(@Value("${geek.auth.jwt-secret}") String secret,
                                 @Value("${geek.auth.issuer:geek-mall}") String issuer) {
        return new JwtService(secret, issuer);
    }

    @Bean
    ServiceJwtFilter serviceJwtFilter(JwtService jwtService) { return new ServiceJwtFilter(jwtService); }

    @Bean
    FilterRegistrationBean<ServiceRequestFilter> serviceRequestFilter() {
        var bean = new FilterRegistrationBean<>(new ServiceRequestFilter());
        bean.setOrder(-200);
        return bean;
    }

    @Bean
    @ConditionalOnProperty(name = "geek.internal-key")
    ApplicationRunner internalKeyStartupGuard(
            @Value("${geek.internal-key}") String internalKey,
            @Value("${geek.security.reject-placeholder-internal-key:false}") boolean rejectPlaceholder) {
        return args -> {
            if (!InternalServiceAccess.isPlaceholder(internalKey)) {
                return;
            }
            if (rejectPlaceholder) {
                throw new IllegalStateException(
                        "geek.internal-key must not be blank or '" + InternalServiceAccess.PLACEHOLDER_KEY
                                + "'. Set INTERNAL_SERVICE_KEY in compose/.env.");
            }
            log.warn("INTERNAL_SERVICE_KEY is still the documented placeholder; set a unique key before shared/public deploy "
                    + "(or enable geek.security.reject-placeholder-internal-key=true)");
        };
    }

    @Bean
    SecurityFilterChain serviceSecurityFilterChain(HttpSecurity http, ServiceJwtFilter filter,
                                                   ObjectMapper mapper) throws Exception {
        return http.csrf(csrf -> csrf.disable()).cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html",
                                "/internal/**", "/api/v1/internal/**", "/api/v1/products/internal/**", "/ws/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/payments/callback/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/products/**", "/api/v1/search/**", "/api/v1/shops/**",
                                "/api/v1/cms/pages/**", "/api/v1/marketing/ab/**", "/api/v1/marketing/seckill/sessions").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((req, res, ex) -> write(res, mapper, 401, "AU40101", "请先登录或刷新会话", req.getAttribute("requestId")))
                        .accessDeniedHandler((req, res, ex) -> write(res, mapper, 403, "AU40301", "当前角色没有此操作权限", req.getAttribute("requestId"))))
                .addFilterBefore(filter, UsernamePasswordAuthenticationFilter.class).build();
    }

    private static void write(HttpServletResponse response, ObjectMapper mapper, int status, String code,
                              String message, Object requestId) throws java.io.IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getWriter(), ApiResponse.failure(code, message, String.valueOf(requestId)));
    }
}
