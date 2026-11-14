package com.geekmall.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RetrievalEvaluationTest {
    @Test
    void frozenDatasetSeparatesDevelopmentAndTestWithExplicitNoAnswerLabels() throws Exception {
        List<RetrievalEvaluation.Judgment> cases;
        try (var input = new ClassPathResource("eval/retrieval-v2.json").getInputStream()) {
            cases = new ObjectMapper().readValue(input, new TypeReference<>() {});
        }
        assertTrue(cases.size() >= 50);
        assertEquals(cases.size(), cases.stream().map(RetrievalEvaluation.Judgment::id).distinct().count());
        assertEquals(cases.size(), cases.stream().map(RetrievalEvaluation.Judgment::query).distinct().count());
        for (String split : List.of("DEV", "TEST")) {
            var selected = cases.stream().filter(c -> split.equals(c.split())).toList();
            assertFalse(selected.isEmpty());
            assertTrue(selected.stream().anyMatch(c -> c.relevance().isEmpty()));
            assertTrue(selected.stream().anyMatch(c -> "CROSS_SCOPE".equals(c.category())));
            assertTrue(selected.stream().anyMatch(c -> "VERSION".equals(c.category())));
        }
    }

    @Test
    void noAnswerCasesHaveSeparateDenominatorAndLatencyAndScopeFailuresAreReported() throws Exception {
        var rag = mock(HybridRagService.class);
        when(rag.indexingComplete()).thenReturn(true);
        when(rag.embeddingModel()).thenReturn("LEXICAL");
        when(rag.indexStatus()).thenReturn(Map.of("status", "READY", "generation", 1));
        when(rag.retrieveForEvaluation(anyString(), anyInt(), anyString())).thenReturn(List.of(
                new HybridRagService.Citation(1, 1, "injected wrong scope", "RULE", "AFTERSALE", "section", "99",
                        "evidence", 1, Map.of("scope", "PRIVATE"))));
        var report = new RetrievalEvaluation(rag, new ObjectMapper()).evaluate(5);
        assertEquals("TEST", report.get("split"));
        assertTrue(((Number) report.get("answerableCases")).intValue() > 0);
        assertTrue(((Number) report.get("noAnswerCases")).intValue() > 0);
        assertEquals(1.0, report.get("noAnswerFalsePositiveRate"));
        assertTrue(((Number) report.get("scopeViolations")).intValue() > 0);
        assertTrue(((Number) report.get("versionViolations")).intValue() > 0);
        assertTrue(((Number) report.get("p95LatencyMs")).doubleValue() >= 0);
        assertEquals(false, report.get("passesProvisionalThresholds"));
    }
}
