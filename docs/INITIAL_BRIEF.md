# AI Knowledge Assistant — initial implementation brief

The user has authorized starting this independent project for a Java Full Stack / Backend Engineer portfolio. Implement the first milestone, not only a planning document.

## Scope

Use a small bundled corpus of project-owned Markdown documents. Record document IDs, versions and stable chunk IDs; implement ingestion, chunking, index/retrieval and lifecycle updates/deletes. Show source excerpts next to answers. Resolve citations on the backend against retrieved chunks; never trust an arbitrary URL or citation ID emitted by a model. Empty or insufficient evidence must produce an explicit no-evidence response.

Create a Vue 3 + TypeScript interface for document selection/management as needed, questions, loading/error/empty states, answers and source panels. Keep backend services/components maintainable. Use a fixed, reproducible evaluation set with answerable, unanswerable, cross-section and misleading questions; report only measured results and their limitations. Include a document containing malicious instructions as a test fixture, treating it as data.

## Runtime and model access

Java / Spring Boot is the backend direction; Spring AI is a candidate rather than a mandatory unverified integration. Choose compatible versions using official docs and the installed toolchain. Check for existing authorized provider access without exposing credential values. If access is missing, continue building an offline retrieval/extractive baseline and provider-ready integration; make the active mode unmistakable in the UI and README. Do not describe keyword-only search as vector RAG or deterministic excerpts as generated LLM answers. Actual model/embedding integration must be separately verified before being marked complete. Do not ask the user to paste secrets into chat.

The first milestone excludes login, RBAC, autonomous agents, broad tool execution and deployment. Later depth can add hybrid retrieval, reranking, permission filtering and read-only tools after the evidence/citation baseline works.

## Directory and coexistence

Actual code root: D:\Project\ai-knowledge-assistant. Use this exact directory as shell workdir even if the chat's initial output directory is elsewhere. Existing portfolio is at 4173 and e-commerce at 4174 / 8080. Check port availability before choosing 4175 / 8081. Do not interrupt their processes or change their repositories.

## Deliverables

Runnable source, locked dependencies, small sample corpus, meaningful backend tests, fixed evaluation data/results, frontend production build, real screenshots, architecture/API documentation and a concise report of implemented and pending capabilities. Record whether answers and embeddings use a real model, which data source was used and what has actually been executed. Keep all claims reviewable.
