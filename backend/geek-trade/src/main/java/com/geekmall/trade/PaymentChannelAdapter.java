package com.geekmall.trade;

import com.geekmall.common.api.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

interface PaymentChannelAdapter {
    String channel();

    Map<String, Object> create(String paymentNo);

    boolean simulated();
}

@Component
class SimulatedPaymentChannelAdapter implements PaymentChannelAdapter {
    public String channel() {
        return "SIMULATED";
    }

    public Map<String, Object> create(String paymentNo) {
        return Map.of("paymentNo", paymentNo, "mode", "SIMULATED", "nextAction", "CALL_SIMULATE");
    }

    public boolean simulated() {
        return true;
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
