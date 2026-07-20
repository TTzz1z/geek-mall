package com.geekmall.auth;

import com.geekmall.common.api.ApiResponse;
import com.geekmall.common.security.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
class AuthController {
    private final AuthService authService;

    AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/auth/login/password")
    ApiResponse<AuthService.TokenPair> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        String deviceId = request.getHeader("X-Device-Id");
        return ApiResponse.success(authService.login(body.username(), body.password().toCharArray(), deviceId,
                requestId(request)), requestId(request));
    }

    @PostMapping("/auth/register")
    ApiResponse<AuthService.TokenPair> register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request) {
        String deviceId = request.getHeader("X-Device-Id");
        return ApiResponse.success(authService.register(body.username(), body.password().toCharArray(), deviceId,
                requestId(request)), requestId(request));
    }

    @PostMapping("/auth/refresh")
    ApiResponse<AuthService.TokenPair> refresh(@Valid @RequestBody RefreshRequest body, HttpServletRequest request) {
        return ApiResponse.success(authService.refresh(body.refreshToken()), requestId(request));
    }

    @PostMapping("/auth/logout")
    ApiResponse<Void> logout(@Valid @RequestBody RefreshRequest body,
                             @AuthenticationPrincipal AuthPrincipal principal, HttpServletRequest request) {
        authService.logout(body.refreshToken(), principal.userId(), requestId(request));
        return ApiResponse.success(null, requestId(request));
    }

    @GetMapping("/auth/session")
    ApiResponse<AuthPrincipal> session(@AuthenticationPrincipal AuthPrincipal principal, HttpServletRequest request) {
        return ApiResponse.success(principal, requestId(request));
    }

    @GetMapping("/admin/menus")
    ApiResponse<List<AuthService.MenuItem>> menus(@AuthenticationPrincipal AuthPrincipal principal,
                                                   HttpServletRequest request) {
        return ApiResponse.success(authService.menus(principal.userId()), requestId(request));
    }

    @GetMapping("/admin/menus/catalog")
    @PreAuthorize("@rbac.has('system:user:view')")
    ApiResponse<List<AuthService.MenuItem>> menuCatalog(HttpServletRequest request) {
        return ApiResponse.success(authService.allMenus(), requestId(request));
    }

    @GetMapping("/admin/users")
    @PreAuthorize("@rbac.has('system:user:view')")
    ApiResponse<List<AuthService.UserSummary>> users(HttpServletRequest request) {
        return ApiResponse.success(authService.adminUsers(), requestId(request));
    }

    @PostMapping("/admin/users/{id}/status")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    ApiResponse<AuthService.UserSummary> setUserStatus(@PathVariable long id,
                                                       @RequestParam String status,
                                                       @AuthenticationPrincipal AuthPrincipal principal,
                                                       HttpServletRequest request) {
        return ApiResponse.success(authService.setUserStatus(id, status, principal.userId()), requestId(request));
    }

    @GetMapping("/admin/roles")
    @PreAuthorize("@rbac.has('system:user:view')")
    ApiResponse<List<AuthService.RoleSummary>> roles(HttpServletRequest request) {
        return ApiResponse.success(authService.adminRoles(), requestId(request));
    }

    @GetMapping("/admin/roles/{id}/menus")
    @PreAuthorize("@rbac.has('system:user:view')")
    ApiResponse<List<Long>> roleMenus(@PathVariable long id, HttpServletRequest request) {
        return ApiResponse.success(authService.roleMenuIds(id), requestId(request));
    }

    @PutMapping("/admin/roles/{id}/menus")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    ApiResponse<Map<String, Object>> replaceRoleMenus(@PathVariable long id,
                                                      @RequestBody List<Long> menuIds,
                                                      HttpServletRequest request) {
        return ApiResponse.success(authService.replaceRoleMenus(id, menuIds == null ? List.of() : menuIds),
                requestId(request));
    }

    @GetMapping("/admin/audit-logs")
    @PreAuthorize("@rbac.has('system:user:view')")
    ApiResponse<List<AuthService.AuditLog>> auditLogs(@RequestParam(required = false) Integer limit,
                                                      HttpServletRequest request) {
        return ApiResponse.success(authService.adminAuditLogs(limit == null ? 100 : limit), requestId(request));
    }

    @GetMapping("/merchant/demo/orders")
    @PreAuthorize("@rbac.has('merchant:order:view')")
    ApiResponse<List<AuthService.MerchantOrder>> merchantOrders(
            @RequestParam(required = false) Long merchantId, HttpServletRequest request) {
        return ApiResponse.success(authService.merchantOrders(merchantId), requestId(request));
    }

    private static String requestId(HttpServletRequest request) {
        return String.valueOf(request.getAttribute("requestId"));
    }

    record LoginRequest(@NotBlank @Size(max = 64) String username,
                        @NotBlank @Size(min = 8, max = 128) String password) {
    }

    record RegisterRequest(@NotBlank @Size(min = 4, max = 32) String username,
                           @NotBlank @Size(min = 8, max = 64) String password) {
    }

    record RefreshRequest(@NotBlank @Size(max = 256) String refreshToken) {
    }
}
