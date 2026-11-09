# 離線檢索品質實驗 — 第二里程碑

2026-10-05（Asia/Taipei）；量測完成時間 `2026-10-04T17:31:11.475Z`。本次完成可重現的參數比較，沒有找到同時解決同義詞漏找與錯誤前提的詞彙設定。保留 production 預設 **800 / 5 / 0.60**。

歷史 [results.json](../eval/results.json) 原檔不變，第一里程碑報告完整保留於 [EVALUATION_BASELINE.md](EVALUATION_BASELINE.md)。新的 [原始比較結果](../eval/retrieval-comparison.json) 包含每題來源、摘錄、實際設定、request ID、三輪 timing、雜湊與環境；[完整 27 組表格／逐題差異](RETRIEVAL_COMPARISON.md) 可用腳本重建。

## 條件與重現方法

- 原始四份專案自有 Markdown、文件 version=1、同一組 16 題，沒有修改題目、標籤、內容或已知失敗。題集 SHA-256：`72ca3adfba6fbac3257a717fd41bdde183383909f1959bfbd1a8649d92f6d89b`。
- [矩陣](../eval/experiment.json)：chunk 上限 800／400／160 UTF-16 units × topK 5／3／1 × coverage threshold 0.60／0.40／0.80，共 27 組。BM25 k1=1.2、b=0.75、lexical-v1 tokenizer 固定。
- 每組重新啟動 offline JVM、全新暫存 store、OS 指派的 loopback port。驗證 corpus 原文雜湊／version、status 設定，每次回應再確認實際設定與 indexRevision。
- 每組完整暖機 16 題一輪，排除暖機；再循序跑三轮，合計 **1296 個量測 query HTTP calls**、432 個暖機 query calls。每組延遲分布 48 samples。三輪來源、狀態、coverage、摘錄與引用結果均一致；品質表只計一輪 16 題，沒有把重複題目當成更多獨立樣本。
- HTTP 計時只包住問答 fetch／JSON 解析，不含啟動、暖機、canonical citation lookups。另保留後端 elapsedMs。引用核對次數多寡隨摘錄数量改變，不代表品質高低。
- Debian 13 x64、Oracle 完整 JDK 21.0.12.1、Node 24.19.0；jar／matrix／dataset／歷史結果 SHA-256 與 Git 基底 SHA 收錄於原始 artifact。新結果需保存到新路徑，脚本拒絕覆蓋既存輸出。

```sh
mvn -f backend/pom.xml verify -B -ntp
node --test scripts/evaluation.test.mjs
node scripts/compare-retrieval.mjs
node scripts/report-comparison.mjs work/evaluation/comparison-<timestamp>.json work/evaluation/comparison-<timestamp>.md
```

## 指標定義

「狀態符合」是與題集 `expectedStatus` 相符；「候選來源召回」是在 `sources` 找到標註的 document／heading，包含被拒答時仍顯示的候選；「摘錄來源召回」只計 `segments` 引用的來源 section。每輪共 14 個預期來源、12 題有來源的問題、4 題無答案問題。

「正確拒答」是四題預期無答案問題中得到 INSUFFICIENT_EVIDENCE 的數量；「錯誤拒答」是十二題可回答問題中被拒答的數量。「引用有效」要求 quote 為來源 substring，canonical lookup 的文件／chunk／heading／行號／version／原文都一致。**這不是答案準確率或問題語意支持率。**

## 代表性比較

設定以 chunk / topK / gate 表示。完整結果見 [比較表](RETRIEVAL_COMPARISON.md)。

| 設定 | chunks | 狀態 | 候選召回 | 摘錄召回 | 正確拒答 | 錯誤拒答 | 引用有效 | HTTP median / p95 ms |
|---|---|---|---|---|---|---|---|---|
| 800 / 5 / .60 baseline | 16 | 14/16 | 13/14 | 13/14 | 3/4 | 1 | 49/49 | 5.038 / 8.903 |
| 800 / 3 / .60 | 16 | 14/16 | 13/14 | 13/14 | 3/4 | 1 | 31/31 | 4.652 / 9.197 |
| 800 / 1 / .60 | 16 | 13/16 | 10/14 | 9/14 | 3/4 | 2 | 11/11 | 4.664 / 8.324 |
| 800 / 5 / .40 | 16 | 15/16 | 13/14 | 13/14 | 3/4 | 0 | 51/51 | 5.185 / 18.258 |
| 800 / 5 / .80 | 16 | 8/16 | 13/14 | 4/14 | 4/4 | 8 | 20/20 | 5.603 / 18.943 |
| 400 / 5 / .60 | 16 | 14/16 | 13/14 | 13/14 | 3/4 | 1 | 49/49 | 4.333 / 8.803 |
| 160 / 5 / .60 | 30 | 14/16 | 13/14 | 13/14 | 3/4 | 1 | 51/51 | 3.981 / 7.773 |
| 160 / 3 / .60 | 30 | 13/16 | 12/14 | 11/14 | 3/4 | 2 | 30/30 | 4.175 / 6.354 |

三輪 baseline 的邏輯結果與歷史 14/16、13/14、49/49 相同；歷史 localhost 延遲屬於另一個 Windows 單輪條件，不能用來聲稱本次效能改善。27 組量測三輪合計 **1989/1989** 次引用核對成功，這是重複驗證次數，不是 1989 個獨立正確答案。

## 改善與退步案例

**降低門檻：Q13 狀態改善，來源仍失敗。** `800/5/.40` 讓 Q13 從拒答變成 EXTRACTS_FOUND，狀態由 14/16 到 15/16；但它仍只找到 Delete／Persistence，沒有預期的 Update section，來源仍是 0/1。這只是接受較少詞彙證據，沒有修復同義詞檢索；Q14 仍錯誤接受。不能把 15/16 稱為檢索品質或答案準確率提升。

**提高門檻：Q14 拒答改善，可回答題目退步。** `800/5/.80` 拒絕 coverage=.60 的 Q14，正確拒答由 3/4 到 4/4；但額外拒絕 Q01、Q02、Q04、Q05、Q10、Q11、Q16，錯誤拒答由 1 到 8、狀態降到 8/16。候選召回仍為 13/14，摘錄召回卻降至 4/14，顯示只看候選召回會遮住門檻造成的損失。

**減少 topK：較少摘錄可保留此小題集的來源，也可能漏跨段落。** `800/3/.60` 維持 14/16 與 13/14，摘錄由 49 段減為 31 段；這是可觀察的輸出量取捨，不代表摘錄都相關或 model token／成本下降。topK=1 時 Q04 少一個 port section，Q05 只取 Delete、漏 Update，coverage 由 2/3 降為 1/2，進而拒答；來源召回下降為 10/14。

**縮小 chunk：400 是無效的切分變動，160 有實際退步。** corpus 的段落均在 400 以下，400 與 800 的 chunk IDs／原文相同，沒有切分品質差異可宣稱。160 則由 16 到 30 chunks，改變 BM25 ranking 與每個 topK 可涵蓋的詞彙。在 `160/3/.60`，Q05 前三個來源沒有 Update；Q15 雖找到 Persistence section，coverage 仍從 baseline 1.0 降至 .50 而拒答。即使 Q05 狀態不變，其來源漏找仍在報告可見。

**延遲沒有可泛化的勝者。** 三輪資料足以檢查基本穩定性，但配置順序固定、JIT／GC／主機背景活動與不同摘錄數量仍會影響 loopback timing。median 與 p95 如上；不據此宣稱效能提升。瀏覽器測試在批次完成後執行；開始時另有展示 backend／preview 啟動，屬於環境噪音限制。

## 回歸與限制

新增回歸測試固定歷史狀態／來源／引用數，保留 Q13／Q14；涵蓋严格門檻的 false-premise／false-refusal 取捨、寬鬆門檻未找回來源、topK 跨段落損失、實際設定覆寫、Unicode 切分與不相容 store 保留。腳本測試核對指標分離、延遲統計、設定漂移／非離線／corpus version 不符拒絕及偽造來源不能算有效引用。實測命令與結果見 [VERIFICATION](VERIFICATION.md)。

小題集與 corpus 共同編寫，沒有獨立 holdout 或人工逐句 entailment 評分。section-level 召回不能驗證被截短的 chunk 含有完整答案；固定 corpus 中 800／5／.60 的操作敘述描述歷史 baseline，不會隨實驗改寫。沒有真實 LLM、embedding、token、模型成本、負載或大 corpus 驗證。

下一步先擴充独立題集與更細的相關段落／證據支持標註，再評估語意檢索或其他方法；真實模型與 embedding 需另行確認可用服務與授權。当前成果提供公平重跑與檢視取捨的流程，保留預設與失敗，不以參數調整掩蓋語意限制。
