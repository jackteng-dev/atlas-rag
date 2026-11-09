# Local Operations 本機操作

## Start 啟動
Start the backend with mvn -f backend/pom.xml spring-boot:run from the project root. Start the production preview with npm --prefix frontend run preview. Check ports 18081 and 4175 before starting. 啟動前請檢查本機連接埠。

## Evidence gate 證據門檻
The evidence gate requires at least 60 percent distinct query keyword coverage across the top five retrieved chunks. Insufficient evidence returns no answer excerpts. Coverage is a lexical heuristic, not a confidence probability or semantic proof. 證據不足時沒有回答摘錄。

## Provider 模型
The default provider is offline. An optional local Ollama excerpt selector requires explicit opt-in and a local model name. It selects exact quotations, never arbitrary citation URLs. Real LLM inference and embedding integration have not been verified. 預設為離線模式，真實模型串接待驗證。

## Testing 測試
Backend tests cover chunk identity, persistence, updates, deletion, citation validation and evidence refusal. A fixed evaluation set includes answerable, unanswerable, cross-section, misleading and malicious-document questions. 測試題集固定並记录實測結果。
