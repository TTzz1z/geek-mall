package com.geekmall.trade;

import com.geekmall.common.api.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

interface PaymentChannelAdapter {
    String channel();

    Map<String, Object> create(String paymentNo);

    boolean simulated();

    default String queryReturn(String paymentNo, String channelTradeNo, BigDecimal amount) {
        throw new BusinessException("TR50305", "支付通道返还查询尚未接入");
    }

    default String returnPayment(String paymentNo, String channelTradeNo, BigDecimal amount) {
        throw new BusinessException("TR50305", "支付通道返还尚未接入");
    }
}

@Component
class SimulatedPaymentChannelAdapter implements PaymentChannelAdapter {
    private final JdbcTemplate jdbc;

    SimulatedPaymentChannelAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public String channel() {
        return "SIMULATED";
    }

    public Map<String, Object> create(String paymentNo) {
        return Map.of("paymentNo", paymentNo, "mode", "SIMULATED", "nextAction", "CALL_SIMULATE");
    }

    public boolean simulated() {
        return true;
    }

    public String queryReturn(String paymentNo, String channelTradeNo, BigDecimal amount) {
        var rows = jdbc.queryForList("SELECT channel_trade_no,amount,channel_return_no FROM trade_simulated_payment_return WHERE payment_no=?", paymentNo);
        if (rows.isEmpty()) return null;
        var row = rows.getFirst();
        if (!channelTradeNo.equals(row.get("channel_trade_no")) || amount.compareTo((BigDecimal) row.get("amount")) != 0) {
            throw new BusinessException("TR40915", "支付返还请求与渠道记录不一致");
        }
        return String.valueOf(row.get("channel_return_no"));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String returnPayment(String paymentNo, String channelTradeNo, BigDecimal amount) {
        String receipt = "MOCK-RETURN-" + paymentNo;
        try {
            jdbc.update("INSERT INTO trade_simulated_payment_return(payment_no,channel_trade_no,amount,channel_return_no,created_at) VALUES(?,?,?,?,CURRENT_TIMESTAMP)", paymentNo, channelTradeNo, amount, receipt);
        } catch (DuplicateKeyException duplicate) {
            // The channel key is the payment number, including after process restart.
        }
        return queryReturn(paymentNo, channelTradeNo, amount);
    }
}

/** Stage5 extension: enable via PAYMENT_WECHAT_ENABLED=true and supply merchant credentials later. */
@Component
class WechatPaymentChannelAdapter implements PaymentChannelAdapter {
    private final boolean enabled;

    WechatPaymentChannelAdapter(@Value("${geek.payment.wechat.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public String channel() {
        return "WECHAT";
    }

    public Map<String, Object> create(String paymentNo) {
        if (!enabled) {
            throw new BusinessException("TR50303", "微信支付未启用（geek.payment.wechat.enabled=false），请使用 SIMULATED");
        }
        throw new BusinessException("TR50304", "微信支付已开启开关但商户凭证未配置，请补充后接入真实下单");
    }

    public boolean simulated() {
        return false;
    }
}

/** Stage5 extension: enable via PAYMENT_ALIPAY_ENABLED=true and supply merchant credentials later. */
@Component
class AlipayPaymentChannelAdapter implements PaymentChannelAdapter {
    private final boolean enabled;

    AlipayPaymentChannelAdapter(@Value("${geek.payment.alipay.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public String channel() {
        return "ALIPAY";
    }

    public Map<String, Object> create(String paymentNo) {
        if (!enabled) {
            throw new BusinessException("TR50303", "支付宝支付未启用（geek.payment.alipay.enabled=false），请使用 SIMULATED");
        }
        throw new BusinessException("TR50304", "支付宝支付已开启开关但商户凭证未配置，请补充后接入真实下单");
    }

    public boolean simulated() {
        return false;
    }
}

@Component
class UnavailableRealPaymentAdapters {
    private final List<PaymentChannelAdapter> adapters;

    UnavailableRealPaymentAdapters(List<PaymentChannelAdapter> adapters) {
        this.adapters = adapters;
    }

    PaymentChannelAdapter require(String channel) {
        return adapters.stream()
                .filter(a -> a.channel().equalsIgnoreCase(channel))
                .findFirst()
                .orElseThrow(() -> new BusinessException("TR50303", channel + " 支付适配器未配置，当前仅可使用模拟支付"));
    }
}
