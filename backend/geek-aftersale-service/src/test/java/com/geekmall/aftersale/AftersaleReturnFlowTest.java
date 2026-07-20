package com.geekmall.aftersale;

import com.geekmall.common.api.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AftersaleReturnFlowTest {
    @Test
    void returnRefundStaysApprovedUntilLogisticsThenReceive() {
        AftersaleStateMachine.require("APPLIED", "APPROVED");
        AftersaleStateMachine.require("APPROVED", "RETURNING");
        AftersaleStateMachine.require("RETURNING", "RECEIVED");
        AftersaleStateMachine.require("RECEIVED", "REFUNDING");
        AftersaleStateMachine.require("REFUNDING", "COMPLETED");
        assertThrows(BusinessException.class, () -> AftersaleStateMachine.require("APPROVED", "RECEIVED"));
    }
}
