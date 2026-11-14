package com.geekmall.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** Real MySQL and Elasticsearch; optional local embedding endpoint is never replaced with fake vectors. */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KnowledgeRetrievalIT {
    @Container static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");
    @Container static final GenericContainer<?> elastic = new GenericContainer<>("docker.elastic.co/elasticsearch/elasticsearch:8.17.3")
            .withExposedPorts(9200).withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false").withEnv("ES_JAVA_OPTS", "-Xms384m -Xmx384m")
            .waitingFor(Wait.forHttp("/").forStatusCode(200)).withStartupTimeout(Duration.ofMinutes(3));
    private final ObjectMapper mapper = new ObjectMapper();

    private JdbcTemplate database() {
        var ds = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        Flyway.configure().locations("filesystem:src/main/resources/db/migration").dataSource(ds).load().migrate();
        return new JdbcTemplate(ds);
    }

    private String elasticUrl() { return "http://" + elastic.getHost() + ":" + elastic.getMappedPort(9200); }

    @Test
    @Order(1)
    void bm25AndConfiguredEmbeddingModelsUseIdenticalOnlineAndEvaluationPath() throws Exception {
        var jdbc = database();
        var models = new ArrayList<String>();
        models.add("LEXICAL");
        String embeddingUrl = System.getProperty("rag.embedding.url", "");
        for (String model : System.getProperty("rag.embedding.models", "").split(",")) {
            if (!model.isBlank()) models.add(model.trim());
        }
        Path reportDir = Path.of(System.getProperty("rag.reportDir", "D:/Temp/maven/shoping-rag-batch"));
        Files.createDirectories(reportDir);
        var summaries = new ArrayList<Map<String, Object>>();
        int number = 0;
        for (String model : models) {
            boolean lexical = "LEXICAL".equals(model);
            if (!lexical) assertFalse(embeddingUrl.isBlank(), "Real model evaluation requires rag.embedding.url");
            var rag = new HybridRagService(jdbc, mapper, new EmbeddingClient(lexical ? "" : embeddingUrl, model, ""),
                    elasticUrl(), "rag-evaluation-" + number++, lexical ? 0 : Double.parseDouble(System.getProperty("rag.minimum.similarity", "0")));
            for (int batch = 0; batch < 10 && !rag.indexingComplete(); batch++) rag.indexPending();
            assertTrue(rag.indexingComplete(), () -> "Index failed for " + model + ": " + rag.indexStatus());
            var evaluation = new RetrievalEvaluation(rag, mapper);
            // DEV is observed first; TEST thresholds remain the frozen pre-experiment thresholds.
            Map<String, Object> dev = evaluation.evaluate(5, "DEV");
            Map<String, Object> test = evaluation.evaluate(5, "TEST");
            assertEquals(0, test.get("scopeViolations"));
            assertEquals(0, test.get("versionViolations"));
            var report = new LinkedHashMap<String, Object>();
            report.put("runAt", java.time.Instant.now().toString());
            report.put("engine", lexical ? "BM25" : "BM25 + " + model + " RRF hybrid");
            report.put("elasticsearch", "8.17.3");
            report.put("mysql", "8.4");
            report.put("embeddingEndpoint", lexical ? "DISABLED" : embeddingUrl);
            report.put("dev", dev);
            report.put("test", test);
            String filename = "retrieval-" + model.replaceAll("[^A-Za-z0-9._-]", "_") + ".json";
            mapper.writerWithDefaultPrettyPrinter().writeValue(reportDir.resolve(filename).toFile(), report);
            for (var judgment : judgments()) {
                // Real GPU inference may cross the 0.45 semantic threshold by tiny rounding differences.
                // Compare the ranked evidence and provenance, rather than requiring identical RRF scores.
                assertEquals(evidence(rag.retrieve(judgment.query(), 5, judgment.scope())),
                        evidence(rag.retrieveForEvaluation(judgment.query(), 5, judgment.scope())), judgment.id());
            }
            summaries.add(Map.of("model", model, "report", filename, "recall", test.get("recall"),
                    "mrr", test.get("mrr"), "ndcg", test.get("ndcg"), "p95LatencyMs", test.get("p95LatencyMs"),
                    "noAnswerFalsePositiveRate", test.get("noAnswerFalsePositiveRate"),
                    "passesProvisionalThresholds", test.get("passesProvisionalThresholds")));
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(reportDir.resolve("retrieval-comparison.json").toFile(), summaries);
    }

    private List<List<Object>> evidence(List<HybridRagService.Citation> citations) {
        return citations.stream().map(c -> List.<Object>of(c.documentId(), c.chunkId(), c.sourceId(), c.versionNo(), c.debug().get("scope"))).toList();
    }

    private List<RetrievalEvaluation.Judgment> judgments() throws Exception {
        try (var input = getClass().getResourceAsStream("/eval/retrieval-v2.json")) {
            return mapper.readValue(input, new com.fasterxml.jackson.core.type.TypeReference<>() {});
        }
    }

    @Test
    @Order(2)
    void deletedIndexRebuildsThenCandidateActivatesRollsBackAndOffSaleFencesStaleHits() {
        var jdbc = database();
        var rag = new HybridRagService(jdbc, mapper, new EmbeddingClient("", "unused", ""), elasticUrl(), "rag-recovery");
        rag.indexPending();
        assertTrue(rag.indexingComplete());
        assertFalse(rag.retrieve("Air 14 内存", 5).isEmpty());
        RestClient.create(elasticUrl()).delete().uri("/rag-recovery").retrieve().toBodilessEntity();
        assertFalse(rag.indexingComplete());
        rag.indexPending();
        assertTrue(rag.indexingComplete());
        assertFalse(rag.retrieve("Air 14 内存", 5).isEmpty());

        var ingest = new KnowledgeIngestService(jdbc, mapper, mock(KnowledgeDocumentParser.class),
                new DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())));
        var first = ingest.syncProduct(Map.of("id", 40401, "title", "回归专用火星键盘", "subtitle", "第一代磁轴"), "UPSERT");
        rag.indexPending();
        var second = ingest.syncProduct(Map.of("id", 40401, "title", "回归专用火星键盘", "subtitle", "第二代静音磁轴"), "UPSERT");
        assertEquals(1, ingest.countActiveBySource("40401"));
        rag.indexPending();
        assertEquals("2", rag.retrieve("回归专用火星键盘", 5).stream().filter(c -> "40401".equals(c.sourceId())).findFirst().orElseThrow().versionNo());
        rag.requestActivation(((Number) first.get("documentId")).longValue());
        rag.indexPending();
        assertEquals("1", rag.retrieve("回归专用火星键盘", 5).stream().filter(c -> "40401".equals(c.sourceId())).findFirst().orElseThrow().versionNo());
        assertEquals("SUPERSEDED", jdbc.queryForObject("SELECT activation_status FROM knowledge_document WHERE id=?", String.class, second.get("documentId")));
        ingest.syncProduct(Map.of("id", 40401), "OFF_SALE");
        assertTrue(rag.retrieve("回归专用火星键盘", 5).stream().noneMatch(c -> "40401".equals(c.sourceId())));
        rag.indexPending();
        assertEquals(0, ingest.countActiveBySource("40401"));
    }
}
