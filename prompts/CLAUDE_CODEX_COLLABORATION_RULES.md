# Atlas｜Claude Code × 本機 Codex CLI 協作規範

> 本文件是 `CLAUDE_CODE_MASTER_PROMPT.md` 的補充要求。Claude 是主控與整合者，Codex 是受限的實作助手。此要求只適用於**Claude Code 與 Codex CLI 都能在同一台本機／同一可存取的開發工作區執行**時；若 Claude 是遠端工作階段、無法呼叫使用者本機 CLI，就不能假裝已經委派。

## 1. 工作分工

**Claude Code 負責：**
- 產品架構、資料模型、跨模組設計及介面契約。
- Tenant / RBAC、認證與安全決策、Qdrant/文件授權、Migration / 備份、Agent 工具權限和正式部署交接。
- 拆分任務、指定修改範圍、檢查 Codex 產出、整合程式碼、處理衝突、執行完整回歸及最終 Git 操作。
- 持續推動 Atlas 的整體實作，不得只將工作丟給 Codex 就停止。

**優先委派給本機 Codex CLI 的獨立小任務：**
- 獨立 Vue 元件、樣式調整、表單/表格/載入狀態等局部 UI。
- 型別宣告、純函式、DTO/Mapper、簡單欄位驗證及無安全關鍵的 API 輔助程式。
- JUnit / Vitest / Playwright 測試案例、測試資料、Story / 範例。
- 文件、README、OpenAPI 範例、程式註解、格式調整和小型重構。
- 具備明確規格與驗收條件、可在少量檔案內完成的重複性工作。

**Claude 應親自掌控、不直接交給 Codex 自主決策的任務：**
- 身分認證、租戶隔離、權限授權、機密存取、金流、資料庫 schema 正式決策及資料遷移。
- 跨服務技術架構、正式主機／CD、受保護路徑、Qdrant 或 MinIO 正式資料操作。
- 不可逆操作、權限提升、刪除資料、擴大工具/網路存取範圍。
- 複雜功能可把「非核心子模組」拆給 Codex，但安全邊界及驗收由 Claude 負責。

## 2. 每次委派的流程（預設自動進行，不必反覆詢問）

1. Claude 先確認當前 git branch/status、任務是否與其他修改衝突、`AGENTS.md` 和正式 CD 保護規則。
2. 先測試 `codex --version`、`codex exec --help`，確認本機 CLI 可用且登入正常；從 **atlas-rag 專案根目錄**執行，不使用雲端 Codex 冒充本機。
3. 對每個小任務撰寫包含「目的、准許修改的相對路徑、禁止修改路徑、完成標準、測試指令、交付摘要」的專屬提示詞。
4. 優先使用 `codex exec` 的非互動模式與 **workspace-write 沙盒**；實際命令依本機 `--help` 核對。不得使用 danger-full-access、繞過核准或超出專案的寫入權限。
5. 讓 Codex 完成局部實作和對應小測試；Codex **不得自行** push / merge `main`、改正式部署規則、執行正式 DB migration 或碰觸主機資源。
6. Claude 取得 Codex 結果後，必須檢查 `git diff`、檔案變更、品質與安全性，親自補修並整合，執行必要的全域測試，再由 Claude 建立 checkpoint commit。
7. 記錄每個 Codex 任務的目的、修改檔案、結果、測試與整合狀態到 `docs/platform/CODEX_DELEGATION_LOG.md`；沒有實際呼叫或測試，必須明示。

## 3. 避免兩個 Agent 互相覆寫

- **嚴禁** Claude 和 Codex 同時編輯同一檔案、同一區段或同一 DB migration。
- 若要真正並行，使用各自隔離的 git worktree / feature branch，讓 Claude 檢查後再整合；若無法可靠隔離，改成**順序委派**。
- 委派期間 Claude 不得在相同工作樹內重構受影響模組。Codex 不得覆寫 Claude 或使用者尚未提交的修改。
- 失敗時由 Claude 記錄、縮小範圍再重試或自行處理，不可無限制重複觸發代理。
- 單一大任務優先切成能在短時間內驗收的小批次；驗收後再分派下一批。

## 4. 範例命令與 Codex 專用提示詞

僅供參考；**以本機 CLI `codex exec --help` 的實際語法為準**。

```powershell
# 先在 atlas-rag Repository 根目錄執行
codex --version
codex exec --help

# 指定一個局部、可以獨立驗收的任務
codex exec --sandbox workspace-write '只修改 frontend/src/components/DocumentStatusBadge.vue，
建立文件狀態徽章元件，支援 READY / PROCESSING / FAILED 三個狀態；
不得修改其他檔案、不得執行 git push/merge、不得連線正式環境。
完成後回報修改檔案及實際驗證結果。'
```

上面檔案路徑為**示範待建立元件**，不是聲稱 Repo 已存在該檔案。實際任務的檔案、props、設計規格和測試要由 Claude 依目前專案狀態決定。

## 5. 無法使用本機 Codex 時

如果 `codex` 不存在、未登入、呼叫失敗、需要使用者批准權限，或 Claude 工作階段無法存取使用者本機：

- 明確記錄 `CODEX_UNAVAILABLE` 與實際原因，**不要捏造委派成功**。
- 不要偷偷安裝 CLI、要求 API Key、切換付費 API，或把本機程式碼傳到未經授權的遠端服務。
- Claude 應繼續自己完成可進行的開發工作；只在確實需要新的授權／本機連線時請使用者處理。
- 不要讓「Codex 暫不可用」阻止整個 Atlas 平台的推進。

## 6. 最後的交接

每個主要模組完成時，Claude 要在進度摘要列出：**自行完成項目、Codex 委派項目、整合與重測結果、未完成項目**。最後的 `SERVER_CLOUD_HANDOFF.md` 只列已驗證功能和部署契約，不把 Codex 產出直接當作已可正式上線。
