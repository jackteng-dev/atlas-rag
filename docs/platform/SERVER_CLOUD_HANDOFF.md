# Atlas Server-side Claude／Cloud 交接與一次正式切換（已填寫）

**文件狀態**：`READY_FOR_SERVER_PRECHECK` — 本機開發與驗證完成；伺服器端尚未盤點、未切換。**本文件不是已核准的部署命令**：
任何寫入正式環境的步驟都需要 Owner 明確批准，且必須先完成 B 節唯讀盤點。最終狀態只能是 `READY_FOR_OWNER_APPROVAL`、
`BLOCKED_WITH_EVIDENCE` 或 `CUTOVER_COMPLETED_WITH_EVIDENCE`。

## A. 本次切換的事實

| 欄位 | 值 |
|---|---|
| Atlas GitHub Repo | `jackteng-dev/atlas-rag` |
| 目標功能分支 | `feature/atlas-platform-v1`（**不得**在預檢與 Owner 批准前 merge 到 `main`；merge 會觸發既有 GHCR → 主機 CD） |
| 已測試 Commit SHA | `e51a197`（本機 277 tests / build / e2e / smoke / eval 均在此內容上執行；之後僅有文件 commit） |
| PR / CI 證據 | Draft PR https://github.com/jackteng-dev/atlas-rag/pull/5（CI 結果以 PR checks 為準；只觸發 `showcase-verify` 的 pull_request 事件；`homelab-release.yml` 只在 `main` 的 workflow_run 執行） |
| 產物／Docker digest | 本機未發布任何映像。`deploy/platform/Dockerfile` 只供本機／審查建置；正式映像 recipe 由伺服器端在切換時決定 |
| 既有正式部署 SHA / digest | `<由 server-side Claude 唯讀確認>` |
| 執行者與批准者 | `<owner / server-side Claude / 明確授權>` |
| 切換窗口 | `<填寫>` |
| 本機測試結果 | 見 G 節與 `docs/HANDOFF.md`（Maven verify、前端 build、smoke 11/11、eval 11/12+OCR、併發 6 人、重啟恢復、備份還原） |

## B. 強制前置：正式系統唯讀盤點（伺服器端執行，未做前不得進入 F 節）
必須以**唯讀**方式取得並記錄：
1. 目前 Atlas 容器／服務名稱、映像 digest、OCI source/revision label、啟動參數與環境變數**名稱**（不得輸出值）。
2. CD 機制：共享 timer／拉取腳本、`homelab-release.yml` 發布的 GHCR 映像與 recipe label、`.homelab/*.json` 契約。
3. 資料位置：JSON store 路徑、accounts 檔、Qdrant collection 名稱與資料目錄、模型檔路徑與 SHA-256（對照 `config/local-rag-artifacts.json`）。
4. 現有備份機制與最近一次成功的還原演練證據。
5. 主機資源：CPU、RAM、磁碟可用量、是否有 GPU；其他共站服務與反向代理（domain/TLS/port）。
6. Baseline：Atlas 與其他網站健康檢查與回應時間。

任何一項不確定 → 停止，狀態設為 `BLOCKED_WITH_EVIDENCE`，不要猜路徑或服務名稱，也不要照抄本機的 port。

## C. CD 相容性與保護路徑
- 本分支**沒有修改**受保護路徑：`config/`、`backend/src/main/resources/`、`scripts/local-deploy.py`、`scripts/local-model-runtime.py`
  （驗證：`git diff --stat origin/main...feature/atlas-platform-v1 -- config backend/src/main/resources scripts/local-deploy.py scripts/local-model-runtime.py` 應為空）。
- 平台設定在 `backend/src/platform/resources/`（Flyway migration、`application-platform.properties`），由 pom 一併打包。
- **相容性結論**：同一個 jar 在沒有 `SPRING_PROFILES_ACTIVE=platform` 時就是 legacy 模式（47 項 legacy 測試未改即通過）。
  因此如果這個分支被 merge 而 CD 照舊部署，正式站仍以 legacy 模式運行，**但**前端 bundle、pom 依賴（PostgreSQL driver、PDFBox、POI、
  MinIO client、Spring Session）與 jar 大小（約 74 MB）都會改變。現有的 publisher 封裝閘門（`scripts/showcase-publish.py` 會檢查 Vue index 與資產）
  必須先在伺服器端重新驗證。**建議**：Owner 批准前不要 merge；由伺服器端先執行 contract switch（新增 platform 服務定義、secrets、volumes），再決定 merge 時機。
- 不得停用或繞過 branch protection、CI/CD；任何 CD 變更都需要 Owner 確認並留存證據。

## D. 資料完整性與遷移
- 正式 JSON 原文、accounts、Qdrant collection 與模型**不得被開發環境碰觸**（本機開發全程只用全新的隔離資料）。
- 切換前：建立有日期與校驗碼的正式備份（JSON、accounts、Qdrant、設定、model manifest），獨立保存並演練還原。
- 新資料庫／物件儲存／Qdrant collection 必須是**全新且隔離的**：PostgreSQL 15+ 資料庫 `atlas`（獨立使用者）、S3 bucket `atlas-documents`
  （MinIO/RustFS/S3 皆可）、Qdrant collection `atlas_platform_v1`（可共用既有 Qdrant 服務，但**不可**使用 legacy collection 名稱）。
- 遷移：依 `MIGRATION_PLAN.md` 對**正式 store 的複本**先 dry-run、再正式匯入；保存 `migration-report.json`，並做
  `source count → imported → markdownSha256 → chunkIdsSha256 → 引用抽樣` 對照。
- Accounts 不遷移；Owner 以 bootstrap admin 登入後邀請成員。legacy 非公開文件會進入 `RESTRICTED` 知識庫並標記 `ACL_REVIEW_REQUIRED`，
  Owner 必須先指定成員。
- Flyway 只往前遷移（V1–V3），沒有 down migration；回滾一律靠「舊環境原封保留 + 導回流量」，必要時從備份還原。

## E. 模型與容量
- 模型與 llama.cpp 與現行正式完全相同（Qwen3-4B Q4_K_M、選配 Qwen3-8B router、BGE-M3、BGE reranker；固定 SHA-256），**不需要下載新模型**。
  平台透過 loopback 呼叫 18111–18114；若正式模型服務在其他 port／容器，請以環境變數 `AKA_*_URL` 指向並保持 loopback
  （或把 Qdrant 服務名加到 `ATLAS_INTERNAL_HOSTS`；模型端點不接受非 loopback）。
- 容量：`ATLAS_MAX_INFERENCE=1`（CPU 同時只跑一個推論），佇列公平排隊；本機 6 人同時提問 p95 17.7 s。正式主機請以實測調整，勿以佇列取代算力。
- 不啟用任何雲端或付費模型（程式中沒有此路徑）。

## F. 切換日 Runbook（伺服器端填入真實命令／路徑後才可執行）
1. **PRECHECK**：Owner 授權與維護窗口；B 節盤點完成；既有正式狀態、CI/CD 行為、備份與回滾 smoke test 均有證據。
2. **FREEZE**：公告維護；legacy 停止寫入（或只讀）；完成最終備份與快照指紋。
3. **STAGE**（隔離，不導流量）：
   - 佈建 PostgreSQL、S3 bucket（若主機無 MinIO，可用 RustFS 或既有 S3），環境變數依 `LOCAL_DEPLOYMENT.md` 設定表，secrets 放主機 secrets 機制，
     `ATLAS_COOKIE_SECURE=true`、`ATLAS_REGISTRATION_OPEN=false`、`ATLAS_BIND_ADDRESS` 只綁內網／反向代理。
   - 以本分支建置映像（建議用 `deploy/platform/Dockerfile` 或現有 publisher 的新 recipe），記錄 digest。
   - 首次啟動帶一次性 `ATLAS_BOOTSTRAP_ADMIN_EMAIL/PASSWORD`，確認後移除。
   - 依 MIGRATION_PLAN 執行 dry-run → 正式匯入 → 等待所有 re-index job `SUCCEEDED`。
4. **VALIDATE**（全部 PASS 才能往下）：`node scripts/platform-smoke.mjs`（11 項）；`scripts/evaluate-platform.mjs`（應與本機結果相當）；
   管理員／一般成員／Org A/B 越權測試；文件更新／刪除／引用；Agent 與人工審核；job 重試；`/api/v2/admin/health` 全部 UP；
   `deploy/platform/backup.sh`（或等效腳本）備份＋還原演練；外部 URL/TLS；其他共站服務健康。
5. **CUTOVER**：Owner 明確批准後，反向代理切到 platform；確認 GHCR/CD 下一次觸發的行為與 schema 相容（必要時先暫停該服務的自動更新並留證據）。
6. **OBSERVE**：成功率、P95、job 佇列、Qdrant/DB 磁碟、錯誤、資料一致性、其他網站健康。
7. **ROLLBACK**：依 `ROLLBACK_PLAN.md`；觸發條件含越權、資料損壞、引用錯誤、持續 5xx、無法登入或還原失敗。不得在故障時臨時刪資料。

## G. 必簽核測試矩陣（本機結果；伺服器端需重跑並填「伺服器結果」）

| 類別 | 測試 | 本機結果 | 證據 | 伺服器結果 |
|---|---|---|---|---|
| 安全 | 無匿名寫入、CSRF、跨 Tenant 文件／原檔／chunk／向量／對話／job／agent／approval 越權 | PASS | `IdentityTenantTest`、`DocumentChatIsolationTest`、`AgentWorkflowTest`、smoke | `PENDING` |
| 資料 | 文件 hash 與版本、舊引用 SUPERSEDED/UNAVAILABLE、legacy 匯入逐字一致、冪等 | PASS | `DocumentChatIsolationTest`、`LegacyMigrationTest` | `PENDING` |
| 功能 | MD/TXT/PDF/DOCX/XLSX、掃描 PDF→NEEDS_OCR、RAG 引用、Agent+HITL | PASS（OCR 引擎未提供） | 整合測試、smoke、eval | `PENDING` |
| 效能 | 6 人併發、冷／溫推論、重啟恢復 | 本機 CPU 實測 | `docs/platform/evidence/*.json` | `PENDING` |
| 備份 | Backup + Restore 演練 | PASS（本機 compose） | HANDOFF、`deploy/platform/backup.sh` | `PENDING` |
| CD | 受保護路徑未改；merge 前必須 contract switch | 已核對路徑 | C 節命令 | `PENDING` |
| 共站 | 其他服務、反向代理、域名/TLS | 未測（本機無法測） | — | `PENDING` |

## H. 附件（皆在 `docs/platform/`）
`IMPLEMENTATION_REPORT.md`、`ARCHITECTURE.md`、`SECURITY_MODEL.md`、`DB_SCHEMA.md`、`API.md`、`MIGRATION_PLAN.md`、
`DATA_COMPATIBILITY_MATRIX.md`、`ROLLBACK_PLAN.md`、`LOCAL_DEPLOYMENT.md`、`EVALUATION_REPORT.md`、`CODEX_DELEGATION_LOG.md`、
`evidence/`；部署工具：`deploy/platform/{compose.yaml,Dockerfile,.env.example,backup.sh}`、`scripts/platform-{smoke,load,restart-drill}.mjs`、
`scripts/evaluate-platform.mjs`。

**目前尚未觸發正式部署的原因**：沒有 merge 到 `main`、沒有推送任何映像、沒有接觸任何伺服器；依使用者指示，切換須由伺服器端完成 B 節預檢並經 Owner 批准。
