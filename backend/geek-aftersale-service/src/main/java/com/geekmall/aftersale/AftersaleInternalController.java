package com.geekmall.aftersale;

import com.geekmall.common.api.ApiResponse;
import com.geekmall.common.api.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/internal/aftersales")
class AftersaleInternalController {
    private final AftersaleController aftersales;
    private final String internalKey;

    AftersaleInternalController(AftersaleController aftersales, @Value("${geek.internal-key}") String internalKey) {
        this.aftersales = aftersales;
        this.internalKey = internalKey;
    }

    @PostMapping("/{no}/warehouse-receive")
    @Transactional
    ApiResponse<Map<String, Object>> warehouseReceive(@RequestHeader("X-Internal-Key") String key,
                                                      @PathVariable String no,
                                                      HttpServletRequest r) {
        if (!internalKey.equals(key)) {
            throw new BusinessException("AU40304", "内部服务凭证无效");
        }
        return ApiResponse.success(aftersales.receiveWarehouse(no, "WAREHOUSE", 0L),
                String.valueOf(r.getAttribute("requestId")));
    }
}
