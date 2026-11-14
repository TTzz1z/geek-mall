package com.geekmall.ai;

import com.geekmall.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/ai/knowledge")
@PreAuthorize("hasAnyRole('SYSTEM_ADMIN','OPS')")
class KnowledgeAdminController {
    private final HybridRagService rag;

    KnowledgeAdminController(HybridRagService rag) { this.rag = rag; }

    @GetMapping("/index/status")
    ApiResponse<Map<String, Object>> status(HttpServletRequest request) {
        return ApiResponse.success(rag.indexStatus(), String.valueOf(request.getAttribute("requestId")));
    }

    @PostMapping("/index/rebuild")
    ApiResponse<Map<String, Object>> rebuild(HttpServletRequest request) {
        return ApiResponse.success(rag.rebuildIndex(), String.valueOf(request.getAttribute("requestId")));
    }

    @PostMapping("/documents/{documentId}/activate")
    ApiResponse<Map<String, Object>> activate(@PathVariable long documentId, HttpServletRequest request) {
        return ApiResponse.success(rag.requestActivation(documentId), String.valueOf(request.getAttribute("requestId")));
    }
}
