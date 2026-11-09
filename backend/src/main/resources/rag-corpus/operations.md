# Atlas 本地 RAG 操作

## 模型與服務
從專案根目錄執行 python3 scripts/local-model-runtime.py，啟動本專案獨立的 Qdrant、BGE-M3 embedding、BGE reranker 及 Qwen2.5 模型。所有服務只監聽 loopback，不使用全域資料庫服務或 Docker。

## 展示啟動
設定 AKA_RAG_ENABLED=true、AKA_CHUNK_STRATEGY=semantic 與獨立 AKA_STORE，再啟動 packaged Java jar。前端使用 npm --prefix frontend run preview；/api 代理至 loopback 18081。

## 文件更新
匯入或更新 Markdown 後，文件版本與 indexRevision 增加。下一次問答會同步 Qdrant，舊版本候選在檢索時即被排除，同步完成後移除舊向量。更新後舊引用得到 409，刪除後得到 404；到期來源也不再可作為引用。

## 範圍與權限
公開展示預設只可讀公開文件。設定 AKA_AUTH_USERS_FILE 可啟用 Spring Security 的 bcrypt 帳號與角色；角色與部門由伺服器帳號檔決定，不接受瀏覽器自填角色。過期文件保留 metadata 供管理，檢索與模型上下文會排除過期內容。
