# 本機部署：Linux x64 / Windows WSL2

最終在使用者本機執行；不依赖 Codex Cloud 或臨時 tunnel。預定可用 RAM 24–32GB，其他作品各自設定未占用連接埠。此套件已選定免費本地 Qwen3 4B、BGE-M3、BGE reranker、Qdrant；沒有付費 API 或雲端模型 fallback。原生 Windows 程序管理沒有在本次驗證，Windows 請在 WSL2 Linux x64 執行。

需要 Java 21+、Python 3.10+、Node 24、curl、tar。已打包前端的 jar 不需要 npm 或 Maven；從原始碼建置則需要完整 JDK、Maven 與 `npm ci`。模型／runtime 下載約 3.82GB，另預留解壓、向量與備份空間；一般啟動不會重新下載。

## 從原始碼建置

```sh
npm --prefix frontend ci
npm --prefix frontend run build
mvn -f backend/pom.xml -Plocal-web verify -B -ntp
python3 scripts/package-local.py
```

產物在 `work/packages/`，包含 frontend 的 Java jar、啟停工具、設定與下載 manifest，不含模型、使用者文件、密碼檔或 QA 帳密。解壓之後先對照 `package-manifest.json` 的 SHA256；從解壓後的 `ai-knowledge-assistant` 根目錄執行後續指令。

## 下載、設定、啟動

```sh
node scripts/setup-local-rag.mjs --download
cp config/local-deployment.example.json config/local-deployment.json
python3 scripts/local-deploy.py start --config config/local-deployment.json
python3 scripts/local-deploy.py status --config config/local-deployment.json
```

下載固定 commit／release 與 SHA256，TLS 與 HTTP 代理沿用環境設定。若缺少或 checksum 不符，服務不會啟動。`--legacy` 才選用歷史 Qwen2.5 1.5B 下載；它不適用於目前預設部署。

前端與 API 共同在 `http://127.0.0.1:18081`。初始離線範例可升級成目前架構文件：

```sh
node scripts/bootstrap-rag-demo.mjs http://127.0.0.1:18081
```

只覆寫 checksum 符合已知 seed 的 architecture／operations，保留 version 檢查；使用者編輯的文件會拒絕覆寫。首次問答會建立向量，之後依文件版本同步。後端啟動不代表模型已就緒，`status` 會核對 HTML、API 模式、實際 LLM alias、embedding、reranker、Qdrant；任何一項失敗都回傳非零 exit code。

修改 JSON 的 instance、port、store、collection、models.workDirectory 以及五個 model ports，可隔離不同實例；路徑必須留在本專案。`models.threads=null` 依 CPU／cgroup 自動設定，最多四執行緒；本機可自行指定。若想共用已啟動的四個模型服务，可設 `models.reuse=true`，健康檢查仍執行，停止此網站不會停止借用的模型。模型檔與 binary 路徑可配置，預設為 pinned Linux x64 版本。

自行管理模型時，start 在啟動子程序之前串流核對三個模型與兩個執行檔 SHA256，預設取 pinned manifest，缺少或不符則拒絕啟動。若使用自己選定的其他免費 artifact，需在 `models.artifactSha256` 明確設定對應欄位 hash，並自行核對來源與 `llmModel`；路徑仍只能在本專案。`reuse=true` 只核對借用服務的健康與 alias，不能證明外部程序實際載入的模型 bytes。

所有服務只監聽 loopback。對外展示需另外設定反向代理／tunnel；這不改變本機啟動方式。正式文件需要認證時，設定 `accountsFile` 指向本專案內 JSON，格式見 [RAG_STACK](RAG_STACK.md)。帳號密碼 hash 必須由使用者產生，測試用帳號不隨套件提供。公開模式只允許公開文件，但寫入 API 也可由匿名訪客操作；請勿把自己的敏感內容匯入公開展示。

Ragas 是另外執行的離線批次，會與網站共用同一個 CPU LLM；請安排在沒有展示流量時執行。第一次問答包含來源向量建置，啟動健康檢查尚未暖機這個步驟。在本次 2 CPU / 8 GiB 雲端測試，冷啟動與評分同時執行曾造成 150 秒 client timeout；後端在約 162 秒完成。這不能當作本機效能預測，也不能宣稱通道已斷線。模型 stage 耗時與 request ID 可用來區分等待來源建置、重排、推論及網路問題。

## 停止、備份、還原

```sh
python3 scripts/local-deploy.py backup --config config/local-deployment.json
python3 scripts/local-deploy.py stop --config config/local-deployment.json
python3 scripts/local-deploy.py restore --config config/local-deployment.json --archive work/backups/實際檔名.zip --replace
python3 scripts/local-deploy.py start --config config/local-deployment.json
```

備份包含同一 revision 的原始 Markdown／chunks／metadata、部署設定與已配置的帳號檔；檔案權限 0600，請自行保管。它可能包含敏感文件與 password hashes，不能放到公開 Git。JSON store 以原子替換寫入，活躍服務可以讀取一致的快照。向量不是唯一真實來源，因此不備份 Qdrant；還原後下一次問答會依來源重建／同步。

還原先核對 archive entry 白名單、每檔 SHA256 與每份 Markdown contentHash，並取得與 Java 相容的 store 檔案鎖；另一個程序正在使用同一 store 時拒絕還原。既存檔案預設拒絕覆寫，`--replace` 前須確認目標；覆寫前保留 `.before-restore-<timestamp>`。多檔還原不是跨檔 transaction，磁碟錯誤時請用保留檔恢復並檢查記錄。

程序管理會核對 PID、啟動時間、隨機 token 與工作目錄，只停止這個 instance 的子程序。未配對或重用的 PID 不會被終止；模型借用模式不碰外部程序。記錄在 `work/deployment/<instance>/`；CPU 推論為循序展示用途，沒有負載或長時間 uptime 保證。

## CPU 推論與背景提問

RAG 網頁以 HTTP 202 建立背景工作，每兩秒用短請求查詢結果，最多等待五分鐘。這避免單次 CPU 推論超過外部通道等待期限；同步 `/api/questions` 保留相容性，仍可能遭外部 gateway 504。個別模型呼叫上限 120 秒。關閉頁面不會取消已接受的推論；服務重啟會清除工作，需重新提問。文件改版或自然過期時不會發布舊來源回答。

目前先完成 Cloud 工作，本機搬移、真機 App 與多專案整合暫缓，詳見 [待補清單](CLOUD_WORK_AND_LOCAL_FOLLOWUP.md)。新登入展示的操作與範圍見 [ROLE_DEMO](ROLE_DEMO.md)；先前匯出 zip 仍屬第四里程碑版本，最新原始碼與展示版本以分支及實測紀錄為準。
