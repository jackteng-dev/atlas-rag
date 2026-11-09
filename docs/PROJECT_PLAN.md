# AI Knowledge Assistant — 獨立作品提案

## 目前狀態

**2026-10-05：第二里程碑的離線檢索品質實驗已實作。** 可設定 chunk 大小、topK、詞彙證據門檻，使用原始四份 corpus／16 題進行隔離批次比較，記錄實際設定、來源／拒答／引用／延遲與逐題差異。baseline 與 Q13／Q14 失敗紀錄保留；詳見 [EVALUATION](EVALUATION.md) 與 [VERIFICATION](VERIFICATION.md)。

第三里程碑已加入免費本地 BGE-M3／Qdrant／混合搜尋／BGE 重排／Qwen2.5 生成，以及 metadata／認證／RBAC；預設保留 BM25／原文摘錄。詳見 [RAG_STACK](RAG_STACK.md)。developer-portfolio 維持 Phase 1；本次只修改此倉庫，依使用者要求保存到 `codex/ai-retrieval-evaluation` 分支。

## 展示定位

以 Java 後端為主要技術方向，建立「回答可核對、檢索可觀察、品質可比較」的文件知識庫助理。建議先使用一組可公開展示的技術文件或專案文件，清楚限定回答範圍。

已使用 Java、Spring Boot 3.5.16、Vue 3、TypeScript。真實免費模型與向量儲存使用 llama.cpp / Qdrant 直接 HTTP 整合；Spring AI 未加入。

## 第一里程碑：有來源的離線檢索／摘錄

以下核心已實作；當時使用 offline baseline；新的生成式 RAG 驗證另見第三里程碑：

- 先處理 Markdown 文件，記錄來源、版本與段落識別碼，再建立切分、索引及檢索流程。
- 展示檢索到的段落，回答附上可核對的文件與段落引用。
- 引用位置由後端對應至實際檢索結果，不直接使用模型生成的任意網址。
- 檢索結果為空時，由應用程式回覆證據不足；其餘回答也需評估是否受到來源支持。
- 文件更新或刪除後，驗證舊內容不再出現在檢索結果。
- 顯示索引狀態、前端逾時、API 錯誤與可選本機模型失敗狀態；模型 timeout 路徑已實作，未以真實模型驗證。

已有實際問答操作、來源段落、文件處理流程、架構圖、評估紀錄與六張截圖，見 docs/ 下文件。證據不足使用詞彙涵蓋率門檻，不能保證語意充分性；同義詞與錯誤前提仍有失敗案例。

## 第二階段：可比較的檢索品質（本次完成離線部分）

- 建立固定題集，包含可回答、無答案、跨段落與容易混淆的問題，為題目標註參考來源。
- 已比較 chunk 800／400／160、topK 5／3／1、門檻 0.60／0.40／0.80 的 27 組離線設定；混合檢索與 reranking 在第三里程碑新增，沒有混入本段歷史比較。
- 已量測候選來源召回、摘錄來源召回、引用原文／版本有效性、無答案處理與延遲。語意證據支持尚未評分；沒有模型，token／成本不適用。
- 保留相同資料版本、題集、模型及參數，使不同設定的結果可以公平比較。
- 加入文件內惡意指令的測試案例，確認系統把文件當資料處理。

完成後展示：基準方法與改良方法的實測差異、失敗案例與技術取捨。未量測前不宣稱正確率、成本或效能改善。

## 第三階段：加入後端工程深度

以下依實際需求選擇，先完成第一階段再擴充：

- **權限感知的檢索**：由後端以已驗證的使用者身分決定可讀文件，在組裝模型上下文之前進行存取限制；以越權案例驗證隔離。
- **可追查的查詢流程**：使用 request ID 串起查詢、檢索文件版本、引用與模型結果，規劃記錄保留範圍。
- **受限的工具查詢**：先選一項唯讀功能，例如查詢公開商品資訊；由後端檢查權限與參數，再執行工具。

完成後才評估多步驟 agent 是否帶來實際價值；透過一致題集比較效果、延遲、成本與失敗處理。

## 作品集如何展示

展示頁預計包括：

1. 使用情境、資料範圍與目前完成狀態。
2. 真實操作畫面、引用段落與無答案案例。
3. 文件匯入至回答產生的流程與資料模型。
4. 切分、檢索、引用與權限處理的技術決策。
5. 固定題集、測試結果、限制與失敗案例。
6. 可執行的原始碼、使用說明及 Demo 連結。

外部作品集的 `features`、`challenges`、`architecture` 本次沒有修改。若之後要更新，只能使用這裡已驗證的 baseline 成果與限制，若標示真實模型／向量 RAG，應同時列出第三里程碑的實測條件與限制。

## 第四里程碑：品質驗收與本機交付

使用者已確認最終在本機部署、可用 RAM 24–32GB，先採免費方案。本次只完成此專案的可攜部署與品質證據，不修改外部作品集或其他專案。

- 凍結 40 題：校準 16、保留 24，包含自然中文、跨段落／跨文件、錯誤前提、過期、角色／部門、偽造 header 與來源核對。
- 真實 Qwen3 4B、BGE-M3 與 Qdrant；依校準案例調整生成格式／回答指令與重排窗口，保留原始失敗及 holdout 失敗。
- 本地 Ragas 0.4.3 控制案例與預選子集批次，並以實際引用原文做實作助理覆核；人工 review worksheet 尚未有獨立人員簽核。
- local-web jar 一個網站 port，Linux／WSL2 啟停／健康檢查、checksum 備份／還原、Java store lock 與舊向量清除；匯出不含模型／使用者文件／帳密的 zip。

下一次品質調整需補新的校準／保留題，不能把本次保留題調到全過後仍宣稱為 unseen holdout。沒有 GraphRAG、agent、線上每題背景評分、負載或固定域名承諾。

## 參考資料

- [Spring AI：Retrieval Augmented Generation](https://docs.spring.io/spring-ai/reference/api/retrieval-augmented-generation.html)：RAG 流程、檢索 advisor 與 metadata filtering。
- [Spring AI：AI Concepts](https://docs.spring.io/spring-ai/reference/concepts.html)：文件切分、embedding 與 RAG 基本概念。
- [OWASP：RAG Security Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/RAG_Security_Cheat_Sheet.html)：文件、檢索與上下文處理的安全設計參考。

## 第五里程碑：先 Cloud，後本機

本輪完成 Atlas 登入／權限展示，改以隔離虛構文件與展示帳號運行；新增角色與部門呈現、依權限唯讀／管理介面与切換身分時的工作區清除。跨作品規劃與本機／設備／外部帳號待補條件見 [CLOUD_WORK_AND_LOCAL_FOLLOWUP](CLOUD_WORK_AND_LOCAL_FOLLOWUP.md)，其他倉庫的實作另行接續。既有品質 holdout 與模型／生成提示保持不變。
