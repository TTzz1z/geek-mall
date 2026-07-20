package com.geekmall.auth;

import com.geekmall.common.api.BusinessException;
import com.geekmall.common.domain.DistributedId;
import com.geekmall.common.security.AuthAccessKeys;
import com.geekmall.common.security.AuthPrincipal;
import com.geekmall.common.security.JwtService;
import com.geekmall.common.security.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
class AuthService {
    private final JdbcTemplate jdbc;
    private final JwtService jwtService;
    private final AuthProperties properties;
    private final StringRedisTemplate redis;

    AuthService(JdbcTemplate jdbc, JwtService jwtService, AuthProperties properties, StringRedisTemplate redis) {
        this.jdbc = jdbc;
        this.jwtService = jwtService;
        this.properties = properties;
        this.redis = redis;
    }

    @Transactional
    TokenPair login(String username, char[] password, String deviceId, String requestId) {
        UserRow user;
        try {
            user = jdbc.queryForObject("""
                    SELECT u.id, u.username, u.password_hash, u.user_type, u.status,
                           mu.merchant_id, mu.shop_id
                    FROM sys_user u
                    LEFT JOIN merchant_user mu ON mu.sys_user_id = u.id AND mu.status = 'ENABLED'
                    WHERE u.username = ?
                    """, (rs, rowNum) -> new UserRow(
                    rs.getLong("id"), rs.getString("username"), rs.getString("password_hash"),
                    rs.getString("user_type"), rs.getString("status"), nullableLong(rs, "merchant_id"),
                    nullableLong(rs, "shop_id")), username);
        } catch (EmptyResultDataAccessException exception) {
            throw new BusinessException("AU40102", "用户名或密码错误");
        }
        if (!"ENABLED".equals(user.status()) || !Pbkdf2PasswordHasher.matches(password, user.passwordHash())) {
            throw new BusinessException("AU40102", "用户名或密码错误");
        }
        AuthPrincipal principal = loadPrincipal(user);
        String refreshToken = UUID.randomUUID() + "." + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO sys_refresh_token(id, user_id, token_hash, device_id, audience, expires_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), user.id(), sha256(refreshToken), normalizeDevice(deviceId),
                principal.audience(), Timestamp.from(Instant.now().plus(properties.refreshTtl())), Timestamp.from(Instant.now()));
        jdbc.update("""
                INSERT INTO sys_operation_log(id, user_id, module, action, target_id, request_id, created_at)
                VALUES (?, ?, 'AUTH', 'LOGIN', ?, ?, ?)
                """, UUID.randomUUID().toString(), user.id(), normalizeDevice(deviceId), requestId,
                Timestamp.from(Instant.now()));
        return tokens(principal, refreshToken);
    }

    @Transactional
    TokenPair register(String username, char[] password, String deviceId, String requestId) {
        String normalized = username.trim();
        if (!normalized.matches("^[a-zA-Z][a-zA-Z0-9_]{3,31}$")) {
            throw new BusinessException("AU40001", "用户名需以字母开头，4-32 位字母数字或下划线");
        }
        if (password == null || password.length < 8 || password.length > 64) {
            throw new BusinessException("AU40002", "密码长度需为 8-64 位");
        }
        Integer exists = jdbc.queryForObject("SELECT COUNT(1) FROM sys_user WHERE username=?", Integer.class, normalized);
        if (exists != null && exists > 0) {
            throw new BusinessException("AU40901", "用户名已被注册");
        }
        long userId = DistributedId.next();
        Instant now = Instant.now();
        String hash = Pbkdf2PasswordHasher.hash(password);
        try {
            jdbc.update("""
                    INSERT INTO sys_user(id, username, password_hash, user_type, status, created_at, updated_at)
                    VALUES (?, ?, ?, 'USER', 'ENABLED', ?, ?)
                    """, userId, normalized, hash, Timestamp.from(now), Timestamp.from(now));
        } catch (DuplicateKeyException exception) {
            throw new BusinessException("AU40901", "用户名已被注册");
        }
        jdbc.update("INSERT INTO sys_user_role(user_id, role_id) VALUES (?, 30)", userId);
        jdbc.update("""
                INSERT INTO sys_operation_log(id, user_id, module, action, target_id, request_id, created_at)
                VALUES (?, ?, 'AUTH', 'REGISTER', ?, ?, ?)
                """, UUID.randomUUID().toString(), userId, normalized, requestId, Timestamp.from(now));
        return login(normalized, password, deviceId, requestId);
    }

    @Transactional
    TokenPair refresh(String refreshToken) {
        RefreshRow refresh;
        try {
            refresh = jdbc.queryForObject("""
                    SELECT rt.user_id, rt.audience, rt.expires_at, u.username, u.user_type, u.status,
                           u.password_hash, mu.merchant_id, mu.shop_id
                    FROM sys_refresh_token rt
                    JOIN sys_user u ON u.id = rt.user_id
                    LEFT JOIN merchant_user mu ON mu.sys_user_id = u.id AND mu.status = 'ENABLED'
                    WHERE rt.token_hash = ? AND rt.revoked_at IS NULL
                    """, (rs, rowNum) -> new RefreshRow(
                    rs.getLong("user_id"), rs.getString("audience"), rs.getTimestamp("expires_at").toInstant(),
                    new UserRow(rs.getLong("user_id"), rs.getString("username"), rs.getString("password_hash"),
                            rs.getString("user_type"), rs.getString("status"), nullableLong(rs, "merchant_id"),
                            nullableLong(rs, "shop_id"))), sha256(refreshToken));
        } catch (EmptyResultDataAccessException exception) {
            throw new BusinessException("AU40103", "刷新凭证无效");
        }
        if (refresh.expiresAt().isBefore(Instant.now()) || !"ENABLED".equals(refresh.user().status())) {
            throw new BusinessException("AU40103", "刷新凭证已过期");
        }
        return tokens(loadPrincipal(refresh.user()), refreshToken);
    }

    @Transactional
    void logout(String refreshToken, long userId, String requestId) {
        jdbc.update("UPDATE sys_refresh_token SET revoked_at = ? WHERE token_hash = ? AND user_id = ?",
                Timestamp.from(Instant.now()), sha256(refreshToken), userId);
        jdbc.update("""
                INSERT INTO sys_operation_log(id, user_id, module, action, request_id, created_at)
                VALUES (?, ?, 'AUTH', 'LOGOUT', ?, ?)
                """, UUID.randomUUID().toString(), userId, requestId, Timestamp.from(Instant.now()));
    }

    List<MenuItem> menus(long userId) {
        return jdbc.query("""
                SELECT DISTINCT m.id, m.parent_id, m.menu_name, m.route_path, m.icon, m.permission_code,
                                m.menu_type, m.sort_order
                FROM sys_menu m
                JOIN sys_role_menu rm ON rm.menu_id = m.id
                JOIN sys_user_role ur ON ur.role_id = rm.role_id
                WHERE ur.user_id = ? AND m.status = 'ENABLED'
                ORDER BY m.sort_order, m.id
                """, (rs, rowNum) -> new MenuItem(rs.getLong("id"), nullableLong(rs, "parent_id"),
                rs.getString("menu_name"), rs.getString("route_path"), rs.getString("icon"),
                rs.getString("permission_code"), rs.getString("menu_type")), userId);
    }

    List<UserSummary> adminUsers() {
        return jdbc.query("SELECT id, username, user_type, status FROM sys_user ORDER BY id",
                (rs, rowNum) -> new UserSummary(rs.getLong("id"), rs.getString("username"),
                        rs.getString("user_type"), rs.getString("status")));
    }

    @Transactional
    UserSummary setUserStatus(long userId, String status, long operatorId) {
        if (!"ENABLED".equals(status) && !"DISABLED".equals(status)) {
            throw new BusinessException("AU40021", "用户状态无效");
        }
        if (userId == operatorId && "DISABLED".equals(status)) {
            throw new BusinessException("AU40022", "不能停用当前登录账号");
        }
        int changed = jdbc.update("UPDATE sys_user SET status=?, updated_at=CURRENT_TIMESTAMP WHERE id=?", status, userId);
        if (changed == 0) throw new BusinessException("AU40421", "用户不存在");
        if ("DISABLED".equals(status)) {
            jdbc.update("UPDATE sys_refresh_token SET revoked_at=CURRENT_TIMESTAMP WHERE user_id=? AND revoked_at IS NULL", userId);
            redis.opsForValue().set(AuthAccessKeys.disabledUserKey(userId), "1");
        } else {
            redis.delete(AuthAccessKeys.disabledUserKey(userId));
        }
        return jdbc.query("SELECT id, username, user_type, status FROM sys_user WHERE id=?",
                (rs, rowNum) -> new UserSummary(rs.getLong("id"), rs.getString("username"),
                        rs.getString("user_type"), rs.getString("status")), userId).getFirst();
    }

    List<RoleSummary> adminRoles() {
        return jdbc.query("""
                SELECT r.id, r.role_code AS roleCode, r.role_name AS roleName, r.data_scope AS dataScope, r.status,
                       (SELECT COUNT(1) FROM sys_role_menu rm WHERE rm.role_id=r.id) AS menuCount,
                       (SELECT COUNT(1) FROM sys_user_role ur WHERE ur.role_id=r.id) AS userCount
                FROM sys_role r ORDER BY r.id
                """, (rs, rowNum) -> new RoleSummary(rs.getLong("id"), rs.getString("roleCode"), rs.getString("roleName"),
                rs.getString("dataScope"), rs.getString("status"), rs.getInt("menuCount"), rs.getInt("userCount")));
    }

    List<MenuItem> allMenus() {
        return jdbc.query("""
                SELECT id, parent_id, menu_name, route_path, icon, permission_code, menu_type
                FROM sys_menu WHERE status = 'ENABLED'
                ORDER BY sort_order, id
                """, (rs, rowNum) -> new MenuItem(rs.getLong("id"), nullableLong(rs, "parent_id"),
                rs.getString("menu_name"), rs.getString("route_path"), rs.getString("icon"),
                rs.getString("permission_code"), rs.getString("menu_type")));
    }

    List<Long> roleMenuIds(long roleId) {
        if (jdbc.queryForList("SELECT id FROM sys_role WHERE id=?", roleId).isEmpty()) {
            throw new BusinessException("AU40431", "角色不存在");
        }
        return jdbc.queryForList("SELECT menu_id FROM sys_role_menu WHERE role_id=? ORDER BY menu_id", Long.class, roleId);
    }

    @Transactional
    Map<String, Object> replaceRoleMenus(long roleId, List<Long> menuIds) {
        if (jdbc.queryForList("SELECT id FROM sys_role WHERE id=?", roleId).isEmpty()) {
            throw new BusinessException("AU40431", "角色不存在");
        }
        Set<Long> unique = new LinkedHashSet<>();
        for (Long menuId : menuIds) {
            if (menuId != null && menuId > 0) unique.add(menuId);
        }
        if (!unique.isEmpty()) {
            String placeholders = String.join(",", unique.stream().map(id -> "?").toList());
            List<Object> args = new java.util.ArrayList<>(unique);
            Integer found = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM sys_menu WHERE id IN (" + placeholders + ") AND status='ENABLED'",
                    Integer.class, args.toArray());
            if (found == null || found != unique.size()) {
                throw new BusinessException("AU40031", "存在无效或已停用菜单");
            }
        }
        jdbc.update("DELETE FROM sys_role_menu WHERE role_id=?", roleId);
        for (Long menuId : unique) {
            jdbc.update("INSERT INTO sys_role_menu(role_id, menu_id) VALUES(?, ?)", roleId, menuId);
        }
        return Map.of("roleId", roleId, "menuCount", unique.size(), "menuIds", List.copyOf(unique));
    }

    List<AuditLog> adminAuditLogs(int limit) {
        int size = Math.min(Math.max(limit, 1), 200);
        return jdbc.query("""
                SELECT id, user_id AS userId, module, action, target_id AS targetId, request_id AS requestId, created_at AS createdAt
                FROM sys_operation_log ORDER BY created_at DESC LIMIT ?
                """, (rs, rowNum) -> new AuditLog(rs.getString("id"),
                rs.getObject("userId") == null ? null : rs.getLong("userId"),
                rs.getString("module"), rs.getString("action"), rs.getString("targetId"),
                rs.getString("requestId"), rs.getTimestamp("createdAt").toInstant().toString()), size);
    }

    /**
     * DEMO ONLY：读取 geek_system.demo_merchant_order 样例行，供商家后台早期演示。
     * 真实交易订单在 geek_trade.trade_order / trade_sub_order，勿将本接口当作订单主数据源。
     */
    List<MerchantOrder> merchantOrders(Long requestedMerchantId) {
        TenantContext.assertAccessible(requestedMerchantId);
        long merchantId = TenantContext.requireMerchantId();
        return jdbc.query("""
                SELECT order_no, merchant_id, title, amount, status
                FROM demo_merchant_order WHERE merchant_id = ? ORDER BY order_no
                """, (rs, rowNum) -> new MerchantOrder(rs.getString("order_no"), rs.getLong("merchant_id"),
                rs.getString("title"), rs.getBigDecimal("amount"), rs.getString("status")), merchantId);
    }

    private TokenPair tokens(AuthPrincipal principal, String refreshToken) {
        return new TokenPair(jwtService.issue(principal, properties.accessTtl()), refreshToken,
                properties.accessTtl().toSeconds(), principal);
    }

    private AuthPrincipal loadPrincipal(UserRow user) {
        Set<String> roles = new LinkedHashSet<>(jdbc.queryForList("""
                SELECT r.role_code FROM sys_role r
                JOIN sys_user_role ur ON ur.role_id = r.id
                WHERE ur.user_id = ? AND r.status = 'ENABLED'
                """, String.class, user.id()));
        Set<String> permissions = new LinkedHashSet<>(jdbc.queryForList("""
                SELECT DISTINCT m.permission_code FROM sys_menu m
                JOIN sys_role_menu rm ON rm.menu_id = m.id
                JOIN sys_user_role ur ON ur.role_id = rm.role_id
                WHERE ur.user_id = ? AND m.status = 'ENABLED' AND m.permission_code IS NOT NULL
                """, String.class, user.id()));
        String audience = "USER".equals(user.userType()) ? "USER" : "BACKOFFICE";
        return new AuthPrincipal(user.id(), user.username(), audience, user.merchantId(), user.shopId(),
                Set.copyOf(roles), Set.copyOf(permissions));
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String normalizeDevice(String deviceId) {
        return deviceId == null || deviceId.isBlank() ? "web-unknown" : deviceId.substring(0, Math.min(64, deviceId.length()));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    record TokenPair(String accessToken, String refreshToken, long expiresIn, AuthPrincipal principal) {
    }

    record MenuItem(long id, Long parentId, String name, String path, String icon, String permission, String type) {
    }

    record UserSummary(long id, String username, String userType, String status) {
    }

    record RoleSummary(long id, String roleCode, String roleName, String dataScope, String status,
                       int menuCount, int userCount) {
    }

    record AuditLog(String id, Long userId, String module, String action, String targetId,
                    String requestId, String createdAt) {
    }

    record MerchantOrder(String orderNo, long merchantId, String title, java.math.BigDecimal amount, String status) {
    }

    private record UserRow(long id, String username, String passwordHash, String userType, String status,
                           Long merchantId, Long shopId) {
    }

    private record RefreshRow(long userId, String audience, Instant expiresAt, UserRow user) {
    }
}
