package com.geekmall.product;

import com.geekmall.common.api.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
class DocumentStorageService {
    private static final String REGION = "us-east-1";
    private static final String SERVICE = "s3";
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final Set<String> COVER_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/webp", "image/gif", "image/svg+xml"
    );

    private final HttpClient http = HttpClient.newHttpClient();
    private final URI endpoint;
    private final String accessKey;
    private final String secretKey;
    private final String bucket;
    private final String publicBaseUrl;
    private volatile boolean coverPolicyApplied;

    DocumentStorageService(@Value("${geek.object-storage.endpoint}") String endpoint,
                           @Value("${geek.object-storage.access-key}") String accessKey,
                           @Value("${geek.object-storage.secret-key}") String secretKey,
                           @Value("${geek.object-storage.bucket}") String bucket,
                           @Value("${geek.object-storage.public-base-url:http://127.0.0.1:9100}") String publicBaseUrl) {
        this.endpoint = URI.create(endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint);
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.bucket = bucket;
        this.publicBaseUrl = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length() - 1) : publicBaseUrl;
    }

    String upload(long spuId, MultipartFile file) {
        if (file.isEmpty()) throw new BusinessException("PD40005", "上传文件不能为空");
        String original = file.getOriginalFilename() == null ? "document.bin" : file.getOriginalFilename();
        String safeName = original.replaceAll("[^a-zA-Z0-9._-]", "_");
        String objectName = "products/" + spuId + "/" + UUID.randomUUID() + "-" + safeName;
        try {
            ensureBucket();
            byte[] content = file.getBytes();
            String contentType = file.getContentType() == null ? "application/octet-stream" : file.getContentType();
            HttpResponse<Void> response = send("PUT", "/" + bucket + "/" + objectName, content, contentType, "");
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("object upload HTTP " + response.statusCode());
            return "minio://" + bucket + "/" + objectName;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception exception) {
            throw new BusinessException("PD50301", "商品资料对象存储不可用");
        }
    }

    /** Upload cover image and return a browser-reachable HTTP URL. */
    String uploadCover(long spuId, MultipartFile file) {
        if (file.isEmpty()) throw new BusinessException("PD40005", "上传文件不能为空");
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        String original = file.getOriginalFilename() == null ? "cover.png" : file.getOriginalFilename();
        String lowerName = original.toLowerCase(Locale.ROOT);
        if (!COVER_TYPES.contains(contentType)
                && !(lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg") || lowerName.endsWith(".png")
                || lowerName.endsWith(".webp") || lowerName.endsWith(".gif") || lowerName.endsWith(".svg"))) {
            throw new BusinessException("PD40006", "封面仅支持 jpg/png/webp/gif/svg");
        }
        if (contentType.isBlank() || "application/octet-stream".equals(contentType)) {
            contentType = guessImageType(lowerName);
        }
        String safeName = original.replaceAll("[^a-zA-Z0-9._-]", "_");
        String objectName = "covers/" + spuId + "/" + UUID.randomUUID() + "-" + safeName;
        try {
            ensureBucket();
            ensureCoverReadPolicy();
            byte[] content = file.getBytes();
            HttpResponse<Void> response = send("PUT", "/" + bucket + "/" + objectName, content, contentType, "");
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("cover upload HTTP " + response.statusCode());
            return publicBaseUrl + "/" + bucket + "/" + objectName;
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception exception) {
            throw new BusinessException("PD50301", "商品封面对象存储不可用");
        }
    }

    private void ensureBucket() throws Exception {
        HttpResponse<Void> head = send("HEAD", "/" + bucket, new byte[0], "application/octet-stream", "");
        if (head.statusCode() == 404) {
            HttpResponse<Void> created = send("PUT", "/" + bucket, new byte[0], "application/octet-stream", "");
            if (created.statusCode() / 100 != 2 && created.statusCode() != 409) {
                throw new IllegalStateException("bucket create HTTP " + created.statusCode());
            }
        } else if (head.statusCode() / 100 != 2) {
            throw new IllegalStateException("bucket check HTTP " + head.statusCode());
        }
    }

    private void ensureCoverReadPolicy() throws Exception {
        if (coverPolicyApplied) return;
        String policy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":["*"]},"Action":["s3:GetObject"],"Resource":["arn:aws:s3:::%s/covers/*"]}]}
                """.formatted(bucket).trim();
        byte[] body = policy.getBytes(StandardCharsets.UTF_8);
        HttpResponse<Void> response = send("PUT", "/" + bucket, body, "application/json", "policy=");
        if (response.statusCode() / 100 != 2 && response.statusCode() != 204) {
            throw new IllegalStateException("bucket policy HTTP " + response.statusCode());
        }
        coverPolicyApplied = true;
    }

    private HttpResponse<Void> send(String method, String path, byte[] body, String contentType, String query) throws Exception {
        Instant now = Instant.now();
        String amzDate = AMZ_DATE.format(now);
        String date = DATE.format(now);
        String payloadHash = sha256(body);
        String host = endpoint.getAuthority();
        String canonicalQuery = query == null ? "" : query;
        // For ?policy= MinIO expects empty value; canonical query is "policy="
        String canonicalHeaders = "content-type:" + contentType + "\n" + "host:" + host + "\n"
                + "x-amz-content-sha256:" + payloadHash + "\n" + "x-amz-date:" + amzDate + "\n";
        String signedHeaders = "content-type;host;x-amz-content-sha256;x-amz-date";
        String canonicalRequest = method + "\n" + path + "\n" + canonicalQuery + "\n" + canonicalHeaders + "\n"
                + signedHeaders + "\n" + payloadHash;
        String scope = date + "/" + REGION + "/" + SERVICE + "/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] dateKey = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] regionKey = hmac(dateKey, REGION);
        byte[] serviceKey = hmac(regionKey, SERVICE);
        byte[] signingKey = hmac(serviceKey, "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(signingKey, stringToSign));
        String authorization = "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
        HttpRequest.BodyPublisher publisher = body.length == 0
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body);
        String uri = endpoint.resolve(path).toString();
        if (!canonicalQuery.isBlank()) {
            uri = uri + "?" + canonicalQuery;
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(uri)).method(method, publisher)
                .header("Content-Type", contentType)
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", amzDate)
                .header("Authorization", authorization)
                .build();
        return http.send(request, HttpResponse.BodyHandlers.discarding());
    }

    private static String guessImageType(String name) {
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".svg")) return "image/svg+xml";
        return "image/jpeg";
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static byte[] hmac(byte[] key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }
}
