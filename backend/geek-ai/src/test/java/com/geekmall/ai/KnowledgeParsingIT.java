package com.geekmall.ai;

import com.geekmall.common.api.BusinessException;
import io.minio.*;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class KnowledgeParsingIT {
    @Container static final GenericContainer<?> minio = new GenericContainer<>("minio/minio:RELEASE.2025-04-22T22-12-26Z")
            .withExposedPorts(9000).withCommand("server", "/data")
            .withEnv("MINIO_ROOT_USER", "rag-validation").withEnv("MINIO_ROOT_PASSWORD", "rag-validation-local-only")
            .waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000));
    @Container static final GenericContainer<?> tika = new GenericContainer<>("apache/tika:4.2.0-full")
            .withExposedPorts(9998).withEnv("JAVA_TOOL_OPTIONS", "-Xmx384m")
            .waitingFor(Wait.forHttp("/version").forPort(9998)).withStartupTimeout(Duration.ofMinutes(2));

    @Test
    void extractsRealPdfFromConfiguredMinioThroughTikaAndRejectsUnparsedTextFile() throws Exception {
        String endpoint = "http://" + minio.getHost() + ":" + minio.getMappedPort(9000);
        var storage = MinioClient.builder().endpoint(endpoint).credentials("rag-validation", "rag-validation-local-only").build();
        storage.makeBucket(MakeBucketArgs.builder().bucket("rag-documents").build());
        byte[] pdf = pdf("Knowledge extraction regression sentinel.");
        storage.putObject(PutObjectArgs.builder().bucket("rag-documents").object("manual.pdf")
                .stream(new ByteArrayInputStream(pdf), (long) pdf.length, -1L).contentType("application/pdf").build());
        byte[] unparsed = "This is not a supported binary document".getBytes(StandardCharsets.UTF_8);
        storage.putObject(PutObjectArgs.builder().bucket("rag-documents").object("not-a-pdf.pdf")
                .stream(new ByteArrayInputStream(unparsed), (long) unparsed.length, -1L).contentType("application/pdf").build());
        var parser = new KnowledgeDocumentParser("http://" + tika.getHost() + ":" + tika.getMappedPort(9998),
                endpoint, "rag-documents", "rag-validation", "rag-validation-local-only");
        var parsed = parser.parse("minio://rag-documents/manual.pdf");
        assertEquals("application/pdf", parsed.mime());
        assertTrue(parsed.text().contains("Knowledge extraction regression sentinel."));
        assertFalse(parsed.ocr());
        assertThrows(BusinessException.class, () -> parser.parse("minio://rag-documents/not-a-pdf.pdf"));
        assertThrows(BusinessException.class, () -> parser.parse("minio://another-bucket/manual.pdf"));
    }

    private static byte[] pdf(String text) throws Exception {
        var bytes = new ByteArrayOutputStream();
        bytes.write("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
        String stream = "BT /F1 12 Tf 40 150 Td (" + text + ") Tj ET";
        String[] objects = {"<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 400 200] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
                "<< /Length " + stream.length() + " >>\nstream\n" + stream + "\nendstream"};
        var offsets = new ArrayList<Integer>();
        for (int i = 0; i < objects.length; i++) {
            offsets.add(bytes.size());
            bytes.write(((i + 1) + " 0 obj\n" + objects[i] + "\nendobj\n").getBytes(StandardCharsets.US_ASCII));
        }
        int xref = bytes.size();
        bytes.write("xref\n0 6\n0000000000 65535 f \n".getBytes(StandardCharsets.US_ASCII));
        for (int offset : offsets) bytes.write(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", offset).getBytes(StandardCharsets.US_ASCII));
        bytes.write(("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n" + xref + "\n%%EOF\n").getBytes(StandardCharsets.US_ASCII));
        return bytes.toByteArray();
    }
}
