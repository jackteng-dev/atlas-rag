package dev.knowledge.platform.knowledge;

import dev.knowledge.platform.core.*;
import dev.knowledge.platform.jobs.JobQueue;
import dev.knowledge.platform.ops.AuditService;
import dev.knowledge.platform.tenant.*;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController @Profile("platform") @RequestMapping("/api/v2/orgs/{orgId}")
public class KnowledgeController {
    public record KbRequest(String workspaceId, String name, String description, String visibility) {}
    public record RoleRequest(String role) {}
    public record DocPatch(String title, List<String> tags, String expiresAt) {}

    private final TenantAccess access;
    private final KnowledgeBaseService kbs;
    private final DocumentService docs;
    private final JobQueue jobs;
    private final AuditService audit;
    public KnowledgeController(TenantAccess access, KnowledgeBaseService kbs, DocumentService docs, JobQueue jobs, AuditService audit) {
        this.access = access; this.kbs = kbs; this.docs = docs; this.jobs = jobs; this.audit = audit;
    }

    @GetMapping("/knowledge-bases") List<KnowledgeBaseService.KnowledgeBase> listKbs(@PathVariable String orgId) { return kbs.list(access.require(orgId)); }
    @PostMapping("/knowledge-bases") ResponseEntity<KnowledgeBaseService.KnowledgeBase> createKb(@PathVariable String orgId, @RequestBody KbRequest b) {
        return ResponseEntity.status(201).body(kbs.create(access.require(orgId), b.workspaceId(), b.name(), b.description(), b.visibility()));
    }
    @GetMapping("/knowledge-bases/{kbId}") KnowledgeBaseService.KnowledgeBase getKb(@PathVariable String orgId, @PathVariable String kbId) { return kbs.get(access.require(orgId), kbId); }
    @PatchMapping("/knowledge-bases/{kbId}") KnowledgeBaseService.KnowledgeBase patchKb(@PathVariable String orgId, @PathVariable String kbId, @RequestBody KbRequest b) {
        return kbs.update(access.require(orgId), kbId, b.name(), b.description(), b.visibility());
    }
    @DeleteMapping("/knowledge-bases/{kbId}") ResponseEntity<Void> deleteKb(@PathVariable String orgId, @PathVariable String kbId) {
        var ctx = access.require(orgId);
        for (var doc : kbs.delete(ctx, kbId)) docs.enqueuePurge(ctx.orgId(), doc, ctx.userId());
        return ResponseEntity.accepted().build();
    }
    @GetMapping("/knowledge-bases/{kbId}/members") List<KnowledgeBaseService.KbMember> kbMembers(@PathVariable String orgId, @PathVariable String kbId) {
        return kbs.members(access.require(orgId), kbId);
    }
    @PutMapping("/knowledge-bases/{kbId}/members/{userId}") ResponseEntity<Void> setKbMember(@PathVariable String orgId, @PathVariable String kbId, @PathVariable String userId, @RequestBody RoleRequest b) {
        kbs.setMember(access.require(orgId), kbId, userId, b.role()); return ResponseEntity.noContent().build();
    }
    @DeleteMapping("/knowledge-bases/{kbId}/members/{userId}") ResponseEntity<Void> removeKbMember(@PathVariable String orgId, @PathVariable String kbId, @PathVariable String userId) {
        kbs.removeMember(access.require(orgId), kbId, userId); return ResponseEntity.noContent().build();
    }

    @GetMapping("/documents") List<DocumentService.DocumentItem> listDocs(@PathVariable String orgId, @RequestParam(required = false) String kbId) {
        return docs.list(access.require(orgId), kbId);
    }
    @PostMapping(path = "/knowledge-bases/{kbId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<DocumentService.DocumentItem> upload(@PathVariable String orgId, @PathVariable String kbId, @RequestParam("file") MultipartFile file,
                                                        @RequestParam(value = "title", required = false) String title) throws IOException {
        return ResponseEntity.accepted().body(docs.upload(access.require(orgId), kbId, file.getOriginalFilename(), file.getBytes(), title));
    }
    @GetMapping("/documents/{docId}") DocumentService.DocumentItem doc(@PathVariable String orgId, @PathVariable String docId) { return docs.detail(access.require(orgId), docId); }
    @PatchMapping("/documents/{docId}") DocumentService.DocumentItem patchDoc(@PathVariable String orgId, @PathVariable String docId, @RequestBody DocPatch b) {
        return docs.patch(access.require(orgId), docId, b.title(), b.tags(), b.expiresAt());
    }
    @DeleteMapping("/documents/{docId}") ResponseEntity<Void> deleteDoc(@PathVariable String orgId, @PathVariable String docId) {
        docs.delete(access.require(orgId), docId); return ResponseEntity.accepted().build();
    }
    @PostMapping(path = "/documents/{docId}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<DocumentService.DocumentItem> newVersion(@PathVariable String orgId, @PathVariable String docId, @RequestParam("file") MultipartFile file) throws IOException {
        return ResponseEntity.accepted().body(docs.uploadVersion(access.require(orgId), docId, file.getOriginalFilename(), file.getBytes()));
    }
    @PostMapping("/documents/{docId}/versions/{n}/retry") DocumentService.DocumentItem retry(@PathVariable String orgId, @PathVariable String docId, @PathVariable int n) {
        return docs.retry(access.require(orgId), docId, n);
    }
    @PostMapping("/documents/{docId}/versions/{n}/cancel") DocumentService.DocumentItem cancel(@PathVariable String orgId, @PathVariable String docId, @PathVariable int n) {
        return docs.cancel(access.require(orgId), docId, n);
    }
    @PostMapping("/documents/{docId}/reindex") ResponseEntity<DocumentService.DocumentItem> reindex(@PathVariable String orgId, @PathVariable String docId) {
        return ResponseEntity.accepted().body(docs.reindex(access.require(orgId), docId));
    }
    @GetMapping("/documents/{docId}/versions/{n}/file") ResponseEntity<InputStreamResource> file(@PathVariable String orgId, @PathVariable String docId, @PathVariable int n) {
        var f = docs.file(access.require(orgId), docId, n);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).contentLength(f.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + URLEncoder.encode(f.filename(), StandardCharsets.UTF_8).replace("+", "%20"))
                .body(new InputStreamResource(f.stream()));
    }
    @GetMapping("/documents/{docId}/versions/{n}/chunks") List<DocumentService.Chunk> chunks(@PathVariable String orgId, @PathVariable String docId, @PathVariable int n,
                                                                                          @RequestParam(defaultValue = "500") int limit) {
        return docs.chunks(access.require(orgId), docId, n, limit);
    }
    @GetMapping("/documents/{docId}/versions/{n}/chunks/{chunkId}") DocumentService.Chunk chunk(@PathVariable String orgId, @PathVariable String docId, @PathVariable int n, @PathVariable String chunkId) {
        return docs.chunk(access.require(orgId), docId, n, chunkId);
    }

    // ----- jobs (members see their own jobs; admins see all of the org) -----
    @GetMapping("/jobs") List<JobQueue.View> listJobs(@PathVariable String orgId, @RequestParam(required = false) String type, @RequestParam(required = false) String state) {
        var ctx = access.require(orgId);
        return jobs.list(ctx.orgId(), ctx.admin() ? null : ctx.userId(), type, state, 200).stream().map(JobQueue::view).toList();
    }
    @GetMapping("/jobs/{jobId}") JobQueue.View job(@PathVariable String orgId, @PathVariable String jobId) { return JobQueue.view(ownJob(orgId, jobId)); }
    @PostMapping("/jobs/{jobId}/cancel") JobQueue.View cancelJob(@PathVariable String orgId, @PathVariable String jobId) {
        var job = ownJob(orgId, jobId);
        jobs.cancel(job.orgId(), job.id());
        audit.record(job.orgId(), access.require(orgId).userId(), "job.cancel", "job", job.id());
        return JobQueue.view(jobs.byId(job.id()).orElseThrow());
    }
    @PostMapping("/jobs/{jobId}/retry") JobQueue.View retryJob(@PathVariable String orgId, @PathVariable String jobId) {
        var job = ownJob(orgId, jobId);
        if (!jobs.retry(job.orgId(), job.id())) throw Errors.conflict("NOT_RETRYABLE", "Only failed or cancelled jobs can be retried.");
        return JobQueue.view(jobs.byId(job.id()).orElseThrow());
    }
    private JobQueue.Job ownJob(String orgId, String jobId) {
        var ctx = access.require(orgId);
        var job = jobs.byId(ctx.orgId(), Db.parse(jobId, "job")).orElseThrow(() -> Errors.notFound("job"));
        if (!ctx.admin() && !ctx.userId().equals(job.createdBy())) throw Errors.notFound("job");
        return job;
    }
}
