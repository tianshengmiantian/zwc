package com.westart.ai.westart.tool.document;

import com.westart.ai.westart.common.exception.ApiIntegrationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentExtractionServiceTest {

    private DocumentExtractionService service;

    @BeforeEach
    void setUp() {
        service = new DocumentExtractionService();
    }

    // ---- isSupportedDocument ----

    @Test
    void isSupportedDocument_txt() {
        byte[] bytes = "Hello, world!".getBytes(StandardCharsets.UTF_8);
        assertTrue(service.isSupportedDocument("notes.txt", bytes));
    }

    @Test
    void isSupportedDocument_md() {
        byte[] bytes = "# Title".getBytes(StandardCharsets.UTF_8);
        assertTrue(service.isSupportedDocument("readme.md", bytes));
    }

    @Test
    void isSupportedDocument_csv() {
        byte[] bytes = "a,b,c".getBytes(StandardCharsets.UTF_8);
        assertTrue(service.isSupportedDocument("data.csv", bytes));
    }

    @Test
    void isSupportedDocument_json() {
        byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
        assertTrue(service.isSupportedDocument("config.json", bytes));
    }

    @Test
    void isSupportedDocument_unknown() {
        byte[] bytes = {0x00, 0x01, 0x02};
        assertFalse(service.isSupportedDocument("image.png", bytes));
    }

    @Test
    void isSupportedDocument_pdfMagic() {
        byte[] bytes = new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D, 0x31, 0x2E, 0x34};
        assertTrue(service.isSupportedDocument("document.pdf", bytes));
    }

    @Test
    void isSupportedDocument_docxMagic() {
        // ZIP magic bytes (DOCX is a ZIP file)
        byte[] bytes = new byte[]{0x50, 0x4B, 0x03, 0x04, 0x00, 0x00};
        assertTrue(service.isSupportedDocument("report.docx", bytes));
    }

    @Test
    void isSupportedDocument_xlsxMagic() {
        byte[] bytes = new byte[]{0x50, 0x4B, 0x03, 0x04, 0x00, 0x00};
        assertTrue(service.isSupportedDocument("data.xlsx", bytes));
    }

    // ---- extract TXT ----

    @Test
    void extract_txtUtf8() {
        String content = "Hello, world!\nSecond line.";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        DocumentExtractionService.ExtractionResult result = service.extract("notes.txt", bytes);
        assertEquals("Hello, world!\nSecond line.", result.text());
        assertEquals("notes.txt", result.fileName());
        assertEquals("TXT", result.fileType());
        assertFalse(result.truncated());
    }

    @Test
    void extract_txtGbk() {
        String content = "中文内容测试";
        byte[] bytes = content.getBytes(java.nio.charset.Charset.forName("GBK"));
        DocumentExtractionService.ExtractionResult result = service.extract("notes.txt", bytes);
        assertEquals("中文内容测试", result.text());
    }

    @Test
    void extract_txtEmpty() {
        byte[] bytes = "".getBytes(StandardCharsets.UTF_8);
        assertThrows(ApiIntegrationException.class, () -> service.extract("empty.txt", bytes));
    }

    @Test
    void extract_txtWhitespaceOnly() {
        byte[] bytes = "   \n  \t  ".getBytes(StandardCharsets.UTF_8);
        assertThrows(ApiIntegrationException.class, () -> service.extract("blank.txt", bytes));
    }

    // ---- extract with size limits ----

    @Test
    void extract_tooLarge() {
        byte[] bytes = new byte[(int) (DocumentExtractionService.MAX_DOCUMENT_BYTES + 1)];
        assertThrows(ApiIntegrationException.class, () -> service.extract("large.txt", bytes));
    }

    @Test
    void extract_nullBytes() {
        assertThrows(ApiIntegrationException.class, () -> service.extract("null.txt", null));
    }

    @Test
    void extract_emptyBytes() {
        assertThrows(ApiIntegrationException.class, () -> service.extract("empty.txt", new byte[0]));
    }

    // ---- unsupported format ----

    @Test
    void extract_unsupportedFormat() {
        byte[] bytes = "data".getBytes(StandardCharsets.UTF_8);
        assertThrows(ApiIntegrationException.class, () -> service.extract("image.png", bytes));
    }

    // ---- detectDocumentType ----

    @Test
    void detectDocumentType_byExtension() {
        assertEquals(DocumentExtractionService.DocumentType.PDF,
                DocumentExtractionService.detectDocumentType("report.pdf", null));
        assertEquals(DocumentExtractionService.DocumentType.DOCX,
                DocumentExtractionService.detectDocumentType("report.docx", null));
        assertEquals(DocumentExtractionService.DocumentType.XLSX,
                DocumentExtractionService.detectDocumentType("data.xlsx", null));
        assertEquals(DocumentExtractionService.DocumentType.TXT,
                DocumentExtractionService.detectDocumentType("readme.md", null));
        assertEquals(DocumentExtractionService.DocumentType.TXT,
                DocumentExtractionService.detectDocumentType("config.json", null));
        assertEquals(DocumentExtractionService.DocumentType.TXT,
                DocumentExtractionService.detectDocumentType("data.yaml", null));
        assertEquals(DocumentExtractionService.DocumentType.UNKNOWN,
                DocumentExtractionService.detectDocumentType("photo.jpg", null));
    }

    @Test
    void detectDocumentType_byMagicBytes() {
        byte[] pdf = {0x25, 0x50, 0x44, 0x46};
        assertEquals(DocumentExtractionService.DocumentType.PDF,
                DocumentExtractionService.detectDocumentType("unknown", pdf));

        byte[] zip = {0x50, 0x4B, 0x03, 0x04};
        assertEquals(DocumentExtractionService.DocumentType.DOCX,
                DocumentExtractionService.detectDocumentType("unknown", zip));

        byte[] zipXlsx = {0x50, 0x4B, 0x03, 0x04};
        assertEquals(DocumentExtractionService.DocumentType.XLSX,
                DocumentExtractionService.detectDocumentType("data.xlsx", zipXlsx));
    }

    @Test
    void detectDocumentType_noExtensionNoMagic() {
        assertEquals(DocumentExtractionService.DocumentType.UNKNOWN,
                DocumentExtractionService.detectDocumentType("unknown", new byte[]{0x00}));
    }

    // ---- truncation ----

    @Test
    void extract_truncation() {
        // Create a string that exceeds MAX_EXTRACTED_CHARS
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < DocumentExtractionService.MAX_EXTRACTED_CHARS + 1000; i++) {
            sb.append('A');
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        DocumentExtractionService.ExtractionResult result = service.extract("long.txt", bytes);
        assertEquals(DocumentExtractionService.MAX_EXTRACTED_CHARS, result.text().length());
        assertTrue(result.truncated());
    }

    @Test
    void extract_noTruncation() {
        String content = "Short text";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        DocumentExtractionService.ExtractionResult result = service.extract("short.txt", bytes);
        assertFalse(result.truncated());
    }

    // ---- fileName handling ----

    @Test
    void extract_blankFileName_noExtension() {
        byte[] bytes = "content".getBytes(StandardCharsets.UTF_8);
        // No extension and no magic bytes → unsupported
        assertThrows(ApiIntegrationException.class, () -> service.extract("", bytes));
    }

    @Test
    void extract_nullFileName_noExtension() {
        byte[] bytes = "content".getBytes(StandardCharsets.UTF_8);
        assertThrows(ApiIntegrationException.class, () -> service.extract(null, bytes));
    }

    @Test
    void extract_blankFileName_withExtension() {
        byte[] bytes = "content".getBytes(StandardCharsets.UTF_8);
        DocumentExtractionService.ExtractionResult result = service.extract("  .txt  ", bytes);
        assertEquals(".txt", result.fileName());
    }
}
