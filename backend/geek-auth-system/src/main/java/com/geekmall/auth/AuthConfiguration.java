package com.geekmall.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.ApiResponse;
import com.geekmall.common.security.JwtService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

@Configuration
@EnableConfigurationProperties(AuthProperties.class)
class AuthConfiguration {
    @Bean
    JwtService jwtService(AuthProperties properties) {
        return new JwtService(properties.jwtSecret(), properties.issuer());
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwtFilter,
                                            ObjectMapper objectMapper) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/login/password", "/api/v1/auth/register", "/api/v1/auth/refresh",
                                "/actuator/health/**", "/actuator/info", "/actuator/prometheus",
                                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> writeError(
                                response, objectMapper, "AU40101", "请先登录或刷新会话", request.getAttribute("requestId")))
                        .accessDeniedHandler((request, response, exception) -> writeError(
                                response, objectMapper, "AU40301", "当前角色没有此操作权限", request.getAttribute("requestId"))))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }

    private static void writeError(HttpServletResponse response, ObjectMapper mapper, String code,
                                   String message, Object requestId) throws java.io.IOException {
        response.setStatus(code.startsWith("AU401") ? 401 : 403);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getWriter(), ApiResponse.failure(code, message, String.valueOf(requestId)));
    }
}
