package com.geekmall.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.ApiResponse;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.domain.DistributedId;
import com.geekmall.common.security.AuthPrincipal;
import com.geekmall.common.security.TenantContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
class SettlementController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String internalKey;
    private final BigDecimal defaultRate;
    private final AnalyticsDailyCarryJob analyticsDailyCarryJob;

    SettlementController(JdbcTemplate jdbc, ObjectMapper mapper,
                         AnalyticsDailyCarryJob analyticsDailyCarryJob,
                         @Value("${geek.internal-key}") String internalKey,
                         @Value("${geek.commission-rate:0.05}") BigDecimal defaultRate) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.analyticsDailyCarryJob = analyticsDailyCarryJob;
        this.internalKey = internalKey;
        this.defaultRate = defaultRate;
    }

    @PostMapping("/internal/settlement/order-completed")
    @Transactional
    ApiResponse<Map<String, Object>> orderCompleted(@RequestHeader(value = "X-Internal-Key", required = false) String key,
                                                    @Valid @RequestBody OrderCompleted body,
                                                    HttpServletRequest r) {
        requireInternal(key);
        lockOrderItem(body.orderItemId());
        BigDecimal rate = resolveRate(body.merchantId());
        BigDecimal commission = SettlementCalculator.commission(body.income(), rate);
        long id = DistributedId.next();
        Instant now = Instant.now();
        if (!jdbc.queryForList("SELECT id FROM settlement_pending_item WHERE order_item_id=? FOR UPDATE", body.orderItemId()).isEmpty()) {
            return ok(Map.of("status", "IDEMPOTENT", "orderItemId", body.orderItemId()), r);
        }
        BigDecimal refund = jdbc.queryForObject("""
                SELECT COALESCE(SUM(amount),0) FROM settlement_refund_application WHERE order_item_id=?
                """, BigDecimal.class, body.orderItemId());
        String status = refund.signum() > 0 && refund.compareTo(body.income()) >= 0 ? "REFUNDED" : "PENDING";
        jdbc.update("""
                INSERT INTO settlement_pending_item(id,merchant_id,order_no,order_item_id,income,commission,refund_amount,status,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """, id, body.merchantId(), body.orderNo(), body.orderItemId(), body.income(), commission,
                refund, status, Timestamp.from(now), Timestamp.from(now));
        bumpMetric("GMV", body.income());
        bumpMetric("ORDER_COUNT", BigDecimal.ONE);
        return ok(Map.of("pendingId", id, "commission", commission, "status", status), r);
    }

    @PostMapping("/internal/settlement/refund")
    @Transactional
    ApiResponse<Map<String, Object>> refundAffect(@RequestHeader(value = "X-Internal-Key", required = false) String key,
                                                  @Valid @RequestBody RefundBody body,
                                                  HttpServletRequest r) {
        requireInternal(key);
        lockOrderItem(body.orderItemId());
        var previous = jdbc.queryForList("""
                SELECT order_item_id,amount FROM settlement_refund_application WHERE refund_no=? FOR UPDATE
                """, body.refundNo());
        if (!previous.isEmpty()) {
            var applied = previous.getFirst();
            if (((Number) applied.get("order_item_id")).longValue() != body.orderItemId()
                    || ((BigDecimal) applied.get("amount")).compareTo(body.refundAmount()) != 0) {
                throw new BusinessException("ST40904", "退款编号已用于其他请求");
            }
            return ok(Map.of("status", "IDEMPOTENT", "orderItemId", body.orderItemId()), r);
        }
        jdbc.update("""
                INSERT INTO settlement_refund_application(refund_no,order_item_id,amount,created_at)
                VALUES(?,?,?,CURRENT_TIMESTAMP)
                """, body.refundNo(), body.orderItemId(), body.refundAmount());
        var rows = jdbc.queryForList("SELECT * FROM settlement_pending_item WHERE order_item_id=? FOR UPDATE", body.orderItemId());
        bumpMetric("REFUND_AMOUNT", body.refundAmount());
        if (rows.isEmpty()) {
            return ok(Map.of("status", "RECORDED", "orderItemId", body.orderItemId()), r);
        }
        Map<String, Object> row = rows.getFirst();
        BigDecimal income = (BigDecimal) row.get("income");
        BigDecimal current = (BigDecimal) row.get("refund_amount");
        BigDecimal next = SettlementCalculator.applyRefund(current, body.refundAmount(), income);
        String status = String.valueOf(row.get("status"));
        if ("SETTLED".equals(status)) {
            updateStatementRefund(body.orderItemId(), next.subtract(current));
        } else if (next.compareTo(income) >= 0) {
            status = "REFUNDED";
        }
        jdbc.update("UPDATE settlement_pending_item SET refund_amount=?, status=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                next, status, row.get("id"));
        return ok(Map.of("orderItemId", body.orderItemId(), "refundAmount", next, "status", status), r);
    }

    private void lockOrderItem(long itemId) {
        jdbc.update("""
                INSERT INTO settlement_order_item_lock(order_item_id) VALUES(?)
                ON DUPLICATE KEY UPDATE order_item_id=order_item_id
                """, itemId);
    }

    private void updateStatementRefund(long itemId, BigDecimal delta) {
        var statement = jdbc.queryForMap("""
                SELECT s.id,s.income,s.commission,s.refund,s.adjustment
                FROM settlement_statement s JOIN settlement_statement_item i ON i.statement_id=s.id
                WHERE i.source_type='ORDER_ITEM' AND i.source_id=? FOR UPDATE
                """, String.valueOf(itemId));
        BigDecimal refund = ((BigDecimal) statement.get("refund")).add(delta);
        BigDecimal payable = SettlementCalculator.payable((BigDecimal) statement.get("income"),
                (BigDecimal) statement.get("commission"), refund, (BigDecimal) statement.get("adjustment"));
        jdbc.update("UPDATE settlement_statement SET refund=?,payable=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                refund, payable, statement.get("id"));
    }

    @PostMapping("/merchant/settlements/generate")
    @PreAuthorize("hasRole('MERCHANT')")
    @Transactional
    ApiResponse<Map<String, Object>> generate(@RequestParam(required = false) String periodEnd,
                                              @AuthenticationPrincipal AuthPrincipal p,
                                              HttpServletRequest r) {
        long merchantId = TenantContext.requireMerchantId();
        LocalDate end = periodEnd == null ? LocalDate.now() : LocalDate.parse(periodEnd);
        LocalDate start = end.minusDays(6);
        var items = jdbc.queryForList("""
                SELECT * FROM settlement_pending_item
                WHERE merchant_id=? AND status='PENDING' AND created_at < ?
                FOR UPDATE
                """, merchantId, Timestamp.from(end.plusDays(1).atStartOfDay().toInstant(java.time.ZoneOffset.ofHours(8))));
        if (items.isEmpty()) {
            throw new BusinessException("ST40001", "无可结算明细");
        }
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal commission = BigDecimal.ZERO;
        BigDecimal refund = BigDecimal.ZERO;
        for (var item : items) {
            income = income.add((BigDecimal) item.get("income"));
            commission = commission.add((BigDecimal) item.get("commission"));
            refund = refund.add((BigDecimal) item.get("refund_amount"));
        }
        BigDecimal payable = SettlementCalculator.payable(income, commission, refund, BigDecimal.ZERO);
        long sid = DistributedId.next();
        String no = "ST" + sid;
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO settlement_statement(id,settlement_no,merchant_id,period_start,period_end,income,commission,refund,adjustment,payable,status,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,?,0,?, 'PENDING',?,?)
                """, sid, no, merchantId, Date.valueOf(start), Date.valueOf(end), income, commission, refund, payable,
                Timestamp.from(now), Timestamp.from(now));
        for (var item : items) {
            jdbc.update("INSERT INTO settlement_statement_item(id,statement_id,source_type,source_id,amount) VALUES(?,?,?,?,?)",
                    DistributedId.next(), sid, "ORDER_ITEM", String.valueOf(item.get("order_item_id")), item.get("income"));
            jdbc.update("UPDATE settlement_pending_item SET status='SETTLED', settle_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    item.get("id"));
        }
        return ok(statementDetail(no, merchantId, true), r);
    }

    @GetMapping("/merchant/settlements")
    @PreAuthorize("hasRole('MERCHANT')")
    ApiResponse<List<Map<String, Object>>> list(HttpServletRequest r) {
        long merchantId = TenantContext.requireMerchantId();
        return ok(jdbc.queryForList("""
                SELECT settlement_no AS settlementNo, period_start AS periodStart, period_end AS periodEnd,
                       income, commission, refund, payable, status, created_at AS createdAt
                FROM settlement_statement WHERE merchant_id=? ORDER BY created_at DESC LIMIT 50
                """, merchantId), r);
    }

    @GetMapping("/merchant/settlements/{settlementNo}")
    @PreAuthorize("hasRole('MERCHANT')")
    ApiResponse<Map<String, Object>> detail(@PathVariable String settlementNo, HttpServletRequest r) {
        return ok(statementDetail(settlementNo, TenantContext.requireMerchantId(), true), r);
    }

    @PostMapping("/merchant/settlements/{settlementNo}/confirm")
    @PreAuthorize("hasRole('MERCHANT')")
    @Transactional
    ApiResponse<Map<String, Object>> confirm(@PathVariable String settlementNo, HttpServletRequest r) {
        long merchantId = TenantContext.requireMerchantId();
        Map<String, Object> st = loadStatement(settlementNo, merchantId);
        if (!"PENDING".equals(String.valueOf(st.get("status")))) {
            throw new BusinessException("ST40002", "结算单状态不可确认");
        }
        jdbc.update("UPDATE settlement_statement SET status='CONFIRMED', confirmed_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                st.get("id"));
        return ok(statementDetail(settlementNo, merchantId, true), r);
    }

    @PostMapping("/merchant/withdrawals")
    @PreAuthorize("hasRole('MERCHANT')")
    @Transactional
    ApiResponse<Map<String, Object>> withdraw(@Valid @RequestBody WithdrawBody body, HttpServletRequest r) {
        long merchantId = TenantContext.requireMerchantId();
        BigDecimal available = availableBalance(merchantId);
        if (body.amount().compareTo(available) > 0) {
            throw new BusinessException("ST40003", "可提现余额不足");
        }
        long id = DistributedId.next();
        String no = "WD" + id;
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO withdrawal_request(id,withdrawal_no,merchant_id,amount,status,bank_snapshot,created_at,updated_at)
                VALUES(?,?,?,?,'PENDING',?,?,?)
                """, id, no, merchantId, body.amount(), body.bankSnapshot(), Timestamp.from(now), Timestamp.from(now));
        return ok(Map.of("withdrawalNo", no, "status", "PENDING", "amount", body.amount()), r);
    }

    @GetMapping("/merchant/withdrawals")
    @PreAuthorize("hasRole('MERCHANT')")
    ApiResponse<List<Map<String, Object>>> withdrawals(HttpServletRequest r) {
        long merchantId = TenantContext.requireMerchantId();
        return ok(jdbc.queryForList("""
                SELECT withdrawal_no AS withdrawalNo, amount, status, created_at AS createdAt, approved_at AS approvedAt
                FROM withdrawal_request WHERE merchant_id=? ORDER BY created_at DESC LIMIT 50
                """, merchantId), r);
    }

    @PostMapping("/admin/withdrawals/{withdrawalNo}/approve")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Transactional
    ApiResponse<Map<String, Object>> approveWithdraw(@PathVariable String withdrawalNo,
                                                     @RequestParam boolean pass,
                                                     HttpServletRequest r) {
        var rows = jdbc.queryForList("SELECT * FROM withdrawal_request WHERE withdrawal_no=? FOR UPDATE", withdrawalNo);
        if (rows.isEmpty()) throw new BusinessException("ST40401", "提现单不存在");
        String status = pass ? "APPROVED" : "REJECTED";
        jdbc.update("UPDATE withdrawal_request SET status=?, approved_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                status, rows.getFirst().get("id"));
        return ok(Map.of("withdrawalNo", withdrawalNo, "status", status), r);
    }

    @GetMapping("/admin/withdrawals")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS')")
    ApiResponse<List<Map<String, Object>>> adminWithdrawals(@RequestParam(required = false) String status,
                                                            HttpServletRequest r) {
        String sql = """
                SELECT withdrawal_no AS withdrawalNo, merchant_id AS merchantId, amount, status,
                       created_at AS createdAt, approved_at AS approvedAt
                FROM withdrawal_request
                """ + (status == null || status.isBlank() ? "" : " WHERE status=?")
                + " ORDER BY created_at DESC LIMIT 100";
        return ok(status == null || status.isBlank() ? jdbc.queryForList(sql) : jdbc.queryForList(sql, status), r);
    }

    @GetMapping("/admin/settlements")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS')")
    ApiResponse<List<Map<String, Object>>> adminSettlements(@RequestParam(required = false) String status,
                                                            HttpServletRequest r) {
        String sql = """
                SELECT settlement_no AS settlementNo, merchant_id AS merchantId,
                       period_start AS periodStart, period_end AS periodEnd,
                       income, commission, refund, adjustment, payable, status, created_at AS createdAt
                FROM settlement_statement
                """ + (status == null || status.isBlank() ? "" : " WHERE status=?")
                + " ORDER BY created_at DESC LIMIT 100";
        return ok(status == null || status.isBlank() ? jdbc.queryForList(sql) : jdbc.queryForList(sql, status), r);
    }

    @GetMapping("/merchant/deposit")
    @PreAuthorize("hasRole('MERCHANT')")
    ApiResponse<Map<String, Object>> deposit(HttpServletRequest r) {
        long merchantId = TenantContext.requireMerchantId();
        var rows = jdbc.queryForList("SELECT total_amount AS totalAmount, frozen_amount AS frozenAmount, status FROM merchant_deposit WHERE merchant_id=?", merchantId);
        if (rows.isEmpty()) {
            return ok(Map.of("totalAmount", BigDecimal.ZERO, "frozenAmount", BigDecimal.ZERO, "status", "NONE"), r);
        }
        return ok(rows.getFirst(), r);
    }

    @PostMapping("/user/invoices/titles")
    @PreAuthorize("hasRole('USER')")
    @Transactional
    ApiResponse<Map<String, Object>> saveTitle(@Valid @RequestBody TitleBody body,
                                               @AuthenticationPrincipal AuthPrincipal p,
                                               HttpServletRequest r) {
        long id = DistributedId.next();
        jdbc.update("""
                INSERT INTO invoice_title(id,user_id,title_type,title,tax_no,status,created_at)
                VALUES(?,?,?,?,?,'ACTIVE',?)
                """, id, p.userId(), body.titleType(), body.title(), body.taxNo(), Timestamp.from(Instant.now()));
        return ok(Map.of("titleId", id, "title", body.title()), r);
    }

    @PostMapping("/user/invoices")
    @PreAuthorize("hasRole('USER')")
    @Transactional
    ApiResponse<Map<String, Object>> applyInvoice(@Valid @RequestBody InvoiceBody body,
                                                  @AuthenticationPrincipal AuthPrincipal p,
                                                  HttpServletRequest r) {
        long id = DistributedId.next();
        String no = "IV" + id;
        Instant now = Instant.now();
        jdbc.update("""
                INSERT INTO invoice_application(id,application_no,user_id,order_no,title_id,amount,status,created_at,updated_at)
                VALUES(?,?,?,?,?,?,'ISSUED',?,?)
                """, id, no, p.userId(), body.orderNo(), body.titleId(), body.amount(), Timestamp.from(now), Timestamp.from(now));
        return ok(Map.of("applicationNo", no, "status", "ISSUED", "provider", "SIMULATED"), r);
    }

    @GetMapping("/user/invoices")
    @PreAuthorize("hasRole('USER')")
    ApiResponse<List<Map<String, Object>>> myInvoices(@AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        return ok(jdbc.queryForList("""
                SELECT application_no AS applicationNo, order_no AS orderNo, amount, status, created_at AS createdAt
                FROM invoice_application WHERE user_id=? ORDER BY created_at DESC LIMIT 50
                """, p.userId()), r);
    }

    @GetMapping("/user/invoices/titles")
    @PreAuthorize("hasRole('USER')")
    ApiResponse<List<Map<String, Object>>> myInvoiceTitles(@AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        return ok(jdbc.queryForList("""
                SELECT id AS titleId, title_type AS titleType, title, tax_no AS taxNo, status, created_at AS createdAt
                FROM invoice_title WHERE user_id=? AND status='ACTIVE' ORDER BY created_at DESC LIMIT 50
                """, p.userId()), r);
    }

    @GetMapping("/merchant/analytics/dashboard")
    @PreAuthorize("hasRole('MERCHANT')")
    ApiResponse<Map<String, Object>> merchantDashboard(HttpServletRequest r) {
        long merchantId = TenantContext.requireMerchantId();
        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT COALESCE(SUM(income),0) AS gmv,
                       COALESCE(SUM(refund_amount),0) AS refund,
                       COUNT(DISTINCT order_no) AS orderCount,
                       COUNT(1) AS lineCount
                FROM settlement_pending_item WHERE merchant_id=?
                """, merchantId);
        List<Map<String, Object>> byStatus = jdbc.queryForList("""
                SELECT status AS dimension, COALESCE(SUM(income),0) AS value
                FROM settlement_pending_item WHERE merchant_id=?
                GROUP BY status ORDER BY value DESC
                """, merchantId);
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("GMV", totals.get("gmv"));
        overview.put("ORDER_COUNT", totals.get("orderCount"));
        overview.put("PAID_USERS", totals.get("orderCount"));
        overview.put("REFUND", totals.get("refund"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("overview", overview);
        data.put("categories", byStatus);
        data.put("metricDate", String.valueOf(LocalDate.now()));
        data.put("carryForward", false);
        data.put("scope", "MERCHANT");
        data.put("merchantId", merchantId);
        data.put("updatedAt", Instant.now().toString());
        data.put("lineCount", totals.get("lineCount"));
        return ok(data, r);
    }

    @GetMapping("/merchant/analytics/metrics")
    @PreAuthorize("hasRole('MERCHANT')")
    ApiResponse<List<Map<String, Object>>> merchantMetrics(@RequestParam(required = false) String from,
                                                           @RequestParam(required = false) String to,
                                                           HttpServletRequest r) {
        long merchantId = TenantContext.requireMerchantId();
        LocalDate f = from == null ? LocalDate.now().minusDays(6) : LocalDate.parse(from);
        LocalDate t = to == null ? LocalDate.now() : LocalDate.parse(to);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT DATE(created_at) AS metricDate,
                       'GMV' AS metricCode,
                       'ALL' AS dimension,
                       COALESCE(SUM(income),0) AS metricValue
                FROM settlement_pending_item
                WHERE merchant_id=? AND DATE(created_at) BETWEEN ? AND ?
                GROUP BY DATE(created_at)
                UNION ALL
                SELECT DATE(created_at), 'ORDER_COUNT', 'ALL', COUNT(DISTINCT order_no)
                FROM settlement_pending_item
                WHERE merchant_id=? AND DATE(created_at) BETWEEN ? AND ?
                GROUP BY DATE(created_at)
                UNION ALL
                SELECT DATE(created_at), 'REFUND', 'ALL', COALESCE(SUM(refund_amount),0)
                FROM settlement_pending_item
                WHERE merchant_id=? AND DATE(created_at) BETWEEN ? AND ?
                GROUP BY DATE(created_at)
                ORDER BY metricDate DESC, metricCode
                """, merchantId, Date.valueOf(f), Date.valueOf(t),
                merchantId, Date.valueOf(f), Date.valueOf(t),
                merchantId, Date.valueOf(f), Date.valueOf(t));
        return ok(rows, r);
    }

    @GetMapping("/admin/analytics/dashboard")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS','PRODUCT_ADMIN')")
    ApiResponse<Map<String, Object>> dashboard(HttpServletRequest r) {
        List<Map<String, Object>> metrics = jdbc.queryForList("""
                SELECT metric_code AS metricCode, dimension, metric_value AS metricValue, metric_date AS metricDate
                FROM analytics_metric_daily WHERE metric_date=CURDATE()
                """);
        boolean carryForward = false;
        Object metricDate = java.sql.Date.valueOf(LocalDate.now());
        if (metrics.isEmpty()) {
            metrics = jdbc.queryForList("""
                    SELECT metric_code AS metricCode, dimension, metric_value AS metricValue, metric_date AS metricDate
                    FROM analytics_metric_daily
                    WHERE metric_date=(SELECT MAX(metric_date) FROM analytics_metric_daily)
                    """);
            carryForward = !metrics.isEmpty();
            if (!metrics.isEmpty()) metricDate = metrics.getFirst().get("metricDate");
        } else {
            metricDate = metrics.getFirst().get("metricDate");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        Map<String, Object> overview = new LinkedHashMap<>();
        List<Map<String, Object>> categories = new ArrayList<>();
        for (var m : metrics) {
            String code = String.valueOf(m.get("metricCode"));
            String dim = String.valueOf(m.get("dimension"));
            if ("ALL".equals(dim)) {
                overview.put(code, m.get("metricValue"));
            } else if ("GMV".equals(code)) {
                categories.add(Map.of("dimension", dim, "value", m.get("metricValue")));
            }
        }
        data.put("overview", overview);
        data.put("categories", categories);
        data.put("metricDate", String.valueOf(metricDate));
        data.put("carryForward", carryForward);
        data.put("updatedAt", Instant.now().toString());
        return ok(data, r);
    }

    @PostMapping("/admin/analytics/carry-forward")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS')")
    ApiResponse<Map<String, Object>> carryForward(HttpServletRequest r) {
        int inserted = analyticsDailyCarryJob.runOnce();
        return ok(Map.of("inserted", inserted), r);
    }

    @GetMapping("/admin/analytics/metrics")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS','PRODUCT_ADMIN')")
    ApiResponse<List<Map<String, Object>>> metrics(@RequestParam(required = false) String from,
                                                   @RequestParam(required = false) String to,
                                                   HttpServletRequest r) {
        LocalDate f = from == null ? LocalDate.now().minusDays(7) : LocalDate.parse(from);
        LocalDate t = to == null ? LocalDate.now() : LocalDate.parse(to);
        return ok(jdbc.queryForList("""
                SELECT metric_date AS metricDate, metric_code AS metricCode, dimension, metric_value AS metricValue
                FROM analytics_metric_daily WHERE metric_date BETWEEN ? AND ? ORDER BY metric_date, metric_code
                """, Date.valueOf(f), Date.valueOf(t)), r);
    }

    private BigDecimal resolveRate(long merchantId) {
        var rows = jdbc.queryForList("""
                SELECT rate FROM settlement_commission_rule
                WHERE status='ENABLED' AND (merchant_id IS NULL OR merchant_id=?)
                ORDER BY merchant_id IS NULL, id DESC LIMIT 1
                """, merchantId);
        if (rows.isEmpty()) return defaultRate;
        return (BigDecimal) rows.getFirst().get("rate");
    }

    private BigDecimal availableBalance(long merchantId) {
        BigDecimal confirmed = jdbc.queryForObject(
                "SELECT COALESCE(SUM(payable),0) FROM settlement_statement WHERE merchant_id=? AND status='CONFIRMED'",
                BigDecimal.class, merchantId);
        BigDecimal withdrawn = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount),0) FROM withdrawal_request WHERE merchant_id=? AND status IN ('PENDING','APPROVED','PAID')",
                BigDecimal.class, merchantId);
        return (confirmed == null ? BigDecimal.ZERO : confirmed)
                .subtract(withdrawn == null ? BigDecimal.ZERO : withdrawn);
    }

    private Map<String, Object> loadStatement(String no, long merchantId) {
        var rows = jdbc.queryForList("SELECT * FROM settlement_statement WHERE settlement_no=? AND merchant_id=?", no, merchantId);
        if (rows.isEmpty()) throw new BusinessException("ST40402", "结算单不存在");
        return rows.getFirst();
    }

    private Map<String, Object> statementDetail(String no, long merchantId, boolean merchantScoped) {
        Map<String, Object> st = merchantScoped ? loadStatement(no, merchantId) : loadStatementAny(no);
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("settlementNo", st.get("settlement_no"));
        d.put("periodStart", st.get("period_start"));
        d.put("periodEnd", st.get("period_end"));
        d.put("income", st.get("income"));
        d.put("commission", st.get("commission"));
        d.put("refund", st.get("refund"));
        d.put("payable", st.get("payable"));
        d.put("status", st.get("status"));
        d.put("items", jdbc.queryForList(
                "SELECT source_type AS sourceType, source_id AS sourceId, amount FROM settlement_statement_item WHERE statement_id=?",
                st.get("id")));
        return d;
    }

    private Map<String, Object> loadStatementAny(String no) {
        var rows = jdbc.queryForList("SELECT * FROM settlement_statement WHERE settlement_no=?", no);
        if (rows.isEmpty()) throw new BusinessException("ST40402", "结算单不存在");
        return rows.getFirst();
    }

    private void bumpMetric(String code, BigDecimal value) {
        long id = DistributedId.next();
        jdbc.update("""
                INSERT INTO analytics_metric_daily(id,metric_date,metric_code,dimension,metric_value,updated_at)
                VALUES(?,CURDATE(),?,'ALL',?,CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE metric_value=metric_value+VALUES(metric_value), updated_at=CURRENT_TIMESTAMP
                """, id, code, value);
    }

    private void requireInternal(String key) {
        if (key == null || !key.equals(internalKey)) {
            throw new BusinessException("ST40300", "内部调用未授权");
        }
    }

    private static <T> ApiResponse<T> ok(T d, HttpServletRequest r) {
        return ApiResponse.success(d, String.valueOf(r.getAttribute("requestId")));
    }

    record OrderCompleted(@NotNull Long merchantId, @NotBlank String orderNo, @NotNull Long orderItemId,
                          @NotNull @DecimalMin("0.01") BigDecimal income) {}
    record RefundBody(@NotBlank String refundNo, @NotNull Long orderItemId,
                      @NotNull @DecimalMin("0.01") BigDecimal refundAmount) {}
    record WithdrawBody(@NotNull @DecimalMin("0.01") BigDecimal amount, String bankSnapshot) {}
    record TitleBody(@NotBlank String titleType, @NotBlank String title, String taxNo) {}
    record InvoiceBody(@NotBlank String orderNo, @NotNull Long titleId, @NotNull @DecimalMin("0.01") BigDecimal amount) {}
}
