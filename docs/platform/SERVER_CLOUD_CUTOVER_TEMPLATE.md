# Atlas Server-side Claude／Cloud 專用交接與一次正式切換範本

**文件狀態**：TEMPLATE — 本機 Claude 完工後必須填入實際資料；未完成者標記 `BLOCKED`，**不能當作已核准的部署命令執行**。

## A. 本次切換的事實

| 欄位 | 完工時填寫 |
|---|---|
| Atlas GitHub Repo | `jackteng-dev/atlas-rag` |
| 目標功能分支 | `<branch>` |
| 已測試 Commit SHA | `<sha>` |
| PR / CI 證據 | `<URL / run ID / result>` |
| 產物／Docker digest | `<SHA256 / image digest>` |
| 既有正式部署 SHA / digest | `<由 server-side Claude 唯讀確認>` |
| 執行者與批准者 | `<owner / server-side Claude / 明確授權>` |
| 切換窗口、維護時間 | `<填寫>` |
| 本機測試結果 | `<測試／命令／日期／結果>` |

## B. 強制前置：正式系統唯讀盤點

伺服器端 Claude／Cloud **先只讀**驗證：目前正式站容器／服務、現有 GHCR／CI／CD timer、資料庫、JSON Store、模型路徑及 SHA、Qdrant Collection、備份機制、Port 與 Domain/TLS、其他共用網站資源。任何事實不確定，先停止切換，不猜路徑、不猜服務名稱、不照抄本機 port。

**必要證據**：既有部署內容指紋、現有來源 SHA、現有使用者資料位置、備份路徑及回復能力；完成原站健康與其他站不受影響的 baseline。

## C. CD 相容性與保護路徑

- 原 `main` merge 可能觸發 GitHub Actions → GHCR → 主機 CD 自動更新；本機開發的 feature branch/Draft PR **不得**先意外切換正式站。
- 檢查變更路徑：`config/`、`backend/src/main/resources/`、`scripts/local-deploy.py`、`scripts/local-model-runtime.py` 及其他主機偵測到的受控 contract。
- 若新程式仍使用舊 CD recipe 不相容，需**由伺服器端**先執行受控 contract switch、修改正式 compose/secrets/volume/migration 流程並驗證，完成前不得 merge 觸發自動切版。
- 不得擅自停用、改寫或繞過 branch protection / CI／CD；必要變更應由 Owner 確認、記錄證據及回復方案。

## D. 資料完整性與遷移

- **現存正式 JSON 原文、Accounts、向量與模型不得被開發環境碰觸。**
- 建立有日期／校驗碼的正式備份，涵蓋 DB/JSON、Document 原件、Metadata、索引配置、Model manifest、部署設定與密鑰參照。備份獨立保存並演練回復。
- 在隔離新 PostgreSQL/MinIO/Qdrant 中執行資料匯入／轉換，建立 `source record count → imported count → hash validation → document version → citation checks` 對照表。
- 新與舊環境並行啟動、使用不同 port/volume/collection，先做 read-only 影子驗證；沒有數據驗證不得切換。
- DB Migration 需先評估是否向後相容；不可假設 Flyway down migration 能保證資料回復。Rollback 優先舊環境原封保留、重新導流，必要時自備份還原。

## E. 模型與容量規劃

- 檢查實機 CPU／RAM／GPU／VRAM、模型檔 SHA、磁碟容量、Qdrant/DB 磁碟寫入、吞吐與並行工作上限。
- 明確寫出本地 Qwen3 4B/8B 模型端點／資源限制；未驗證就不自動下載替換或啟用雲端付費 API。
- 推論容量不足，應以 Queue/限流和可觀測性保護使用者，而非過度開啟同時推論。

## F. 切換日 Runbook（填入真正命令／路徑後才可執行）

1. `PRECHECK`: 取得 Owner 授權、確認維護窗口、既有正式狀態／CI/CD、備份與 rollback smoke test。
2. `FREEZE`: 暫停資料寫入或使用安全雙寫/同步方案（依架構決定），完成 final backup 和快照指紋。
3. `STAGE`: 隔離部署新系統，Migration、索引、檔案/權限、內部健康檢查，不導流公開流量。
4. `VALIDATE`: 管理員、一般員工、部門、Org A/B、文件更新/刪除/來源、RAG、Agent、審核、工作佇列、失敗回復、監控、外部 URL/TLS。
5. `CUTOVER`: 只在前述條件 PASS、Owner 明確批准後切換流量或版本；同時確認 GHCR/CD 下一次觸發的行為與 schema 相容。
6. `OBSERVE`: 記錄成功率、P95、Job、Qdrant、DB、錯誤、資料一致性與其他網站健康。
7. `ROLLBACK`: 如果授權資料洩漏、資料損壞、來源錯誤、持續 5xx、無法登入/還原或重要流程故障，依預先驗證的回復方案撤回；不得在故障時臨時亂刪資料。

## G. 必簽核測試矩陣

| 類別 | 測試 | 結果 | 證據 |
|---|---|---|---|
| 安全 | 無匿名寫入、跨 Tenant SQL/向量/MinIO/來源/Chat/Jobs/Agent 不能越權 | `PENDING` | |
| 資料 | 原始文件 hash 與版本、索引與歷史引用一致 | `PENDING` | |
| 功能 | PDF/DOCX/XLSX 匯入、RAG、引用、Agent/HITL | `PENDING` | |
| 效能 | 溫/冷推論、排隊、CPU/RAM/GPU、磁碟空間 | `PENDING` | |
| 備份 | Backup + Restore 演練成功 | `PENDING` | |
| CD | protected path contract 與 main 自動部署可控 | `PENDING` | |
| 共站 | 其他服務、反向代理、域名/TLS、健康檢查不受影響 | `PENDING` | |

## H. 開發端須帶入附件

- `IMPLEMENTATION_REPORT.md`、`DB_SCHEMA.md`、`API.md`、`SECURITY_MODEL.md`。
- `MIGRATION_PLAN.md`、`DATA_COMPATIBILITY_MATRIX.md`、`ROLLBACK_PLAN.md`。
- `LOCAL_DEPLOYMENT.md`、版本化 Compose、`.env.example`、部署 health/smoke scripts。
- CI 成功連結／Hash、Migration 測試及主要 E2E 影片或日誌摘要。

**部署最終狀態只能是**：`READY_FOR_OWNER_APPROVAL`、`BLOCKED_WITH_EVIDENCE`、或 `CUTOVER_COMPLETED_WITH_EVIDENCE`；不得只因本機 build 成功就寫「正式已部署」。
