package com.geekmall.member;

import com.geekmall.common.api.ApiResponse;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.domain.DistributedId;
import com.geekmall.common.security.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/user/addresses")
@PreAuthorize("hasRole('USER')")
class AddressController {
    private final JdbcTemplate jdbc;

    AddressController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    ApiResponse<List<Map<String, Object>>> list(@AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        return ok(jdbc.queryForList(
                "SELECT id,receiver,mobile,region_code AS regionCode,region_name AS regionName,detail,is_default AS isDefault,created_at AS createdAt FROM member_user_address WHERE user_id=? ORDER BY is_default DESC,updated_at DESC",
                p.userId()), r);
    }

    @GetMapping("/{id}")
    ApiResponse<Map<String, Object>> detail(@PathVariable long id, @AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        return ok(owned(id, p.userId()), r);
    }

    @PostMapping
    @Transactional
    ApiResponse<Map<String, Object>> create(@Valid @RequestBody AddressBody body, @AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        long id = DistributedId.next();
        if (body.isDefault()) clearDefault(p.userId());
        jdbc.update("INSERT INTO member_user_address(id,user_id,receiver,mobile,region_code,region_name,detail,is_default,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                id, p.userId(), body.receiver(), body.mobile(), body.regionCode(), body.regionName(), body.detail(), body.isDefault() ? 1 : 0);
        return ok(owned(id, p.userId()), r);
    }

    @PutMapping("/{id}")
    @Transactional
    ApiResponse<Map<String, Object>> update(@PathVariable long id, @Valid @RequestBody AddressBody body, @AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        owned(id, p.userId());
        if (body.isDefault()) clearDefault(p.userId());
        jdbc.update("UPDATE member_user_address SET receiver=?,mobile=?,region_code=?,region_name=?,detail=?,is_default=?,updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=?",
                body.receiver(), body.mobile(), body.regionCode(), body.regionName(), body.detail(), body.isDefault() ? 1 : 0, id, p.userId());
        return ok(owned(id, p.userId()), r);
    }

    @DeleteMapping("/{id}")
    @Transactional
    ApiResponse<Map<String, Object>> delete(@PathVariable long id, @AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        owned(id, p.userId());
        jdbc.update("DELETE FROM member_user_address WHERE id=? AND user_id=?", id, p.userId());
        return ok(Map.of("deleted", true), r);
    }

    @PostMapping("/{id}/default")
    @Transactional
    ApiResponse<Map<String, Object>> setDefault(@PathVariable long id, @AuthenticationPrincipal AuthPrincipal p, HttpServletRequest r) {
        owned(id, p.userId());
        clearDefault(p.userId());
        jdbc.update("UPDATE member_user_address SET is_default=1,updated_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=?", id, p.userId());
        return ok(owned(id, p.userId()), r);
    }

    private void clearDefault(long userId) {
        jdbc.update("UPDATE member_user_address SET is_default=0,updated_at=CURRENT_TIMESTAMP WHERE user_id=? AND is_default=1", userId);
    }

    private Map<String, Object> owned(long id, long userId) {
        var rows = jdbc.queryForList(
                "SELECT id,receiver,mobile,region_code AS regionCode,region_name AS regionName,detail,is_default AS isDefault,created_at AS createdAt FROM member_user_address WHERE id=? AND user_id=?",
                id, userId);
        if (rows.isEmpty()) throw new BusinessException("MB40402", "收货地址不存在");
        return rows.getFirst();
    }

    private static <T> ApiResponse<T> ok(T d, HttpServletRequest r) {
        return ApiResponse.success(d, String.valueOf(r.getAttribute("requestId")));
    }

    record AddressBody(@NotBlank String receiver, @NotBlank String mobile, @NotBlank String regionCode, @NotBlank String regionName, @NotBlank String detail, boolean isDefault) {}
}
