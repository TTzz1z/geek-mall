package com.geekmall.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.BusinessException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Comparator;

@Service
class RetrievalEvaluation {
    private final HybridRagService rag;
    private final List<Judgment> dataset;

    RetrievalEvaluation(HybridRagService rag, ObjectMapper mapper) throws IOException {
        this.rag = rag;
        try (var input = new ClassPathResource("eval/retrieval-v2.json").getInputStream()) {
            dataset = mapper.readValue(input, new TypeReference<>() {});
        }
    }

    Map<String, Object> evaluate(int k) {
        return evaluate(k, "TEST");
    }

    Map<String, Object> evaluate(int k, String split) {
        if (!List.of("DEV", "TEST").contains(split)) throw new BusinessException("AI40006", "评测 split 必须是 DEV 或 TEST");
        if (!rag.indexingComplete()) throw new BusinessException("AI50315", "索引重建尚未完成");
        int limit = Math.max(1, Math.min(20, k));
        var selected = dataset.stream().filter(test -> split.equals(test.split())).toList();
        var details = new ArrayList<Map<String, Object>>();
        var answerable = new ArrayList<Scores>();
        var latencies = new ArrayList<Double>();
        int noAnswer = 0, falsePositives = 0, scopeViolations = 0, versionViolations = 0;
        for (var test : selected) {
            long started = System.nanoTime();
            var citations = rag.retrieveForEvaluation(test.query(), limit, test.scope());
            double latency = (System.nanoTime() - started) / 1_000_000.0;
            latencies.add(latency);
            var sources = citations.stream().map(HybridRagService.Citation::sourceId).toList();
            Scores score = score(sources, test.relevance(), limit);
            if (test.relevance().isEmpty()) {
                noAnswer++;
                if (!sources.isEmpty()) falsePositives++;
            } else answerable.add(score);
            scopeViolations += (int) citations.stream().filter(c -> !test.scope().equals(c.debug().get("scope"))).count();
            if (test.expectedVersions() != null) {
                versionViolations += (int) citations.stream().filter(c -> test.expectedVersions().containsKey(c.sourceId())
                        && !test.expectedVersions().get(c.sourceId()).equals(c.versionNo())).count();
            }
            var detail = new LinkedHashMap<String, Object>();
            detail.put("id", test.id());
            detail.put("category", test.category());
            detail.put("query", test.query());
            detail.put("scope", test.scope());
            detail.put("relevance", test.relevance());
            detail.put("rationale", test.rationale());
            detail.put("retrieved", sources);
            detail.put("versions", citations.stream().map(c -> c.sourceId() + ":" + c.versionNo()).toList());
            detail.put("latencyMs", latency);
            detail.put("scores", score);
            details.add(detail);
        }
        latencies.sort(Comparator.naturalOrder());
        double recall = answerable.stream().mapToDouble(Scores::recall).average().orElse(0);
        double mrr = answerable.stream().mapToDouble(Scores::mrr).average().orElse(0);
        double ndcg = answerable.stream().mapToDouble(Scores::ndcg).average().orElse(0);
        double p95 = latencies.isEmpty() ? 0 : latencies.get((int) Math.ceil(latencies.size() * 0.95) - 1);
        double falsePositiveRate = noAnswer == 0 ? 0 : falsePositives / (double) noAnswer;
        var report = new LinkedHashMap<String, Object>();
        report.put("dataset", "retrieval-v2");
        report.put("split", split);
        report.put("model", rag.embeddingModel());
        report.put("k", limit);
        report.put("answerableCases", answerable.size());
        report.put("noAnswerCases", noAnswer);
        report.put("recall", recall);
        report.put("mrr", mrr);
        report.put("ndcg", ndcg);
        report.put("p95LatencyMs", p95);
        report.put("noAnswerFalsePositiveRate", falsePositiveRate);
        report.put("scopeViolations", scopeViolations);
        report.put("versionViolations", versionViolations);
        report.put("configuration", Map.of("candidateMultiplier", 4, "sourceDeduplication", "sourceId within requested PUBLIC scope",
                "fusion", "RRF-60", "semanticSimilarity", rag.minimumSimilarity() > 0 ? rag.minimumSimilarity() : 0.45,
                "semanticAdmissionRequired", rag.minimumSimilarity() > 0, "dimensions", EmbeddingClient.DIMENSIONS));
        report.put("index", rag.indexStatus());
        report.put("thresholdStatus", "PROVISIONAL_PRE_EXPERIMENT_NOT_BASELINE_CALIBRATED");
        report.put("thresholds", Map.of("recallMin", 0.80, "mrrMin", 0.70, "ndcgMin", 0.75,
                "p95LatencyMsMax", 2000, "noAnswerFalsePositiveRateMax", 0.20, "scopeViolationsMax", 0, "versionViolationsMax", 0));
        report.put("passesProvisionalThresholds", recall >= 0.80 && mrr >= 0.70 && ndcg >= 0.75 && p95 <= 2000
                && falsePositiveRate <= 0.20 && scopeViolations == 0 && versionViolations == 0);
        report.put("limitations", "人工编写的种子语料问题；仅覆盖3个来源、6个分块；DEV/TEST按问题固定划分，非独立文档泛化验证。无答案检索结果仅表示候选误召回，不表示模型已编造答案。");
        report.put("details", details);
        return report;
    }

    static Scores score(List<String> retrieved, Map<String, Integer> relevance, int k) {
        int found = 0;
        double mrr = 0;
        double dcg = 0;
        var unique = retrieved.stream().distinct().limit(k).toList();
        for (int i = 0; i < unique.size(); i++) {
            int grade = relevance.getOrDefault(unique.get(i), 0);
            if (grade == 0) continue;
            found++;
            if (mrr == 0) mrr = 1.0 / (i + 1);
            dcg += gain(grade, i);
        }
        var ideal = relevance.values().stream().sorted(java.util.Comparator.reverseOrder()).limit(k).toList();
        double max = 0;
        for (int i = 0; i < ideal.size(); i++) max += gain(ideal.get(i), i);
        double recall = relevance.isEmpty() ? 0 : found / (double) relevance.size();
        return new Scores(recall, mrr, max == 0 ? 0 : dcg / max);
    }

    private static double gain(int grade, int rank) {
        return (Math.pow(2, grade) - 1) / (Math.log(rank + 2) / Math.log(2));
    }

    record Judgment(String id, String split, String category, String scope, String query, Map<String, Integer> relevance,
                    Map<String, String> expectedVersions, String rationale) {}
    record Scores(double recall, double mrr, double ndcg) {}
}
