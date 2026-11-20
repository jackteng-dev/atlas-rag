package dev.knowledge.platform.unit;

import dev.knowledge.ApiException;
import dev.knowledge.platform.ingestion.DocumentParser;
import dev.knowledge.platform.ingestion.DocumentParser.Kind;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class DocumentParserTest {
    private final DocumentParser parser = new DocumentParser(800);

    @ParameterizedTest
    @CsvSource({"notes.md, MARKDOWN", "notes.markdown, MARKDOWN", "notes.txt, TEXT",
            "NOTES.MD, MARKDOWN", "NOTES.MARKDOWN, MARKDOWN", "NOTES.TXT, TEXT"})
    void detectsAllowedTextExtensions(String filename, Kind expected) {
        assertThat(DocumentParser.detect(filename, utf8("A valid text document."))).isEqualTo(expected);
    }

    @Test
    void detectsPdfByExtensionAndMagicBytes() throws Exception {
        byte[] bytes = pdf("A valid PDF document with a text layer.");
        assertThat(DocumentParser.detect("notes.pdf", bytes)).isEqualTo(Kind.PDF);
        assertThat(DocumentParser.detect("NOTES.PDF", bytes)).isEqualTo(Kind.PDF);
    }

    @Test
    void detectsOfficeExtensionsWithRequiredArchiveEntries() throws Exception {
        // Detection checks the ZIP structure and required entry; parsing is a separate operation.
        byte[] docx = zip("word/document.xml");
        byte[] xlsx = zip("xl/workbook.xml");
        assertThat(DocumentParser.detect("notes.docx", docx)).isEqualTo(Kind.DOCX);
        assertThat(DocumentParser.detect("NOTES.DOCX", docx)).isEqualTo(Kind.DOCX);
        assertThat(DocumentParser.detect("notes.xlsx", xlsx)).isEqualTo(Kind.XLSX);
        assertThat(DocumentParser.detect("NOTES.XLSX", xlsx)).isEqualTo(Kind.XLSX);
    }

    @ParameterizedTest
    @ValueSource(strings = {"tool.exe", "page.html", "archive.zip", "notes", "notes.md.exe"})
    void rejectsUnsupportedExtensions(String filename) {
        assertDetectError(filename, utf8("Some content"), 415, "UNSUPPORTED_TYPE");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "../x.md", "a/b.md", "a\\b.md", "bad\0.md"})
    void rejectsInvalidFilenames(String filename) {
        assertDetectError(filename, utf8("Some content"), 400, "INVALID_FILENAME");
    }

    @Test
    void enforcesFilenameLengthBoundary() {
        assertThat(DocumentParser.detect("a".repeat(252) + ".md", utf8("Text"))).isEqualTo(Kind.MARKDOWN);
        assertDetectError("a".repeat(253) + ".md", utf8("Text"), 400, "INVALID_FILENAME");
    }

    @ParameterizedTest
    @ValueSource(strings = {"notes.md", "notes.markdown", "notes.txt", "notes.pdf", "notes.docx", "notes.xlsx"})
    void rejectsEmptyFiles(String filename) {
        assertDetectError(filename, new byte[0], 400, "EMPTY_FILE");
    }

    @Test
    void rejectsPdfWithoutPdfSignature() {
        assertDetectError("fake.pdf", utf8("This is not a PDF."), 415, "CONTENT_MISMATCH");
    }

    @ParameterizedTest
    @ValueSource(strings = {"notes.md", "notes.markdown", "notes.txt"})
    void rejectsMalformedUtf8(String filename) {
        assertDetectError(filename, new byte[]{(byte) 0xc3, 0x28}, 415, "ENCODING");
    }

    @ParameterizedTest
    @ValueSource(strings = {"notes.md", "notes.markdown", "notes.txt"})
    void rejectsNulInText(String filename) {
        assertDetectError(filename, utf8("Before\0after"), 415, "BINARY_TEXT");
    }

    @Test
    void rejectsZipPathTraversalEvenWhenRequiredEntryExists() throws Exception {
        assertDetectError("unsafe.docx", zip("word/document.xml", "../evil"), 400, "ARCHIVE_REJECTED");
    }

    @Test
    void rejectsValidZipWithoutWordDocumentEntry() throws Exception {
        assertDetectError("wrong.docx", zip("other.xml"), 415, "CONTENT_MISMATCH");
    }

    @Test
    void markdownChunksLocateSourceLinesAndSections() {
        byte[] bytes = utf8("# Leave policy\n\nApply seven days ahead.\nGet manager approval.\n\n## Remote work\n\nTwo days per week.\n");
        var parsed = parser.parse(Kind.MARKDOWN, "policy-v1", bytes);

        assertThat(parsed.kind()).isEqualTo(Kind.MARKDOWN);
        assertThat(parsed.needsOcr()).isFalse();
        assertThat(parsed.pageCount()).isNull();
        assertThat(parsed.chunks()).hasSize(2);
        var first = parsed.chunks().getFirst();
        assertThat(first.heading()).isEqualTo("Leave policy");
        assertThat(first.content()).isEqualTo("Apply seven days ahead.\nGet manager approval.");
        assertThat(first.locator()).containsEntry("lineStart", 3).containsEntry("lineEnd", 4)
                .containsEntry("section", "Leave policy");
        var second = parsed.chunks().get(1);
        assertThat(second.heading()).isEqualTo("Remote work");
        assertThat(second.content()).isEqualTo("Two days per week.");
        assertThat(second.locator()).containsEntry("lineStart", 8).containsEntry("lineEnd", 8)
                .containsEntry("section", "Remote work");
    }

    @Test
    void markdownChunkIdsAreDeterministicPrefixedAndUniqueForRepeatedContent() {
        byte[] bytes = utf8("# Policy\n\nRepeated paragraph.\n\nRepeated paragraph.\n");
        var first = parser.parse(Kind.MARKDOWN, "policy-v1", bytes).chunks();
        var second = new DocumentParser(800).parse(Kind.MARKDOWN, "policy-v1", bytes).chunks();

        assertThat(first).hasSize(2).isEqualTo(second);
        assertThat(first).extracting(DocumentParser.ParsedChunk::chunkId).doesNotHaveDuplicates();
        assertThat(first).allSatisfy(chunk -> assertThat(chunk.chunkId()).startsWith("policy-v1-"));
    }

    @Test
    void xlsxUsesHeaderFieldNamesAndOneBasedSheetRowLocators() throws Exception {
        byte[] bytes;
        try (var workbook = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var parts = workbook.createSheet("Parts");
            var header = parts.createRow(0);
            header.createCell(0).setCellValue("Part No");
            header.createCell(1).setCellValue("Max Voltage");
            var row = parts.createRow(1);
            row.createCell(0).setCellValue("MCU-200");
            row.createCell(1).setCellValue(5.5);
            var next = parts.createRow(2);
            next.createCell(0).setCellValue("MCU-300");
            next.createCell(1).setCellValue(3.3);
            var stock = workbook.createSheet("Stock");
            stock.createRow(0).createCell(0).setCellValue("Quantity");
            stock.createRow(1).createCell(0).setCellValue(42);
            workbook.write(out);
            bytes = out.toByteArray();
        }

        assertThat(DocumentParser.detect("parts.xlsx", bytes)).isEqualTo(Kind.XLSX);
        var parsed = parser.parse(Kind.XLSX, "parts-v1", bytes);
        assertThat(parsed.kind()).isEqualTo(Kind.XLSX);
        assertThat(parsed.needsOcr()).isFalse();
        assertThat(parsed.chunks()).hasSize(2);
        var parts = parsed.chunks().getFirst();
        assertThat(parts.content()).contains("Part No: MCU-200; Max Voltage: 5.5",
                "Part No: MCU-300; Max Voltage: 3.3");
        assertThat(parts.locator()).containsEntry("sheet", "Parts").containsEntry("row", 2)
                .containsEntry("rowEnd", 3);
        var stock = parsed.chunks().get(1);
        assertThat(stock.content()).contains("Quantity: 42").doesNotContain("Part No");
        assertThat(stock.locator()).containsEntry("sheet", "Stock").containsEntry("row", 2)
                .containsEntry("rowEnd", 2);
    }

    @Test
    void pdfChunksHavePageLocatorsAndPageQualifiedIds() throws Exception {
        var parsed = parser.parse(Kind.PDF, "spec-v1", pdf(
                "MCU-100 operates from 1.8 V to 3.6 V.",
                "Operating temperature is -40 C to 85 C."));

        assertThat(parsed.kind()).isEqualTo(Kind.PDF);
        assertThat(parsed.pageCount()).isEqualTo(2);
        assertThat(parsed.needsOcr()).isFalse();
        assertThat(parsed.chunks()).hasSize(2);
        assertThat(parsed.chunks().getFirst().content()).contains("MCU-100 operates");
        assertThat(parsed.chunks().getFirst().locator()).containsEntry("page", 1);
        assertThat(parsed.chunks().getFirst().chunkId()).startsWith("spec-v1-p1-");
        assertThat(parsed.chunks().get(1).content()).contains("Operating temperature");
        assertThat(parsed.chunks().get(1).locator()).containsEntry("page", 2);
        assertThat(parsed.chunks().get(1).chunkId()).startsWith("spec-v1-p2-");
    }

    @Test
    void twoPagePdfWithoutTextNeedsOcrAndProducesNoChunks() throws Exception {
        var parsed = parser.parse(Kind.PDF, "scan-v1", pdf(null, null));
        assertThat(parsed.pageCount()).isEqualTo(2);
        assertThat(parsed.needsOcr()).isTrue();
        assertThat(parsed.chunks()).isEmpty();
    }

    private static void assertDetectError(String filename, byte[] bytes, int status, String code) {
        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> DocumentParser.detect(filename, bytes))
                .satisfies(error -> {
                    assertThat(error.status).isEqualTo(status);
                    assertThat(error.code).isEqualTo(code);
                });
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] zip(String... entries) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            for (String entry : entries) {
                zip.putNextEntry(new ZipEntry(entry));
                zip.write(utf8("<document/>"));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    private static byte[] pdf(String... pages) throws Exception {
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            for (String text : pages) {
                var page = new PDPage();
                doc.addPage(page);
                if (text == null) continue;
                try (var content = new PDPageContentStream(doc, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(72, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
