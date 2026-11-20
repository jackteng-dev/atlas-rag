# Codex task T02 — focused backend unit tests (no Spring context, no Docker)

You work in a git worktree of the Atlas repo on branch `codex/backend-unit-tests`. Claude Code is the architect and will
review and integrate your diff. Write **pure JUnit 5 + AssertJ unit tests** for existing platform classes. Do not change
production code; if you believe production code has a bug, describe it in your final report instead of fixing it.

## Allowed to create
- New files only under `backend/src/test/java/dev/knowledge/platform/unit/`.
## Forbidden
- Modifying any existing file, anything under `backend/src/main/**`, `backend/src/platform/**`, `frontend/**`, `config/**`, `scripts/**`.
- git commit / push / merge, network access, production servers.

## Classes to test (read them first)
1. `dev.knowledge.platform.ingestion.DocumentParser`
   - `detect`: extension allow-list (.md .markdown .txt .pdf .docx .xlsx), 415 `UNSUPPORTED_TYPE` for others (e.g. .exe, .html),
     400 `INVALID_FILENAME` for `../x.md`, `a/b.md`, names > 255 chars; 400 `EMPTY_FILE`; 415 `CONTENT_MISMATCH` when a .pdf lacks `%PDF-`;
     415 `ENCODING` for invalid UTF-8 text; 415 `BINARY_TEXT` for text with NUL; zip guard: a .docx zip with an entry named `../evil` → 400 `ARCHIVE_REJECTED`;
     a valid zip lacking `word/document.xml` → 415 `CONTENT_MISMATCH`. Build zips in-memory with `java.util.zip.ZipOutputStream`.
   - `parse` for MARKDOWN: chunks carry `lineStart`/`lineEnd`/`section` locators; chunk ids are deterministic for identical input and start with the given prefix.
   - `parse` for XLSX (build with Apache POI `XSSFWorkbook` in-memory): header row becomes field names, locator has `sheet`, `row`, `rowEnd`.
   - `parse` for PDF (build with PDFBox in-memory, see `DocumentChatIsolationTest.pdf(...)` for a helper you may copy): page locators; a 2-page PDF with no text → `needsOcr() == true` and no chunks.
   Assertions on `dev.knowledge.ApiException` use its public fields `status` and `code`.
2. `dev.knowledge.platform.identity.LoginThrottle` — use the package-private constructor `LoginThrottle(Clock)` via a test placed in the SAME package
   (`backend/src/test/java/dev/knowledge/platform/identity/LoginThrottleTest.java` is allowed as the single exception to the folder rule):
   5 failures block, success resets the email counter, the window expires after 15 minutes (use a mutable test Clock), IP-based blocking.
3. `dev.knowledge.platform.identity.Passwords` static `validate` / `normalizeEmail`: length bounds, email-name containment, repetitiveness, invalid emails, lower-casing.
4. `dev.knowledge.platform.tenant.TenantAccess.effective(Role, visibility, workspaceRole, kbRole)` (package-private static; put this test in
   `backend/src/test/java/dev/knowledge/platform/tenant/KbAccessMatrixTest.java`, second allowed exception): table-driven test of the full matrix
   (OWNER/ADMIN → MANAGE always; workspace MANAGER → MANAGE; ORG visibility gives EDITOR→EDIT, VIEWER→READ; RESTRICTED gives nothing unless kb/workspace role;
   kb EDITOR on RESTRICTED → EDIT; kb VIEWER on ORG for an org EDITOR → EDIT (best wins)).
5. `dev.knowledge.platform.agent.AgentRunHandler.evaluate(Object actual, String op, Object expected)` (package-private static; test file in
   `backend/src/test/java/dev/knowledge/platform/agent/RuleEvaluateTest.java`, third allowed exception): gte/lte/gt/lt numeric, eq case-insensitive,
   contains, exists, null actual, non-numeric values → false, unknown op → false.

## Done when
`mvn -f backend/pom.xml -B -ntp -q test -Dtest='dev.knowledge.platform.unit.**,LoginThrottleTest,KbAccessMatrixTest,RuleEvaluateTest' -Dsurefire.failIfNoSpecifiedTests=false`
passes (Maven may need network for dependencies already in ~/.m2; if the sandbox blocks Maven entirely, say so and still deliver compilable tests).
Report: files created, test count, the command output tail, and any suspected production bugs.
