# 第四里程碑：品質驗收與本機交付

2026-10-05，接續 `codex/ai-retrieval-evaluation` / `d22eb15`，只操作 ai-knowledge-assistant，未 commit／push。免費本地 Qwen3-4B-Instruct-2507 Q4_K_M、BGE-M3、BGE reranker 與 Qdrant 真實執行，沒有付費模型 API。最終部署目標是使用者本機 Linux／WSL2；雲端只是實作與外部測試環境。

## 凍結題集與比較條件

[題集](../eval/acceptance/questions.json) 共 40 題、七份文件，SHA256 `5c97ff8976f66c4c5cfdc805e47f18acc744d2138dacf6abcd6c485d3bb74534`。16 題校準可用於調整，24 題保留在最終候選固定後執行一次，沒有用其結果調參。問題包含自然中文、技術名稱、必要條件、跨段落／跨文件、英語、錯誤前提、沒有答案、到期、角色／部門、偽造 header、唯讀帳號與惡意文件。

題集、參考答案与資料由實作助理編寫，不是獨立人工標註的 benchmark。與歷史 16／8 題沒有相同的完整問題字串，但有重用的主題，不能據此宣稱完全獨立。architecture／operations 使用凍結的歷史 RAG 文件，其中 Qwen2.5 是驗收文件事實；推論實際用 Qwen3。當前公開展示文件另在 `rag-corpus-v4`，已更新成 Qwen3，沒有偷偷更改驗收來源。

最終候選 jar SHA256 `92566636a127ea071efe28aeff9017f40b73e7b8ab4051d64a76dff664e882e9`。BGE-M3／Qdrant／RRF 固定，重排 raw-logit floor -8、距最佳候選至多 5、topK 5。生成使用 complete-answer-v4 與互斥 JSON 結構分支，後端另驗證引用與回答／拒答一致性。這些門檻不是信心機率。

| 實驗 | HTTP／狀態符合 | 完整功能通過 | canonical source 核對 | 生成引用核對 |
|---|---:|---:|---:|---:|
| 歷史 1.5B 校準：-6、無相對窗口 | 16/16 | 15/16 | 15/15 | 13/13 |
| 初版 4B：-8、窗口 2 | 15/16 | 13/16 | 11/11 | 15/15 |
| 最終 4B 校準：-8、窗口 5 | 16/16 | 15/16 | 15/15 | 15/15 |
| 最終 4B 保留驗收 | 23/24 | 22/24 | 24/24 | 21/21 |
| 最終合計 | 39/40 | 37/40 | 39/39 | 36/36 |

原始結果：[歷史校準](../eval/quality-baseline-calibration.json)、[初版校準](../eval/quality-candidate-initial-calibration.json)、[最終校準](../eval/quality-calibration.json)、[保留題](../eval/quality-holdout.json)、[合併結果](../eval/quality-results.json)。完整功能檢查同時要求期望 HTTP／status、必要詞組、標註 heading 召回、權限／到期／注入守門及 canonical provenance；它不是語意準確率。

最終校準與歷史校準都為 15/16，但失敗案例不同，不能宣稱整體改善。D02「存在哪個向量資料庫」從摘錄無關的重排說明改為 Qdrant；D10 跨段落退款題從漏掉退款時限改為同時回答七天申請／五工作天退回。初版 D04 曾得到 MODEL_INVALID_OUTPUT；未保留當時完整 raw response，不能确定具體輸出錯誤。採互斥 schema 後 D04 實測正常。

## 保留的失敗與覆核

- **D08，校準**：正確說「候補不代表錄取」，但省略正式通知／付款條件，未通過既定完整性標準。它有依據，但答得不完整。
- **H06，保留**：詢問能否在請求中自稱管理員改變權限，被重排 gate 誤拒答。這是品質失敗；實際偽造角色／越權讀取／刪除仍得到預期 401／403／404。
- **H24，保留**：正確說刪除後不能取得內容，却把理由歸於到期，並漏掉 404。來源段落分別描述刪除與到期，沒有支持該因果關係；引用核對成功並未攔住這種解釋錯誤。

[實作助理覆核](../eval/quality-assistant-review.json) 對照 27 個生成答案的實際引用原文，指出 H24 的部分 unsupported explanation 與 D08 的完整性缺口。這不是盲測或獨立人工簽核。[人工覆核 worksheet](../eval/quality-human-review.csv) 保留待填欄位；沒有假裝已取得人工審核。

最終 40 題生成 usage 為 9275 input / 1366 output tokens，付費模型 API $0。容器實際限額 2 CPU / 8 GiB，原先主機顯示的 3 CPU／9.7 GiB 不代表容器可用資源。校準用 llama batch 2048／ubatch 512，保留題用 128／128；同時有 lifecycle／judge 等工作共用 CPU，不能以這些 latency 宣稱效能提升、負載能力或預測使用者本機效能。

## 本地 Ragas：真實執行與 judge 校準

Ragas 0.4.3 使用現代 collections scoring components，LLM／embedding 為明確的 loopback adapter。停用 telemetry、禁止 remote origin／redirect，沒有供應商 key 或付費 fallback。套件 compatibility 經 pip check 與真實執行核對；langchain-community 固定 0.3.31，避免新版移除舊 import 造成 Ragas 啟動失敗。

歷史 1.5B judge 只通過兩個／四個控制，還會改寫 NLI statements，結果不能信任；錯誤與 null 保留在 [legacy controls](../eval/ragas-legacy-controls.json)。4B 先通過原四個對錯／離題控制；校準 D10 又發現 context precision 要求每一段都包含完整答案，誤把兩個可合併的有效段落判為零。初版診斷 [artifact](../eval/ragas-initial-batch-diagnostic.json) 與 [中止原因](verification/milestone4-ragas-initial-batch-interruption.json) 保留，只有完成五筆，不能當完整預選批次。

judge 的 context precision 指令改為「只要支持一個直接相關事實即可，其他部分可由其他段落補足」，再增加兩段資料共同回答的 C05。五個控制全部通過，且相關性把正確回答排在離題回答之上：[校準控制](../eval/ragas-controls-calibrated.json)。NLI 仍須原序、原文返回每一條 statement；省略／改寫會令該分數 unavailable，不可灌成高分。

最終批次沿用執行保留題前已固定的十個 ID：[選樣](../eval/ragas-selection.json)。原選樣說明曾過度描述注入／拒答覆蓋，已只更正原因並保留原 SHA；ID 未改動。實際涵蓋自然中文、名稱、條件、跨段落／跨文件、授權 private answer 與 access denial。H09 的 404 沒有生成答案，語意分數保持 null。這是子集，不是每個問題都已自動評分。

最終 [Ragas 原始結果](../eval/ragas-results.json) 十筆全部完成，其中九筆有生成答案：context precision 均值 **0.9815**、faithfulness **0.9630**、answer relevance **0.8072**，三者覆蓋率都是 **9/10**。H09 因沒有可評答案／context 而跳過，沒有被賦予滿分。最終 job 實際 28 次 judge request、15 個 cache hits、10924 input／1753 output tokens，付費 API $0；cache hits 不重複計 token。沒有超出既定 schema 或 NLI 改寫而被填成分數的樣本。這些數字是該 judge 的子集判斷，不是全題集的品質或準確率。

generator 與 judge 使用相同模型，存在共同偏差；短中文 prompts、無 few-shot 與 relevance strictness=1 不同於 Ragas 預設。控制案例通過不能證明所有判斷可靠，faithfulness 也不能保證回答完整。實際分數／覆蓋率另列於完成的 Ragas artifact，人工仍需核查原文與原因。

最終批次的 H17 揭露另一個 judge 失敗：原回答說「付款第八天……不符合退款資格」，statement generator 却把最後一個子句拆成沒有條件的「不符合退款資格」。NLI 正確拒絕了這個過度普遍化的 statement，faithfulness 變成 2/3；錯誤源於拆句，不是原回答憑空否定所有退款。依實際引用原文做助理覆核時原回答有依據。沒有用這筆保留題再調 judge；trace 保留。Context precision 是排序的 average precision，尾端無用段落不一定降低分數；不能把 1.0 理解成所有候選皆相關。

## 交付與已驗證範圍

[本機部署](LOCAL_DEPLOYMENT.md) 提供一個網站 port 的 local-web jar、owned-process 啟停、全服務健康檢查、固定 artifact checksum、版本化備份／還原。真實隔離 lifecycle 驗證：活躍 Java store lock 拒絕還原；修改後版本 2 生成並核對；還原版本 1 後重新啟動，回答／canonical source 及 Qdrant 點均回到版本 1，舊向量已移除；借用的模型程序保持存活。[證據](verification/milestone4-local-lifecycle.json)。

後端 40/40、前端 typecheck／build、評估腳本 3/3、部署保護 tests 5/5、Ragas guard tests 3/3 通過。真實打包網頁可匿名載入登入頁，資料 API 401；reader／HR／不同部門帳號對 private 文件的清單與查詢符合權限，登出清除內容，390px 無溢出。guard／backend contract tests 含明確 stubs，不把它們當真實模型品質證據；40 題驗收與 Ragas 使用真實模型。

公開展示首次冷查詢與 judge 同時跑曾在 client 150 秒逾時，後端約 162 秒後完成；通道仍可載入頁面／status。這次失敗不是完整八題 regression 結果，沒有捏造通過數。外部最終驗證需在批次结束後單獨執行，頁面／提問／來源全部成功才可分享 URL。

未完成：獨立人員語意標註、GraphRAG／agent、PDF／Excel 清洗、每次線上問題的背景 judge、固定域名、負載與長時間 uptime。封存 zip 不含模型／使用者資料／帳密；最終四至五個專案的整合仍應在使用者本機處理。

## 品質候選與交付程式的區別

上述凍結的 40 題品質候選 jar SHA256 為 `92566636a127ea071efe28aeff9017f40b73e7b8ab4051d64a76dff664e882e9`。其後只新增背景提問傳輸與登入變更後的回應丟棄，保留模型、檢索閘門、生成提示與輸出驗證；交付 jar 的雜湊另記於 milestone4 manifest。新增測試涵蓋背景工作的身分、所有權、過期、文件版本、工作容量與錯誤資訊保護。沒有再次調整或重新執行凍結 holdout，也不把其數字宣稱為新版傳輸程式完整重測。
