<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { api, askInBackground, setCredentials, type Answer, type Document, type DocumentSummary, type Source, type Status } from './api'

const documents = ref<DocumentSummary[]>([])
const status = ref<Status | null>(null)
const tab = ref<'ask' | 'library'>('ask')
const selected = ref<string[]>([])
const question = ref('')
const submittedQuestion = ref('')
const answer = ref<Answer | null>(null)
const activeSource = ref<Source | null>(null)
const sourceVerified = ref('')
const busy = ref(false)
const questionSeconds = ref(0)
const loading = ref(true)
const error = ref('')
const sourceBusy = ref(false)
const editing = ref<Document | null>(null)
const editorOpen = ref(false)
const emptyForm = () => ({ id: '', title: '', markdown: '', department: 'public', owner: status.value?.actor ?? 'public-demo', expiresAt: '', roles: '', tags: '' })
const form = ref(emptyForm())
const login = ref({ username: '', password: '' })
const needsLogin = computed(() => status.value?.authentication === 'BASIC_SERVER_ACCOUNTS' && status.value.actor === 'public-demo')
const deleteTarget = ref<string | null>(null)
const notice = ref('')
const actorRoles = computed(() => status.value?.roles ?? [])
const publicMode = computed(() => status.value?.authentication === 'PUBLIC_DEMO')
const canImport = computed(() => publicMode.value || actorRoles.value.some(role => ['ADMIN', 'EDITOR'].includes(role)))
function canManageDocument(doc: DocumentSummary) {
  return actorRoles.value.includes('ADMIN')
    || (actorRoles.value.includes('EDITOR') && doc.metadata.owner === status.value?.actor)
    || (publicMode.value && doc.metadata.department === 'public' && doc.metadata.allowedRoles.length === 0)
}
const editingReadOnly = computed(() => !!editing.value && !canManageDocument(editing.value))
const isRag = computed(() => status.value?.mode === 'LOCAL_RAG_GENERATION')
const modelLabel = computed(() => status.value?.rag?.llm?.includes('qwen3') ? 'Qwen3 4B' : 'Qwen2.5 1.5B')
const examples = computed(() => [
  { label: '架構與技術', question: isRag.value ? '你用什麼架構' : 'What backend and frontend ports are used?', hint: '架構文件 · 來源核對' },
  { label: '文件生命週期', question: 'How does document update handle expectedVersion?', hint: '版本衝突 · 舊段落移除' },
  { label: '證據不足', question: 'What is the employee salary and vacation budget?', hint: '無答案 · 明確拒答' },
  { label: '檢索方式', question: isRag.value ? '為什麼要同時使用關鍵字和向量檢索？' : 'Does retrieval use embeddings or a vector database?', hint: '檢索流程 · 檢查原文' },
])
const selectionLabel = computed(() => selected.value.length ? `${selected.value.length} 份文件` : '所有文件')
const sourceIndex = (id: string) => (answer.value?.sources.findIndex(s => s.chunkId === id) ?? -1) + 1
const modeLabel = computed(() => isRag.value ? '本地 LLM / 混合檢索' : status.value?.mode === 'OFFLINE_EXTRACTIVE' ? '離線檢索 / 原文摘錄' : '本機模型選取摘錄')
const stageLabels: Record<string, string> = { authorization_and_expiry: '權限與到期過濾', vector_index: '向量索引同步', bm25: '關鍵字搜尋', dense_retrieval: '向量搜尋', rrf_fusion: '合併候選', reranker: '模型重排', generation: '模型回答', citation_validation: '引用核對' }
const stageName = (name: string) => stageLabels[name] ?? name
const expired = (doc: DocumentSummary) => !!doc.metadata.expiresAt && Date.parse(doc.metadata.expiresAt) <= Date.now()
function clearAnswer() { answer.value = null; activeSource.value = null; sourceVerified.value = ''; submittedQuestion.value = '' }
async function refresh() {
  status.value = await api<Status>('/status')
  const docs = needsLogin.value ? [] : await api<DocumentSummary[]>('/documents')
  documents.value = docs
  selected.value = selected.value.filter(id => docs.some(d => d.id === id))
}
function clearWorkspace() {
  clearAnswer(); documents.value = []; selected.value = []; editorOpen.value = false
  editing.value = null; deleteTarget.value = null; notice.value = ''; form.value = emptyForm()
}
async function signIn() {
  clearWorkspace()
  setCredentials(login.value.username, login.value.password)
  login.value.password = ''
  await reload()
  if (needsLogin.value) { setCredentials('', ''); error.value = '帳號或密碼無效。' }
}
async function signOut() { setCredentials('', ''); login.value = { username: '', password: '' }; clearWorkspace(); await reload() }
async function reload() {
  error.value = ''; loading.value = true
  try { await refresh(); clearAnswer() } catch (e) { error.value = String(e instanceof Error ? e.message : e) }
  finally { loading.value = false }
}
function toggle(id: string) {
  selected.value = selected.value.includes(id) ? selected.value.filter(x => x !== id) : [...selected.value, id]
  clearAnswer()
}
async function ask(sample?: string) {
  if (busy.value || loading.value) return
  if (sample) question.value = sample
  if (!question.value.trim()) return
  busy.value = true; questionSeconds.value = 0; error.value = ''; clearAnswer(); submittedQuestion.value = question.value.trim()
  try {
    const request = { question: submittedQuestion.value, documentIds: selected.value }
    answer.value = isRag.value ? await askInBackground(request, seconds => { questionSeconds.value = seconds })
      : await api<Answer>('/questions', { method: 'POST', body: JSON.stringify(request) })
    activeSource.value = answer.value.sources[0] ?? null
  } catch (e) { error.value = e instanceof Error ? e.message : String(e) }
  finally { busy.value = false }
}
function viewSource(source: Source) { activeSource.value = source; sourceVerified.value = '' }
async function verifySource() {
  const source = activeSource.value
  if (!source || sourceBusy.value) return
  sourceBusy.value = true; sourceVerified.value = ''
  try { const current = await api<Source>(source.href.slice(4)); sourceVerified.value = current.excerpt === source.excerpt ? '已核對：目前文件版本與引用原文一致。' : '內容已變更，請重新提問。' }
  catch (e) { sourceVerified.value = e instanceof Error ? e.message : String(e) }
  finally { sourceBusy.value = false }
}
function newDocument() { if (!canImport.value) return; editing.value = null; form.value = emptyForm(); editorOpen.value = true; error.value = ''; notice.value = '' }
async function editDocument(id: string) {
  if (busy.value) return
  busy.value = true; error.value = ''
  try { editing.value = await api<Document>(`/documents/${id}`); const doc = editing.value; form.value = { ...emptyForm(), id, title: doc.title, markdown: doc.markdown, department: doc.metadata.department, owner: doc.metadata.owner, expiresAt: doc.metadata.expiresAt ?? '', roles: doc.metadata.allowedRoles.join(', '), tags: doc.metadata.tags.join(', ') }; editorOpen.value = true; notice.value = '' }
  catch (e) { error.value = e instanceof Error ? e.message : String(e) }
  finally { busy.value = false }
}
async function readFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  if (!file.name.toLowerCase().endsWith('.md') || file.size > 600000) { error.value = '請選擇小於 600 KB 的 UTF-8 .md 檔案。'; input.value = ''; return }
  form.value.markdown = await file.text()
  if (!editing.value) { form.value.title = file.name.replace(/\.md$/i, ''); form.value.id = file.name.toLowerCase().replace(/\.md$/, '').replace(/[^a-z0-9-]/g, '-').replace(/^-+|-+$/g, '').slice(0,64) || 'imported-document' }
  input.value = ''
}
async function saveDocument() {
  if (editingReadOnly.value || (!editing.value && !canImport.value)) return;
  busy.value = true; error.value = ''
  try {
    const metadata = { department: form.value.department, owner: form.value.owner, expiresAt: form.value.expiresAt ? new Date(form.value.expiresAt).toISOString() : null, allowedRoles: form.value.roles.split(',').map(s => s.trim()).filter(Boolean), tags: form.value.tags.split(',').map(s => s.trim()).filter(Boolean) }
    const doc = editing.value
      ? await api<Document>(`/documents/${editing.value.id}`, { method: 'PUT', body: JSON.stringify({ title: form.value.title, markdown: form.value.markdown, expectedVersion: editing.value.version, metadata }) })
      : await api<Document>('/documents', { method: 'POST', body: JSON.stringify({ id: form.value.id, title: form.value.title, markdown: form.value.markdown, metadata }) })
    editorOpen.value = false; clearAnswer(); await refresh(); notice.value = `${doc.title} 已儲存為 v${doc.version}，${doc.chunks.length} 個段落；${isRag.value ? '下次問答會同步向量索引。' : '已可檢索。'}`
  } catch (e) { error.value = e instanceof Error ? e.message : String(e) }
  finally { busy.value = false }
}
async function deleteDocument(doc: DocumentSummary) {
  if (!canManageDocument(doc)) return;
  busy.value = true; error.value = ''
  try { await api<void>(`/documents/${doc.id}?expectedVersion=${doc.version}`, { method: 'DELETE' }); clearAnswer(); await refresh(); notice.value = `${doc.title} 已刪除，原引用失效。`; deleteTarget.value = null }
  catch (e) { error.value = e instanceof Error ? e.message : String(e) }
  finally { busy.value = false }
}
onMounted(reload)
</script>

<template>
  <div class="workspace">
    <aside class="sidebar">
      <a class="brand" href="#" @click.prevent="tab = 'ask'"><span class="brand-mark">a<span>·</span></span><span>atlas<small>KNOWLEDGE ASSISTANT</small></span></a>
      <div class="workspace-label"><span class="dot"></span> LOCAL WORKSPACE <span class="tiny">01</span></div>
      <nav aria-label="主要導覽">
        <button :class="{ current: tab === 'ask' }" @click="tab = 'ask'"><span>◈</span> 知識問答 <small>ASK</small></button>
        <button :class="{ current: tab === 'library' }" @click="tab = 'library'"><span>▤</span> 文件資料庫 <small>{{ documents.length }}</small></button>
      </nav>
      <div class="sidebar-section"><span>檢索範圍</span><button :disabled="busy || loading" @click="selected = []; clearAnswer()">全部</button></div>
      <div class="document-list">
        <label v-for="doc in documents" :key="doc.id" class="document-choice" :class="{ selected: selected.includes(doc.id) }">
          <input type="checkbox" :checked="selected.includes(doc.id)" :disabled="busy || loading" @change="toggle(doc.id)">
          <span class="file-icon">MD</span><span class="document-name">{{ doc.title }}<small>{{ doc.chunkCount }} chunks <span>· v{{ doc.version }}</span></small></span>
        </label>
        <p v-if="!loading && !documents.length" class="sidebar-empty">尚無文件。請至資料庫匯入 Markdown。</p>
      </div>
      <div class="sidebar-bottom"><div><span class="dot" :class="{ offline: !status || !!error }"></span>{{ status ? '本機索引可用' : '等待後端' }}<button :disabled="loading || busy" @click="reload" aria-label="重新載入">↻</button></div><small>Java / Spring Boot + Vue<br>{{ isRag ? 'Hybrid RAG · Local models' : 'Offline baseline · Evidence first' }}</small></div>
    </aside>

    <main>
      <header class="topbar"><div>WORKSPACE <span>/</span> {{ tab === 'ask' ? '知識問答' : '文件資料庫' }}</div><span class="mode-badge"><span class="dot"></span>{{ modeLabel }}</span></header>
      <div class="page">
        <div class="page-heading"><div><p class="eyebrow">{{ tab === 'ask' ? 'READ. RETRIEVE. VERIFY.' : 'VERSIONED KNOWLEDGE' }}</p><h1>{{ tab === 'ask' ? '每個回答，都有跡可循。' : '讓知識保持最新。' }}</h1><p class="subtitle">{{ tab === 'ask' ? '從你的 Markdown 文件尋找證據，逐段核對來源。' : '匯入、更新與刪除文件，同步替換目前可檢索的段落。' }}</p></div><span class="milestone">{{ isRag ? 'RAG' : 'M01' }}<span>LOCAL<br>{{ isRag ? 'MODELS' : 'BASELINE' }}</span></span></div>
        <div v-if="status?.authentication === 'BASIC_SERVER_ACCOUNTS' && !needsLogin" class="identity">已登入 {{ status.actor }}<span class="actor-scope">角色：{{ actorRoles.join('、') || '無角色' }} · 部門：{{ status.departments?.join('、') || '無部門' }}</span><button @click="signOut">登出／切換帳號</button></div>
        <form v-if="needsLogin" class="login-panel" @submit.prevent="signIn"><h2>登入文件知識庫</h2><p class="login-help">使用已配置的帳號登入。切換帳號後，可讀文件與操作權限會依伺服器設定更新。</p><label>帳號<input v-model="login.username" autocomplete="username" required></label><label>密碼<input v-model="login.password" type="password" autocomplete="current-password" required></label><button class="primary" type="submit">登入</button></form>
        <div class="stats"><div><span>DOCUMENTS</span><strong>{{ status?.documentCount ?? '—' }}<small>份文件</small></strong></div><div><span>INDEXED CHUNKS</span><strong>{{ status?.chunkCount ?? '—' }}<small>個段落</small></strong></div><div><span>RETRIEVAL</span><strong class="text-stat">{{ isRag ? 'Hybrid' : 'BM25' }}<small>{{ isRag ? '關鍵字＋向量' : '關鍵字檢索' }}</small></strong></div><div><span>INDEX REVISION</span><strong>{{ status?.indexRevision ?? '—' }}<small>目前快照</small></strong></div></div>
        <div v-if="error" class="alert error" role="alert"><strong>操作未完成</strong><span>{{ error }}</span><button @click="error = ''" aria-label="關閉錯誤">×</button></div>
        <div v-if="notice" class="alert success" role="status">{{ notice }}</div>
        <div v-if="loading" class="loading" role="status"><span class="spinner"></span>正在讀取文件與索引狀態…</div>

        <template v-if="tab === 'ask' && !loading && !needsLogin">
          <div class="ask-layout">
            <section class="conversation">
              <form class="question-box" @submit.prevent="ask()"><label for="question"><span>01</span> 向文件提問</label><textarea id="question" v-model="question" :disabled="busy" maxlength="1000" placeholder="例如：文件更新後，舊版本的引用會發生什麼事？" @keydown.ctrl.enter.prevent="ask()"></textarea><div class="question-footer"><span>▤ {{ selectionLabel }} <span class="keyboard">Ctrl + Enter</span></span><button class="primary" type="submit" :disabled="busy || !question.trim() || !documents.length">{{ busy ? '檢索中…' : '尋找證據' }} <span>↗</span></button></div></form>
              <div v-if="busy" class="loading answer-loading" role="status"><span class="spinner"></span>{{ isRag ? '本地模型正在檢索與生成' : '檢索目前文件版本並核對引用' }}…<span v-if="isRag"> {{ questionSeconds }} 秒</span></div>
              <section v-else-if="answer" class="answer-panel" aria-label="回答結果">
                <div class="answer-header"><span class="eyebrow">02 / EVIDENCE RESPONSE</span><span :class="['result-badge', { insufficient: answer.status === 'INSUFFICIENT_EVIDENCE' }]">{{ answer.status === 'INSUFFICIENT_EVIDENCE' ? '證據不足' : answer.generated.length ? '模型回答 · 附來源' : '找到原文摘錄' }}</span></div>
                <h2>{{ submittedQuestion }}</h2><p class="answer-message">{{ answer.message }}</p>
                <div v-for="(statement, index) in answer.generated" :key="index" class="generated-answer"><p>{{ statement.text }}</p><button v-for="id in statement.chunkIds" :key="id" class="citation" @click="viewSource(answer.sources.find(s => s.chunkId === id)!)">{{ sourceIndex(id) }}</button></div>
                <p v-if="answer.generated.length" class="evidence-caption">以下為引用的來源原文</p>
                <div v-for="(segment, index) in answer.segments" :key="`${segment.chunkId}-${index}`" class="excerpt"><button class="citation" @click="viewSource(answer.sources.find(s => s.chunkId === segment.chunkId)!)">{{ sourceIndex(segment.chunkId) }}</button><blockquote>{{ segment.quote }}</blockquote></div>
                <div v-if="!answer.segments.length && !answer.generated.length" class="no-evidence"><span>∅</span><p>沒有足夠證據支持這個問題。<small>可嘗試不同關鍵字，或在文件資料庫匯入相關內容。</small></p></div>
                <div class="answer-metrics"><span>詞彙涵蓋率 <b>{{ Math.round(answer.coverage * 100) }}%</b></span><span>後端 <b>{{ answer.elapsedMs }} ms</b></span><span>快照 <b>r{{ answer.indexRevision }}</b></span></div>
                <details v-if="isRag" class="pipeline-detail"><summary>查看檢索流程與模型用量</summary><ol><li v-for="stage in answer.pipeline" :key="stage.name"><span>{{ stageName(stage.name) }}</span><small>{{ stage.status }} · {{ stage.outputCount }} 筆 · {{ stage.elapsedMs }} ms</small></li></ol><p>{{ modelLabel }} · 輸入 {{ answer.modelInputTokens ?? '—' }} / 輸出 {{ answer.modelOutputTokens ?? '—' }} tokens · 付費 API $0</p><p>詞彙涵蓋率與重排分數均不是回答信心。忠實度、答案相關性尚須人工評估。</p></details>
                <p v-if="answer.missingTerms.length" class="missing-terms">未涵蓋詞：{{ answer.missingTerms.join(' · ') }}</p><p class="request-id">TRACE {{ answer.requestId }}</p>
              </section>
              <div v-else class="starter"><div class="section-title"><h2>從一個問題開始</h2><span>TRY A QUESTION</span></div><div class="example-grid"><button v-for="sample in examples" :key="sample.label" :disabled="busy || !documents.length" @click="ask(sample.question)"><span>{{ sample.label }} <b>↗</b></span><p>{{ sample.question }}</p><small>{{ sample.hint }}</small></button></div><p class="corpus-note">範例資料為此專案自有的架構、文件政策與操作筆記。{{ isRag ? '中文自然問句由向量檢索與重排處理；仍可能找錯來源或生成錯誤內容，請核對引用。' : '中英關鍵字可檢索；同義改寫可能找不到來源。' }}</p></div>
            </section>

            <aside class="sources-panel" aria-label="來源證據"><div class="section-title"><h2>來源證據 <small>{{ answer?.sources.length ?? 0 }}</small></h2><span>VERIFY</span></div>
              <div v-if="!answer?.sources.length" class="sources-empty"><span>▤</span><h3>{{ answer ? '尚無相關段落' : '證據會出現在這裡' }}</h3><p>提問後可檢查文件版本、<br>chunk ID 與原文位置。</p></div>
              <template v-else><p class="source-intro">{{ answer.status === 'INSUFFICIENT_EVIDENCE' ? '候選段落 · 尚不足以構成答案' : '點選來源，核對檢索到的原文' }}</p><div class="source-list"><button v-for="(source, index) in answer.sources" :key="source.chunkId" :class="{ active: activeSource?.chunkId === source.chunkId }" @click="viewSource(source)"><span class="source-number">{{ index + 1 }}</span><span>{{ source.heading }}<small>{{ source.documentId }} · v{{ source.version }} · L{{ source.lineStart }}–{{ source.lineEnd }}</small></span><b>↗</b></button></div><section v-if="activeSource" class="source-detail"><div class="source-meta"><span>RAW EXCERPT</span><span>{{ isRag ? '重排 logit' : 'BM25' }} {{ activeSource.score.toFixed(2) }}</span></div><h3>{{ activeSource.title }}</h3><pre>{{ activeSource.excerpt }}</pre><dl><dt>CHUNK ID</dt><dd>{{ activeSource.chunkId }}</dd><dt>PROVENANCE</dt><dd>v{{ activeSource.version }} · lines {{ activeSource.lineStart }}–{{ activeSource.lineEnd }}</dd></dl><button class="verify-button" :disabled="sourceBusy" @click="verifySource">{{ sourceBusy ? '核對中…' : '核對目前來源版本' }} ↗</button><p v-if="sourceVerified" class="verification" role="status">{{ sourceVerified }}</p></section></template>
              <div class="honesty-note"><span>ⓘ</span><p>{{ isRag ? '使用本地 BGE-M3、Qdrant、BGE 重排與生成模型。引用原文可核對，語意正確性仍需檢查。' : '目前沒有 embedding 或向量檢索；回答為原文摘錄。' }}</p></div>
            </aside>
          </div>
        </template>

        <section v-if="tab === 'library' && !loading && !needsLogin" class="library"><div class="section-title"><div><h2>Markdown 文件</h2><p>目前版本索引 · 本機 JSON 持久化</p></div><button v-if="canImport" class="primary" :disabled="busy" @click="newDocument">＋ 匯入文件</button></div><div v-if="!documents.length" class="empty-library">資料庫是空的。匯入一份 Markdown 開始。</div><article v-for="doc in documents" :key="doc.id" class="document-card"><span class="large-file">MD</span><div class="doc-info"><h3>{{ doc.title }}</h3><p>{{ doc.id }} <span>·</span> {{ doc.chunkCount }} chunks <span>·</span> v{{ doc.version }}</p><p class="document-policy">{{ doc.metadata.department }} · {{ doc.metadata.owner }} · {{ doc.metadata.allowedRoles.length ? doc.metadata.allowedRoles.join(', ') : '公開' }}<span v-if="expired(doc)" class="expired-label">已到期，排除於問答</span><span v-else-if="doc.metadata.expiresAt">到期 {{ new Date(doc.metadata.expiresAt).toLocaleString() }}</span></p><code>SHA-256 {{ doc.contentHash.slice(0,16) }}…</code></div><div class="doc-actions"><button :disabled="busy" @click="editDocument(doc.id)">{{ canManageDocument(doc) ? '檢視 / 更新' : '檢視原文' }}</button><button v-if="canManageDocument(doc) && deleteTarget !== doc.id" class="danger" :disabled="busy" @click="deleteTarget = doc.id">刪除</button><template v-else-if="canManageDocument(doc)"><button class="danger" :disabled="busy" @click="deleteDocument(doc)">確認刪除</button><button :disabled="busy" @click="deleteTarget = null">取消</button></template></div></article><div class="library-note">更新需符合目前 expectedVersion；版本衝突會回傳 409。刪除後不保留舊文件內容，舊引用會失效。</div></section>
        <footer class="page-footer"><span>ATLAS / KNOWLEDGE ASSISTANT</span><span>可追查來源 · 可觀察檢索 · 可重跑評估</span></footer>
      </div>
    </main>
  </div>

  <div v-if="editorOpen" class="modal-backdrop"><section class="editor" role="dialog" aria-modal="true" aria-labelledby="editor-title"><header><div><p class="eyebrow">DOCUMENT INGESTION</p><h2 id="editor-title">{{ editing ? `${editingReadOnly ? '檢視文件' : '更新文件'} · v${editing.version}` : '匯入 Markdown' }}</h2></div><button :disabled="busy" aria-label="關閉編輯器" @click="editorOpen = false">×</button></header><form @submit.prevent="saveDocument"><div v-if="error" class="alert error" role="alert">{{ error }}</div><div class="editor-fields"><label>文件 ID<input v-model="form.id" :disabled="!!editing || busy || editingReadOnly" required pattern="[a-z0-9][a-z0-9-]{0,63}" maxlength="64" placeholder="例如：api-guide"></label><label>文件標題<input v-model="form.title" :disabled="busy || editingReadOnly" required maxlength="120" placeholder="例如：API 設計指南"></label></div><details class="metadata-editor"><summary>文件歸屬、權限與到期日</summary><div class="editor-fields"><label>部門<input v-model="form.department" :disabled="busy || editingReadOnly" maxlength="64" required></label><label>擁有者<input v-model="form.owner" :disabled="busy || editingReadOnly" maxlength="64" required></label><label>到期時間（ISO 8601，可留空）<input v-model="form.expiresAt" :disabled="busy || editingReadOnly" placeholder="2026-12-31T00:00:00Z"></label><label>允許角色（逗號分隔，留空為公開）<input v-model="form.roles" :disabled="busy || editingReadOnly" placeholder="HR, ENGINEER"></label><label>標籤（逗號分隔）<input v-model="form.tags" :disabled="busy || editingReadOnly" placeholder="architecture, rag"></label></div><p>公開展示可管理公開文件；保護文件的角色與部門由伺服器登入帳號決定。</p></details><div v-if="!editingReadOnly" class="upload-row"><label class="upload">選擇 .md 檔案<input type="file" accept=".md,text/markdown" :disabled="busy" @change="readFile"></label><span>UTF-8 · 最多 200,000 字元</span></div><label class="markdown-label">Markdown 內容<textarea v-model="form.markdown" :disabled="busy" :readonly="editingReadOnly" required maxlength="200000" placeholder="# 文件標題&#10;&#10;## 章節&#10;在這裡輸入可檢索的內文。"></textarea></label><footer><span>{{ form.markdown.length.toLocaleString() }} 字元 · 切分上限 {{ status?.chunkMaxChars ?? '—' }} UTF-16 units</span><button v-if="!editingReadOnly" class="primary" :disabled="busy">{{ busy ? '儲存中…' : editing ? '儲存新版本' : '匯入並建立索引' }} ↗</button></footer></form></section></div>
</template>
