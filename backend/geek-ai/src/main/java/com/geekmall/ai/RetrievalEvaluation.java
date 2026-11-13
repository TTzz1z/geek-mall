package com.geekmall.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekmall.common.api.BusinessException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Service
class RetrievalEvaluation {
    private final HybridRagService rag;
    private final List<Judgment> dataset;

    RetrievalEvaluation(HybridRagService rag, ObjectMapper mapper) throws IOException {
        this.rag = rag;
        try (var input = new ClassPathResource("eval/retrieval-v1.json").getInputStream()) {
            dataset = mapper.readValue(input, new TypeReference<>() {});
        }
    }

    Map<String, Object> evaluate(int k) {
        if (!rag.indexingComplete()) throw new BusinessException("AI50315", "索引重建尚未完成");
        int limit = Math.max(1, Math.min(20, k));
        var details = dataset.stream().map(test -> {
            var sources = rag.retrieveForEvaluation(test.query(), limit, test.scope()).stream()
                    .map(HybridRagService.Citation::sourceId).toList();
            return Map.of("query", test.query(), "scope", test.scope(), "retrieved", sources,
                    "scores", score(sources, test.relevance(), limit));
        }).toList();
        var scores = details.stream().map(row -> (Scores) row.get("scores")).toList();
        return Map.of("dataset", "retrieval-v1", "model", rag.embeddingModel(), "k", limit,
                "recall", scores.stream().mapToDouble(Scores::recall).average().orElse(0),
                "mrr", scores.stream().mapToDouble(Scores::mrr).average().orElse(0),
                "ndcg", scores.stream().mapToDouble(Scores::ndcg).average().orElse(0), "details", details);
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

    record Judgment(String scope, String query, Map<String, Integer> relevance) {}
    record Scores(double recall, double mrr, double ndcg) {}
}
