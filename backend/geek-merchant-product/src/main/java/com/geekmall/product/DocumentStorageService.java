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
import java.util.UUID;

@Service
class DocumentStorageService {
    static final String PUBLIC_COVER_PREFIX = "public/covers/";
    static final String PRIVATE_DOCUMENT_PREFIX = "private/documents/";

    private static final String REGION = "us-east-1";
    private static final String SERVICE = "s3";
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

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

    StoredObject uploadDocument(long spuId, MultipartFile file) {
        UploadFileValidator.ValidatedUpload upload = UploadFileValidator.document(file);
        String objectName = PRIVATE_DOCUMENT_PREFIX + spuId + "/" + UUID.randomUUID() + "-" + upload.safeFileName();
        putObject(objectName, upload, "商品资料对象存储不可用");
        return new StoredObject(objectName, "minio://" + bucket + "/" + objectName,
                upload.safeFileName(), upload.contentType(), upload.size());
    }

    StoredObject uploadCover(long spuId, MultipartFile file) {
        UploadFileValidator.ValidatedUpload upload = UploadFileValidator.cover(file);
        String objectName = PUBLIC_COVER_PREFIX + spuId + "/" + UUID.randomUUID() + "." + upload.extension();
        try {
            ensureBucket();
            ensureCoverReadPolicy();
            putObjectInternal(objectName, upload);
            return new StoredObject(objectName, publicBaseUrl + "/" + bucket + "/" + objectName,
                    upload.safeFileName(), upload.contentType(), upload.size());
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("PD50301", "商品封面对象存储不可用");
        }
    }

    DownloadedObject downloadDocument(String reference) {
        String prefix = "minio://" + bucket + "/";
        if (reference == null || !reference.startsWith(prefix)) {
            throw new BusinessException("PD40012", "商品资料对象地址无效");
        }
        String objectName = reference.substring(prefix.length());
        if (!objectName.startsWith(PRIVATE_DOCUMENT_PREFIX) || objectName.contains("..") || objectName.contains("\\")) {
            throw new BusinessException("PD40331", "仅允许下载私有商品资料");
        }
        String storedName = objectName.substring(objectName.lastIndexOf('/') + 1);
        String fileName = storedName.replaceFirst("^[0-9a-fA-F-]{36}-", "");
        String contentType = contentTypeFor(fileName);
        try {
            HttpResponse<byte[]> response = sendForBytes("GET", "/" + bucket + "/" + objectName,
                    new byte[0], "application/octet-stream", "");
            if (response.statusCode() == 404) throw new BusinessException("PD40409", "商品资料不存在");
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("object download HTTP " + response.statusCode());
            return new DownloadedObject(fileName, contentType, response.body());
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("PD50301", "商品资料对象存储不可用");
        }
    }

    boolean isPrivateDocumentReference(String reference) {
        return reference != null && reference.startsWith("minio://" + bucket + "/" + PRIVATE_DOCUMENT_PREFIX)
                && !reference.contains("..") && !reference.contains("\\");
    }

    private void putObject(String objectName, UploadFileValidator.ValidatedUpload upload, String errorMessage) {
        try {
            ensureBucket();
            putObjectInternal(objectName, upload);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new BusinessException("PD50301", errorMessage);
        }
    }

    private void putObjectInternal(String objectName, UploadFileValidator.ValidatedUpload upload) throws Exception {
        HttpResponse<Void> response = send("PUT", "/" + bucket + "/" + objectName,
                upload.content(), upload.contentType(), "");
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("object upload HTTP " + response.statusCode());
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
        byte[] body = coverReadPolicy(bucket).getBytes(StandardCharsets.UTF_8);
        HttpResponse<Void> response = send("PUT", "/" + bucket, body, "application/json", "policy=");
        if (response.statusCode() / 100 != 2 && response.statusCode() != 204) {
            throw new IllegalStateException("bucket policy HTTP " + response.statusCode());
        }
        coverPolicyApplied = true;
    }

    static String coverReadPolicy(String bucket) {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":["*"]},"Action":["s3:GetObject"],"Resource":["arn:aws:s3:::%s/%s*"]}]}
                """.formatted(bucket, PUBLIC_COVER_PREFIX).trim();
    }

    private HttpResponse<Void> send(String method, String path, byte[] body, String contentType, String query) throws Exception {
        return http.send(signedRequest(method, path, body, contentType, query), HttpResponse.BodyHandlers.discarding());
    }

    private HttpResponse<byte[]> sendForBytes(String method, String path, byte[] body, String contentType, String query) throws Exception {
        return http.send(signedRequest(method, path, body, contentType, query), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpRequest signedRequest(String method, String path, byte[] body, String contentType, String query) throws Exception {
        Instant now = Instant.now();
        String amzDate = AMZ_DATE.format(now);
        String date = DATE.format(now);
        String payloadHash = sha256(body);
        String host = endpoint.getAuthority();
        String canonicalQuery = query == null ? "" : query;
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
        if (!canonicalQuery.isBlank()) uri += "?" + canonicalQuery;
        return HttpRequest.newBuilder(URI.create(uri)).method(method, publisher)
                .header("Content-Type", contentType)
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", amzDate)
                .header("Authorization", authorization)
                .build();
    }

    private static String contentTypeFor(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".txt")) return "text/plain";
        throw new BusinessException("PD40012", "商品资料文件类型无效");
    }

    private static String sha256(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static byte[] hmac(byte[] key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    record StoredObject(String objectPath, String reference, String fileName, String contentType, long size) {
    }

    record DownloadedObject(String fileName, String contentType, byte[] content) {
    }
}
