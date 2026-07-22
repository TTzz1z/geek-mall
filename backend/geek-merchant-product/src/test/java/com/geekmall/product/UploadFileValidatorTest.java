package com.geekmall.product;

import com.geekmall.common.api.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadFileValidatorTest {

    @Test
    void acceptsAllowedCoverFilesWithMatchingMagicNumbers() {
        var jpeg = UploadFileValidator.cover(file("cover.jpg", "image/jpeg", bytes(0xff, 0xd8, 0xff, 0xe0)));
        var png = UploadFileValidator.cover(file("cover.png", "image/png",
                bytes(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)));
        var webp = UploadFileValidator.cover(file("cover.webp", "image/webp",
                "RIFF0000WEBP".getBytes(StandardCharsets.US_ASCII)));

        assertThat(jpeg.contentType()).isEqualTo("image/jpeg");
        assertThat(png.extension()).isEqualTo("png");
        assertThat(webp.extension()).isEqualTo("webp");
    }

    @Test
    void rejectsGifSvgAndOtherIllegalExtensions() {
        assertThatThrownBy(() -> UploadFileValidator.cover(file("cover.gif", "image/gif", "GIF89a".getBytes())))
                .isInstanceOf(BusinessException.class).hasMessageContaining("仅支持 JPEG、PNG、WebP");
        assertThatThrownBy(() -> UploadFileValidator.cover(file("cover.svg", "image/svg+xml", "<svg/>".getBytes())))
                .isInstanceOf(BusinessException.class).hasMessageContaining("仅支持 JPEG、PNG、WebP");
        assertThatThrownBy(() -> UploadFileValidator.document(file("payload.exe", "application/octet-stream", bytes(1))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("仅支持 PDF、DOCX、XLSX、TXT");
    }

    @Test
    void rejectsForgedMimeAndMagicNumber() {
        assertThatThrownBy(() -> UploadFileValidator.cover(file("cover.jpg", "image/png", bytes(0xff, 0xd8, 0xff))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Content-Type");
        assertThatThrownBy(() -> UploadFileValidator.cover(file("cover.png", "image/png", bytes(0xff, 0xd8, 0xff))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("内容与声明");
        assertThatThrownBy(() -> UploadFileValidator.document(file("spec.pdf", "application/pdf", "MZ fake".getBytes())))
                .isInstanceOf(BusinessException.class).hasMessageContaining("内容与声明");
    }

    @Test
    void rejectsOversizedFilesBeforeStorage() {
        byte[] oversizedCover = new byte[(int) UploadFileValidator.COVER_MAX_BYTES + 1];
        oversizedCover[0] = (byte) 0xff;
        oversizedCover[1] = (byte) 0xd8;
        oversizedCover[2] = (byte) 0xff;

        assertThatThrownBy(() -> UploadFileValidator.cover(file("cover.jpg", "image/jpeg", oversizedCover)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能超过5MB");

        byte[] oversizedDocument = new byte[(int) UploadFileValidator.DOCUMENT_MAX_BYTES + 1];
        byte[] pdfMagic = "%PDF-".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(pdfMagic, 0, oversizedDocument, 0, pdfMagic.length);
        assertThatThrownBy(() -> UploadFileValidator.document(file("spec.pdf", "application/pdf", oversizedDocument)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能超过10MB");
    }

    @Test
    void acceptsWhitelistedDocumentFormats() throws IOException {
        var pdf = UploadFileValidator.document(file("spec.pdf", "application/pdf", "%PDF-1.7\n%%EOF".getBytes()));
        var docx = UploadFileValidator.document(file("manual.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                officeFile("word/document.xml")));
        var xlsx = UploadFileValidator.document(file("parameters.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                officeFile("xl/workbook.xml")));
        var txt = UploadFileValidator.document(file("readme.txt", "text/plain", "UTF-8 商品说明".getBytes(StandardCharsets.UTF_8)));

        assertThat(pdf.contentType()).isEqualTo("application/pdf");
        assertThat(docx.extension()).isEqualTo("docx");
        assertThat(xlsx.extension()).isEqualTo("xlsx");
        assertThat(txt.extension()).isEqualTo("txt");
    }

    @Test
    void rejectsPathTraversalAndDangerousDoubleSuffix() {
        assertThatThrownBy(() -> UploadFileValidator.document(file("../spec.pdf", "application/pdf", "%PDF-".getBytes())))
                .isInstanceOf(BusinessException.class).hasMessageContaining("文件名不安全");
        assertThatThrownBy(() -> UploadFileValidator.document(file("invoice.exe.pdf", "application/pdf", "%PDF-".getBytes())))
                .isInstanceOf(BusinessException.class).hasMessageContaining("危险后缀");
    }

    @Test
    void minioPolicyPublishesOnlyCoverPrefix() {
        String policy = DocumentStorageService.coverReadPolicy("products");
        assertThat(policy).contains("arn:aws:s3:::products/public/covers/*");
        assertThat(policy).doesNotContain(DocumentStorageService.PRIVATE_DOCUMENT_PREFIX);
    }

    private static MockMultipartFile file(String name, String contentType, byte[] content) {
        return new MockMultipartFile("file", name, contentType, content);
    }

    private static byte[] officeFile(String requiredEntry) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, String> entry : Map.of(
                    "[Content_Types].xml", "<Types/>",
                    requiredEntry, "<document/>"
            ).entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) result[i] = (byte) values[i];
        return result;
    }
}
