package dev.knowledge.platform.ingestion;

import dev.knowledge.ApiException;
import dev.knowledge.MarkdownChunker;
import dev.knowledge.RetrievalConfiguration;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.zip.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.*;

/**
 * Real parsers for MD / TXT / PDF / DOCX / XLSX producing chunks with provable source locators. Scanned PDFs without a
 * text layer are reported as NEEDS_OCR (no OCR engine is bundled; nothing is guessed). Document text is data only.
 */
public final class DocumentParser {
    public static final String VERSION = "atlas-parser-1";
    public enum Kind { MARKDOWN, TEXT, PDF, DOCX, XLSX }
    public record ParsedChunk(String chunkId, String heading, String content, Map<String, Object> locator) {}
    public record Parsed(Kind kind, List<ParsedChunk> chunks, Integer pageCount, boolean needsOcr) {}

    static final long MAX_ZIP_ENTRIES = 10_000;
    static final long MAX_UNCOMPRESSED = 200L * 1024 * 1024;
    static final int MAX_TEXT_CHARS = 5_000_000;
    private final MarkdownChunker chunker;

    public DocumentParser(int maxChars) {
        chunker = new MarkdownChunker(new RetrievalConfiguration(maxChars, RetrievalConfiguration.baseline().topK(), RetrievalConfiguration.baseline().coverageThreshold()));
    }

    /** Validates name, extension and magic bytes together; the client-declared MIME type is never trusted. */
    public static Kind detect(String filename, byte[] bytes) {
        if (filename == null || filename.isBlank() || filename.length() > 255 || filename.contains("/") || filename.contains("\\") || filename.contains("\0"))
            throw new ApiException(400, "INVALID_FILENAME", "File name is invalid.");
        String lower = filename.toLowerCase(Locale.ROOT);
        Kind kind;
        if (lower.endsWith(".md") || lower.endsWith(".markdown")) kind = Kind.MARKDOWN;
        else if (lower.endsWith(".txt")) kind = Kind.TEXT;
        else if (lower.endsWith(".pdf")) kind = Kind.PDF;
        else if (lower.endsWith(".docx")) kind = Kind.DOCX;
        else if (lower.endsWith(".xlsx")) kind = Kind.XLSX;
        else throw new ApiException(415, "UNSUPPORTED_TYPE", "Supported formats: .md, .txt, .pdf, .docx, .xlsx.");
        if (bytes.length == 0) throw new ApiException(400, "EMPTY_FILE", "The file is empty.");
        switch (kind) {
            case PDF -> { if (!startsWith(bytes, "%PDF-")) throw mismatch(); }
            case DOCX -> { if (!startsWith(bytes, "PK\u0003\u0004")) throw mismatch(); inspectZip(bytes, "word/document.xml"); }
            case XLSX -> { if (!startsWith(bytes, "PK\u0003\u0004")) throw mismatch(); inspectZip(bytes, "xl/workbook.xml"); }
            default -> { strictUtf8(bytes); }
        }
        return kind;
    }
    public static String mimeType(Kind kind) {
        return switch (kind) {
            case MARKDOWN -> "text/markdown"; case TEXT -> "text/plain"; case PDF -> "application/pdf";
            case DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        };
    }
    private static ApiException mismatch() { return new ApiException(415, "CONTENT_MISMATCH", "File content does not match its extension."); }
    private static boolean startsWith(byte[] bytes, String prefix) {
        var p = prefix.getBytes(StandardCharsets.ISO_8859_1);
        if (bytes.length < p.length) return false;
        for (int i = 0; i < p.length; i++) if (bytes[i] != p[i]) return false;
        return true;
    }
    /** Zip-bomb / path traversal guard before any Office parser touches the archive. */
    static void inspectZip(byte[] bytes, String required) {
        boolean found = false; long entries = 0, total = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry e; var buffer = new byte[64 * 1024];
            while ((e = zip.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) throw new ApiException(400, "ARCHIVE_REJECTED", "The document archive has too many entries.");
                var name = e.getName();
                if (name.startsWith("/") || name.contains("..") || name.contains("\\")) throw new ApiException(400, "ARCHIVE_REJECTED", "The document archive contains unsafe paths.");
                if (name.equals(required)) found = true;
                int n; while ((n = zip.read(buffer)) > 0) { total += n; if (total > MAX_UNCOMPRESSED) throw new ApiException(400, "ARCHIVE_REJECTED", "The document expands beyond the allowed size."); }
            }
        } catch (ApiException ex) { throw ex; }
        catch (IOException ex) { throw new ApiException(400, "CORRUPT_FILE", "The document archive cannot be read."); }
        if (!found) throw mismatch();
    }
    static String strictUtf8(byte[] bytes) {
        try {
            var text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if (text.indexOf('\0') >= 0) throw new ApiException(415, "BINARY_TEXT", "Text files must not contain binary data.");
            if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
            return text;
        } catch (CharacterCodingException e) { throw new ApiException(415, "ENCODING", "Text files must be UTF-8 encoded."); }
    }

    public Parsed parse(Kind kind, String chunkPrefix, byte[] bytes) {
        return switch (kind) {
            case MARKDOWN, TEXT -> new Parsed(kind, fromMarkdown(chunkPrefix, limit(strictUtf8(bytes)), Map.of()), null, false);
            case PDF -> pdf(chunkPrefix, bytes);
            case DOCX -> new Parsed(kind, fromMarkdown(chunkPrefix, limit(docxToMarkdown(bytes)), Map.of()), null, false);
            case XLSX -> new Parsed(kind, xlsx(chunkPrefix, bytes), null, false);
        };
    }
    private static String limit(String text) {
        if (text.length() > MAX_TEXT_CHARS) throw new ApiException(413, "TEXT_TOO_LARGE", "Extracted text exceeds 5,000,000 characters.");
        return text;
    }

    private List<ParsedChunk> fromMarkdown(String prefix, String markdown, Map<String, Object> extra) {
        if (markdown.isBlank()) return List.of();
        List<dev.knowledge.Models.Chunk> chunks;
        try { chunks = chunker.split(prefix, markdown); }
        catch (ApiException e) { if ("EMPTY_DOCUMENT".equals(e.code)) return List.of(); throw e; }
        var result = new ArrayList<ParsedChunk>();
        for (var c : chunks) {
            var locator = new LinkedHashMap<String, Object>(extra);
            locator.put("lineStart", c.lineStart()); locator.put("lineEnd", c.lineEnd()); locator.put("section", c.heading());
            result.add(new ParsedChunk(c.id(), c.heading(), c.text(), locator));
        }
        return result;
    }

    private Parsed pdf(String prefix, byte[] bytes) {
        try (var doc = Loader.loadPDF(bytes)) {
            if (doc.isEncrypted()) throw new ApiException(400, "ENCRYPTED_PDF", "Encrypted PDFs are not supported.");
            int pages = doc.getNumberOfPages();
            var stripper = new PDFTextStripper();
            var all = new ArrayList<ParsedChunk>(); long chars = 0;
            for (int page = 1; page <= pages; page++) {
                stripper.setStartPage(page); stripper.setEndPage(page);
                var text = stripper.getText(doc).replace("\r\n", "\n").strip();
                chars += text.length();
                if (chars > MAX_TEXT_CHARS) throw new ApiException(413, "TEXT_TOO_LARGE", "Extracted text exceeds the limit.");
                if (text.isBlank()) continue;
                // Page-local line numbers; ids stay unique across pages via the page-qualified prefix.
                for (var c : fromMarkdown(prefix + "-p" + page, "# Page " + page + "\n" + text, Map.of("page", page))) all.add(c);
            }
            boolean needsOcr = pages > 0 && chars < Math.max(20, pages * 10L);
            return new Parsed(Kind.PDF, needsOcr ? List.of() : all, pages, needsOcr);
        } catch (ApiException e) { throw e; }
        catch (IOException e) { throw new ApiException(400, "CORRUPT_FILE", "The PDF cannot be read."); }
    }

    static String docxToMarkdown(byte[] bytes) {
        ZipSecureFile.setMinInflateRatio(0.01);
        try (var doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            var out = new StringBuilder();
            for (var element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph p) {
                    var text = p.getText().strip();
                    if (text.isEmpty()) { out.append('\n'); continue; }
                    int level = headingLevel(p.getStyleID(), p.getStyle());
                    if (level > 0) out.append("\n").append("#".repeat(level)).append(' ').append(text.replace('\n', ' ')).append("\n\n");
                    else out.append(text).append("\n\n");
                } else if (element instanceof XWPFTable table) {
                    for (var row : table.getRows()) {
                        var cells = row.getTableCells().stream().map(c -> c.getText().replace('\n', ' ').replace("|", "/").strip()).toList();
                        out.append("| ").append(String.join(" | ", cells)).append(" |\n");
                    }
                    out.append('\n');
                }
            }
            return out.toString();
        } catch (IOException | RuntimeException e) { throw new ApiException(400, "CORRUPT_FILE", "The Word document cannot be read."); }
    }
    private static int headingLevel(String styleId, String style) {
        for (var s : new String[]{styleId, style}) {
            if (s == null) continue;
            var m = java.util.regex.Pattern.compile("(?i)heading\\s*([1-6])|標題\\s*([1-6])").matcher(s);
            if (m.find()) return Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2));
            if (s.equalsIgnoreCase("Title")) return 1;
        }
        return 0;
    }

    private List<ParsedChunk> xlsx(String prefix, byte[] bytes) {
        ZipSecureFile.setMinInflateRatio(0.01);
        var formatter = new DataFormatter(Locale.ROOT);
        var result = new ArrayList<ParsedChunk>();
        var occurrences = new HashMap<String, Integer>();
        try (var wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            long chars = 0;
            for (var sheet : wb) {
                List<String> header = null;
                var block = new StringBuilder(); int blockStart = -1, blockEnd = -1;
                for (var row : sheet) {
                    var values = new ArrayList<String>();
                    for (int c = 0; c < Math.max(0, row.getLastCellNum()); c++) {
                        var cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                        values.add(cell == null ? "" : formatter.formatCellValue(cell).strip());
                    }
                    if (values.stream().allMatch(String::isEmpty)) continue;
                    if (header == null) { header = values; continue; }
                    var line = new StringBuilder();
                    for (int i = 0; i < values.size(); i++) {
                        if (values.get(i).isEmpty()) continue;
                        String name = i < header.size() && !header.get(i).isEmpty() ? header.get(i) : "Column " + (i + 1);
                        if (!line.isEmpty()) line.append("; ");
                        line.append(name).append(": ").append(values.get(i));
                    }
                    int rowNo = row.getRowNum() + 1;
                    String text = "Row " + rowNo + " — " + line;
                    chars += text.length();
                    if (chars > MAX_TEXT_CHARS) throw new ApiException(413, "TEXT_TOO_LARGE", "Extracted text exceeds the limit.");
                    if (!block.isEmpty() && block.length() + text.length() + 1 > 800) {
                        emitRows(result, occurrences, prefix, sheet.getSheetName(), block, blockStart, blockEnd);
                    }
                    if (block.isEmpty()) blockStart = rowNo; else block.append('\n');
                    block.append(text.length() > 800 ? text.substring(0, 800) : text); blockEnd = rowNo;
                }
                emitRows(result, occurrences, prefix, sheet.getSheetName(), block, blockStart, blockEnd);
            }
            return result;
        } catch (ApiException e) { throw e; }
        catch (IOException | RuntimeException e) { throw new ApiException(400, "CORRUPT_FILE", "The spreadsheet cannot be read."); }
    }
    private static void emitRows(List<ParsedChunk> out, Map<String, Integer> occurrences, String prefix, String sheet, StringBuilder block, int start, int end) {
        if (block.isEmpty()) return;
        var body = block.toString();
        var fingerprint = MarkdownChunker.hash(prefix + "\n" + sheet + "\n" + body);
        int n = occurrences.merge(fingerprint, 1, Integer::sum);
        var locator = new LinkedHashMap<String, Object>();
        locator.put("sheet", sheet); locator.put("row", start); locator.put("rowEnd", end); locator.put("section", sheet);
        out.add(new ParsedChunk(prefix + "-" + fingerprint.substring(0, 16) + "-" + n, sheet, body, locator));
        block.setLength(0);
    }
}
