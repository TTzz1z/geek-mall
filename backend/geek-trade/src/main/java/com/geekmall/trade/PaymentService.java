package com.geekmall.trade;

import com.geekmall.common.api.BusinessException;
import com.geekmall.common.domain.DistributedId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@Service
class PaymentService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final UnavailableRealPaymentAdapters adapters;
    private final InventoryClient inventory;
    private final TradeEventStore events;
    private final TradeTasks tasks;
    private final String merchantNo;
    private final String secret;

    PaymentService(JdbcTemplate jdbc, PlatformTransactionManager manager,
                   UnavailableRealPaymentAdapters adapters, InventoryClient inventory,
                   TradeEventStore events, TradeTasks tasks,
                   @Value("${geek.payment.merchant-no}") String merchantNo,
                   @Value("${geek.payment.callback-secret}") String secret) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.adapters = adapters;
        this.inventory = inventory;
        this.events = events;
        this.tasks = tasks;
        this.merchantNo = merchantNo;
        this.secret = secret;
    }

    Map<String, Object> create(long userId, PaymentController.CreatePayment request) {
        PaymentChannelAdapter adapter = adapters.require(request.channel().toUpperCase(Locale.ROOT));
        Map<String, Object> order = jdbc.queryForMap(
                "SELECT id,payable_amount,status,paid_payment_id FROM trade_order WHERE order_no=? AND user_id=?",
                request.orderNo(), userId);
        if (!"PENDING_PAYMENT".equals(order.get("status"))) {
            throw new BusinessException("TR40909", "当前订单不可发起支付");
        }
        String paymentNo = "PAY" + DistributedId.next();
        long id = DistributedId.next();
        jdbc.update("""
                INSERT INTO trade_payment(id,payment_no,order_id,user_id,channel,merchant_no,amount,status,created_at,updated_at)
                VALUES(?,?,?,?,?,?,?,'PENDING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """, id, paymentNo, order.get("id"), userId, adapter.channel(), merchantNo, order.get("payable_amount"));
        Map<String, Object> result = new LinkedHashMap<>(adapter.create(paymentNo));
        result.put("amount", order.get("payable_amount"));
        result.put("channel", adapter.channel());
        return result;
    }

    Map<String, Object> payment(long userId, String no) {
        var rows = jdbc.queryForList("""
                SELECT p.payment_no AS paymentNo,o.order_no AS orderNo,p.channel,p.amount,p.status,
                       p.channel_trade_no AS channelTradeNo,p.created_at AS createdAt,p.paid_at AS paidAt
                FROM trade_payment p JOIN trade_order o ON o.id=p.order_id
                WHERE p.payment_no=? AND p.user_id=?
                """, no, userId);
        if (rows.isEmpty()) {
            throw new BusinessException("TR40405", "支付单不存在");
        }
        return rows.getFirst();
    }

    Map<String, Object> simulate(long userId, String no) {
        Map<String, Object> payment = payment(userId, no);
        if (!"SIMULATED".equals(payment.get("channel"))) {
            throw new BusinessException("TR40910", "非模拟支付单不能执行模拟付款");
        }
        PaymentController.Callback body = new PaymentController.Callback(
                UUID.randomUUID().toString(), no, merchantNo, (BigDecimal) payment.get("amount"),
                "SUCCESS", "MOCK-" + DistributedId.next());
        return callback("SIMULATED", sign(canonical("SIMULATED", body)), body);
    }

    Map<String, Object> callback(String channel, String signature, PaymentController.Callback body) {
        String canonical = canonical(channel, body);
        if (!secureEquals(sign(canonical), signature)) {
            throw new BusinessException("TR40301", "支付回调签名无效");
        }
        if (!merchantNo.equals(body.merchantNo())) {
            throw new BusinessException("TR40302", "支付回调商户号无效");
        }
        String hash = CheckoutService.sha256(canonical);
        CallbackResult result = tx.execute(status -> process(channel.toUpperCase(Locale.ROOT), body, hash));
        return Map.of(
                "paymentNo", body.paymentNo(),
                "orderNo", Objects.requireNonNullElse(result.orderNo(), ""),
                "status", result.status(),
                "idempotentReplay", result.replay());
    }

    void createFulfillments(String orderNo) {
        long userId = jdbc.queryForObject("SELECT user_id FROM trade_order WHERE order_no=?", Long.class, orderNo);
        var plans = jdbc.queryForList("""
                SELECT fp.warehouse_id AS warehouseId,fp.sku_id AS skuId,fp.quantity,
                       s.merchant_id AS merchantId,i.id AS orderItemId
                FROM trade_fulfillment_plan fp
                JOIN trade_order_item i ON i.sub_order_id=fp.sub_order_id AND i.sku_id=fp.sku_id
                JOIN trade_sub_order s ON s.id=i.sub_order_id
                JOIN trade_order o ON o.id=fp.order_id
                WHERE o.order_no=?
                """, orderNo);
        Map<Long, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        for (var p : plans) {
            long wh = ((Number) p.get("warehouseId")).longValue();
            grouped.computeIfAbsent(wh, k -> new ArrayList<>()).add(p);
        }
        for (var e : grouped.entrySet()) {
            var items = e.getValue();
            long merchantId = ((Number) items.getFirst().get("merchantId")).longValue();
            var payloadItems = items.stream()
                    .map(i -> Map.of("orderItemId", i.get("orderItemId"), "skuId", i.get("skuId"), "quantity", i.get("quantity")))
                    .toList();
            inventory.createFulfillment(Map.of(
                    "orderNo", orderNo,
                    "userId", userId,
                    "merchantId", merchantId,
                    "warehouseId", e.getKey(),
                    "items", payloadItems));
        }
    }

    private CallbackResult process(String channel, PaymentController.Callback body, String hash) {
        try {
            jdbc.update("""
                    INSERT INTO trade_payment_callback(id,channel,callback_id,payload_hash,payment_no,process_status,created_at)
                    VALUES(?,?,?,?,?,'PROCESSING',CURRENT_TIMESTAMP)
                    """, DistributedId.next(), channel, body.callbackId(), hash, body.paymentNo());
        } catch (DuplicateKeyException e) {
            Map<String, Object> old = jdbc.queryForMap(
                    "SELECT payload_hash,process_status FROM trade_payment_callback WHERE channel=? AND callback_id=?",
                    channel, body.callbackId());
            if (!hash.equals(old.get("payload_hash"))) {
                throw new BusinessException("TR40911", "相同回调 ID 的报文内容不一致");
            }
            String orderNo = jdbc.queryForObject(
                    "SELECT o.order_no FROM trade_payment p JOIN trade_order o ON o.id=p.order_id WHERE p.payment_no=?",
                    String.class, body.paymentNo());
            return new CallbackResult(orderNo, String.valueOf(old.get("process_status")), true);
        }
        Map<String, Object> payment = jdbc.queryForMap("SELECT * FROM trade_payment WHERE payment_no=? FOR UPDATE", body.paymentNo());
        if (!channel.equals(payment.get("channel"))) {
            throw new BusinessException("TR40912", "支付渠道不匹配");
        }
        if (((BigDecimal) payment.get("amount")).compareTo(body.amount()) != 0) {
            throw new BusinessException("TR40913", "支付回调金额不匹配");
        }
        if (!merchantNo.equals(payment.get("merchant_no"))) {
            throw new BusinessException("TR40302", "支付单商户号异常");
        }
        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM trade_order WHERE id=? FOR UPDATE", payment.get("order_id"));
        String orderNo = String.valueOf(order.get("order_no"));
        if ("SUCCESS".equals(payment.get("status"))) {
            jdbc.update("UPDATE trade_payment_callback SET process_status='SUCCESS',processed_at=CURRENT_TIMESTAMP WHERE channel=? AND callback_id=?",
                    channel, body.callbackId());
            return new CallbackResult(orderNo, "SUCCESS", true);
        }
        if (!"SUCCESS".equals(body.status())) {
            jdbc.update("UPDATE trade_payment SET status='FAILED',channel_trade_no=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    body.channelTradeNo(), payment.get("id"));
            jdbc.update("UPDATE trade_payment_callback SET process_status='FAILED',processed_at=CURRENT_TIMESTAMP WHERE channel=? AND callback_id=?",
                    channel, body.callbackId());
            return new CallbackResult(orderNo, "FAILED", false);
        }
        if ("PENDING_PAYMENT".equals(order.get("status")) && order.get("paid_payment_id") == null) {
            jdbc.update("UPDATE trade_payment SET status='SUCCESS',channel_trade_no=?,paid_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    body.channelTradeNo(), payment.get("id"));
            int changed = jdbc.update("""
                    UPDATE trade_order SET status='PAID',paid_amount=payable_amount,paid_payment_id=?,version=version+1,
                    paid_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP
                    WHERE id=? AND status='PENDING_PAYMENT' AND paid_payment_id IS NULL
                    """, payment.get("id"), order.get("id"));
            if (changed == 0) {
                throw new BusinessException("TR40914", "订单支付状态竞争失败");
            }
            jdbc.update("UPDATE trade_sub_order SET status='PAID',updated_at=CURRENT_TIMESTAMP WHERE order_id=?", order.get("id"));
            jdbc.update("UPDATE trade_discount_reservation SET status='USED',updated_at=CURRENT_TIMESTAMP WHERE order_id=? AND status='FROZEN'",
                    order.get("id"));
            jdbc.update("UPDATE trade_order_timeout SET status='COMPLETED',updated_at=CURRENT_TIMESTAMP WHERE order_id=?", order.get("id"));
            jdbc.update("""
                    INSERT INTO trade_order_log(id,order_id,from_status,to_status,operator_type,operator_id,detail,created_at)
                    VALUES(?,?, 'PENDING_PAYMENT','PAID','PAYMENT',0,?,CURRENT_TIMESTAMP)
                    """, DistributedId.next(), order.get("id"), body.channelTradeNo());
            events.append("PAYMENT", body.paymentNo(), "payment.payment.succeeded.v1",
                    Map.of("paymentNo", body.paymentNo(), "orderNo", orderNo, "amount", body.amount(), "channel", channel));
            tasks.enqueue("DEDUCT_STOCK", orderNo);
            tasks.enqueue("SETTLE_ASSETS", orderNo);
            tasks.enqueue("CREATE_FULFILLMENT", orderNo);
            jdbc.update("UPDATE trade_payment_callback SET process_status='SUCCESS',processed_at=CURRENT_TIMESTAMP WHERE channel=? AND callback_id=?",
                    channel, body.callbackId());
            return new CallbackResult(orderNo, "SUCCESS", false);
        }
        jdbc.update("UPDATE trade_payment SET status='LATE_SUCCESS',channel_trade_no=?,paid_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?",
                body.channelTradeNo(), payment.get("id"));
        jdbc.update("""
                UPDATE trade_payment_callback SET process_status='MANUAL_REVIEW',detail='ORDER_NOT_PAYABLE',processed_at=CURRENT_TIMESTAMP
                WHERE channel=? AND callback_id=?
                """, channel, body.callbackId());
        tasks.enqueue("PAYMENT_AFTER_CLOSE", orderNo, "订单已关闭或已由其他支付单支付");
        return new CallbackResult(orderNo, "MANUAL_REVIEW", false);
    }

    private String canonical(String channel, PaymentController.Callback b) {
        return channel.toUpperCase(Locale.ROOT) + "|" + b.callbackId() + "|" + b.paymentNo() + "|"
                + b.merchantNo() + "|" + b.amount().setScale(2) + "|" + b.status() + "|" + b.channelTradeNo();
    }

    private String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean secureEquals(String a, String b) {
        return b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    record CallbackResult(String orderNo, String status, boolean replay) {
    }
}
