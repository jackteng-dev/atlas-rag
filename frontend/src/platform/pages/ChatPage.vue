<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiError } from '../api/client'
import type { Conversation, ConversationDetail, KnowledgeBase, Citation, Chunk, DocumentItem, Message } from '../api/types'
import { useSessionStore } from '../stores/session'
import { useStatusStore } from '../stores/status'
import { useTask, usePolling } from '../composables/useTask'
import { orgPath, date, locator } from '../composables/format'
import { fileExtension } from '../composables/files'
import ErrorBanner from '../components/ErrorBanner.vue'
import Spinner from '../components/Spinner.vue'
import StatusBadge from '../components/StatusBadge.vue'
import Modal from '../components/Modal.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import FileTypeBadge from '../components/FileTypeBadge.vue'
import ConversationList from '../components/ConversationList.vue'
const org = useSessionStore().currentOrgId; const status = useStatusStore()
const route = useRoute(); const router = useRouter(); const conversationId = String(route.params.conversationId ?? '')
const conversations = ref<Conversation[]>([]); const kbs = ref<KnowledgeBase[]>([]); const detail = ref<ConversationDetail | null>(null)
const documents = ref<DocumentItem[]>([])
const content = ref(''); const model = ref<'fast' | 'quality'>(status.status?.models.find(m => m.available)?.id ?? 'fast')
const modelOptions = computed(() => (status.status?.models ?? []).filter(m => m.id === 'fast' || m.available))
const modelAvailable = computed(() => !!status.status?.models.some(m => m.id === model.value && m.available))
const pending = computed(() => !!detail.value?.messages.some(m => m.role === 'ASSISTANT' && m.status === 'PENDING'))
const selectedKbs = computed(() => detail.value?.conversation.kbIds.map(id => kbs.value.find(k => k.id === id)?.name ?? id) ?? [])
const createOpen = ref(false); const deleteOpen = ref(false); const newTitle = ref(''); const kbIds = ref<string[]>([])
const collapsed = ref(false); const drawer = ref(false)
const { busy, error, run } = useTask(); const action = useTask(); const threadTask = useTask(); const sourceTask = useTask()
const locked = computed(() => action.busy.value || pending.value)
const citation = ref<Citation | null>(null); const source = ref<Chunk | null>(null); const sourceNotice = ref('')
const traces = ref<Record<string, string>>({}); const traceErrors = ref<Record<string, boolean>>({})
const thread = ref<HTMLElement>(); const textarea = ref<HTMLTextAreaElement>(); const followLatest = ref(true)
const composing = ref(false)
const examples = ['這份文件的重點是什麼？', '有哪些規定或限制？', '某流程要怎麼申請？']
const modelKind = (id: string) => id === 'fast' ? '快速' : '高品質'
const displayModel = (id: string) => ({ 'qwen3-4b-instruct-2507': 'Qwen3 4B', 'qwen3-8b': 'Qwen3 8B' }[id] ?? id)
function messageModel(message: Message) {
  if (!message.model) return '模型未提供'
  const match = status.status?.models.find(m => m.model === message.model || m.id === message.model)
  return match ? modelKind(match.id) + ' · ' + match.model : message.model
}
const elapsed = (message: Message) => message.meta.pipeline?.length ? (message.meta.pipeline.reduce((sum, stage) => sum + stage.elapsedMs, 0) / 1000).toFixed(1) + ' 秒' : null
const shortTitle = (title: string) => Array.from(title).length > 18 ? Array.from(title).slice(0,18).join('') + '…' : title
function citationExtension(item: Citation) {
  const doc = documents.value.find(d => d.id === item.documentId && d.latestVersion === item.versionNo)
  if (doc?.extension) return doc.extension
  const extension = fileExtension(item.title)
  return ['md','txt','pdf','docx','xlsx'].includes(extension) ? extension : ''
}
async function loadTraces() {
  const failed = detail.value?.messages.filter(m => m.status === 'FAILED' && m.jobId && !traces.value[m.jobId]) ?? []
  await Promise.all(failed.map(async message => {
    const id = message.jobId!
    try { traces.value[id] = (await api.job(org,id)).traceId; delete traceErrors.value[id] }
    catch { traceErrors.value[id] = true }
  }))
}
async function load() {
  await run(async () => {
    const [a,b,c] = await Promise.all([api.conversations(org),api.knowledgeBases(org),conversationId ? api.conversation(org,conversationId) : Promise.resolve(null)])
    conversations.value = a; kbs.value = b; detail.value = c
    // Metadata is optional: citation/source access remains authorized by its own endpoint.
    if (c) documents.value = await api.documents(org).catch(() => [])
    await loadTraces()
  })
}
async function refreshThread() {
  if (!conversationId) return
  await threadTask.run(async () => { detail.value = await api.conversation(org,conversationId); await loadTraces() })
}
function openCreate() { newTitle.value = ''; kbIds.value = []; action.error.value = null; drawer.value = false; createOpen.value = true }
async function create() {
  if (!kbIds.value.length || action.busy.value) return
  await action.run(async () => {
    const conversation = await api.createConversation(org,{ ...(newTitle.value.trim() ? { title:newTitle.value.trim() } : {}), kbIds: kbIds.value })
    createOpen.value = false; await router.push(orgPath(org,'chat/' + conversation.id))
  })
}
async function send() {
  if (!content.value.trim() || locked.value || !detail.value || !modelAvailable.value || composing.value) return
  await action.run(async () => {
    const result = await api.sendMessage(org,conversationId,{ content: content.value.trim(), model: model.value })
    detail.value!.messages.push(result.userMessage,result.assistantMessage)
    if (result.job.traceId) traces.value[result.job.id] = result.job.traceId
    content.value = ''; threadTask.error.value = null
  })
  await nextTick(); textarea.value?.focus()
}
function composerKeydown(e: KeyboardEvent) {
  if (e.key !== 'Enter' || e.shiftKey || e.isComposing || composing.value || e.keyCode === 229) return
  e.preventDefault(); void send()
}
function resizeComposer() {
  if (!textarea.value) return
  textarea.value.style.height = 'auto'
  textarea.value.style.height = Math.min(textarea.value.scrollHeight, 216) + 'px'
  textarea.value.style.overflowY = textarea.value.scrollHeight > 216 ? 'auto' : 'hidden'
  if (followLatest.value) scrollLatest()
}
function onScroll() {
  if (thread.value) followLatest.value = thread.value.scrollHeight - thread.value.scrollTop - thread.value.clientHeight < 72
}
function scrollLatest() { if (thread.value) thread.value.scrollTop = thread.value.scrollHeight; followLatest.value = true }
async function fillExample(question: string) { content.value = question; await nextTick(); textarea.value?.focus() }
watch(content, () => nextTick(resizeComposer))
watch(() => detail.value?.messages.map(m => [m.id,m.status,m.content,m.citations.length].join(':')).join('|'), async () => {
  const shouldFollow = followLatest.value
  await nextTick(); resizeComposer()
  if (shouldFollow) scrollLatest()
}, { flush:'post' })
async function remove() {
  await action.run(async () => { await api.deleteConversation(org,conversationId); await router.replace(orgPath(org,'chat')) })
}
function openCitation(item: Citation) { citation.value = { ...item }; source.value = null; sourceNotice.value = ''; sourceTask.error.value = null }
async function viewSource() {
  if (!citation.value) return
  const item = citation.value
  source.value = null; sourceNotice.value = ''
  await sourceTask.run(async () => {
    try { source.value = await api.chunk(org,item.documentId,item.versionNo,item.chunkId) }
    catch (e) {
      if (e instanceof ApiError && (e.status === 409 || e.status === 404 || e.status === 403)) {
        if (e.status === 409) {
          citation.value = { ...item, state:'SUPERSEDED' }
          sourceNotice.value = '此引用版本已被新版本取代（VERSION_SUPERSEDED）。請重新提問以取得目前版本的來源；這裡不會顯示新版本內文。'
        } else { sourceNotice.value = '來源已刪除、到期或您已無權讀取（' + e.status + '），無法檢視原文。'; citation.value = { ...item, state:'UNAVAILABLE', quote:'' } }
      }
      throw e
    }
  })
}
onMounted(load)
usePolling(refreshThread, () => pending.value && !threadTask.error.value && !action.busy.value, 2000)
</script>
<template>
  <div class="chat-shell">
    <header class="chat-toolbar">
      <div class="actions"><button class="chat-desktop" aria-controls="chat-conversations" :aria-expanded="!collapsed" @click="collapsed = !collapsed">{{ collapsed ? '展開對話' : '收合對話' }}</button><button class="chat-mobile" :aria-expanded="drawer" @click="drawer = true">☰ 對話</button><h1>知識對話</h1></div>
      <button :disabled="busy" @click="load">重新整理</button>
    </header>
    <ErrorBanner :error="error" />
    <div class="chat-workspace" :class="{ 'list-collapsed': collapsed }">
      <aside v-if="!collapsed" id="chat-conversations" class="chat-conversations"><ConversationList :conversations="conversations" :org="org" :busy="busy" :can-create="!!kbs.length" @create="openCreate" /></aside>
      <section class="thread-column" aria-label="知識對話">
        <Spinner v-if="busy && !detail" />
        <template v-if="detail">
          <header class="thread-header"><h2>{{ detail.conversation.title || '知識對話' }}</h2><button :disabled="locked" :title="pending ? '請等待回答完成' : ''" @click="action.error.value = null; deleteOpen = true">刪除對話</button></header>
          <ErrorBanner :error="threadTask.error.value"><button :disabled="threadTask.busy.value" @click="refreshThread">重新讀取對話</button></ErrorBanner>
          <div ref="thread" class="thread-scroll" tabindex="0" aria-label="對話訊息捲動區" @scroll="onScroll">
            <div class="thread-content" role="log" aria-label="對話訊息" aria-live="polite" :aria-busy="pending">
              <div v-if="!detail.messages.length" class="thread-empty"><span class="chat-atlas-mark" aria-hidden="true">A</span><h2>今天想查證什麼？</h2><div class="example-questions"><button v-for="question in examples" :key="question" @click="fillExample(question)">{{ question }}</button></div></div>
              <article v-for="message in detail.messages" :key="message.id" class="chat-message" :class="message.role === 'USER' ? 'chat-user' : 'chat-assistant'" tabindex="0" :aria-label="message.role === 'USER' ? '您的訊息' : 'Atlas 回答'">
                <div v-if="message.role === 'ASSISTANT'" class="assistant-label"><span class="chat-atlas-mark" aria-hidden="true">A</span><strong>Atlas</strong></div>
                <p v-if="message.content" class="chat-text">{{ message.content }}</p>
                <template v-if="message.role === 'ASSISTANT'">
                  <span v-if="message.status === 'PENDING'" class="message-indicator pending" role="status"><span class="spinner" aria-hidden="true"></span>正在檢索與生成…</span>
                  <span v-else-if="message.status === 'REFUSED'" class="message-indicator neutral">找不到足夠證據</span>
                  <div v-else-if="message.status === 'FAILED'" class="message-indicator negative" role="alert"><strong>回答失敗</strong><span>{{ message.meta.error ? message.meta.error.code + '：' + message.meta.error.message : '無法完成回答，請稍後再試。' }}</span><small v-if="message.jobId && traces[message.jobId]">traceId：{{ traces[message.jobId] }}</small><small v-else>{{ message.jobId && traceErrors[message.jobId] ? '無法取得 traceId，請重新讀取對話。' : '伺服器未提供 traceId。' }}</small></div>
                  <div v-if="message.citations.length" class="chat-citations"><button v-for="item in message.citations" :key="item.ordinal + ':' + item.chunkId" class="source-chip" :title="item.title + ' · v' + item.versionNo + ' · ' + locator(item.locator)" :aria-label="'引用 ' + item.ordinal + '：' + item.title" @click="openCitation(item)"><span>[{{ item.ordinal }}] {{ shortTitle(item.title) }}</span><FileTypeBadge v-if="citationExtension(item)" :extension="citationExtension(item)" /></button></div>
                  <p v-else-if="message.status === 'COMPLETE'" class="no-citations muted">此回答未附引用，請另行查證。</p>
                  <div class="answer-meta"><span>{{ messageModel(message) }}</span><span v-if="elapsed(message)">{{ elapsed(message) }}</span><details v-if="message.meta.pipeline?.length"><summary>處理流程</summary><ul><li v-for="(stage,index) in message.meta.pipeline" :key="index">{{ stage.name }} · {{ stage.status }} · {{ stage.elapsedMs }} ms</li></ul></details></div>
                </template>
                <time class="message-time" :datetime="message.createdAt">{{ date(message.createdAt) }}</time>
              </article>
            </div>
          </div>
          <div class="composer-dock">
            <button v-if="!followLatest" class="latest-button" @click="scrollLatest">↓ 最新訊息</button>
            <form class="chat-composer" @submit.prevent="send">
              <label class="sr-only" for="chat-question">向知識庫提問</label>
              <textarea id="chat-question" ref="textarea" v-model="content" rows="1" required :disabled="locked" placeholder="輸入您想查證的問題…" @input="resizeComposer" @keydown="composerKeydown" @compositionstart="composing = true" @compositionend="composing = false"></textarea>
              <div class="composer-controls"><label class="model-control"><span class="sr-only">回答模型</span><select v-model="model" aria-label="回答模型" :disabled="locked" :title="model === 'quality' ? '首次使用需載入，約 30–60 秒' : '選擇回答模型'"><option v-for="option in modelOptions" :key="option.id" :value="option.id" :disabled="!option.available">{{ modelKind(option.id) }} · {{ displayModel(option.model) }}</option><option v-if="!modelOptions.length" value="fast" disabled>模型暫不可用</option></select></label><div class="composer-kbs" aria-label="本次使用的知識庫"><span v-for="(name,index) in selectedKbs" :key="index" class="kb-chip" :title="name">{{ name }}</span></div><button class="primary send-button" :disabled="!content.trim() || locked || !modelAvailable" aria-label="傳送問題"><svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true"><path d="M12 19V5m-6 6 6-6 6 6" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" /></svg></button></div>
            </form>
            <p v-if="model === 'quality'" class="composer-hint">首次使用需載入，約 30–60 秒</p><p v-if="!modelAvailable" class="composer-hint">目前選用模型暫不可用，請稍後再試。</p>
            <ErrorBanner v-if="!createOpen && !deleteOpen" :error="action.error.value" />
          </div>
        </template>
        <div v-else-if="!busy && !error" class="chat-start"><span class="chat-atlas-mark" aria-hidden="true">A</span><h2>讓每個答案都有依據</h2><p class="muted">選擇本次使用的知識庫，開始查證。</p><form class="form-stack" @submit.prevent="create"><fieldset :disabled="action.busy.value"><legend>選擇知識庫（至少一個）</legend><label v-for="kb in kbs" :key="kb.id" class="check-label"><input v-model="kbIds" type="checkbox" :value="kb.id">{{ kb.name }}</label><p v-if="!kbs.length" class="muted">目前沒有可讀知識庫，請先建立或取得存取權限。</p></fieldset><ErrorBanner v-if="!createOpen" :error="action.error.value" /><button class="primary" :disabled="action.busy.value || !kbIds.length">開始對話</button></form></div>
      </section>
    </div>
  </div>
  <Modal v-if="drawer" title="我的對話" side @close="drawer = false"><ConversationList :conversations="conversations" :org="org" :busy="busy" :can-create="!!kbs.length" @create="openCreate" @selected="drawer = false" /></Modal>
  <Modal v-if="createOpen" title="新增對話" :busy="action.busy.value" @close="createOpen = false"><form class="form-stack" @submit.prevent="create"><label>對話標題（可留空）<input v-model="newTitle" :disabled="action.busy.value"></label><fieldset :disabled="action.busy.value"><legend>選擇知識庫（至少一個）</legend><label v-for="kb in kbs" :key="kb.id" class="check-label"><input v-model="kbIds" type="checkbox" :value="kb.id">{{ kb.name }}</label></fieldset><ErrorBanner :error="action.error.value" /><button class="primary" :disabled="action.busy.value || !kbIds.length">建立對話</button></form></Modal>
  <ConfirmDialog v-if="deleteOpen" title="刪除對話" message="確定刪除此對話及其訊息？" :busy="action.busy.value" :error="action.error.value" @close="deleteOpen = false" @confirm="remove" />
  <Modal v-if="citation" title="引用來源" side :busy="sourceTask.busy.value" @close="citation = null"><h3>[{{ citation.ordinal }}] {{ citation.title }}</h3><div class="actions"><StatusBadge :state="citation.state" /><span>v{{ citation.versionNo }}</span></div><p class="muted">{{ locator(citation.locator) }}</p><blockquote v-if="citation.quote">{{ citation.quote }}</blockquote><p v-if="citation.state === 'UNAVAILABLE'" class="notice">此來源目前無法存取，引用原文已隱藏。</p><p v-if="citation.state === 'SUPERSEDED' && !sourceNotice" class="notice">此引用來自舊版本，請查證版本狀態。</p><p v-if="sourceNotice" role="status" class="notice">{{ sourceNotice }}</p><button :disabled="sourceTask.busy.value || citation.state === 'UNAVAILABLE'" :title="citation.state === 'UNAVAILABLE' ? '來源目前無法存取' : ''" @click="viewSource">檢視來源原文</button><Spinner v-if="sourceTask.busy.value" /><ErrorBanner :error="sourceTask.error.value" /><section v-if="source"><h3>{{ source.heading || source.title }}</h3><p class="muted">v{{ source.versionNo }} · {{ locator(source.locator) }}</p><pre>{{ source.content }}</pre></section></Modal>
</template>
<style scoped>
.chat-shell { flex:1; display:flex; flex-direction:column; min-height:0; min-width:0 }
.chat-toolbar { display:flex; align-items:center; justify-content:space-between; gap:12px; padding:14px 24px; border-bottom:1px solid var(--border); flex-shrink:0 }
.chat-toolbar h1 { font-size:18px; margin:0 }
.chat-toolbar button, .thread-header button { font-size:12px; min-height:32px; padding:6px 10px }
.chat-workspace { display:grid; grid-template-columns:230px minmax(0,1fr); flex:1; min-height:0 }
.chat-workspace.list-collapsed { grid-template-columns:minmax(0,1fr) }
.chat-conversations { padding:24px 14px; background:var(--surface); border-right:1px solid var(--border); min-height:0; overflow:hidden }
.thread-column { min-width:0; min-height:0; display:flex; flex-direction:column; position:relative }
.thread-header { display:flex; align-items:center; justify-content:space-between; gap:16px; padding:14px 24px; flex-shrink:0 }
.thread-header h2 { font-size:14px; margin:0; color:var(--muted) }
.thread-scroll { flex:1; min-height:0; overflow-y:auto; overscroll-behavior:contain; scrollbar-gutter:stable }
.thread-content { max-width:820px; margin:0 auto; padding:20px 24px 36px; display:flex; flex-direction:column; gap:30px; min-height:100% }
.chat-message { position:relative; min-width:0; border:0; background:transparent; padding:0; width:100% }
.chat-text { white-space:pre-wrap; overflow-wrap:anywhere; line-height:1.9; margin:0 0 12px }
.chat-user { align-self:flex-end; max-width:85%; width:fit-content; text-align:right }
.chat-user .chat-text { background:var(--primary-soft); border-radius:22px 22px 5px 22px; padding:12px 18px; text-align:left; margin:0; line-height:1.7 }
.assistant-label { display:flex; align-items:center; gap:8px; font-size:12px; margin-bottom:12px }
.chat-atlas-mark { display:inline-grid; place-items:center; width:28px; height:28px; border:1px solid var(--border); border-radius:9px; font:italic 20px Georgia,serif; color:var(--primary); background:var(--surface-alt); flex-shrink:0 }
.message-time { display:block; font-size:10px; color:var(--muted); opacity:0; margin-top:4px }
.chat-message:hover .message-time, .chat-message:focus .message-time, .chat-message:focus-within .message-time { opacity:1 }
.message-indicator { display:inline-flex; align-items:center; gap:8px; padding:5px 10px; border-radius:12px; font-size:12px; margin:0 0 12px }
.message-indicator.negative { display:flex; flex-direction:column; align-items:flex-start; overflow-wrap:anywhere }
.message-indicator .spinner { width:13px; height:13px }
.chat-citations { display:flex; flex-wrap:wrap; gap:6px; margin:10px 0 }
.source-chip { display:inline-flex; max-width:100%; gap:6px; min-height:30px; padding:4px 8px; font-size:11px; font-weight:400; border-radius:8px; color:var(--muted); background:var(--surface) }
.source-chip span { overflow-wrap:anywhere; min-width:0 }
.answer-meta { display:flex; gap:6px 12px; align-items:baseline; flex-wrap:wrap; color:var(--muted); font-size:11px; margin-top:12px }
.answer-meta details[open] { flex-basis:100% }
.answer-meta details ul { margin:8px 0; padding-left:18px }
.no-citations { font-size:12px }
.composer-dock { flex-shrink:0; padding:12px 24px 16px; width:100%; max-width:820px; margin:0 auto; position:relative; background:var(--bg) }
.chat-composer { padding:8px 12px 12px; border:1px solid var(--border); border-radius:24px; background:var(--surface); box-shadow:var(--shadow) }
.chat-composer:focus-within { border-color:var(--primary) }
.chat-composer textarea { display:block; width:100%; min-height:48px; max-height:216px; height:48px; padding:12px 8px; line-height:24px; border:0; background:transparent; resize:none; overflow-y:hidden; border-radius:12px }
.chat-composer textarea:focus-visible { outline:0 }
.composer-controls { display:flex; align-items:center; gap:8px; padding-top:4px; flex-wrap:wrap }
.model-control { max-width:100%; flex-shrink:1 }
.model-control select { width:auto; max-width:100%; min-height:32px; padding:6px 25px 6px 10px; font-size:11px; border-radius:20px; background:var(--surface-alt) }
.composer-kbs { display:flex; flex-wrap:wrap; gap:4px; flex:1; min-width:0 }
.kb-chip { display:inline-block; max-width:150px; overflow:hidden; white-space:nowrap; text-overflow:ellipsis; padding:3px 8px; background:var(--surface-alt); color:var(--muted); border-radius:12px; font-size:10px }
.chat-composer .send-button { margin-left:auto; width:36px; height:36px; min-height:36px; flex-shrink:0; padding:0; border-radius:50% }
.composer-hint { color:var(--muted); font-size:11px; margin:6px 8px 0 }
.composer-dock .error-banner { margin-top:10px; max-height:120px; overflow:auto }
.latest-button { position:absolute; bottom:100%; left:50%; transform:translateX(-50%); font-size:12px; border-radius:24px; box-shadow:var(--shadow); white-space:nowrap }
.thread-empty { display:flex; flex:1; flex-direction:column; align-items:center; justify-content:center; gap:16px; padding:32px 0; text-align:center }
.thread-empty .chat-atlas-mark, .chat-start>.chat-atlas-mark { width:46px; height:46px; border-radius:14px; font-size:30px }
.thread-empty h2 { font-size:26px; margin:0 }
.example-questions { display:flex; flex-wrap:wrap; justify-content:center; gap:8px }
.example-questions button { font-size:12px; font-weight:400; border-radius:16px; padding:10px 14px }
.chat-start { margin:auto; width:100%; max-width:520px; padding:40px 24px; text-align:center; overflow-y:auto }
.chat-start h2 { margin:20px 0 0; font-size:24px }
.chat-start form { margin-top:28px; text-align:left }
.chat-start fieldset { max-height:35dvh; overflow-y:auto }
.chat-mobile { display:none }
@media(max-width:767px) {
  .chat-toolbar { padding:10px 16px }
  .chat-toolbar h1 { font-size:15px }
  .chat-toolbar .chat-desktop, .chat-conversations { display:none }
  .chat-toolbar .chat-mobile { display:inline-flex }
  .chat-workspace { grid-template-columns:minmax(0,1fr) }
  .thread-header { padding:10px 16px }
  .thread-content { padding:16px 16px 28px; gap:24px }
  .composer-dock { padding:8px 12px max(12px, env(safe-area-inset-bottom)) }
  .chat-user { max-width:90% }
  .composer-kbs { order:3; flex-basis:100% }
  .thread-empty h2 { font-size:24px }
}
</style>
