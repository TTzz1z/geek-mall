package com.geekmall.marketing;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.ApiResponse;
import com.geekmall.common.api.BusinessException;
import com.geekmall.common.domain.DistributedId;
import com.geekmall.common.security.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@RestController
@RequestMapping("/api/v1")
class PageAbController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    PageAbController(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @GetMapping("/cms/pages/{pageCode}")
    ApiResponse<Map<String, Object>> publishedPage(@PathVariable String pageCode, HttpServletRequest r) {
        var rows = jdbc.queryForList("""
                SELECT page_code AS pageCode, version_no AS versionNo, title, blocks_json AS blocksJson,
                       status, published_at AS publishedAt
                FROM cms_page_version
                WHERE page_code=? AND status='PUBLISHED'
                ORDER BY version_no DESC LIMIT 1
                """, pageCode);
        if (rows.isEmpty()) throw new BusinessException("MK40410", "页面未发布");
        Map<String, Object> page = new LinkedHashMap<>(rows.getFirst());
        page.put("blocks", parseBlocks(String.valueOf(page.get("blocksJson"))));
        page.remove("blocksJson");
        return ok(page, r);
    }

    @GetMapping("/admin/cms/pages/{pageCode}/versions")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS')")
    ApiResponse<List<Map<String, Object>>> versions(@PathVariable String pageCode, HttpServletRequest r) {
        return ok(jdbc.queryForList("""
                SELECT version_no AS versionNo, title, status, published_at AS publishedAt, created_at AS createdAt
                FROM cms_page_version WHERE page_code=? ORDER BY version_no DESC
                """, pageCode), r);
    }

    @GetMapping("/admin/cms/pages/{pageCode}/versions/{versionNo}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS')")
    ApiResponse<Map<String, Object>> versionDetail(@PathVariable String pageCode,
                                                   @PathVariable int versionNo,
                                                   HttpServletRequest r) {
        var rows = jdbc.queryForList("""
                SELECT page_code AS pageCode, version_no AS versionNo, title, blocks_json AS blocksJson,
                       status, published_at AS publishedAt, created_at AS createdAt
                FROM cms_page_version WHERE page_code=? AND version_no=?
                """, pageCode, versionNo);
        if (rows.isEmpty()) throw new BusinessException("MK40411", "版本不存在");
        Map<String, Object> page = new LinkedHashMap<>(rows.getFirst());
        page.put("blocks", parseBlocks(String.valueOf(page.get("blocksJson"))));
        page.remove("blocksJson");
        return ok(page, r);
    }

    @PostMapping("/admin/cms/pages/{pageCode}/versions")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS')")
    @Transactional
    ApiResponse<Map<String, Object>> createVersion(@PathVariable String pageCode,
                                                   @Valid @RequestBody PageBody body,
                                                   @AuthenticationPrincipal AuthPrincipal p,
                                                   HttpServletRequest r) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no),0) FROM cms_page_version WHERE page_code=?", Integer.class, pageCode);
        int next = max == null ? 1 : max + 1;
        long id = DistributedId.next();
        jdbc.update("""
                INSERT INTO cms_page_version(id,page_code,version_no,title,blocks_json,status,created_by,created_at)
                VALUES(?,?,?,?,?,'DRAFT',?,?)
                """, id, pageCode, next, body.title(), json(body.blocks()), p.userId(), Timestamp.from(Instant.now()));
        return ok(Map.of("pageCode", pageCode, "versionNo", next, "status", "DRAFT"), r);
    }

    @PutMapping("/admin/cms/pages/{pageCode}/versions/{versionNo}")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS')")
    @Transactional
    ApiResponse<Map<String, Object>> updateVersion(@PathVariable String pageCode,
                                                   @PathVariable int versionNo,
                                                   @Valid @RequestBody PageBody body,
                                                   HttpServletRequest r) {
        String status = jdbc.query("SELECT status FROM cms_page_version WHERE page_code=? AND version_no=?",
                rs -> rs.next() ? rs.getString(1) : null, pageCode, versionNo);
        if (status == null) throw new BusinessException("MK40411", "版本不存在");
        if ("PUBLISHED".equals(status)) throw new BusinessException("MK40920", "已发布版本不可直接修改，请新建草稿");
        jdbc.update("""
                UPDATE cms_page_version SET title=?, blocks_json=? WHERE page_code=? AND version_no=? AND status='DRAFT'
                """, body.title(), json(body.blocks()), pageCode, versionNo);
        return ok(Map.of("pageCode", pageCode, "versionNo", versionNo, "status", "DRAFT"), r);
    }

    @PostMapping("/admin/cms/pages/{pageCode}/publish")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS')")
    @Transactional
    ApiResponse<Map<String, Object>> publish(@PathVariable String pageCode,
                                             @RequestParam int versionNo,
                                             HttpServletRequest r) {
        int changed = jdbc.update("""
                UPDATE cms_page_version SET status='PUBLISHED', published_at=CURRENT_TIMESTAMP
                WHERE page_code=? AND version_no=? AND status IN ('DRAFT','ARCHIVED')
                """, pageCode, versionNo);
        if (changed == 0) throw new BusinessException("MK40411", "可发布版本不存在");
        jdbc.update("""
                UPDATE cms_page_version SET status='ARCHIVED'
                WHERE page_code=? AND version_no<>? AND status='PUBLISHED'
                """, pageCode, versionNo);
        return ok(Map.of("pageCode", pageCode, "versionNo", versionNo, "status", "PUBLISHED"), r);
    }

    @PostMapping("/admin/cms/pages/{pageCode}/rollback")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS')")
    @Transactional
    ApiResponse<Map<String, Object>> rollback(@PathVariable String pageCode,
                                              @RequestParam int versionNo,
                                              HttpServletRequest r) {
        var rows = jdbc.queryForList(
                "SELECT id FROM cms_page_version WHERE page_code=? AND version_no=?", pageCode, versionNo);
        if (rows.isEmpty()) throw new BusinessException("MK40411", "版本不存在");
        jdbc.update("UPDATE cms_page_version SET status='ARCHIVED' WHERE page_code=? AND status='PUBLISHED'", pageCode);
        jdbc.update("""
                UPDATE cms_page_version SET status='PUBLISHED', published_at=CURRENT_TIMESTAMP
                WHERE page_code=? AND version_no=?
                """, pageCode, versionNo);
        return ok(Map.of("pageCode", pageCode, "versionNo", versionNo, "status", "PUBLISHED", "action", "ROLLBACK"), r);
    }

    @GetMapping("/marketing/ab/{experimentCode}")
    ApiResponse<Map<String, Object>> assign(@PathVariable String experimentCode,
                                            @RequestParam(required = false) String userKey,
                                            @AuthenticationPrincipal AuthPrincipal p,
                                            HttpServletRequest r) {
        var exps = jdbc.queryForList(
                "SELECT id, status, traffic_percent FROM ab_experiment WHERE experiment_code=?", experimentCode);
        if (exps.isEmpty() || !"RUNNING".equals(String.valueOf(exps.getFirst().get("status")))) {
            throw new BusinessException("MK40412", "实验未运行");
        }
        long expId = ((Number) exps.getFirst().get("id")).longValue();
        String key = userKey != null ? userKey : (p == null ? r.getRemoteAddr() : String.valueOf(p.userId()));
        var existing = jdbc.queryForList(
                "SELECT variant_code AS variantCode FROM ab_assignment WHERE experiment_id=? AND user_key=?", expId, key);
        if (!existing.isEmpty()) {
            return ok(variantPayload(experimentCode, String.valueOf(existing.getFirst().get("variantCode")), expId), r);
        }
        String variant = pickVariant(expId, key);
        jdbc.update("""
                INSERT INTO ab_assignment(id,experiment_id,user_key,variant_code,assigned_at)
                VALUES(?,?,?,?,?)
                """, DistributedId.next(), expId, key, variant, Timestamp.from(Instant.now()));
        return ok(variantPayload(experimentCode, variant, expId), r);
    }

    @PostMapping("/admin/marketing/ab")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Transactional
    ApiResponse<Map<String, Object>> createExperiment(@Valid @RequestBody ExperimentBody body, HttpServletRequest r) {
        long id = DistributedId.next();
        jdbc.update("""
                INSERT INTO ab_experiment(id,experiment_code,name,status,traffic_percent,created_at)
                VALUES(?,?,?,'RUNNING',?,?)
                """, id, body.experimentCode(), body.name(), body.trafficPercent() == null ? 100 : body.trafficPercent(),
                Timestamp.from(Instant.now()));
        for (var v : body.variants()) {
            jdbc.update("INSERT INTO ab_variant(id,experiment_id,variant_code,weight,config_json) VALUES(?,?,?,?,?)",
                    DistributedId.next(), id, v.code(), v.weight(), json(v.config() == null ? Map.of() : v.config()));
        }
        return ok(Map.of("experimentId", id, "experimentCode", body.experimentCode(), "status", "RUNNING"), r);
    }

    @GetMapping("/admin/marketing/ab")
    @PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS')")
    ApiResponse<List<Map<String, Object>>> listExperiments(HttpServletRequest r) {
        List<Map<String, Object>> exps = jdbc.queryForList("""
                SELECT e.id, e.experiment_code AS experimentCode, e.name, e.status, e.traffic_percent AS trafficPercent,
                       e.created_at AS createdAt,
                       (SELECT COUNT(1) FROM ab_variant v WHERE v.experiment_id=e.id) AS variantCount,
                       (SELECT COUNT(1) FROM ab_assignment a WHERE a.experiment_id=e.id) AS assignmentCount,
                       COALESCE((SELECT weight FROM ab_variant v WHERE v.experiment_id=e.id AND v.variant_code='A' LIMIT 1), 50) AS baselineWeight,
                       COALESCE((SELECT weight FROM ab_variant v WHERE v.experiment_id=e.id AND v.variant_code='B' LIMIT 1), 50) AS variantWeight
                FROM ab_experiment e ORDER BY e.created_at DESC LIMIT 100
                """);
        return ok(exps, r);
    }

    @PostMapping("/admin/marketing/ab/{experimentCode}/pause")
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    @Transactional
    ApiResponse<Map<String, Object>> pauseExperiment(@PathVariable String experimentCode, HttpServletRequest r) {
        int changed = jdbc.update("UPDATE ab_experiment SET status='PAUSED' WHERE experiment_code=? AND status='RUNNING'",
                experimentCode);
        if (changed == 0) throw new BusinessException("MK40412", "实验不存在或未在运行");
        return ok(Map.of("experimentCode", experimentCode, "status", "PAUSED"), r);
    }

    private Map<String, Object> variantPayload(String code, String variant, long expId) {
        var rows = jdbc.queryForList(
                "SELECT config_json FROM ab_variant WHERE experiment_id=? AND variant_code=?", expId, variant);
        Map<String, Object> config = Map.of();
        if (!rows.isEmpty()) {
            try {
                config = mapper.readValue(String.valueOf(rows.getFirst().get("config_json")), new TypeReference<>() {});
            } catch (Exception ignored) {
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("experimentCode", code);
        data.put("variantCode", variant);
        data.put("config", config);
        return data;
    }

    private String pickVariant(long expId, String key) {
        var variants = jdbc.queryForList(
                "SELECT variant_code, weight FROM ab_variant WHERE experiment_id=? ORDER BY variant_code", expId);
        if (variants.isEmpty()) return "A";
        int total = variants.stream().mapToInt(v -> ((Number) v.get("weight")).intValue()).sum();
        if (total <= 0) return String.valueOf(variants.getFirst().get("variant_code"));
        int bucket = Math.floorMod(key.hashCode(), total);
        int acc = 0;
        for (var v : variants) {
            acc += ((Number) v.get("weight")).intValue();
            if (bucket < acc) return String.valueOf(v.get("variant_code"));
        }
        return String.valueOf(variants.getFirst().get("variant_code"));
    }

    private List<Map<String, Object>> parseBlocks(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
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

    record PageBody(@NotBlank String title, @NotNull List<Map<String, Object>> blocks) {}
    record ExperimentBody(@NotBlank String experimentCode, @NotBlank String name, Integer trafficPercent,
                          @NotNull List<VariantBody> variants) {}
    record VariantBody(@NotBlank String code, int weight, Map<String, Object> config) {}
}
