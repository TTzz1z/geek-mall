package com.geekmall.aftersale;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.ApiResponse;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.domain.DistributedId;
import com.geekmall.common.security.AuthPrincipal;
import com.geekmall.common.security.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1")
class AftersaleController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final TradeRefundClient refunds;
    private final TradeOrderClient tradeOrders;

    AftersaleController(JdbcTemplate jdbc, ObjectMapper mapper, TradeRefundClient refunds, TradeOrderClient tradeOrders) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.refunds = refunds;
        this.tradeOrders = tradeOrders;
    }

    @GetMapping("/aftersales")
    @PreAuthorize("hasRole('USER')")
    ApiResponse<List<Map<String, Object>>> listUser(@AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        return ok(jdbc.queryForList(
                "SELECT aftersale_no AS aftersaleNo,order_no AS orderNo,type,status,requested_amount AS requestedAmount,approved_amount AS approvedAmount,created_at AS createdAt FROM aftersale_order WHERE user_id=? ORDER BY created_at DESC",
                p.userId()), r);
    }

    @GetMapping("/merchant/aftersales")
    @PreAuthorize("hasRole('MERCHANT')")
    ApiResponse<List<Map<String, Object>>> listMerchant(HttpServletRequest r) {
        return ok(jdbc.queryForList(
                "SELECT aftersale_no AS aftersaleNo,order_no AS orderNo,type,status,requested_amount AS requestedAmount,approved_amount AS approvedAmount,created_at AS createdAt FROM aftersale_order WHERE merchant_id=? ORDER BY created_at DESC",
                TenantContext.requireMerchantId()), r);
    }

    @PostMapping("/aftersales")
    @PreAuthorize("hasRole('USER')")
    @Transactional
    ApiResponse<Map<String, Object>> apply(@Valid @RequestBody Apply b, @AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        long id = DistributedId.next();
        String no = "AS" + id;
        Map<String, Object> owned = tradeOrders.requireOwnedPaidItem(p.userId(), b.orderNo(), b.orderItemId(), b.merchantId(), b.quantity());
        // Block duplicate in-flight aftersales on the same order line (terminal states may re-apply).
        var open = jdbc.queryForList(
                "SELECT aftersale_no AS aftersaleNo FROM aftersale_order WHERE order_item_id=? AND status NOT IN ('CANCELLED','REJECTED','COMPLETED') LIMIT 1 FOR UPDATE",
                b.orderItemId());
        if (!open.isEmpty()) {
            throw new BusinessException("AS40901", "该商品已有进行中的售后单：" + open.getFirst().get("aftersaleNo"));
        }
        int purchasedQty = owned.get("quantity") instanceof Number n ? n.intValue() : b.quantity();
        Integer usedQty = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity),0) FROM aftersale_order WHERE order_item_id=? AND status NOT IN ('CANCELLED','REJECTED')",
                Integer.class, b.orderItemId());
        if (usedQty != null && usedQty + b.quantity() > purchasedQty) {
            throw new BusinessException("AS40902", "售后数量超过可申请数量");
        }
        BigDecimal maxAmount = (BigDecimal) owned.get("payableAmount");
        BigDecimal amount = b.amount() == null ? maxAmount : b.amount();
        if (amount.signum() < 0) throw new BusinessException("AS40001", "退款金额不能为负");
        if (amount.compareTo(maxAmount) > 0) throw new BusinessException("AS40002", "退款金额不能超过订单行实付金额");
        String snapshot = json(Map.of(
                "orderNo", b.orderNo(),
                "orderItemId", b.orderItemId(),
                "type", b.type(),
                "quantity", b.quantity(),
                "orderStatus", owned.get("status"),
                "title", owned.get("title")));
        jdbc.update(
                "INSERT INTO aftersale_order(id,aftersale_no,order_no,order_item_id,user_id,merchant_id,type,quantity,reason,status,requested_amount,snapshot_json,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,'APPLIED',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id, no, b.orderNo(), b.orderItemId(), p.userId(), b.merchantId(), b.type(), b.quantity(), b.reason(), amount, snapshot);
        log(id, null, "APPLIED", "USER", p.userId(), b.reason());
        return ok(detail(no, p.userId(), false), r);
    }

    @GetMapping("/aftersales/{no}")
    @PreAuthorize("hasRole('USER')")
    ApiResponse<Map<String, Object>> detailUser(@PathVariable String no, @AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        return ok(detail(no, p.userId(), false), r);
    }

    @PostMapping("/aftersales/{no}/cancel")
    @PreAuthorize("hasRole('USER')")
    @Transactional
    ApiResponse<Map<String, Object>> cancel(@PathVariable String no, @AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        transition(no, "CANCELLED", "USER", p.userId(), null, false);
        return ok(detail(no, p.userId(), false), r);
    }

    @PostMapping("/merchant/aftersales/{no}/approve")
    @PreAuthorize("hasRole('MERCHANT')")
    @Transactional
    ApiResponse<Map<String, Object>> approve(@PathVariable String no, @RequestParam(required = false) BigDecimal amount,
                                             @AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        Map<String, Object> row = merchant(no);
        String type = String.valueOf(row.get("type"));
        AftersaleStateMachine.require(String.valueOf(row.get("status")), "APPROVED");
        update(row, "APPROVED", "MERCHANT", p.userId(), null);
        if (amount != null) {
            jdbc.update("UPDATE aftersale_order SET approved_amount=? WHERE id=?", amount, row.get("id"));
            row.put("approved_amount", amount);
        } else if (row.get("approved_amount") == null && row.get("requested_amount") != null) {
            jdbc.update("UPDATE aftersale_order SET approved_amount=? WHERE id=?", row.get("requested_amount"), row.get("id"));
            row.put("approved_amount", row.get("requested_amount"));
        }
        // RETURN_REFUND stays APPROVED until user submits return logistics
        if ("REFUND_ONLY".equals(type)) {
            update(row, "REFUNDING", "SYSTEM", 0, null);
            completeRefund(row);
        } else if ("EXCHANGE".equals(type)) {
            update(row, "EXCHANGING", "SYSTEM", 0, null);
        } else if ("REPAIR".equals(type)) {
            update(row, "REPAIRING", "SYSTEM", 0, null);
        }
        return ok(detail(no, 0, true), r);
    }

    @PostMapping("/merchant/aftersales/{no}/reject")
    @PreAuthorize("hasRole('MERCHANT')")
    @Transactional
    ApiResponse<Map<String, Object>> reject(@PathVariable String no, @RequestParam @NotBlank String reason,
                                            @AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        transition(no, "REJECTED", "MERCHANT", p.userId(), reason, true);
        return ok(detail(no, 0, true), r);
    }

    @PostMapping("/merchant/aftersales/{no}/receive")
    @PreAuthorize("hasRole('MERCHANT')")
    @Transactional
    ApiResponse<Map<String, Object>> receive(@PathVariable String no, @AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        merchant(no);
        return ok(receiveWarehouse(no, "MERCHANT", p.userId()), r);
    }

    @PostMapping("/merchant/aftersales/{no}/complete")
    @PreAuthorize("hasRole('MERCHANT')")
    @Transactional
    ApiResponse<Map<String, Object>> complete(@PathVariable String no, @AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        Map<String, Object> row = merchant(no);
        AftersaleStateMachine.require(String.valueOf(row.get("status")), "COMPLETED");
        update(row, "COMPLETED", "MERCHANT", p.userId(), "履约完成");
        return ok(detail(no, 0, true), r);
    }

    @PostMapping("/aftersales/{no}/return-logistics")
    @PreAuthorize("hasRole('USER')")
    @Transactional
    ApiResponse<Map<String, Object>> returning(@PathVariable String no, @RequestParam @NotBlank String trackingNo,
                                               @AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        Map<String, Object> row = owned(no, p.userId());
        transition(row, "RETURNING", "USER", p.userId(), trackingNo);
        return ok(detail(no, p.userId(), false), r);
    }

    @PostMapping("/aftersales/{no}/arbitration")
    @PreAuthorize("hasRole('USER')")
    @Transactional
    ApiResponse<Map<String, Object>> arbitration(@PathVariable String no, @RequestParam @NotBlank String reason,
                                                 @AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        Map<String, Object> row = owned(no, p.userId());
        transition(row, "ARBITRATING", "USER", p.userId(), reason);
        jdbc.update("INSERT INTO aftersale_arbitration(id,aftersale_id,reason,status,created_at) VALUES(?,?,?,'PENDING',CURRENT_TIMESTAMP)",
                DistributedId.next(), row.get("id"), reason);
        return ok(detail(no, p.userId(), false), r);
    }

    @GetMapping("/admin/arbitrations")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS')")
    ApiResponse<List<Map<String, Object>>> arbitrations(@RequestParam(required = false) String status,
                                                        HttpServletRequest r) {
        String st = status == null || status.isBlank() ? "PENDING" : status.trim().toUpperCase();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.id, a.aftersale_id AS aftersaleId, a.reason, a.status, a.decision, a.decision_reason AS decisionReason,
                       a.operator_id AS operatorId, a.created_at AS createdAt, a.decided_at AS decidedAt,
                       o.aftersale_no AS aftersaleNo, o.order_no AS orderNo, o.type, o.requested_amount AS requestedAmount,
                       o.approved_amount AS approvedAmount, o.user_id AS userId, o.status AS aftersaleStatus
                FROM aftersale_arbitration a
                JOIN aftersale_order o ON o.id = a.aftersale_id
                WHERE (? = 'ALL' OR a.status = ?)
                ORDER BY a.created_at DESC
                LIMIT 200
                """, st, st);
        return ok(rows, r);
    }

    @PostMapping("/admin/arbitrations/{id}/decide")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Transactional
    ApiResponse<Map<String, Object>> decide(@PathVariable long id, @RequestParam @Pattern(regexp = "APPROVED|REJECTED") String decision,
                                            @RequestParam @NotBlank String reason, @AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        Map<String, Object> a = jdbc.queryForMap(
                "SELECT a.id,a.aftersale_id,o.aftersale_no,o.status,o.type,o.order_no,o.order_item_id,o.quantity,o.approved_amount,o.requested_amount FROM aftersale_arbitration a JOIN aftersale_order o ON o.id=a.aftersale_id WHERE a.id=? FOR UPDATE",
                id);
        transition(String.valueOf(a.get("aftersale_no")), decision, "PLATFORM", p.userId(), reason, true);
        jdbc.update("UPDATE aftersale_arbitration SET status='DECIDED',decision=?,decision_reason=?,operator_id=?,decided_at=CURRENT_TIMESTAMP WHERE id=?",
                decision, reason, p.userId(), id);
        if ("APPROVED".equals(decision)) {
            var row = jdbc.queryForMap("SELECT * FROM aftersale_order WHERE aftersale_no=?", a.get("aftersale_no"));
            if ("REFUND_ONLY".equals(String.valueOf(row.get("type")))) {
                update(row, "REFUNDING", "PLATFORM", p.userId(), "仲裁支持退款");
                completeRefund(row);
            } else if ("RETURN_REFUND".equals(String.valueOf(row.get("type")))) {
                // Wait for return logistics / warehouse receive before refund
            } else {
                update(row, "COMPLETED", "PLATFORM", p.userId(), "仲裁支持用户诉求");
            }
        }
        return ok(detail(String.valueOf(a.get("aftersale_no")), 0, true), r);
    }

    @PostMapping("/reviews")
    @PreAuthorize("hasRole('USER')")
    @Transactional
    ApiResponse<Map<String, Object>> review(@Valid @RequestBody Review b, @AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        long id = DistributedId.next();
        jdbc.update(
                "INSERT INTO product_review(id,order_item_id,user_id,merchant_id,score,content,anonymous,review_status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id, b.orderItemId(), p.userId(), b.merchantId(), b.score(), b.content(), b.anonymous());
        return ok(Map.of("reviewId", id, "status", "PENDING"), r);
    }

    Map<String, Object> receiveWarehouse(String no, String operatorType, long operatorId) {
        Map<String, Object> row = jdbc.queryForList("SELECT * FROM aftersale_order WHERE aftersale_no=? FOR UPDATE", no).stream()
                .findFirst()
                .orElseThrow(() -> new BusinessException("AS40401", "售后单不存在"));
        String status = String.valueOf(row.get("status"));
        if ("RECEIVED".equals(status) || "REFUNDING".equals(status) || "COMPLETED".equals(status)) {
            return detail(no, 0, true);
        }
        transition(row, "RECEIVED", operatorType, operatorId, "仓库验收通过");
        if ("RETURN_REFUND".equals(row.get("type"))) {
            update(row, "REFUNDING", "SYSTEM", 0, null);
            completeRefund(row);
        } else if (Set.of("EXCHANGE", "REPAIR").contains(String.valueOf(row.get("type")))) {
            update(row, "COMPLETED", "SYSTEM", 0, "换货/维修完成");
        }
        return detail(no, 0, true);
    }

    private void completeRefund(Map<String, Object> row) {
        BigDecimal approved = row.get("approved_amount") instanceof BigDecimal a ? a
                : row.get("approved_amount") != null ? new BigDecimal(String.valueOf(row.get("approved_amount"))) : null;
        refunds.refund(String.valueOf(row.get("order_no")), ((Number) row.get("order_item_id")).longValue(),
                String.valueOf(row.get("aftersale_no")), ((Number) row.get("quantity")).intValue(), approved);
        row.put("status", "REFUNDING");
        update(row, "COMPLETED", "SYSTEM", 0, "退款已成功");
    }

    private void transition(String no, String target, String who, long operator, String reason, boolean merchant) {
        Map<String, Object> row = merchant ? merchant(no) : owned(no, operator);
        transition(row, target, who, operator, reason);
    }

    private void transition(Map<String, Object> row, String target, String who, long operator, String reason) {
        AftersaleStateMachine.require(String.valueOf(row.get("status")), target);
        update(row, target, who, operator, reason);
    }

    private void update(Map<String, Object> row, String target, String who, long operator, String reason) {
        String from = String.valueOf(row.get("status"));
        jdbc.update("UPDATE aftersale_order SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", target, row.get("id"));
        log(((Number) row.get("id")).longValue(), from, target, who, operator, reason);
        row.put("status", target);
    }

    private Map<String, Object> owned(String no, long user) {
        var rows = jdbc.queryForList("SELECT * FROM aftersale_order WHERE aftersale_no=? AND user_id=? FOR UPDATE", no, user);
        if (rows.isEmpty()) throw new BusinessException("AS40401", "售后单不存在");
        return rows.getFirst();
    }

    private Map<String, Object> merchant(String no) {
        long merchant = TenantContext.requireMerchantId();
        var rows = jdbc.queryForList("SELECT * FROM aftersale_order WHERE aftersale_no=? AND merchant_id=? FOR UPDATE", no, merchant);
        if (rows.isEmpty()) throw new BusinessException("AS40401", "售后单不存在");
        return rows.getFirst();
    }

    private Map<String, Object> detail(String no, long user, boolean staff) {
        var rows = jdbc.queryForList(
                "SELECT aftersale_no AS aftersaleNo,order_no AS orderNo,order_item_id AS orderItemId,type,quantity,reason,status,requested_amount AS requestedAmount,approved_amount AS approvedAmount,created_at AS createdAt,updated_at AS updatedAt FROM aftersale_order WHERE aftersale_no=?"
                        + (staff ? "" : " AND user_id=?"),
                staff ? new Object[]{no} : new Object[]{no, user});
        if (rows.isEmpty()) throw new BusinessException("AS40401", "售后单不存在");
        var d = rows.getFirst();
        d.put("logs", jdbc.queryForList(
                "SELECT from_status AS fromStatus,to_status AS toStatus,operator_type AS operatorType,reason,created_at AS createdAt FROM aftersale_log WHERE aftersale_id=(SELECT id FROM aftersale_order WHERE aftersale_no=?) ORDER BY created_at",
                no));
        return d;
    }

    private void log(long id, String f, String t, String who, long op, String reason) {
        jdbc.update(
                "INSERT INTO aftersale_log(id,aftersale_id,from_status,to_status,operator_type,operator_id,reason,created_at) VALUES(?,?,?,?,?,?,?,CURRENT_TIMESTAMP)",
                DistributedId.next(), id, f, t, who, op, reason);
    }

    private String json(Object v) {
        try {
            return mapper.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> ApiResponse<T> ok(T d, HttpServletRequest r) {
        return ApiResponse.success(d, String.valueOf(r.getAttribute("requestId")));
    }

    record Apply(@NotBlank String orderNo, @NotNull Long orderItemId, @NotNull Long merchantId,
                 @Pattern(regexp = "REFUND_ONLY|RETURN_REFUND|EXCHANGE|REPAIR") String type,
                 @Min(1) int quantity, @NotBlank String reason, BigDecimal amount) {}

    record Review(@NotNull Long orderItemId, @NotNull Long merchantId, @Min(1) @Max(5) int score,
                  @NotBlank String content, boolean anonymous) {}
}
