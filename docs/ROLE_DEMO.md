# Atlas 登入與權限展示

本輪接續 `7066902`，只在 ai-knowledge-assistant 實作。使用免費本地模型與專案獨立文件；所有人資資料為明確標示的虛構示範，沒有匯入真實員工或個人文件。

## 操作

1. 開啟已完成外部實測的 HTTPS 連結，登入一般使用者、HR 或管理員帳號。
2. 右上方顯示伺服器確認的帳號、角色與部門。按「登出／切換帳號」後再登入另一帳號。
3. 一般帳號可讀公開文件，無法讀取人資補助文件；HR 帳號可讀 hr 部門允許 HR 的文件；管理員可讀與管理文件。具有 HR 角色但部門為 engineering 的診斷帳號仍不能讀人資文件。
4. 可選「人資補助規範（虛構示範）」並問「HR 示範員工每月的學習補助是多少元？」；有權限時回答應引用該文件，可點來源核對。一般帳號不應出現該文件、數值或來源。
5. 文件資料庫依當前權限顯示匯入／更新／刪除，無寫入權限時提供唯讀原文。後端 API 仍獨立檢查權限；介面只是呈現可用操作。

生成的示範帳密只留在 Git 忽略的 `work/role-demo/<instance>/profiles.json`，0600 權限；不寫入 source、Git、截圖或驗證 JSON。這是展示用帳號檔，不是正式使用者管理介面。帳號修改需重啟服務，沒有 JWT／SSO 或動態帳號管理功能。

## Cloud 重現步驟

使用已完成模型下載／build 的專案環境，Python 需 `bcrypt==5.0.0`（現有 `work/venv-ragas` 已提供）：

```sh
work/venv-ragas/bin/python scripts/prepare-role-demo.py --instance role-demo-v5 --source-config config/local-deployment.example.json --port 18081
python3 scripts/local-deploy.py start --config work/role-demo/role-demo-v5/config.json
node scripts/bootstrap-role-demo.mjs work/role-demo/role-demo-v5/instance.json
NODE_USE_ENV_PROXY=1 node scripts/verify-role-demo.mjs work/role-demo/role-demo-v5/instance.json
```

如果 18081 或模型 ports 已被占用，先依既有 instance 的部署管理器停止自己的程序，或明確配置不同 ports／模型借用；不能終止其他作品。通道單獨由 `scripts/public-tunnel.py` 管理，網址只有外部頁面、提問與來源實測成功後才分享。驗證腳本的預設 URL 在 `work/public-url.txt`；`AKA_DEMO_BASE=http://127.0.0.1:18081` 可只做本機 Cloud 檢查。

prepare 必須建立新目錄，拒絕覆寫帳號；bootstrap 只接受全新的 pinned seed 文件與 version 1，拒絕已修改的文件或重複執行。既有公開展示 store 與先前驗收題集保留，沒有把公開 store 直接轉成人資資料庫。

新 `/api/status` 只回傳當前 actor 的 `roles`／`departments`，來自 Spring Security；匿名值為空集合。切換身分會清除文件列表、選擇、回答與編輯器內容，並丟棄仍在傳送的舊身分回應。

## 本機與其他作品後續

本輪不要求使用者下載模型到本機或操作實體手機。完整跨作品方向、Cloud 能做事項與待本機／設備／帳號条件，統一在 [雲端與本機待補清單](CLOUD_WORK_AND_LOCAL_FOLLOWUP.md)。發呆亭、ELPSY Shop、App、電商資料庫與搶票網站均按該清單進入各自倉庫；本輪沒有更動那些倉庫。

## 實測紀錄

2026-10-05 16:38（Asia/Taipei）已以真實外部 HTTPS 通過登入／角色展示。匿名資料 API 401；一般與其他部門 HR 的人資文件／canonical source 404；HR 讀取 200、修改 403；管理員讀取／修改 200。一般帳號直接指定私有文件提問也得到 404。

HR 的真實模型回答與管理員瀏覽器各核對一個來源／生成引用，來源按鈕一致；四種帳號均依伺服器角色與部門檢查，唯讀 HR 介面通過，登出清除回答與文件，390px 無溢出。輸入／輸出 token 實際為 329／46，沒有 API／模型 stubs。第一輪瀏覽器檢查因測試在文件列表載入前讀取而失敗；修正測試等待後通過，保留兩份 log，沒有為此調整檢索／生成模型。

後端 45/45、前端 typecheck／production build、離線瀏覽器 4/4 通過；打包 jar 的網頁 assets 與 dist byte 相同。額外的延遲私有回應測試採明確 browser stubs，真實 HTTP 工作所有權／認證仍通過。[外部實測](verification/milestone5-role-demo.json)、[身分切換保護](verification/milestone5-job-auth.json)、[desktop](screenshots/milestone5-role-desktop.png)、[mobile](screenshots/milestone5-role-mobile.png)。

先前 40 題品質數字屬凍結候選；本輪只驗證登入／權限呈現與角色展示，不宣稱重新調參或重新執行同一 holdout。

一般帳號的真實全庫查詢也已驗證：證據閘門拒答，沒有 HR 文件來源或 3000 元示範數值，生成模型未呼叫，見 [JSON](verification/milestone5-unscoped-privacy.json)。prepare／bootstrap 重複執行均拒絕且既有資料 byte 不變，帳號與設定檔為 0600，見 [guards](verification/milestone5-preparation-guards.json)。
