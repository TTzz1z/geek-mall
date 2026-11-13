package com.geekmall.ai;

import com.geekmall.common.api.BusinessException;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import okhttp3.OkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
class KnowledgeDocumentParser {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_TEXT = 240_000;
    private static final Set<String> TYPES = Set.of("application/pdf", "image/png", "image/jpeg", "image/tiff",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private final String tika;
    private final String bucket;
    private final MinioClient storage;

    KnowledgeDocumentParser(@Value("${geek.knowledge.tika-url}") String tika,
                            @Value("${geek.knowledge.minio-endpoint}") String minio,
                            @Value("${geek.knowledge.minio-bucket}") String bucket,
                            @Value("${geek.knowledge.minio-access-key}") String accessKey,
                            @Value("${geek.knowledge.minio-secret-key}") String secretKey) {
        this.tika = tika.replaceAll("/+$", "");
        this.bucket = bucket;
        var client = new OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).build();
        this.storage = MinioClient.builder().endpoint(minio).credentials(accessKey, secretKey).httpClient(client).build();
    }

    Parsed parse(String reference) {
        if (reference == null || !reference.startsWith("minio://" + bucket + "/")) {
            throw new BusinessException("AI40003", "文件必须来自已配置的 MinIO 文档桶");
        }
        String object = reference.substring(("minio://" + bucket + "/").length());
        if (!object.matches("[A-Za-z0-9._/-]{1,480}") || object.startsWith("/") || object.contains("..")) {
            throw new BusinessException("AI40003", "文档对象路径无效");
        }
        try (var stream = storage.getObject(GetObjectArgs.builder().bucket(bucket).object(object).build())) {
            byte[] bytes = stream.readNBytes(MAX_BYTES + 1);
            if (bytes.length == 0 || bytes.length > MAX_BYTES) {
                throw new BusinessException("AI40003", "文件大小须介于 1 字节和 16 MiB");
            }
            String type = put("/detect", bytes, 512).strip();
            if (!TYPES.contains(type)) throw new BusinessException("AI40003", "不支持该文档格式");
            String text = put("/tika/text", bytes, MAX_TEXT * 4).strip();
            if (text.isBlank() || text.length() > MAX_TEXT) {
                throw new BusinessException("AI40004", "文档正文为空或超出解析限制");
            }
            return new Parsed(text, type, type.startsWith("image/"));
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("AI50312", "文档读取或解析失败");
        }
    }

    private String put(String path, byte[] bytes, int limit) throws IOException {
        var connection = (HttpURLConnection) URI.create(tika + path).toURL().openConnection();
        connection.setConnectTimeout(3_000);
        connection.setReadTimeout(40_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestMethod("PUT");
        connection.setRequestProperty("Content-Type", "application/octet-stream");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(bytes.length);
        try {
            try (var output = connection.getOutputStream()) {
                output.write(bytes);
            }
            if (connection.getResponseCode() != 200) throw new IOException("invalid Tika response");
            try (var input = connection.getInputStream()) {
                byte[] body = input.readNBytes(limit + 1);
                if (body.length > limit) throw new IOException("Tika response exceeds limit");
                return new String(body, StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }

    record Parsed(String text, String mime, boolean ocr) {}
}
