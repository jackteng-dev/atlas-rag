package dev.knowledge.platform;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.knowledge.*;
import dev.knowledge.Models.*;
import dev.knowledge.platform.migration.LegacyStoreImporter;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Legacy JSON store → platform import keeps ids, versions, chunk ids and text; it is idempotent and never modifies the source. */
class LegacyMigrationTest extends PlatformTestBase {
    @Autowired LegacyStoreImporter importer;

    @Test void importsLegacyStoreVerbatimAndIsIdempotent() throws Exception {
        var t = newTenant("legacy");
        var slug = db.sql("select slug from organizations where id = cast(:o as uuid)").param("o", t.orgId()).query(String.class).single();
        // Build a legacy store with the legacy code itself (structural chunking, as the default legacy mode).
        var dir = Files.createTempDirectory("legacy-store");
        var storeFile = dir.resolve("store.json");
        var legacy = new DocumentStore(storeFile, new ObjectMapper(), new MarkdownChunker(), false);
        legacy.create(new ImportRequest("security-policy", "Security policy", "# Passwords\n\nRotate passwords every ninety days.\n\n# Badges\n\nBadges must be visible.\n", null));
        legacy.create(new ImportRequest("finance-rules", "Finance rules", "# Claims\n\nClaims over 5000 need director approval.\n",
                new Metadata("finance", "public-demo", null, List.of("ADMIN"), List.of("finance"))));
        legacy.update("security-policy", new UpdateRequest("Security policy", "# Passwords\n\nRotate passwords every sixty days.\n\n# Badges\n\nBadges must be visible.\n", 1));
        byte[] before = Files.readAllBytes(storeFile);
        var state = new ObjectMapper().readValue(before, StoreState.class);
        var security = state.documents().stream().filter(d -> d.id().equals("security-policy")).findFirst().orElseThrow();
        assertThat(security.version()).isEqualTo(2);

        var dry = importer.importStore(storeFile, slug, t.email(), true);
        assertThat(dry.imported()).isEqualTo(2);
        assertThat(db.sql("select count(*) from documents where org_id = cast(:o as uuid)").param("o", t.orgId()).query(Long.class).single()).isZero();

        var report = importer.importStore(storeFile, slug, t.email(), false);
        assertThat(report.imported()).isEqualTo(2);
        assertThat(report.failed()).isZero();
        assertThat(report.aclReviewRequired()).isEqualTo(1);
        assertThat(Files.readAllBytes(storeFile)).isEqualTo(before); // source untouched

        // Same version number and chunk ids are served by the platform citation endpoint.
        var docId = db.sql("select id from documents where org_id = cast(:o as uuid) and legacy_id = 'security-policy'").param("o", t.orgId()).query(String.class).single();
        var doc = call(t.auth(), get("/api/v2/orgs/" + t.orgId() + "/documents/" + docId), null, 200);
        assertThat(doc.path("currentVersion").asInt()).isEqualTo(2);
        var chunk = security.chunks().getFirst();
        var served = call(t.auth(), get("/api/v2/orgs/" + t.orgId() + "/documents/" + docId + "/versions/2/chunks/" + chunk.id()), null, 200);
        assertThat(served.path("content").asText()).isEqualTo(chunk.text());
        assertThat(served.path("locator").path("lineStart").asInt()).isEqualTo(chunk.lineStart());
        // Restricted legacy document went to a RESTRICTED knowledge base.
        var restrictedKb = db.sql("select kb.visibility from documents d join knowledge_bases kb on kb.id = d.kb_id where d.legacy_id = 'finance-rules' and d.org_id = cast(:o as uuid)")
                .param("o", t.orgId()).query(String.class).single();
        assertThat(restrictedKb).isEqualTo("RESTRICTED");
        // Vectors are rebuilt by re-index jobs.
        var deadline = java.time.Instant.now().plusSeconds(20);
        while (FakeModelsConfiguration.POINTS.values().stream().noneMatch(p -> p.documentId().toString().equals(docId)) && java.time.Instant.now().isBefore(deadline)) Thread.sleep(100);
        assertThat(FakeModelsConfiguration.POINTS.values().stream().anyMatch(p -> p.documentId().toString().equals(docId))).isTrue();

        // Re-running is idempotent.
        var again = importer.importStore(storeFile, slug, t.email(), false);
        assertThat(again.imported()).isZero();
        assertThat(again.skippedExisting()).isEqualTo(2);
        assertThat(db.sql("select count(*) from documents where org_id = cast(:o as uuid)").param("o", t.orgId()).query(Long.class).single()).isEqualTo(2);
    }
}
