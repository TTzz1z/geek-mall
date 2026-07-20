package com.geekmall.member;

import com.geekmall.common.api.ApiResponse;
import com.geekmall.common.api.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/internal/member/addresses")
class AddressInternalController {
    private final JdbcTemplate jdbc;
    private final String internalKey;

    AddressInternalController(JdbcTemplate jdbc, @Value("${geek.internal-key}") String internalKey) {
        this.jdbc = jdbc;
        this.internalKey = internalKey;
    }

    @GetMapping("/{id}")
    ApiResponse<Map<String, Object>> detail(@RequestHeader("X-Internal-Key") String key,
                                            @PathVariable long id,
                                            @RequestParam long userId) {
        require(key);
        var rows = jdbc.queryForList(
                "SELECT id,receiver,mobile,region_code AS regionCode,region_name AS regionName,detail,is_default AS isDefault FROM member_user_address WHERE id=? AND user_id=?",
                id, userId);
        if (rows.isEmpty()) throw new BusinessException("MB40402", "收货地址不存在");
        return ApiResponse.success(rows.getFirst(), null);
    }

    private void require(String key) {
        if (!internalKey.equals(key)) throw new BusinessException("AU40304", "内部服务凭证无效");
    }
}
