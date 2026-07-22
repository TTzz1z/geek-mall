package com.geekmall.product;

import com.geekmall.common.api.BusinessException;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class UploadFileValidator {
    static final long COVER_MAX_BYTES = 5L * 1024 * 1024;
    static final long DOCUMENT_MAX_BYTES = 10L * 1024 * 1024;

    private static final Map<String, String> COVER_TYPES = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "webp", "image/webp"
    );
    private static final Map<String, String> DOCUMENT_TYPES = Map.of(
            "pdf", "application/pdf",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "txt", "text/plain"
    );
    private static final Set<String> DANGEROUS_EXTENSIONS = Set.of(
            "bat", "cmd", "com", "cpl", "dll", "exe", "hta", "htm", "html", "jar", "js", "jsp",
            "mjs", "msi", "php", "ps1", "py", "scr", "sh", "svg", "vbs", "xhtml"
    );

    private UploadFileValidator() {
    }

    static ValidatedUpload cover(MultipartFile file) {
        return validate(file, COVER_MAX_BYTES, COVER_TYPES, true);
    }

    static ValidatedUpload document(MultipartFile file) {
        return validate(file, DOCUMENT_MAX_BYTES, DOCUMENT_TYPES, false);
    }

    private static ValidatedUpload validate(MultipartFile file, long maxBytes,
                                             Map<String, String> allowedTypes, boolean cover) {
        String label = cover ? "商品封面" : "商品资料";
        if (file == null || file.isEmpty()) {
            throw new BusinessException("PD40005", label + "不能为空");
        }
        if (file.getSize() > maxBytes) {
            throw new BusinessException(cover ? "PD41301" : "PD41302",
                    label + "不能超过" + (maxBytes / 1024 / 1024) + "MB");
        }

        String safeName = safeFileName(file.getOriginalFilename(), label);
        String extension = extensionOf(safeName);
        String expectedType = allowedTypes.get(extension);
        if (expectedType == null) {
            throw typeError(cover);
        }

        String declaredType = normalizeContentType(file.getContentType());
        if (!expectedType.equals(declaredType)) {
            throw new BusinessException("PD40008", label + "的扩展名与 Content-Type 不一致");
        }

        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            throw new BusinessException("PD40009", label + "读取失败");
        }
        if (content.length == 0) {
            throw new BusinessException("PD40005", label + "不能为空");
        }
        if (content.length > maxBytes) {
            throw new BusinessException(cover ? "PD41301" : "PD41302",
                    label + "不能超过" + (maxBytes / 1024 / 1024) + "MB");
        }
        if (!matchesContent(extension, content)) {
            throw new BusinessException("PD40010", label + "内容与声明的文件类型不一致");
        }
        return new ValidatedUpload(safeName, extension, expectedType, content);
    }

    private static BusinessException typeError(boolean cover) {
        return new BusinessException(cover ? "PD40006" : "PD40007",
                cover ? "商品封面仅支持 JPEG、PNG、WebP" : "商品资料仅支持 PDF、DOCX、XLSX、TXT");
    }

    private static String safeFileName(String originalName, String label) {
        if (originalName == null || originalName.isBlank()) {
            throw new BusinessException("PD40011", label + "文件名不能为空");
        }
        String name = originalName.strip();
        if (name.length() > 180 || name.contains("/") || name.contains("\\") || name.contains("..")
                || name.endsWith(".") || name.chars().anyMatch(ch -> Character.isISOControl(ch))) {
            throw new BusinessException("PD40011", label + "文件名不安全");
        }
        String[] parts = name.toLowerCase(Locale.ROOT).split("\\.");
        if (parts.length < 2 || Arrays.stream(parts).limit(parts.length - 1L).anyMatch(DANGEROUS_EXTENSIONS::contains)) {
            throw new BusinessException("PD40011", label + "文件名包含危险后缀");
        }
        String extension = parts[parts.length - 1];
        String base = name.substring(0, name.length() - extension.length() - 1)
                .replaceAll("[^a-zA-Z0-9_-]", "_")
                .replaceAll("_+", "_");
        if (base.isBlank()) base = "file";
        if (base.length() > 100) base = base.substring(0, 100);
        return base + "." + extension;
    }

    private static String extensionOf(String name) {
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private static String normalizeContentType(String contentType) {
        if (contentType == null) return "";
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).strip().toLowerCase(Locale.ROOT);
    }

    private static boolean matchesContent(String extension, byte[] content) {
        return switch (extension) {
            case "jpg", "jpeg" -> startsWith(content, 0xff, 0xd8, 0xff);
            case "png" -> startsWith(content, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a);
            case "webp" -> content.length >= 12 && asciiAt(content, 0, "RIFF") && asciiAt(content, 8, "WEBP");
            case "pdf" -> asciiAt(content, 0, "%PDF-");
            case "docx" -> validOfficeOpenXml(content, "word/document.xml");
            case "xlsx" -> validOfficeOpenXml(content, "xl/workbook.xml");
            case "txt" -> validUtf8Text(content);
            default -> false;
        };
    }

    private static boolean validOfficeOpenXml(byte[] content, String requiredEntry) {
        if (!startsWith(content, 0x50, 0x4b, 0x03, 0x04)) return false;
        boolean contentTypes = false;
        boolean required = false;
        int entries = 0;
        long expandedBytes = 0;
        byte[] buffer = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 10_000 || entry.getName().contains("..") || entry.getName().startsWith("/")) return false;
                contentTypes |= "[Content_Types].xml".equals(entry.getName());
                required |= requiredEntry.equals(entry.getName());
                int read;
                while ((read = zip.read(buffer)) != -1) {
                    expandedBytes += read;
                    if (expandedBytes > 50L * 1024 * 1024) return false;
                }
            }
            return contentTypes && required;
        } catch (IOException exception) {
            return false;
        }
    }

    private static boolean validUtf8Text(byte[] content) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
            return text.codePoints().noneMatch(ch -> ch == 0 || (Character.isISOControl(ch) && ch != '\n' && ch != '\r' && ch != '\t'));
        } catch (CharacterCodingException exception) {
            return false;
        }
    }

    private static boolean asciiAt(byte[] content, int offset, String value) {
        byte[] expected = value.getBytes(StandardCharsets.US_ASCII);
        if (content.length < offset + expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (content[offset + i] != expected[i]) return false;
        }
        return true;
    }

    private static boolean startsWith(byte[] content, int... expected) {
        if (content.length < expected.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if ((content[i] & 0xff) != expected[i]) return false;
        }
        return true;
    }

    record ValidatedUpload(String safeFileName, String extension, String contentType, byte[] content) {
        long size() {
            return content.length;
        }
    }
}
