# Codex task T05 — conversational chat UI, multi-file upload, file type in lists

Worktree `atlas-rag-wt-frontend`, branch `codex/chat-ux`. Same rules as T01/T03: modify only `frontend/src/platform/**`; no package changes,
no git commit/push/merge, no network installs, no `v-html`. Read `docs/platform/API.md` (DocumentItem now has `filename`, `extension`, `sizeBytes`).

## 1. ChatPage — conversational layout (owner request: "like ChatGPT", original Atlas styling, no copied branding)
Current page renders every message as a bordered card with a status badge and timestamp; the owner wants a chat-app feel.
- Layout: left column = conversation list (collapsible; on <768px becomes a drawer opened by a button); main column = a centered thread
  (max-width ~820px) that scrolls, with the composer **pinned at the bottom** of the viewport.
- User messages: right-aligned rounded bubbles (subtle tinted background), no card border, no "完成" badge. Keep line breaks (`white-space: pre-wrap`).
- Assistant messages: left-aligned plain flowing text on the page background with a small "Atlas" avatar/label; no card box, no "完成" badge.
  Show a status badge ONLY for PENDING (animated "正在檢索與生成…" indicator), REFUSED (muted "找不到足夠證據" note) and FAILED (error + traceId).
- Citations: compact numbered chips under the answer. Chip label = `[n] ` + citation title truncated to ~18 chars with the file extension badge if known,
  full title + version + locator in a tooltip/`title`. Clicking opens the existing citation side panel (keep its behaviour: state badge, quote,
  "檢視來源原文" with 409/404 handling).
- Below each assistant answer, a quiet meta line: model label (e.g. "快速 · qwen3-4b-instruct-2507" from `message.model` mapped with
  `status.models` → id fast/quality), total elapsed seconds (sum of `meta.pipeline[].elapsedMs`), and a "處理流程" toggle that expands the
  pipeline stages list (name · status · ms) — collapsed by default.
- Timestamps: hide by default; show on hover/focus as a small muted text.
- Composer (bottom, rounded large container): auto-growing textarea (1–8 rows), Enter sends, Shift+Enter newline, IME-safe (ignore Enter while
  `isComposing`). Inside the composer's bottom row: the model selector as a compact pill/select showing "快速 · Qwen3 4B" / "高品質 · Qwen3 8B"
  (derive the display name from `status.models[].model`: map `qwen3-4b-instruct-2507` → "Qwen3 4B", `qwen3-8b` → "Qwen3 8B", otherwise show the raw
  model id; quality option only if available, with hint "首次使用需載入，約 30–60 秒"), the selected knowledge bases as small chips (read-only, from the
  conversation's kbIds → names), and a circular send button (arrow icon via inline SVG/text, accessible label "傳送問題"); disabled while pending.
- Auto-scroll to the newest message when a message is added or an answer completes, unless the user scrolled up (then show a "↓ 最新訊息" button).
- Empty conversation state: centered prompt "今天想查證什麼？" plus up to 3 example question buttons that fill the composer
  (generic, e.g. "這份文件的重點是什麼？", "有哪些規定或限制？", "某流程要怎麼申請？").
- No conversation selected: a centered start screen with KB checkboxes and a "開始對話" button (replaces the modal for the first conversation; keep
  the modal for "新增對話" from the list header).
- Keep polling every 2 s while an assistant message is PENDING (existing behaviour). Dark mode must look right (use existing tokens).

## 2. Multi-file upload (UploadForm and wherever it is used)
- `<input type="file" multiple>` plus a drag-and-drop zone ("拖曳檔案到這裡，或點擊選擇", keyboard accessible).
- Client-side check per file: extension in .md .txt .pdf .docx .xlsx and size ≤ 50 MB; rejected files are listed with the reason and not sent.
- Upload accepted files **sequentially** (one request per file, existing endpoint), showing a queue list: file name, extension badge, size,
  per-file progress bar, and result (成功 / 失敗 + server message + traceId). Allow removing a queued (not yet started) file and cancelling the
  remaining queue. Emit `uploaded` after each success so lists refresh.
- After the queue finishes, show a summary "已上傳 X 個，失敗 Y 個".

## 3. Show file type in lists
- `KnowledgeBaseDetailPage` documents table and `DocumentsPage`: add a "類型" column with an extension badge (MD / TXT / PDF / DOCX / XLSX, distinct
  muted colours) and show `filename` under the title in small muted text (so same-titled documents are distinguishable); add a size column (KB/MB).
  Mobile cards include the badge.
- `OverviewPage` recent documents (if any list exists) — same badge.

## Done when
`cd frontend && npm run build` passes with zero type errors. Report files changed and anything in the contract that blocked you.
