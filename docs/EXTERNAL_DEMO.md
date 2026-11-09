# 外部 HTTPS 展示

## 第四里程碑：免費 Qwen3 4B 與背景提問

目前以 [本機部署工具](LOCAL_DEPLOYMENT.md) 管理單一包含網頁的 Spring Boot jar、三項免費 CPU 模型與 Qdrant；模型與應用只綁定 loopback。外部 Tunnelmole WSS 通道另由 `scripts/public-tunnel.py` 管理，沿用 inherited HTTPS_PROXY / CA，TLS 驗證保持開啟。通道每二十秒 ping，斷線自動重建，網址可能改變；這是臨時測試，最終部署在使用者本機。

```sh
python3 scripts/local-deploy.py start --config config/local-deployment.json
node scripts/bootstrap-rag-demo.mjs http://127.0.0.1:18081
python3 scripts/public-tunnel.py
node scripts/verify-rag-demo.mjs
```

通道依賴的專案本地 npm 安裝方式見下方歷史段落。公開前必須實測 HTTPS 頁面、HTTP 202 提問、200 狀態查詢、真實生成、每個 canonical source、瀏覽器來源核對、token 與手機版面。CPU 模型的長同步請求曾遇 gateway 504；新版採短請求查詢背景結果，沒有因此改用付費模型或調整品質 holdout。

2026-10-05 15:32（Asia/Taipei）已成功實測：API / browser 各 5/5 來源、1/1 生成引用；頁面 200、工作建立 202、查詢 200、來源按鈕一致，無 JS error 或手機溢出。桌面／手機截圖已目視檢查。權限自然問句的額外診斷仍誤拒答，實際伺服器 ACL 測試與該問句的語意回答是不同檢查。最新環境 revision **31**，observations_current=true，HTTP policy unrestricted / enforced；仍使用 inherited proxy / CA。

最新正式實測證據另存 [JSON](verification/2026-10-05-public-rag-v4-demo.json)；舊網址與下方紀錄屬歷史，不應視為仍有效。最新 URL 以成功實測證據及 `work/public-url.txt` 為準。


## 第三里程碑：免費本地 RAG

使用者已解除「不啟用／下載模型」限制，要求先完全不需付費。新版使用 [RAG_STACK](RAG_STACK.md) 的 Qwen2.5、BGE-M3、BGE reranker 與專案本地 Qdrant；預設模式仍 offline，展示明確使用 `--rag`。下面離線紀錄保留為歷史證據。

下載與通道沿用環境 proxy / CA / TLS；沒有增加直接 Internet TCP 授權或關閉 TLS 驗證。Cloudflare 的直接 tunnel TCP 不在目前政策授權清單，採用已實測可用的免費 Tunnelmole WSS 經 `HTTPS_PROXY`。沒有付費模型服務、訂閱或 API key。模型 runtime 與三項展示程序均只在此倉庫，全部應用 ports 為 loopback。

模型及應用需要分別維持運行。先完成 README / RAG_STACK 的 build 與免費下載，再啟動模型 runtime。通道依賴安裝在 Git 忽略的專案工具目錄：

```sh
npm install --prefix work/tools/tunnel --no-audit --no-fund tunnelmole@2.4.0 https-proxy-agent@7.0.6
python3 scripts/local-model-runtime.py
```

另一終端啟動應用與通道；新 store 才需安全更新展示 corpus：

```sh
python3 scripts/demo-supervisor.py --rag --tunnel
```

```sh
node scripts/bootstrap-rag-demo.mjs
node scripts/evaluate-rag.mjs
node scripts/verify-rag-demo.mjs
```

`verify-rag-demo.mjs` 不修改文件，以真實外部 HTTPS curl 與 Chromium 驗證頁面、中文提問、所有候選來源 canonical lookup、生成引用、無證據案例、UI 點擊來源、token/stage 顯示與 390px 版面。TLS 驗證保持開啟；遠端 Chromium 使用 inherited proxy。可設定 `AKA_DEMO_BASE=http://127.0.0.1:4175` 進行本地檢查；Node 遠端評估另需 `NODE_USE_ENV_PROXY=1`。

本次雲端使用 Python `subprocess.Popen(..., stdin=DEVNULL, stdout=<本倉庫 log>, stderr=STDOUT, start_new_session=True)` 啟動兩個 supervisor，使其不依賴工具終端。应用 supervisor 每兩秒檢查自身子程序，WebSocket 每 20 秒 ping，通道斷線時由 supervisor 重建。模型 runtime 若子程序退出會關閉其餘自身程序；需查看 log 修復重啟。前端／模型 supervisor PID 與狀態、最新 URL 在 `work/`；停止前核對 `/proc/<pid>/cmdline` 與 cwd，只 SIGTERM 自己的 supervisor。

免費隨機 HTTPS 通道仍依賴這個執行環境及程序存活，重連可能換網址。它是外部測試連結，沒有承諾長期主機或固定域名。2026-10-05 12:34（Asia/Taipei）真實外部驗證成功：[外部實測 JSON](verification/2026-10-05-public-rag-demo.json)、[程序恢復／checksum manifest](verification/milestone3-manifest.json)、[desktop screenshot](screenshots/milestone3-rag-desktop.png)、[390px screenshot](screenshots/milestone3-rag-mobile.png)。原始離線兩份紀錄不覆蓋。

實測 HTTPS：`https://khux3w-ip-90-96-179-66.tunnelmole.net`。頁面 HTTP 200；API 與真實瀏覽器各用「你用什麼架構」得到 ANSWER_GENERATED，各 5/5 候選來源與 1/1 生成引用核對；來源按鈕顯示目前版本原文一致。無證據案例拒答，token/stage 顯示通過，沒有 pageerror／意外對話框，390px 沒有水平溢出；兩張圖已目視檢查。

最後讀取的環境 spec revision 為 **17**，`observations_current=true`，HTTP policy `unrestricted/enforced`；executor 的 TCP domains / ip_ranges 仍為空。所有外部 HTTP / WSS 經既有 proxy，沒有新增 TCP 授權。實際終止自己的 frontend 後 supervisor 將 PID 6915 重啟為 7121，代理 status 回復 READY / LOCAL_RAG_GENERATION。

## 歷史：指定提交的離線展示

2026-10-05 UTC，從 `codex/ai-retrieval-evaluation` 的 `d22eb1560e52f7d57dab8be392ef0089dbdcd376` 啟動。只操作 `/workspace/ai-knowledge-assistant`；沒有修改其他倉庫、commit 或 push。應用程式與預設檢索參數維持該提交。

使用 Tunnelmole 2.4.0 的 WSS 通道，經繼承的 `HTTPS_PROXY` 連至 `wss://service.tunnelmole.com:8083`，轉送至 loopback Vite preview 4175；`/api` 再由 Vite 代理至 loopback Spring Boot 18081。沒有使用直接 Internet TCP、改路由、關閉 proxy 或 TLS 驗證。最新版 environment status 為 spec revision 3、`observations_current: true`、HTTP policy `unrestricted / enforced`；executor policy 的一般 TCP 授權清單仍為空。

目前 HTTPS 位址及驗證時間記錄於 [實測紀錄](verification/2026-10-05-public-demo.json)。這是暫時測試通道，依賴本次雲端環境及三項執行程序持續運作；重啟通道可能取得不同位址。不是正式部署或長期 hosting。

## 通道恢復（2026-10-05，Asia/Taipei）

使用者後續回報 `No matching tunnelmole domain`。實際檢查發現三項服務程序都已退出，原網址映射失效；没有取得退出事件，無法確定退出的直接原因。環境最新版 spec revision 7 的 HTTP policy 仍為 `unrestricted / enforced`。

改以 `work/demo-supervisor.py` 管理三項服務，supervisor 與各子程序使用獨立 session，脫離工具終端的 process group。supervisor 每兩秒檢查子程序，退出後重啟；等前端代理 status 確認 offline 才建立通道。WebSocket 每 20 秒發送 ping，關閉後退出讓 supervisor 重建；隨機公開網址可能改變。關閉 debug 與 telemetry，日誌留在 `work/logs/*-supervised.log`。本次也實測僅終止自己的 frontend PID 後自動重啟，health check 恢復成功。

啟動 supervisor 使用 Python `subprocess.Popen(..., start_new_session=True)` 並將 stdin/stdout/stderr 脫離終端。其 PID 與子程序記錄於 `work/demo-supervisor.pid`、`work/demo-supervisor-status.json`；停止時核對 `/proc/<pid>/cmdline` 確實為本專案 supervisor，再對該 PID 發送 SIGTERM，讓它只關閉自己的子程序。下方前景終端指令保留作為手動啟動方式。

恢復後仍強制 `AKA_PROVIDER=offline`、`AKA_ALLOW_LOCAL_MODEL=false`；status 再次確認 `OFFLINE_EXTRACTIVE / BM25_KEYWORD / NONE`。沒有啟用真實 LLM 或下載模型。當前恢復後的網址與外部瀏覽器實測證據另存 [恢復紀錄](verification/2026-10-05-public-demo-recovery.json)，先前紀錄保留。

## 實測結果

- 完整 JDK 21.0.12.1+1、Maven 3.9.16：後端 26/26 tests，jar build 成功。
- Node 24.19.0：locked npm install、前端型別檢查與 production build 通過；評估腳本 tests 3/3。
- 既有系統 Chromium：本機端到端 4/4 通過。
- 真實外部 HTTPS 頁面與 JS/CSS 載入成功，瀏覽器呈現 `離線檢索 / 原文摘錄`。
- 外部 `POST /api/questions` 的 backend/frontend ports 問題得到 `EXTRACTS_FOUND`；5 個來源均核對 document ID、chunk ID、version、heading、line range、原文與摘錄 substring。
- 真實外部瀏覽器提問成功，點擊「核對目前來源版本」顯示原文與版本一致；沒有 pageerror，390px 手機版沒有水平溢出。
- 外部無證據問題得到 `INSUFFICIENT_EVIDENCE`、零摘錄。
- 經相同外部 HTTPS 完整執行固定 16 題：狀態 14/16、來源召回 13/14、引用原文與版本核對 49/49。Q13/Q14 的既有失敗保留；引用有效不代表語意答案正確。

全程 `AKA_PROVIDER=offline`、`AKA_ALLOW_LOCAL_MODEL=false`，status 為 `OFFLINE_EXTRACTIVE / BM25_KEYWORD / NONE`。沒有真實模型呼叫、embedding、付費 API 或模型下載；後端 Ollama tests 僅使用 loopback HTTP stub。

## 本次啟動與證據

Maven 與完整 JDK 解壓在忽略的 `work/tools/`；Maven settings 由繼承的 proxy 產生，沿用 `/etc/ssl/certs/java/cacerts`。沒有安裝全域服務。三項展示程序由持續存活的前景終端管理；初次短命 shell 的背景子程序退出，造成一次瀏覽器測試失敗，改用前景終端後完整 4/4 通過。

後端：

```sh
AKA_PROVIDER=offline AKA_ALLOW_LOCAL_MODEL=false \
AKA_STORE="$PWD/work/demo-store.json" \
work/tools/jdk-21.0.12.1+1/bin/java -jar backend/target/knowledge-assistant-0.1.0.jar
```

前端（Tunnelmole host 僅透過 runtime environment 加入 Vite allowed hosts）：

```sh
__VITE_ADDITIONAL_SERVER_ALLOWED_HOSTS=.tunnelmole.net npm --prefix frontend run preview
```

通道依賴僅安裝在 `work/tools/tunnel/`，沒有修改應用程式 package/lockfile。本次 runner `work/start-tunnel.mjs` 使用 `HttpsProxyAgent(HTTPS_PROXY)` 為 Tunnelmole WebSocket 設定 proxy，並把 Tunnelmole storage 改放在本倉庫 `work/tunnel-storage`；telemetry 關閉。不修改全域 HOME 或網路設定。先確認前端 health check 後再啟動：

```sh
TUNNELMOLE_TELEMETRY=0 TUNNELMOLE_QUIET_MODE=1 \
AKA_TUNNEL_STORAGE="$PWD/work/tunnel-storage" node work/start-tunnel.mjs
```

`work/verify-public.mjs` 以 curl 與系統 Chromium，經 inherited proxy 實測同一外部 HTTPS。Node 24 的原生 fetch 需要明確啟用 env proxy；初次評估因未啟用而連線失敗，最終成功命令為：

```sh
NODE_USE_ENV_PROXY=1 AKA_EVAL_BASE="$(cat work/public-url.txt)" node scripts/evaluate.mjs
```

詳細 logs：`work/logs/backend-verify.log`、`frontend-e2e.log`、`public-verification.log`、`external-evaluation-proxy.log`。外部真實截圖：`work/screenshots/public-answer-sources.png`、`public-mobile.png`；已目視核對回答與來源畫面。原始 JSON、store 與工具留在忽略的 work，未覆蓋既有 eval artifact 或歷史截圖。停止展示時在各自前景終端按 Ctrl+C，只停止本專案程序。
