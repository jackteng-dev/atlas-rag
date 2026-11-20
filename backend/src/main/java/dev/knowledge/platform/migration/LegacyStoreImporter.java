package dev.knowledge.platform.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.knowledge.Models.*;
import dev.knowledge.MarkdownChunker;
import dev.knowledge.platform.core.Json;
import dev.knowledge.platform.ingestion.DocumentParser;
import dev.knowledge.platform.jobs.JobQueue;
import dev.knowledge.platform.knowledge.DocumentService;
import dev.knowledge.platform.storage.ObjectStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Offline, idempotent import of a legacy single-instance JSON store into the platform (see docs/platform/MIGRATION_PLAN.md).
 * <ul>
 * <li>The source file is opened read-only and never modified; a SHA-256 of it is recorded in the report.</li>
 * <li>Document ids, version numbers, chunk ids, headings, text and line numbers are copied verbatim (no re-chunking), so legacy
 *     citations ({documentId, version, chunkId}) resolve to the same text.</li>
 * <li>Legacy role/department ACLs cannot be mapped 1:1; non-public documents go to RESTRICTED knowledge bases per department and
 *     are flagged ACL_REVIEW_REQUIRED.</li>
 * <li>Vectors are rebuilt by re-index jobs in a new collection; the legacy Qdrant collection is not touched.</li>
 * </ul>
 * Activated only with {@code atlas.migrate.legacy-store}; writes a JSON verification report and can exit afterwards.
 */
@Component @Profile("platform") @Order(10)
public class LegacyStoreImporter implements ApplicationRunner {
    private final JdbcClient db;
    private final ObjectMapper mapper;
    private final Json json;
    private final ObjectStore objects;
    private final JobQueue jobs;
    private final TransactionTemplate tx;
    private final ConfigurableApplicationContext context;
    private final String source, orgSlug, ownerEmail, reportPath;
    private final boolean dryRun, exitAfter;

    public LegacyStoreImporter(JdbcClient db, ObjectMapper mapper, Json json, ObjectStore objects, JobQueue jobs, TransactionTemplate tx, ConfigurableApplicationContext context,
                               @Value("${atlas.migrate.legacy-store:}") String source, @Value("${atlas.migrate.org-slug:}") String orgSlug,
                               @Value("${atlas.migrate.owner-email:}") String ownerEmail, @Value("${atlas.migrate.report:work/migration-report.json}") String reportPath,
                               @Value("${atlas.migrate.dry-run:true}") boolean dryRun, @Value("${atlas.migrate.exit:false}") boolean exitAfter) {
        this.db = db; this.mapper = mapper; this.json = json; this.objects = objects; this.jobs = jobs; this.tx = tx; this.context = context;
        this.source = source; this.orgSlug = orgSlug; this.ownerEmail = ownerEmail; this.reportPath = reportPath; this.dryRun = dryRun; this.exitAfter = exitAfter;
    }

    public record DocReport(String legacyId, int version, String title, String department, String result, String markdownSha256, String chunkIdsSha256,
                            int chunks, String note) {}
    public record Report(String source, String sourceSha256, boolean dryRun, String orgSlug, String startedAt, String finishedAt, int sourceDocuments,
                         int imported, int skippedExisting, int failed, int aclReviewRequired, List<DocReport> documents) {}

    @Override public void run(ApplicationArguments args) throws Exception {
        if (source.isBlank()) return;
        var log = LoggerFactory.getLogger(LegacyStoreImporter.class);
        var report = importStore(Path.of(source), orgSlug, ownerEmail, dryRun);
        var out = Path.of(reportPath).toAbsolutePath();
        Files.createDirectories(out.getParent());
        Files.writeString(out, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        log.info("Legacy import {}: {} source, {} imported, {} skipped, {} failed. Report: {}", dryRun ? "DRY-RUN" : "DONE",
                report.sourceDocuments(), report.imported(), report.skippedExisting(), report.failed(), out);
        if (exitAfter) System.exit(SpringApplication.exit(context, () -> report.failed() == 0 ? 0 : 2));
    }

    public Report importStore(Path file, String orgSlug, String ownerEmail, boolean dryRun) throws Exception {
        String started = Instant.now().toString();
        byte[] raw = Files.readAllBytes(file); // read-only
        var state = mapper.readValue(raw, StoreState.class);
        var org = db.sql("select id from organizations where slug = :s").param("s", orgSlug).query(UUID.class).optional()
                .orElseThrow(() -> new IllegalStateException("Target organisation slug not found: " + orgSlug));
        var owner = db.sql("select u.id from users u join memberships m on m.user_id = u.id where lower(u.email) = lower(:e) and m.org_id = :o")
                .param("e", ownerEmail).param("o", org).query(UUID.class).optional()
                .orElseThrow(() -> new IllegalStateException("Owner must be a member of the target organisation: " + ownerEmail));
        var reports = new ArrayList<DocReport>();
        int imported = 0, skipped = 0, failed = 0, review = 0;
        UUID workspace = dryRun ? null : ensureWorkspace(org);
        var kbs = new HashMap<String, UUID>();
        for (var doc : state.documents()) {
            String mdSha = DocumentService.sha256(doc.markdown().getBytes(StandardCharsets.UTF_8));
            String chunkSha = MarkdownChunker.hash(String.join("\n", doc.chunks().stream().map(Chunk::id).toList()));
            var meta = doc.metadata();
            boolean restricted = !meta.publicAccess();
            if (restricted) review++;
            String note = restricted ? "ACL_REVIEW_REQUIRED: legacy department=" + meta.department() + " roles=" + meta.allowedRoles() + " → RESTRICTED knowledge base" : "";
            try {
                var existing = db.sql("select d.id, v.sha256 from documents d join document_versions v on v.id = d.current_version_id where d.org_id = :o and d.legacy_id = :l")
                        .param("o", org).param("l", doc.id()).query((rs, n) -> rs.getString("sha256")).optional();
                if (existing.isPresent()) {
                    reports.add(new DocReport(doc.id(), doc.version(), doc.title(), meta.department(), existing.get().equals(mdSha) ? "SKIPPED_EXISTING" : "CONFLICT_EXISTING_DIFFERENT",
                            mdSha, chunkSha, doc.chunks().size(), note));
                    if (existing.get().equals(mdSha)) skipped++; else failed++;
                    continue;
                }
                if (doc.chunks().isEmpty() || doc.version() < 1) throw new IllegalStateException("Legacy document has no chunks or version");
                if (!MarkdownChunker.hash(doc.markdown()).equals(doc.contentHash())) throw new IllegalStateException("Legacy content hash mismatch");
                if (!dryRun) {
                    String kbKey = restricted ? "dept:" + meta.department() : "public";
                    UUID kb = kbs.computeIfAbsent(kbKey, k -> ensureKb(org, workspace, owner, k, restricted));
                    importDocument(org, kb, owner, doc, mdSha);
                }
                imported++;
                reports.add(new DocReport(doc.id(), doc.version(), doc.title(), meta.department(), dryRun ? "WOULD_IMPORT" : "IMPORTED", mdSha, chunkSha, doc.chunks().size(), note));
            } catch (Exception e) {
                failed++;
                reports.add(new DocReport(doc.id(), doc.version(), doc.title(), meta.department(), "FAILED", mdSha, chunkSha, doc.chunks().size(), e.getMessage()));
            }
        }
        // Post-import verification: every imported document's chunk ids/text must equal the source.
        if (!dryRun) for (var doc : state.documents()) {
            var stored = db.sql("""
                    select c.chunk_id, c.content from document_chunks c join documents d on d.current_version_id = c.document_version_id
                    where d.org_id = :o and d.legacy_id = :l order by c.ordinal""").param("o", org).param("l", doc.id())
                    .query((rs, n) -> rs.getString(1) + "\u0000" + rs.getString(2)).list();
            var expected = doc.chunks().stream().map(c -> c.id() + "\u0000" + c.text()).toList();
            if (!stored.isEmpty() && !stored.equals(expected)) throw new IllegalStateException("Verification failed for " + doc.id());
        }
        return new Report(file.toString(), DocumentService.sha256(raw), dryRun, orgSlug, started, Instant.now().toString(), state.documents().size(),
                imported, skipped, failed, review, reports);
    }

    private UUID ensureWorkspace(UUID org) {
        return db.sql("select id from workspaces where org_id = :o and name = 'Legacy import'").param("o", org).query(UUID.class).optional().orElseGet(() -> {
            var id = UUID.randomUUID();
            db.sql("insert into workspaces(id, org_id, name, description) values (:id, :o, 'Legacy import', 'Documents migrated from the single-instance JSON store')")
                    .param("id", id).param("o", org).update();
            return id;
        });
    }
    private UUID ensureKb(UUID org, UUID workspace, UUID owner, String key, boolean restricted) {
        String name = restricted ? "Legacy — " + key.substring(5) : "Legacy — public";
        return db.sql("select id from knowledge_bases where org_id = :o and workspace_id = :w and name = :n and deleted_at is null").param("o", org).param("w", workspace).param("n", name)
                .query(UUID.class).optional().orElseGet(() -> {
                    var id = UUID.randomUUID();
                    db.sql("insert into knowledge_bases(id, org_id, workspace_id, name, description, visibility, created_by) values (:id, :o, :w, :n, :d, :v, :u)")
                            .param("id", id).param("o", org).param("w", workspace).param("n", name).param("d", "Imported from the legacy JSON store")
                            .param("v", restricted ? "RESTRICTED" : "ORG").param("u", owner).update();
                    return id;
                });
    }
    private void importDocument(UUID org, UUID kb, UUID owner, Document doc, String mdSha) {
        var bytes = doc.markdown().getBytes(StandardCharsets.UTF_8);
        var docId = UUID.randomUUID(); var versionId = UUID.randomUUID();
        String key = ObjectStore.key(org, docId, doc.version(), mdSha);
        objects.put(key, bytes, "text/markdown");
        tx.executeWithoutResult(s -> {
            var meta = doc.metadata();
            db.sql("""
                    insert into documents(id, org_id, kb_id, title, owner_id, status, latest_version_no, expires_at, tags, legacy_id)
                    values (:id, :o, :kb, :t, :u, 'READY', :v, :x, :tags, :l)""")
                    .param("id", docId).param("o", org).param("kb", kb).param("t", doc.title()).param("u", owner).param("v", doc.version())
                    .param("x", meta.expiresAt() == null ? null : java.sql.Timestamp.from(Instant.parse(meta.expiresAt())))
                    .param("tags", meta.tags().toArray(String[]::new)).param("l", doc.id()).update();
            db.sql("""
                    insert into document_versions(id, org_id, document_id, version_no, sha256, object_key, filename, mime_type, size_bytes, parser_version, pipeline_state, chunk_count, created_by, ready_at)
                    values (:id, :o, :d, :v, :sha, :key, :f, 'text/markdown', :size, 'legacy-json-v1', 'READY', :n, :u, now())""")
                    .param("id", versionId).param("o", org).param("d", docId).param("v", doc.version()).param("sha", mdSha).param("key", key)
                    .param("f", doc.id() + ".md").param("size", (long) bytes.length).param("n", doc.chunks().size()).param("u", owner).update();
            int ordinal = 0;
            for (var c : doc.chunks()) {
                db.sql("""
                        insert into document_chunks(org_id, document_version_id, chunk_id, ordinal, heading, content, content_hash, locator)
                        values (:o, :v, :c, :n, :h, :t, :hash, cast(:l as jsonb))""")
                        .param("o", org).param("v", versionId).param("c", c.id()).param("n", ordinal++).param("h", c.heading()).param("t", c.text())
                        .param("hash", MarkdownChunker.hash(c.text())).param("l", json.write(Map.of("lineStart", c.lineStart(), "lineEnd", c.lineEnd(), "section", c.heading()))).update();
            }
            db.sql("update documents set current_version_id = :v where id = :d").param("v", versionId).param("d", docId).update();
        });
        // Vectors are derived data: rebuilt from the copied chunks into the platform collection.
        jobs.enqueue(org, DocumentService.INGEST, Map.of("versionId", versionId.toString(), "documentId", docId.toString(), "reindex", true), owner, "reindex:" + versionId, 70, 4);
    }
    static String unusedParserVersion() { return DocumentParser.VERSION; }
}
