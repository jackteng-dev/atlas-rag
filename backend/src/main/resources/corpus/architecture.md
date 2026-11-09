# Knowledge Assistant 架構筆記

## Backend 後端
The backend uses Java 21 source compatibility and Spring Boot 3.5.16. REST endpoints run on loopback port 18081. 後端使用 Java 與 Spring Boot，監聽本機連接埠 18081。

## Frontend 前端
The frontend uses Vue 3 and TypeScript. The Vite production preview runs on loopback port 4175 and proxies /api to the backend. 前端使用 Vue 與 TypeScript，預覽連接埠為 4175。

## Retrieval 檢索
Retrieval uses BM25 keyword scoring over Markdown body chunks. English words and Chinese character bigrams are indexed. No embeddings, vector database or semantic retrieval are active. 檢索使用關鍵字 BM25，沒有向量與 embedding。

## Answer 回答
The offline answer is a deterministic collection of verbatim excerpts, not LLM generated prose. Every citation resolves to a retrieved chunk and a document version. 離線回答是原文摘錄，來源可核對文件版本與段落。

## Scope 範圍
Authentication, RBAC, deployment and autonomous multi-step agents are future work. This local milestone has no login system. 認證、權限、部署與多步 agent 尚未實作。
