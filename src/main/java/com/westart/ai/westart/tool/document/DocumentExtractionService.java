package com.westart.ai.westart.tool.document;

import com.westart.ai.westart.common.exception.ApiIntegrationException;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Extracts text from document files (PDF, DOCX, XLSX, TXT) sent by WeChat users.
 */
@Service
public class DocumentExtractionService {

    private static final Logger log = LoggerFactory.getLogger(DocumentExtractionService.class);

    static final long MAX_DOCUMENT_BYTES = 10L * 1024 * 1024;
    static final int MAX_EXTRACTED_CHARS = 50_000;

    /**
     * Checks whether the given file is a supported document format.
     */
    public boolean isSupportedDocument(String fileName, byte[] bytes) {
        return detectDocumentType(fileName, bytes) != DocumentType.UNKNOWN;
    }

    /**
     * Extracts text from the document. Throws if the file is not a supported format.
     */
    public ExtractionResult extract(String fileName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new ApiIntegrationException("文档内容不能为空");
        }
        if (bytes.length > MAX_DOCUMENT_BYTES) {
            throw new ApiIntegrationException("文档不能超过 10 MB");
        }
        DocumentType type = detectDocumentType(fileName, bytes);
        if (type == DocumentType.UNKNOWN) {
            throw new ApiIntegrationException(
                    "不支持的文档格式。支持的格式：PDF、DOCX、XLSX、TXT");
        }
        String text = doExtract(type, bytes);
        if (text.isBlank()) {
            throw new ApiIntegrationException("文档内容为空或无法提取文字");
        }
        boolean truncated = false;
        if (text.length() > MAX_EXTRACTED_CHARS) {
            text = text.substring(0, MAX_EXTRACTED_CHARS);
            truncated = true;
        }
        String displayName = fileName == null || fileName.isBlank()
                ? type.displayName
                : fileName.trim();
        log.info("Extracted {} chars from {} ({})", text.length(), displayName, type);
        return new ExtractionResult(text, displayName, type.displayName, truncated);
    }

    private String doExtract(DocumentType type, byte[] bytes) {
        return switch (type) {
            case PDF -> extractPdf(bytes);
            case DOCX -> extractDocx(bytes);
            case DOC -> extractDoc(bytes);
            case XLSX -> extractXlsx(bytes);
            case TXT -> extractTxt(bytes);
            default -> throw new ApiIntegrationException("不支持的文档格式");
        };
    }

    private String extractPdf(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            return text == null ? "" : text.strip();
        } catch (IOException exception) {
            throw new ApiIntegrationException("PDF 解析失败：" + exception.getMessage(), exception);
        }
    }

    private String extractDocx(byte[] bytes) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes);
             XWPFDocument document = new XWPFDocument(input)) {
            StringBuilder sb = new StringBuilder();
            for (XWPFParagraph paragraph : document.getParagraphs()) {
                String text = paragraph.getText();
                if (text != null && !text.isBlank()) {
                    sb.append(text.strip()).append('\n');
                }
            }
            return sb.toString().strip();
        } catch (IOException exception) {
            throw new ApiIntegrationException("Word 文档解析失败：" + exception.getMessage(), exception);
        }
    }

    private String extractDoc(byte[] bytes) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes);
             HWPFDocument document = new HWPFDocument(input);
             WordExtractor extractor = new WordExtractor(document)) {
            String text = extractor.getText();
            return text == null ? "" : text.strip();
        } catch (IOException exception) {
            throw new ApiIntegrationException("Word 文档解析失败：" + exception.getMessage(), exception);
        }
    }

    private String extractXlsx(byte[] bytes) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes);
             Workbook workbook = WorkbookFactory.create(input)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                if (workbook.getNumberOfSheets() > 1) {
                    sb.append("[").append(sheet.getSheetName()).append("]\n");
                }
                for (Row row : sheet) {
                    boolean hasContent = false;
                    StringBuilder rowSb = new StringBuilder();
                    for (Cell cell : row) {
                        String value = cellStringValue(cell);
                        if (!value.isBlank()) {
                            hasContent = true;
                        }
                        rowSb.append(value).append('\t');
                    }
                    if (hasContent) {
                        // Remove trailing tab
                        if (rowSb.length() > 0) {
                            rowSb.setLength(rowSb.length() - 1);
                        }
                        sb.append(rowSb).append('\n');
                    }
                }
            }
            return sb.toString().strip();
        } catch (IOException exception) {
            throw new ApiIntegrationException("Excel 文档解析失败：" + exception.getMessage(), exception);
        }
    }

    private static String cellStringValue(Cell cell) {
        if (cell == null) {
            return "";
        }
        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue() == null ? "" : cell.getStringCellValue().strip();
            case NUMERIC -> {
                double num = cell.getNumericCellValue();
                if (num == Math.floor(num) && !Double.isInfinite(num)) {
                    yield String.valueOf((long) num);
                }
                yield String.valueOf(num);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try {
                    yield cell.getStringCellValue();
                } catch (Exception e) {
                    try {
                        yield String.valueOf(cell.getNumericCellValue());
                    } catch (Exception e2) {
                        yield "";
                    }
                }
            }
            default -> "";
        };
    }

    private String extractTxt(byte[] bytes) {
        // Try UTF-8 first; fall back to GBK for Chinese text files
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (!containsReplacementChar(text)) {
            return text.strip();
        }
        text = new String(bytes, Charset.forName("GBK"));
        return text.strip();
    }

    private static boolean containsReplacementChar(String text) {
        return text.indexOf('�') >= 0;
    }

    static DocumentType detectDocumentType(String fileName, byte[] bytes) {
        // Magic-byte detection first
        if (bytes != null && bytes.length >= 4) {
            // PDF: %PDF
            if (bytes[0] == 0x25 && bytes[1] == 0x50
                    && bytes[2] == 0x44 && bytes[3] == 0x46) {
                return DocumentType.PDF;
            }
            // ZIP-based formats (DOCX, XLSX): PK\x03\x04
            if (bytes[0] == 0x50 && bytes[1] == 0x4B
                    && bytes[2] == 0x03 && bytes[3] == 0x04) {
                // Distinguish DOCX vs XLSX by extension
                String ext = extensionOf(fileName);
                if ("xlsx".equals(ext) || "xls".equals(ext)) {
                    return DocumentType.XLSX;
                }
                return DocumentType.DOCX;
            }
            // OLE2 compound document (.doc, .xls): D0 CF 11 E0
            if ((bytes[0] & 0xFF) == 0xD0 && (bytes[1] & 0xFF) == 0xCF
                    && (bytes[2] & 0xFF) == 0x11 && (bytes[3] & 0xFF) == 0xE0) {
                String ext = extensionOf(fileName);
                if ("xls".equals(ext)) {
                    return DocumentType.XLSX;
                }
                return DocumentType.DOC;
            }
        }
        // Fall back to extension
        String ext = extensionOf(fileName);
        return switch (ext) {
            case "pdf" -> DocumentType.PDF;
            case "docx" -> DocumentType.DOCX;
            case "doc" -> DocumentType.DOC;
            case "xlsx", "xls" -> DocumentType.XLSX;
            case "txt", "text", "md", "csv", "log", "json", "xml",
                 "yaml", "yml", "properties", "ini", "cfg" -> DocumentType.TXT;
            default -> DocumentType.UNKNOWN;
        };
    }

    private static String extensionOf(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        String normalized = fileName.trim().toLowerCase(Locale.ROOT);
        int dot = normalized.lastIndexOf('.');
        return dot < 0 || dot == normalized.length() - 1 ? "" : normalized.substring(dot + 1);
    }

    enum DocumentType {
        PDF("PDF"),
        DOCX("Word"),
        DOC("Word"),
        XLSX("Excel"),
        TXT("TXT"),
        UNKNOWN("未知");

        final String displayName;

        DocumentType(String displayName) {
            this.displayName = displayName;
        }
    }

    public record ExtractionResult(
            String text,
            String fileName,
            String fileType,
            boolean truncated
    ) {
    }
}
