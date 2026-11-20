package dev.knowledge.platform;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.function.Predicate;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** The PRD hard gate: Org A / Org B isolation across documents, files, chunks, vectors, chat, jobs, plus version consistency. */
class DocumentChatIsolationTest extends PlatformTestBase {

    static final String POLICY_V1 = "# Leave policy\n\nAnnual leave requests require manager approval seven days in advance.\n\n## Remote work\n\nRemote work is allowed two days per week.\n";
    static final String POLICY_V2 = "# Leave policy\n\nAnnual leave requests require manager approval fourteen days in advance.\n\n## Remote work\n\nRemote work is allowed three days per week.\n";

    JsonNode ready(Tenant t, String docId) throws Exception {
        return awaitDocument(t.auth(), t.orgId(), docId, d -> "READY".equals(d.path("status").asText()) && d.path("versions").path(0).path("pipelineState").asText().equals("READY"));
    }
    JsonNode awaitMessage(Auth auth, String orgId, String conversationId, Predicate<JsonNode> done) throws Exception {
        var deadline = Instant.now().plus(Duration.ofSeconds(30));
        JsonNode detail;
        do {
            detail = call(auth, get("/api/v2/orgs/" + orgId + "/conversations/" + conversationId), null, 200);
            var last = detail.path("messages").get(detail.path("messages").size() - 1);
            if (done.test(last)) return last;
            Thread.sleep(100);
        } while (Instant.now().isBefore(deadline));
        throw new AssertionError("Message not finished: " + detail);
    }

    @Test void crossTenantAccessIsDeniedEverywhereAndCitationsFollowVersions() throws Exception {
        var a = newTenant("orga");
        var b = newTenant("orgb");
        var kb = createKb(a, "HR", "ORG").path("id").asText();
        var doc = upload(a.auth(), a.orgId(), kb, "leave-policy.md", POLICY_V1.getBytes(StandardCharsets.UTF_8), 202);
        String docId = doc.path("id").asText();
        var readyDoc = ready(a, docId);
        assertThat(readyDoc.path("currentVersion").asInt()).isEqualTo(1);
        assertThat(readyDoc.path("filename").asText()).isEqualTo("leave-policy.md");
        assertThat(readyDoc.path("extension").asText()).isEqualTo("md");
        var chunks = call(a.auth(), get("/api/v2/orgs/" + a.orgId() + "/documents/" + docId + "/versions/1/chunks"), null, 200);
        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
        assertThat(chunks.path(0).path("locator").path("lineStart").asInt()).isPositive();
        String chunkId = chunks.path(0).path("chunkId").asText();
        String jobId = readyDoc.path("versions").path(0).path("jobId").asText();

        // ---- Org B probing Org A by direct ids: everything is 404, nothing leaks ----
        String aBase = "/api/v2/orgs/" + a.orgId();
        for (var path : List.of(aBase + "/documents/" + docId, aBase + "/documents?kbId=" + kb, aBase + "/knowledge-bases/" + kb,
                aBase + "/documents/" + docId + "/versions/1/file", aBase + "/documents/" + docId + "/versions/1/chunks",
                aBase + "/documents/" + docId + "/versions/1/chunks/" + chunkId, aBase + "/jobs/" + jobId, aBase + "/audit"))
            assertThat(call(b.auth(), get(path), null, 404).toString()).doesNotContain("Leave").doesNotContain("annual");
        // B using A's ids inside B's own org scope: still not found.
        String bBase = "/api/v2/orgs/" + b.orgId();
        call(b.auth(), get(bBase + "/documents/" + docId), null, 404);
        call(b.auth(), get(bBase + "/jobs/" + jobId), null, 404);
        call(b.auth(), post(bBase + "/conversations"), Map.of("kbIds", List.of(kb)), 404);
        call(b.auth(), multipart(bBase + "/documents/" + docId + "/versions").file(new MockMultipartFile("file", "x.md", "text/markdown", "# x\n\nbody".getBytes())), null, 404);
        call(b.auth(), delete(aBase + "/documents/" + docId), null, 404);

        // ---- B's own knowledge never contains A's vectors ----
        var bKb = createKb(b, "B KB", "ORG").path("id").asText();
        var bDoc = upload(b.auth(), b.orgId(), bKb, "b.md", "# Other\n\nBeta organisation travel rules apply to contractors.\n".getBytes(StandardCharsets.UTF_8), 202);
        ready(b, bDoc.path("id").asText());
        var bConv = call(b.auth(), post(bBase + "/conversations"), Map.of("kbIds", List.of(bKb)), 201).path("id").asText();
        call(b.auth(), post(bBase + "/conversations/" + bConv + "/messages"), Map.of("content", "How many days in advance must annual leave be approved?"), 202);
        var bAnswer = awaitMessage(b.auth(), b.orgId(), bConv, m -> m.path("role").asText().equals("ASSISTANT") && !m.path("status").asText().equals("PENDING"));
        assertThat(bAnswer.toString()).doesNotContain("seven").doesNotContain(docId);

        // ---- A asks: answer cites version 1 ----
        var conv = call(a.auth(), post(aBase + "/conversations"), Map.of("kbIds", List.of(kb)), 201).path("id").asText();
        call(a.auth(), post(aBase + "/conversations/" + conv + "/messages"), Map.of("content", "How many days in advance must annual leave be approved?"), 202);
        var answer = awaitMessage(a.auth(), a.orgId(), conv, m -> m.path("role").asText().equals("ASSISTANT") && !m.path("status").asText().equals("PENDING"));
        assertThat(answer.path("status").asText()).isEqualTo("COMPLETE");
        assertThat(answer.path("citations").path(0).path("versionNo").asInt()).isEqualTo(1);
        assertThat(answer.path("citations").path(0).path("state").asText()).isEqualTo("CURRENT");
        assertThat(answer.path("content").asText()).contains("seven");
        // B cannot read A's conversation, even with the right id.
        call(b.auth(), get(aBase + "/conversations/" + conv), null, 404);
        call(b.auth(), get(bBase + "/conversations/" + conv), null, 404);
        // Another member of A cannot read A-owner's private conversation either.
        var colleague = invite(a, "EDITOR");
        call(colleague.auth(), get(aBase + "/conversations/" + conv), null, 404);

        // ---- New version: old citation becomes SUPERSEDED, the old chunk URL answers 409 without new text ----
        call(a.auth(), multipart(aBase + "/documents/" + docId + "/versions").file(new MockMultipartFile("file", "leave-policy.md", "text/markdown", POLICY_V2.getBytes(StandardCharsets.UTF_8))), null, 202);
        awaitDocument(a.auth(), a.orgId(), docId, d -> d.path("currentVersion").asInt() == 2);
        var old = call(a.auth(), get(aBase + "/documents/" + docId + "/versions/1/chunks/" + chunkId), null, 409);
        assertThat(old.path("code").asText()).isEqualTo("VERSION_SUPERSEDED");
        assertThat(old.toString()).doesNotContain("fourteen");
        var after = call(a.auth(), get(aBase + "/conversations/" + conv), null, 200);
        var cite = after.path("messages").get(1).path("citations").path(0);
        assertThat(cite.path("state").asText()).isEqualTo("SUPERSEDED");
        // Re-identical upload is rejected as duplicate.
        call(a.auth(), multipart(aBase + "/documents/" + docId + "/versions").file(new MockMultipartFile("file", "leave-policy.md", "text/markdown", POLICY_V2.getBytes(StandardCharsets.UTF_8))), null, 409);
        // Asking again now returns version 2 evidence.
        call(a.auth(), post(aBase + "/conversations/" + conv + "/messages"), Map.of("content", "How many remote work days per week are allowed?"), 202);
        var second = awaitMessage(a.auth(), a.orgId(), conv, m -> m.path("role").asText().equals("ASSISTANT") && !m.path("status").asText().equals("PENDING"));
        assertThat(second.path("citations").path(0).path("versionNo").asInt()).isEqualTo(2);

        // ---- Permission change: making the KB restricted hides it from a plain viewer immediately ----
        var viewer = invite(a, "VIEWER");
        call(viewer.auth(), get(aBase + "/documents/" + docId), null, 200);
        call(a.auth(), patch(aBase + "/knowledge-bases/" + kb), Map.of("visibility", "RESTRICTED"), 200);
        call(viewer.auth(), get(aBase + "/documents/" + docId), null, 404);
        call(viewer.auth(), get(aBase + "/documents/" + docId + "/versions/2/file"), null, 404);
        call(viewer.auth(), post(aBase + "/conversations"), Map.of("kbIds", List.of(kb)), 404);
        call(a.auth(), put(aBase + "/knowledge-bases/" + kb + "/members/" + viewer.userId()), Map.of("role", "VIEWER"), 204);
        call(viewer.auth(), get(aBase + "/documents/" + docId), null, 200);
        call(viewer.auth(), multipart(aBase + "/knowledge-bases/" + kb + "/documents").file(new MockMultipartFile("file", "v.md", "text/markdown", "# v\n\nviewer text".getBytes())), null, 403);
        call(viewer.auth(), delete(aBase + "/documents/" + docId), null, 403);

        // ---- Delete: document, chunks, file and citations become unavailable; vectors are purged ----
        call(a.auth(), delete(aBase + "/documents/" + docId), null, 202);
        call(a.auth(), get(aBase + "/documents/" + docId), null, 404);
        call(a.auth(), get(aBase + "/documents/" + docId + "/versions/2/chunks/" + chunkId), null, 404);
        var hidden = call(a.auth(), get(aBase + "/conversations/" + conv), null, 200);
        for (var m : hidden.path("messages")) if (m.path("role").asText().equals("ASSISTANT")) {
            assertThat(m.path("content").asText()).doesNotContain("seven").doesNotContain("three");
            assertThat(m.path("citations").path(0).path("state").asText()).isEqualTo("UNAVAILABLE");
            assertThat(m.path("citations").path(0).path("quote").asText()).isEmpty();
        }
        var deadline = Instant.now().plusSeconds(20);
        while (FakeModelsConfiguration.POINTS.values().stream().anyMatch(p -> p.documentId().toString().equals(docId)) && Instant.now().isBefore(deadline)) Thread.sleep(100);
        assertThat(FakeModelsConfiguration.POINTS.values().stream().noneMatch(p -> p.documentId().toString().equals(docId))).isTrue();
        long chunkRows = db.sql("select count(*) from document_chunks c join document_versions v on v.id = c.document_version_id where v.document_id = :d")
                .param("d", UUID.fromString(docId)).query(Long.class).single();
        assertThat(chunkRows).isZero();
    }

    @Test void parsesPdfDocxXlsxWithLocatorsAndFlagsScannedPdf() throws Exception {
        var t = newTenant("formats");
        var kb = createKb(t, "Specs", "ORG").path("id").asText();
        var pdf = ready(t, upload(t.auth(), t.orgId(), kb, "datasheet.pdf", pdf("MCU-100 operates from 1.8 V to 3.6 V.", "Operating temperature is -40 C to 85 C."), 202).path("id").asText());
        assertThat(pdf.path("versions").path(0).path("pageCount").asInt()).isEqualTo(2);
        var pdfChunks = call(t.auth(), get("/api/v2/orgs/" + t.orgId() + "/documents/" + pdf.path("id").asText() + "/versions/1/chunks"), null, 200);
        assertThat(pdfChunks.toString()).contains("\"page\":2");

        var docx = ready(t, upload(t.auth(), t.orgId(), kb, "sop.docx", docx(), 202).path("id").asText());
        var docxChunks = call(t.auth(), get("/api/v2/orgs/" + t.orgId() + "/documents/" + docx.path("id").asText() + "/versions/1/chunks"), null, 200);
        assertThat(docxChunks.toString()).contains("Incoming inspection").contains("AQL 0.65");

        var xlsx = ready(t, upload(t.auth(), t.orgId(), kb, "parts.xlsx", xlsx(), 202).path("id").asText());
        var xlsxChunks = call(t.auth(), get("/api/v2/orgs/" + t.orgId() + "/documents/" + xlsx.path("id").asText() + "/versions/1/chunks"), null, 200);
        assertThat(xlsxChunks.path(0).path("locator").path("sheet").asText()).isEqualTo("Parts");
        assertThat(xlsxChunks.toString()).contains("Part No: MCU-200");

        var scanned = awaitDocument(t.auth(), t.orgId(), upload(t.auth(), t.orgId(), kb, "scan.pdf", pdf(null, null), 202).path("id").asText(),
                d -> d.path("status").asText().equals("NEEDS_OCR"));
        assertThat(scanned.path("versions").path(0).path("errorCode").asText()).isEqualTo("NEEDS_OCR");

        upload(t.auth(), t.orgId(), kb, "tool.exe", new byte[]{'M', 'Z', 1, 2}, 415);
        upload(t.auth(), t.orgId(), kb, "fake.pdf", "not a pdf".getBytes(), 415);
        upload(t.auth(), t.orgId(), kb, "fake.docx", "PK\u0003\u0004 not a zip".getBytes(StandardCharsets.ISO_8859_1), 415);
    }

    @Test void transientIndexingFailureIsRetriedAndOldVersionStaysAnswerable() throws Exception {
        var t = newTenant("retry");
        var kb = createKb(t, "Ops", "ORG").path("id").asText();
        String docId = upload(t.auth(), t.orgId(), kb, "runbook.md", "# Runbook\n\nRestart the ingestion worker after a failed deploy.\n".getBytes(StandardCharsets.UTF_8), 202).path("id").asText();
        ready(t, docId);
        FakeModelsConfiguration.failEmbedding = true;
        call(t.auth(), multipart("/api/v2/orgs/" + t.orgId() + "/documents/" + docId + "/versions")
                .file(new MockMultipartFile("file", "runbook.md", "text/markdown", "# Runbook\n\nRestart the worker twice after a failed deploy.\n".getBytes())), null, 202);
        var failing = awaitDocument(t.auth(), t.orgId(), docId, d -> !d.path("versions").path(0).path("jobId").asText().isEmpty()
                && db.sql("select attempts from jobs where id = cast(:j as uuid)").param("j", d.path("versions").path(0).path("jobId").asText()).query(Integer.class).single() >= 1
                && db.sql("select state from jobs where id = cast(:j as uuid)").param("j", d.path("versions").path(0).path("jobId").asText()).query(String.class).single().equals("QUEUED"));
        // Version 1 is still the current, READY version while version 2 is failing.
        assertThat(failing.path("currentVersion").asInt()).isEqualTo(1);
        assertThat(failing.path("status").asText()).isEqualTo("READY");
        FakeModelsConfiguration.failEmbedding = false;
        db.sql("update jobs set run_after = now() where id = cast(:j as uuid)").param("j", failing.path("versions").path(0).path("jobId").asText()).update();
        awaitDocument(t.auth(), t.orgId(), docId, d -> d.path("currentVersion").asInt() == 2);
    }

    static byte[] pdf(String page1, String page2) throws Exception {
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            for (var text : new String[]{page1, page2}) {
                var page = new PDPage(); doc.addPage(page);
                if (text == null) continue;
                try (var cs = new PDPageContentStream(doc, page)) {
                    cs.beginText(); cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12); cs.newLineAtOffset(72, 700); cs.showText(text); cs.endText();
                }
            }
            doc.save(out); return out.toByteArray();
        }
    }
    static byte[] docx() throws Exception {
        try (var doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            var h = doc.createParagraph(); h.setStyle("Heading1"); h.createRun().setText("Incoming inspection");
            doc.createParagraph().createRun().setText("Sample incoming lots with AQL 0.65 for critical parts.");
            var table = doc.createTable(2, 2);
            table.getRow(0).getCell(0).setText("Part"); table.getRow(0).getCell(1).setText("Plan");
            table.getRow(1).getCell(0).setText("MCU-100"); table.getRow(1).getCell(1).setText("Tightened");
            doc.write(out); return out.toByteArray();
        }
    }
    static byte[] xlsx() throws Exception {
        try (var wb = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sheet = wb.createSheet("Parts");
            var header = sheet.createRow(0); header.createCell(0).setCellValue("Part No"); header.createCell(1).setCellValue("Max Voltage");
            var row = sheet.createRow(1); row.createCell(0).setCellValue("MCU-200"); row.createCell(1).setCellValue(5.5);
            wb.write(out); return out.toByteArray();
        }
    }
}
