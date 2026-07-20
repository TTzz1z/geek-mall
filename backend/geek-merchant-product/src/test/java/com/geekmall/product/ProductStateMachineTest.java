package com.geekmall.product;
import com.geekmall.common.api.BusinessException;import org.junit.jupiter.api.Test;import static org.assertj.core.api.Assertions.*;
class ProductStateMachineTest {
  @Test void acceptsPublishReviewAndSaleLifecycle() {
    assertThatCode(() -> ProductStateMachine.audit("DRAFT", "PENDING_REVIEW")).doesNotThrowAnyException();
    assertThatCode(() -> ProductStateMachine.audit("PENDING_REVIEW", "APPROVED")).doesNotThrowAnyException();
    assertThatCode(() -> ProductStateMachine.sale("OFF_SALE", "ON_SALE")).doesNotThrowAnyException();
    assertThatCode(() -> ProductStateMachine.sale("ON_SALE", "OFF_SALE")).doesNotThrowAnyException();
    assertThatCode(() -> ProductStateMachine.sale("ON_SALE", "VIOLATION_OFF_SALE")).doesNotThrowAnyException();
    assertThatCode(() -> ProductStateMachine.sale("VIOLATION_OFF_SALE", "OFF_SALE")).doesNotThrowAnyException();
  }
  @Test void rejectsSkippingReview() {
    assertThatThrownBy(() -> ProductStateMachine.audit("DRAFT", "APPROVED")).isInstanceOf(BusinessException.class).hasMessageContaining("非法商品状态流转");
  }
  @Test void rejectsMerchantSelfRestoreFromViolation() {
    assertThatThrownBy(() -> ProductStateMachine.sale("VIOLATION_OFF_SALE", "ON_SALE")).isInstanceOf(BusinessException.class);
  }
}
