package com.geekmall.member;

import com.geekmall.common.api.ApiResponse;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.domain.DistributedId;
import com.geekmall.common.security.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
class MemberController {
    private final JdbcTemplate jdbc;
    private final String internalKey;

    MemberController(JdbcTemplate jdbc, @Value("${geek.internal-key}") String internalKey) {
        this.jdbc = jdbc;
        this.internalKey = internalKey;
    }

    @GetMapping("/user/member")
    @PreAuthorize("hasRole('USER')")
    ApiResponse<Map<String, Object>> profile(@AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        ensure(p.userId());
        return ok(jdbc.queryForMap(
                "SELECT user_id AS userId,level_code AS levelCode,growth_value AS growthValue,point_balance AS pointBalance,frozen_points AS frozenPoints FROM member_profile WHERE user_id=?",
                p.userId()), r);
    }

    @GetMapping("/user/points/logs")
    @PreAuthorize("hasRole('USER')")
    ApiResponse<List<Map<String, Object>>> logs(@AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        return ok(jdbc.queryForList(
                "SELECT change_type AS changeType,points,balance,frozen_balance AS frozenBalance,source_type AS sourceType,source_id AS sourceId,created_at AS createdAt FROM member_point_log WHERE user_id=? ORDER BY created_at DESC",
                p.userId()), r);
    }

    @GetMapping("/internal/member/profiles/{userId}")
    ApiResponse<Map<String, Object>> internalProfile(@RequestHeader("X-Internal-Key") String key,
                                                     @PathVariable long userId,
                                                     HttpServletRequest r) {
        requireInternal(key);
        ensure(userId);
        return ok(jdbc.queryForMap(
                "SELECT user_id AS userId,level_code AS levelCode,growth_value AS growthValue,point_balance AS pointBalance,frozen_points AS frozenPoints FROM member_profile WHERE user_id=?",
                userId), r);
    }

    @PostMapping("/internal/member/points/{orderNo}/freeze")
    @Transactional
    ApiResponse<Map<String, Object>> freeze(@RequestHeader("X-Internal-Key") String key,
                                            @PathVariable String orderNo,
                                            @RequestParam long userId,
                                            @RequestParam int points,
                                            HttpServletRequest r) {
        requireInternal(key);
        if (points <= 0) throw new BusinessException("MB40001", "积分必须为正数");
        lockOrder(orderNo, false);
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT closed FROM member_point_order_lock WHERE order_no=?", Boolean.class, orderNo))) {
            throw new BusinessException("MB40903", "订单积分预留已关闭");
        }
        ensure(userId);
        jdbc.queryForObject("SELECT user_id FROM member_profile WHERE user_id=? FOR UPDATE", Long.class, userId);
        var old = jdbc.queryForList("SELECT user_id,points,status FROM member_point_reservation WHERE order_no=? FOR UPDATE", orderNo);
        if (!old.isEmpty()) {
            var reservation = old.getFirst();
            if (((Number) reservation.get("user_id")).longValue() != userId
                    || ((Number) reservation.get("points")).intValue() != points
                    || !"FROZEN".equals(reservation.get("status"))) {
                throw new BusinessException("MB40902", "积分冻结请求与已有预留不一致");
            }
            return ok(Map.of("orderNo", orderNo, "points", points, "idempotentReplay", true), r);
        }
        int changed = jdbc.update(
                "UPDATE member_profile SET point_balance=point_balance-?,frozen_points=frozen_points+?,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND point_balance>=?",
                points, points, userId, points);
        if (changed == 0) throw new BusinessException("MB40901", "可用积分不足");
        jdbc.update(
                "INSERT INTO member_point_reservation(id,user_id,order_no,points,status,created_at,updated_at) VALUES(?,?,?,?,'FROZEN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                DistributedId.next(), userId, orderNo, points);
        log(userId, "FREEZE", -points, orderNo);
        return ok(Map.of("orderNo", orderNo, "points", points, "idempotentReplay", false), r);
    }

    @PostMapping("/internal/member/points/{orderNo}/{action:use|release|refund}")
    @Transactional
    ApiResponse<Map<String, Object>> settle(@RequestHeader("X-Internal-Key") String key,
                                            @PathVariable String orderNo,
                                            @PathVariable String action,
                                            HttpServletRequest r) {
        requireInternal(key);
        lockOrder(orderNo, "release".equals(action) || "refund".equals(action));
        var rows = jdbc.queryForList("SELECT user_id,points,status FROM member_point_reservation WHERE order_no=? FOR UPDATE", orderNo);
        if (rows.isEmpty()) {
            if ("release".equals(action) || "refund".equals(action)) {
                return ok(Map.of("orderNo", orderNo, "status", action.toUpperCase(), "idempotentReplay", true), r);
            }
            throw new BusinessException("MB40401", "积分冻结记录不存在");
        }
        var row = rows.getFirst();
        String target = switch (action) {
            case "use" -> "USED";
            case "release" -> "RELEASED";
            default -> "REFUNDED";
        };
        if (target.equals(row.get("status"))) return ok(Map.of("orderNo", orderNo, "status", target, "idempotentReplay", true), r);
        if (!"FROZEN".equals(row.get("status")) && !("REFUNDED".equals(target) && "USED".equals(row.get("status")))) {
            throw new BusinessException("MB40902", "积分状态不可转换");
        }
        long userId = ((Number) row.get("user_id")).longValue();
        int points = ((Number) row.get("points")).intValue();
        if ("USED".equals(target)) {
            jdbc.update("UPDATE member_profile SET frozen_points=frozen_points-?,updated_at=CURRENT_TIMESTAMP WHERE user_id=?", points, userId);
            log(userId, "USE", 0, orderNo);
        } else if ("REFUNDED".equals(target) && "USED".equals(row.get("status"))) {
            jdbc.update("UPDATE member_profile SET point_balance=point_balance+?,updated_at=CURRENT_TIMESTAMP WHERE user_id=?", points, userId);
            log(userId, "REFUND", points, orderNo);
        } else {
            jdbc.update("UPDATE member_profile SET frozen_points=frozen_points-?,point_balance=point_balance+?,updated_at=CURRENT_TIMESTAMP WHERE user_id=?", points, points, userId);
            log(userId, "RELEASE", points, orderNo);
        }
        jdbc.update("UPDATE member_point_reservation SET status=?,updated_at=CURRENT_TIMESTAMP WHERE order_no=?", target, orderNo);
        return ok(Map.of("orderNo", orderNo, "status", target, "idempotentReplay", false), r);
    }

    private void requireInternal(String key) {
        if (key == null || !internalKey.equals(key)) throw new BusinessException("AU40304", "内部服务凭证无效");
    }

    private void lockOrder(String orderNo, boolean close) {
        jdbc.update("INSERT INTO member_point_order_lock(order_no,closed) VALUES(?,?) ON DUPLICATE KEY UPDATE closed=closed OR VALUES(closed)", orderNo, close);
    }

    private void ensure(long userId) {
        jdbc.update("INSERT IGNORE INTO member_profile(user_id,updated_at) VALUES(?,CURRENT_TIMESTAMP)", userId);
    }

    private void log(long userId, String type, int points, String source) {
        var b = jdbc.queryForMap("SELECT point_balance,frozen_points FROM member_profile WHERE user_id=?", userId);
        jdbc.update(
                "INSERT INTO member_point_log(id,user_id,change_type,points,balance,frozen_balance,source_type,source_id,created_at) VALUES(?,?,?,?,?,?,'ORDER',?,CURRENT_TIMESTAMP)",
                DistributedId.next(), userId, type, points, b.get("point_balance"), b.get("frozen_points"), source);
    }

    private static <T> ApiResponse<T> ok(T d, HttpServletRequest r) {
        return ApiResponse.success(d, String.valueOf(r.getAttribute("requestId")));
    }
}
