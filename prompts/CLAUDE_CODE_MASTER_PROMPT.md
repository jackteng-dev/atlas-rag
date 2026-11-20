# Claude Code 主提示詞｜Atlas AI Platform 全面升級（一次大改）

以下整段可直接貼給 Claude Code；建議同時把 `ATLAS_ENTERPRISE_PLATFORM_PRD.md` 和 `SERVER_CLOUD_CUTOVER_TEMPLATE.md` 放進 `atlas-rag/docs/platform/`，由 Claude 先閱讀。

---

你現在是 **Atlas AI Platform 首席架構師 + 總整合負責人 + 全端實作工程師 + QA + DevOps 設計者**。請在我現有的 GitHub Repository `jackteng-dev/atlas-rag` 上，**直接開始進行一次完整的大幅度重構與功能實作**，而非只分析、只建立計畫或等我每一階段另外下提示詞。

## 最終目標

把目前的「單機、Markdown、本地模型 RAG 文件助理」升級為接近可正式商業化的 **企業級 AI 知識營運與 Agentic AI 平台**，功能深度參考 Dify / RAGFlow / Onyx 以及我提供的 CosmosIDP 提案，但使用 Atlas 原創介面與架構，不能照抄他人產品。

我要 **先把最終目標的大部分功能做出真正可運作的版本，再集中修細節**。因此你可以大改 DB、API、前端、服務架構；不用為了保留舊 Demo 而限制新架構，但必須保留已驗證的 RAG 原理、可核對引用、租戶資料安全、回滾與原始資料遷移能力。

請先讀附件 `ATLAS_ENTERPRISE_PLATFORM_PRD.md`（若我未放入 repo，先以本訊息為最高層需求），以及當前 Repository 的 `AGENTS.md`、`README.md`、`docs/HANDOFF.md`、`.github/workflows/`、`docs/RAG_STACK.md`、`docs/LOCAL_DEPLOYMENT.md`、現有 Java/Vue 原始碼。先 `git fetch`、確認真正的 `main`、`git status`，避免覆蓋未提交修改；保留既有歷史測試資料。

## 執行方式：一條長期分支，連續完成大型功能

1. 建立隔離的 `feature/atlas-platform-v1` 工作分支（若現有分支有工作，先確認保護後再選新分支），按功能模組連續實作。**不要每做完一個小模組就停下來問我可不可以繼續**，只在真正涉及正式環境、機密、不可逆操作時停下。
2. 先做當前程式碼／CD 契約盤點與目標架構，再直接開始 coding，不要停在 PRD。技術選型可以自主決定，但不換掉 Java 21、Spring Boot、Vue 3；優先沿用既有 Qdrant、BGE-M3、BM25/RRF、reranker、Qwen3 4B/8B、llama.cpp。
3. **允許大型破壞性重構及 API v2**，但把原 JSON store、Chunk 版本及引用完整性做成明確 migration/compatibility 設計；舊版被替換前保留備份／離線轉檔方法。不接受「直接刪掉舊資料」或「舊引用偷偷指向新版」。
4. 本機優先交付**真正可運作的端到端功能**，而非大量只有外觀的空畫面。高級功能做不完時可將其作為清楚停用的 Future Module，但核心模組都要能在本機測試。
5. 你有自主權建立 Docker Compose、本機新 Postgres／MinIO／Qdrant、Schema migration、後端新 module、UI routing、Worker、測試與文件；只操作此 repo／專案隔離容器，**不得觸碰現有正式 Server、正式資料、現有正式 DB/Index、其他網站或其他 Repo**。
6. 每個大型模組完成就跑相關測試、commit checkpoint、更新 `docs/HANDOFF.md`。工作階段／context／額度接近限制時，先提交可驗證成果並交接下一步；不要宣稱會在背景自行繼續。新工作階段可讀交接後接續同一條功能分支。

## Claude 主導 + 本機 Codex CLI 協作（新增的強制要求）

**請優先把簡單、重複、可獨立驗收的工作真正交給我本機的 Codex CLI 完成，讓 Claude 專注架構、核心功能、整合和品質把關。**

工作開始前先讀 `prompts/CLAUDE_CODEX_COLLABORATION_RULES.md`，並實際執行 `codex --version`、`codex exec --help`；如果 Claude Code 與本機 Codex 處於不同工作環境、不能直接呼叫我的 Codex CLI，請如實說明，不能假裝已委派。

Codex 優先負責局部 Vue 元件/樣式、DTO/Mapper、測試、文件、樣板程式等小任務。Claude 必須親自負責 Tenant/RBAC、驗證、資料遷移、安全邊界、Agent 工具安全、架構和正式部署決策。允許在不反覆詢問我的情況下自動委派小任務，但每次要明確指定檔案範圍、驗收條件與禁止操作；使用 workspace-write 沙盒，禁止 Codex 自主 merge/push main、動正式環境、擴大權限或繞過核准。

不要讓兩個 AI 同時改同一檔案；沒可靠隔離 worktree 就採順序委派。**Claude 必須審查每次 Codex 的 diff、親自整合並重跑測試**，於 `docs/platform/CODEX_DELEGATION_LOG.md` 留下可稽核紀錄。Codex 不可用時 Claude 自己繼續完成開發，不要因此停止專案。

## 必做的最終平台功能（請按依賴順序完成，不只做第一階段）

**A. Identity / Multi-Tenant / Permissions**：使用者註冊／登入或管理員邀請（可自架 bootstrap）、Organization、Membership、Workspace、Knowledge Base、System/Admin/Editor/Viewer 等角色及動態成員管理；安全 Session + CSRF 或有完整撤銷機制的 Token；租戶隔離必須覆蓋 SQL、MinIO、Qdrant、BM25、來源引用、對話、背景任務、快取與 Agent Tool。關閉匿名文件寫入，預設 Fail Closed。

**B. Data / Document Pipeline**：PostgreSQL + Flyway、MinIO/S3、Qdrant；文件上傳、MD/TXT/PDF/DOCX/XLSX 真解析、可選 OCR、Metadata、分類、版本、權限、進度、重試、取消、重新索引、刪除。建立非同步 Ingestion Pipeline 和持久化工作佇列，避免查詢時才全量重建大批向量。OCR 不可用需明示，不可假裝識別成功。

**C. RAG / AI Chat**：保留 BM25 + BGE-M3 + RRF + Reranker + 本地 Qwen，支援多 KB 查詢、帶來源的多輪對話、歷史紀錄、來源文件頁碼/段落、原始文件預覽、引用版本驗證、證據不足拒答與跨租戶權限重驗。現有模型品質報告、frozen eval 不可覆寫。

**D. Agent / Workflow / HITL / MCP**：能建立 Agent、設定模型/知識庫/系統提示/工具白名單，至少三種可運作的 Agent 範本：知識問答、文件解析比對、唯讀 API/SQL 查詢。要有可執行的工作流程（觸發→檢索→解析或規則→LLM→人工批准→結果輸出），Run Timeline／狀態／失敗重試。高風險寫入要白名單+授權+HITL+稽核；不得把 LLM 輸出直接當成 SQL/Shell/HTTP 寫入指令。MCP 先實作受控 adapter 和管理介面，不允許用戶隨意連任意外網服務。

**E. Model / Usage / Admin / Observability**：本地模型 Provider 抽象（4B/8B 可以選），模型／任務健康監控、每租戶／每使用者／Agent Token 與 Job 用量、配額、限流、Audit Trail、Trace ID、成本（若沒有單價就只顯示實際單位，不編價格）、RAG 評測結果、管理員後台。不得新增必須使用付費 API 才能運作的依賴。

**F. 正式 Web UX**：把目前單一 App.vue 的問答/文件庫拆成一套完整的 Vue 3 + TS 平台，支援 Vue Router、Pinia、獨立頁面、權限導覽、桌面／手機可用。功能頁：登入、組織、總覽、AI Chat、知識庫、文件、Agents、Workflow、審核、連接器、評測、用量、稽核、管理者設定。風格需原創、專業、流暢；沒有功能的按鈕不得假裝可用。

**G. Deployable Product**：建立真正可重現的本機 Docker Compose（隔離 port、volume、網路），`.env.example` 不含任何真實秘密，Migration/Seeder/Health Check/Backup/Restore/Smoke Test、開發／Self-hosted 配置文件。正式部署另外交接，不要拿本機 Compose 直接改動現有正式環境。

## 必須通過的安全與功能測試

- **跨租戶否定測試是硬門檻**：建立 Org A/B，嘗試經由文件 ID、來源 URI、向量檢索、聊天訊息、Job ID、Agent ID、原檔下載、快取、稽核直接越權。應由後端拒絕，而且不得洩漏任何 A/B 資料。
- **文件版本一致性**：更新、刪除、到期、調整權限後，舊引用不得繼續曝光；索引失敗時不能把半完成版本當成 READY；索引重建需能重試且不丟資料。
- **問答工作**：同時多人提問時有持久排隊／公平限流，重啟後狀態可恢復或明確失敗可重試；CPU 本地模型不用硬追求不切實際的並行吞吐。
- **Agent**：沒有核准就不能做有副作用的工具動作；執行全程有 actor/tenant/trace。
- **端到端**：使用者建立組織 → 新增知識庫 → 上傳 PDF/DOCX → 等待解析索引 → 提問 → 查看帶版本的原文引用 → 變更文件權限 → 確認越權被拒 → 建 Agent → 執行帶人工審核的流程。
- 執行 Maven verify、前端 typecheck/build、DB migration、整合測試、Playwright 及適當的 Load/Soak Test；把真實結果與尚未做的測試分開記錄，禁止自稱「測試全過」卻沒執行。

## 關鍵部署限制：正式伺服器最後一次才切換

這是我**明確的新決策**：你可以在本機進行整套大改，但**最終要由另一個伺服器端 Claude／Cloud 工作階段配合現有主機與 CD 做一次正式部署**。本 repo 的 `main` 已與 GHCR + 自動 CD 相連；PR merge 到 main 可能立即推動舊正式站自動更新。**因此在新架構完成、Server-side handoff 和切換預檢前，不要先 merge main、也不要修改或啟動任何正式 CD。**

遵守 `AGENTS.md` 的受保護路徑與最新主機資料契約：`config/`、`backend/src/main/resources/`、`scripts/local-deploy.py`、`scripts/local-model-runtime.py`。新開發若必須變更這些路徑，可以在 feature branch 提出清楚的契約差異與切換需求，但**不能讓它們在未經主機端 contract switch 前進入自動部署**。不得改 GitHub branch protection 或隨意關閉 server deployment workflow。可以開 Draft PR 做 Review/CI；不要自行合併；除非伺服器端已有「CD 安全暫停」的明確證據並得到我另外的授權。

正式資料／模型檔／Qdrant collection／MinIO bucket／DB 密碼／其他網站不得讀取、下載、清洗、刪除或重建。用全新且隔離的本機資料與模擬測試帳號，不連正式主機。要切換時再由 server-side Claude 執行。

## 文檔、里程碑與最終交付

請在 repo 建立 `docs/platform/`，持續更新：

1. `PRD.md` — 本產品規格（可引用我給的 MD）。
2. `ARCHITECTURE.md`、`SECURITY_MODEL.md`、`DB_SCHEMA.md`、`API.md`、`IMPLEMENTATION_REPORT.md`。
3. `LOCAL_DEPLOYMENT.md`、`MIGRATION_PLAN.md`、`DATA_COMPATIBILITY_MATRIX.md`、`ROLLBACK_PLAN.md`、`EVALUATION_REPORT.md`。
4. **`SERVER_CLOUD_HANDOFF.md`** — 根據我附的 `SERVER_CLOUD_CUTOVER_TEMPLATE.md` 填好實際 branch/SHA、build/artifact、檔案 diff、依賴、Migration、現有 Docker/CD 交界、備份驗證、回滾步驟、健康／權限／來源驗收；交給伺服器端 Claude 可直接接手，不得含秘密。
5. `docs/HANDOFF.md` — 交接、真實測試命令及結果、已完成/未完成、下一輪首要工作；務必持續更新。

**執行模式**：先開始一次大規模 coding，不要只回一份架構建議。完成每個主要模組時自行回歸驗證並繼續往下一模組推進。工作階段不足以完成所有功能時，把已可運作功能、測試結果與未完成項目完整保存，後續同分支繼續直到接近產品最終目標。

最終請回報：功能完成矩陣、實測紀錄、本機啟動方式、當前 branch/commit、Draft PR（若有）、尚缺項目、`SERVER_CLOUD_HANDOFF.md` 路徑，以及為何**目前尚未觸發正式部署**。

**現在開始：先核對 repo/AGENTS/CD 的真實狀態，產出必要設計決策後，直接修改原始碼並持續實作。不要停在計畫階段。**
