# 開發交接 — AI Knowledge Assistant / Atlas

## 2026-11-08：自動更新端到端驗證（Atlas）

本次只增加此交接段落；不變更應用邏輯、資料庫、帳號、映像 recipe、CI 或部署契約。

- 目的：驗證 Atlas／Encore CD 恢復與主機讀取憑證換新後，正常 PR → 原完整 CI → merge → 私人 GHCR 發布 → Ubuntu 主機自然排程部署的完整鏈仍可用。
- 無可見內容變更；以主機實際部署來源 SHA 等於本次 main 作為判定。
- 公開唯讀版不在本專案範圍（Atlas 維持不公開），只驗證私人主機部署。
- 未完成上述實測前，不以本段文件或映像發布宣稱自動更新 PASS；結果保存於 server-management 私人交接。

---

## 2026-10-20：Atlas 首次主機 CD 通過／自然自動更新驗證

- 測試標記：ATLAS-CD-VERIFY-20261020-A。本次只新增本交接段落，不修改業務、模型、索引、帳號、config 或發布 recipe。
- 已完成：PR #4 正常合併 main 3339790741b263e4cebac0871ef5c551f0a99447；真 main CI 39429912066 與 Private GHCR publisher 39430039309 PASS。原缺少 local-web 與目錄穿越權限已修復，原失敗紀錄保持。
- 主機實測：root atlas-retry-1792505819800266250／SHA256 3ad100caa928a9c49fb15845a848455d1a9f9addba9f822f9c1c9bc386c5e849 FIRST_CD_COMPLETE/PASS，實際 app digest 3592312676a16906766172ee58504cf89596a1fe0f515f6ddbdd0e0a9a7ed27c 與 OCI source/revision/recipe 吻合。
- 資料保護：新鮮加密備份、NAS copy、完整 JSON/accounts/Qdrant byte 還原、真隔離 old→new 及故障回舊、真實 HYBRID_BM25_DENSE RAG/引用全部 PASS；正式資料不回灌、不 query/重建索引，模型沒有下載。其他四站312次探測0失敗且正式版本保留。
- 重要界線：首次部署成功不等於自然自動更新已完成。本提交用來驗下一個 main→CI→Private GHCR→原共享 timer→實際容器/OCI/資料指紋與成功交易；必須以 server-management 後續 root receipt＋成功 journal 判定，現在仍 BLOCKED。
- 本次測試／build：本文件變更將走原完整 CI 與原 publisher，尚未執行的本 head/main 結果不得先標 PASS。原版本各項成功測試不重跑。
- 下一步：本 head 原 CI PASS 後正常 PR 合併，追實際 main CI/private新digest及自然 CD；不手動切版、不重跑 first/v1，不動 Portfolio/Camp/Shop/Encore、SSH/UFW/Tailscale/NAS/Funnel。全串通過才 Atlas AUTO-CD PASS，之後接續 Shop、Encore。


## 2026-10-20：Atlas 非 root backend 目錄權限修復

- 本次完成：原 CI d520 已 PASS，但固定舊 image 唯讀 audit 在非 root 開啟 JAR 時失敗，並非登入或拉取失敗；publisher backend 的遞迴 COPY 0444 缺目錄穿越權限。回復原部署已使用的 0555／固定 UID 10001，沒有增加容器權限。
- 重要修改：publisher 另增實際 nonroot JAR/index 讀取 gate；package tests 改由未啟動的暫存容器取得 backend archive，驗原 digest/OCI/User、JAR hash、Vue 與目錄 mode，隨後只移除該暫存容器。不以 root 啟動 App，也不修改或重發舊 image。
- 本輪實測：9 項本機 package tests PASS，涵蓋原生 0444 目錄拒絕與 archive metadata。Hosted 真 archive 結果及新封裝依此新 head Actions 為準，尚未執行不可標 PASS。原 d520 封裝 FAIL 保留，不能略過。
- 尚未完成：Ubuntu 首次正式 CD／真自動更新仍 BLOCKED；正式舊 app、資料及失敗交易保留。主機新續接須驗已知錯誤映像／資料保護／隔離 RAG／正式健康才接受 auto。
- 已知界線：不修改業務/config/模型/原 CI 或 publisher workflow；不放寬 /etc、root 或網路權限，沒有新 PAT。read-only preflight 不發布 GHCR 或接觸 Ubuntu。
- 下一步：新 head 原 CI＋封裝全 PASS 後正常合併，驗真 main CI/private新recipe與digest；再集中一次受限 root 續接，禁止重跑 v1。


## 2026-10-19：Ubuntu Atlas 首次 CD 的打包阻擋修復

- 本次完成：修復 publisher 漏用既有 `local-web` profile，並在封裝時驗證真 JAR 的 Vue index、app mount 及所有引用 JS/CSS；不重寫應用或 RAG。
- 重要檔案：`scripts/showcase-publish.py`、`scripts/test-atlas-package.py`、既有 packaging preflight workflow、本交接。
- 本輪實測：6 項本機打包回歸 PASS；原正式來源與 main 之間沒有業務程式/config 變更。Maven 原指令實測產生無 Vue 的 JAR；使用原 profile 後包含 index 與兩份資產。Hosted 原 CI、實際舊映像唯讀稽核及新封裝結果以本分支 Actions 為準，未完成不可標 PASS。
- 尚未完成：Ubuntu Atlas 首發 BLOCKED，root `atlas-first-1792423660564485459` 保留舊 app；完整備份/JSON及Qdrant byte還原已 PASS，隔離新版演練拒絕，未正式切換。真自動更新尚未驗收。
- 已知問題：第一次 CD 失敗處理另遇到沙箱 /etc 唯讀；主機工具需獨立限定修復，不能放寬沙箱或以這次 repo 修改宣稱已修主機。原完整模型品質歷史與限制保留。
- 下一步：原 CI 與此分支封裝/舊 image 內容稽核通過後正常 PR 合併，追真正 main CI/私有發布；由受限 root 新續接流程驗真隔離模型與回復/正式健康，成功後才本站 auto，之後再做最小新 main 自動更新測試。
- 範圍：只修此作品發布與驗證；不下載模型、改正式索引/帳號/資料，不動其他四站或網路/Funnel。完整 Ubuntu 交易/狀態證據仍以 server-management 及 root reports 為準。



## 2026-10-06：工作階段與 context 安全（本輪最新交接）

起始基準為遠端 main `495b537`；只更新規範與交接，沒有商業程式或 migration 變更。

### 本次完成內容

- 四個 workspace 倉庫的 AGENTS 與平台文件加入一致的八項規則：主動檢查可用 context／token／usage、預留收尾空間、額度可能不足時停止新增大型修改、優先測試／build 及交接／commit／push、每個里程碑立即保存、compaction 後重建實際狀態、最後核對乾淨及遠端 main。
- 沒有精確用量介面時採保守分段，不捏造剩餘數字；規則適用整個展示平台及後續 Web／後端／App／搶票專案。

### 重要修改檔案

[AGENTS.md](../AGENTS.md)、本交接、[驗證摘要](verification/context-safety-2026-10-06.json)、[驗證文件](VERIFICATION.md)；全文見 [平台規範](https://github.com/jackteng-dev/developer-portfolio/blob/main/docs/PLATFORM_WORKFLOW.md)。

### 測試／build 結果

- 本輪前端型別檢查與 production build 成功（npm --prefix frontend run build）。
- 本輪後端 45 項通過，0 failure／error／skipped，build 成功。Maven verify；隔離 store／合約 stub，未執行真實模型推論。
- 八項要求完整，四份 AGENTS 與平台文件內容一致；交接六項、新增連結及 git diff --check 核對通過。原始日誌留在 workspace 暫存，Git 僅收錄非敏感摘要與雜湊。
- 瀏覽器／外部 HTTPS／真實模型品質未重跑，前輪數字維持歷史條件。

### 尚未完成事項

- 原功能與本機／設備／帳號待補工作維持前輪紀錄；本輪沒有新增功能。
- ELPSY Shop 正式倉庫來源待確認，新搶票倉庫尚未建立；規範適用，但不宣稱已修改那些倉庫。

### 已知問題

- 目前沒有可用的精確剩餘 context／帳戶 usage 查詢工具；保守保留收尾空間，不宣稱已讀取實際剩餘值。
- 測試／build／push 失敗時如實記錄並保留安全成果，不標為成功、不 force push；各作品既有功能限制仍見前輪紀錄。

### 下一步建議

1. 新階段重新核對遠端 main／交接／Git 狀態；每個可驗證里程碑完成就保存，避免累積到大型任務最後。
2. 依原待補清單續作 Atlas，保持凍結題集與歷史證據；沒有新增模型或品質實驗。
3. 發生 compaction 或資訊不明時，重讀原始檔、status、diff（含 staged）、log、HANDOFF 與遠端後才續作；收尾確認 status 乾淨、main 已包含最新 commit。

## 2026-10-06：平台共同流程（上一輪紀錄）

起始遠端 main：`534113d`；本輪驗證的程式基準：`a8e3445`。完成後由各倉庫 main 提交並推送；確切提交以遠端 main／git log 為準。平台規範見 [共同工作流程](https://github.com/jackteng-dev/developer-portfolio/blob/main/docs/PLATFORM_WORKFLOW.md)。

### 本次完成內容

- 使用者指定的九步流程適用於整個平台，各倉庫各自同步 main、讀交接、核對狀態、測試／build、更新交接、commit／push；新 Web／後端／App 與搶票作品亦適用。
- 保留原 `codex/ai-retrieval-evaluation` 分支，將其 `a8e3445` 既有免費 RAG、部署、品質評估與登入／權限展示成果經本輪回歸驗證後 fast-forward 整合至 main；沒有重調模型、題集或檢索。
- 一般單一作品工作仍限於該作品；本輪需求明確涉及平台流程，才同步更新四個 workspace 倉庫。未修改其他 GitHub 倉庫、寄信、執行金流或下載模型。

### 重要修改檔案

[AGENTS.md](../AGENTS.md)、[README.md](../README.md)、`docs/HANDOFF.md`、[驗證紀錄](verification/platform-workflow-2026-10-06.json)、[VERIFICATION.md](VERIFICATION.md)。

### 測試／build 結果

- **本輪實測**：前端型別檢查與 production build（`npm --prefix frontend run build`）成功。
- **本輪實測**：後端 45 項，0 failure／error／skipped，build 成功。Maven verify；合約與整合測試使用 stub／隔離暫存 store，未重新執行真實模型推論。
- **本輪實測**：評估腳本 3 項、部署／還原安全 5 項、Ragas 網路與判斷防護 3 項，共 11 項通過；使用 deterministic stubs，不算語意品質量測。
- 交接六項內容、共用規範與 `git diff --check` 已核對；原始日誌留在 workspace 暫存，Git 僅收錄不含帳密的摘要與雜湊。
- 舊功能、瀏覽器／HTTPS／真實模型與效能數字保留為歷史條件，本輪未重跑或改寫其結論。

### 尚未完成事項

- 真實本機安裝、多專案同機、個人文件、手機／App、長期負載與獨立人工語意標註仍待補；見 [待補清單](CLOUD_WORK_AND_LOCAL_FOLLOWUP.md)。
- 平台流程對 ELPSY Shop／App／新搶票專案同樣有效；缺少正式倉庫對應或尚未建立的項目不宣稱已修改。

### 已知問題

- 引用核對不等於語意正確；40 題 37/40 與保留題 22/24 是歷史功能結果，仍有漏條件／誤拒答／原因混淆。預設 offline；真實本地 RAG 需明確啟用。正式使用者管理與 SSO 尚未實作。
- 分支保護或檢查失敗時記錄真實阻礙，不 force push；既有 master 倉庫先盤點 main／部署依賴，不自行改名。

### 下一步建議

1. 重新 fetch 本倉庫遠端 main，先讀該版本本文件，再核對 status、branch 與近期 commit；正常後 fast-forward 同步再修改。
2. 依最新 main 與 [待補清單](CLOUD_WORK_AND_LOCAL_FOLLOWUP.md) 選下一個 Cloud 工作項目；保留凍結題集，後續品質改善需另記新驗證條件。
3. 階段完成後跑相應測試與 production build，成功後更新六項交接，commit／push main，再確認遠端一致與沒有重要未提交修改。

## 先前里程碑紀錄（歷史）

更新日期：2026-10-05（Asia/Taipei）。雲端目錄 `/workspace/ai-knowledge-assistant`；本機 `D:\Project\ai-knowledge-assistant`。本次只修改此倉庫，developer-portfolio 維持 Phase 1。

先讀 `AGENTS.md`、[README](../README.md)、[PROJECT_PLAN](PROJECT_PLAN.md)、[VERIFICATION](VERIFICATION.md)、[EVALUATION](EVALUATION.md)，再依工作範圍讀 [ARCHITECTURE](ARCHITECTURE.md)／[API](API.md)。

目前接續第四里程碑：使用者最終在本機部署，已確認可用 RAM 24–32GB，計畫展示四至五個專案，不需再次評估硬體。採完全免費方案，已允許下載／啟用真實模型，不可加入付費 API fallback。本次只操作本倉庫；原功能分支為 `codex/ai-retrieval-evaluation`；2026-10-06 使用者已指定整個平台改以遠端 main 接手、提交與推送。優先使用 [本機部署](LOCAL_DEPLOYMENT.md) 的 local-web jar 與部署管理器；下方第二／三里程碑紀錄保留歷史條件。

生成模型目前為 Qwen3-4B-Instruct-2507 Q4_K_M，BGE 與 Qdrant 維持固定版本。40 題凍結驗收分校準 16／保留 24，保留題不回饋本次調參；資料由實作助理編寫，不能稱獨立人工準確率。Ragas 批次有真實本地推論與已知控制案例；API 當次語意分數仍未量測。歷史 eval、rag-corpus 與截圖保持不變，當前展示文件另在 rag-corpus-v4。

新增交付採 `/api/question-jobs` HTTP 202 + 短請求查詢，以免 CPU 推論超過通道期限。45 項後端測試通過；外部頁面、真實回答、canonical source 與來源按鈕已實測成功。工作身份來自伺服器，發布時重驗權限／到期／版本；認證變更後丟棄瀏覽器舊回應。品質候選 jar 與交付 jar 的區別見 QUALITY_EVALUATION；不重調保留題。

## 最新工作安排

使用者要求先完成 Codex Cloud 可做的部分，需要本機／真機／外部帳號条件的工作暫缓，統一見 [待補清單](CLOUD_WORK_AND_LOCAL_FOLLOWUP.md)。發呆亭權限按營運需要收斂；ELPSY Shop 保留正式電商與 App 的目標；搶票網站列為新的限量售票、排隊與故障處理作品。本輪先接續 Atlas 登入／權限展示，未更動其他倉庫。

## 已完成

第一里程碑：Markdown 匯入／更新／刪除、版本與 deterministic chunk ID、JSON store、BM25 檢索、無證據處理、原文摘錄與 canonical citation 核對。第二里程碑：可設定 chunk 上限／topK／coverage gate、實際配置 metadata、隔離批次比較、穩定性核對、原始 artifact、完整逐題差異與回歸測試。

當前預設仍是 **800 UTF-16 units / topK 5 / gate .60**。固定 16 題 baseline 為狀態 14/16、來源 13/14、引用 49/49；Q13 同義詞漏找、Q14 錯誤前提仍保留。寬鬆 gate 產生 15/16 狀態卻沒有找回 Q13 的 Update source；嚴格 gate 拒絕 Q14 同時造成更多錯誤拒答。不宣稱答案準確率或語意問題已解決。

第三里程碑新增 **免費本地 RAG**：BGE-M3 semantic boundary merging / dense retrieval、Qdrant、BM25 + RRF、BGE reranker、Qwen2.5-1.5B 生成、metadata 與伺服器認證／權限。使用者已解除模型限制，要求先不付費；沒有付費 API。預設仍 offline，RAG 需明確 opt-in。啟動、固定模型 checksum、權限與限制見 [RAG_STACK](RAG_STACK.md)。

## 接續命令

需要完整 JDK 21–25、Maven 3.6.3+、Node 24。從根目錄：

```sh
mvn -f backend/pom.xml verify -B -ntp
npm --prefix frontend ci
npm --prefix frontend run build
node --test scripts/evaluation.test.mjs
node scripts/compare-retrieval.mjs
```

批次不需要展示服務；每組自建全新暫存 store/JVM、OS 指派 loopback port、強制 offline，完成後清理。預設輸出帶時間戳，拒絕覆寫既存檔案。自訂矩陣需含歷史 baseline 800/5/.60，資料／題集仍必須與原始 baseline 相同。完整用法见 README。

展示使用兩個終端：

```sh
java -jar backend/target/knowledge-assistant-0.1.0.jar
```

```sh
npm --prefix frontend run preview
```

API 18081、前端 4175，均為 loopback。Linux Ctrl+C 管理自己的程序；Windows 可用 start-local / stop-local.ps1，重建前先停本專案避免檔案鎖。瀏覽器測試先準備 Chromium：安裝 Playwright shell，或 `AKA_CHROMIUM_PATH=/usr/bin/chromium npm --prefix frontend run test:e2e`。它會操作專用 `browser-lifecycle` ID，勿用於自己的資料；截圖預設 `work/screenshots`，不覆蓋歷史截圖。

`AKA_CHUNK_MAX_CHARS` 32–8000（預設800）、`AKA_TOP_K` 1–5（預設5）、`AKA_COVERAGE_THRESHOLD` 0–1（預設.60）。問答 `topK` 可覆寫預設，UI 省略此欄使用伺服器設定。每次 response 的 `configuration` 記錄有效參數。不相容的持久化切分拒絕啟動並保留原檔；更換 chunk 大小用獨立 `AKA_STORE`，不直接重切個人文件。

## 驗證證據與資料

第二里程碑 2026-10-05 雲端實測：後端 **26/26**、評估腳本 **3/3**、前端型別檢查／production build、Chromium **4/4** 通過。27 組設定各暖機一輪／量測三輪，1296 個 measured queries、1989/1989 次 measured citation checks，邏輯結果三輪一致。詳細條件与工具補齊方式在 VERIFICATION。

- 歷史 baseline：`eval/results.json` 不變，報告另存 `docs/EVALUATION_BASELINE.md`；第一里程碑 logs／screenshots 保留。
- 新結果：`eval/retrieval-comparison.json`、`docs/RETRIEVAL_COMPARISON.md`、`docs/EVALUATION.md`；引用率不代表答案正確。
- `scripts/evaluate.mjs` 改存 `work/evaluation/single-<timestamp>.json`，不再覆蓋历史 evidence。
- `data/`、`work/`、依賴、build、環境檔與瀏覽器暫存不上傳；`docs/verification/milestone2-*.log` 刻意保留實際驗證紀錄。

雲端原本 Maven 缺失、Java 21 缺少完整 JDK 編譯檔案；本次在忽略的 `work/tools/` 解壓 Maven／完整 JDK，沿用 session HTTP proxy 與 CA，沒有安装全域 DB 或 Docker。其他電腦使用自己的完整 JDK／Maven；不要把本次工具絕對路徑當成專案設定。

## Git 與下一步

原成果分支 `codex/ai-retrieval-evaluation` 保留；最新接手改以遠端 main 為準，再依 README build／重跑。不要上傳個人 store 或環境資料。

第二里程碑的後續方向原為擴充 holdout／語意標註；本次已有凍結校準／保留題與本地 judge，但獨立人工標註仍待補。topK=3 在历史小 corpus 可減少摘錄數但未解決 Q13／Q14；不改寫原 baseline。hybrid retrieval、embedding、LLM、reranking、權限與新部署工具已加入；agent 未完成。下載需明確執行 setup --download，後端預設不自動下載或推論。新 local-deploy 專門啟動 RAG，不同於手動 baseline jar。

## 第三里程碑接續

最新後端 38 項測試、前端 build 與真實免費模型問答證據見 VERIFICATION；保留原始 baseline 題集與資料。RAG 專用 corpus 更新 architecture / operations 為 v2，使用獨立 store，八題是共同編寫的功能檢查，不能宣稱相對 baseline 的準確率提升。

外部展示需模型 runtime 加應用／通道 supervisor 持續存活。process PID、不可變 jar、URL 與日誌在忽略的 work；停止前核對命令與 cwd，只關閉自己的程序。檔案重建後須重啟應用才載入新版本。第三里程碑驗證時尚未 commit／push；後續 Git 同步依使用者指示進行。
