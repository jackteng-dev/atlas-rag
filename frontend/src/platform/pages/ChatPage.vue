<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { api, ApiError } from '../api/client'
import type { Conversation, ConversationDetail, KnowledgeBase, Citation, Chunk } from '../api/types'
import { useSessionStore } from '../stores/session'
import { useStatusStore } from '../stores/status'
import { useTask, usePolling } from '../composables/useTask'
import { orgPath, date, locator } from '../composables/format'
import PageHeader from '../components/PageHeader.vue'
import EmptyState from '../components/EmptyState.vue'
import ErrorBanner from '../components/ErrorBanner.vue'
import Spinner from '../components/Spinner.vue'
import StatusBadge from '../components/StatusBadge.vue'
import Modal from '../components/Modal.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
const org = useSessionStore().currentOrgId; const status = useStatusStore()
const route = useRoute(); const router = useRouter(); const conversationId = String(route.params.conversationId ?? '')
const conversations = ref<Conversation[]>([]); const kbs = ref<KnowledgeBase[]>([]); const detail = ref<ConversationDetail | null>(null)
const content = ref(''); const model = ref<'fast' | 'quality'>(status.status?.models.find(m => m.available)?.id ?? 'fast')
const quality = computed(() => !!status.status?.models.some(m => m.id === 'quality' && m.available))
const modelAvailable = computed(() => !!status.status?.models.some(m => m.id === model.value && m.available))
const pending = computed(() => !!detail.value?.messages.some(m => m.role === 'ASSISTANT' && m.status === 'PENDING'))
const createOpen = ref(false); const deleteOpen = ref(false); const newTitle = ref(''); const kbIds = ref<string[]>([])
const { busy, error, run } = useTask(); const action = useTask(); const threadTask = useTask(); const sourceTask = useTask()
const citation = ref<Citation | null>(null); const source = ref<Chunk | null>(null); const sourceNotice = ref('')
async function load() {
  await run(async () => {
    const [a,b,c] = await Promise.all([api.conversations(org),api.knowledgeBases(org),conversationId ? api.conversation(org,conversationId) : Promise.resolve(null)])
    conversations.value = a; kbs.value = b; detail.value = c
  })
}
async function refreshThread() {
  if (!conversationId) return
  await threadTask.run(async () => { detail.value = await api.conversation(org,conversationId) })
}
function openCreate() { newTitle.value = ''; kbIds.value = []; action.error.value = null; createOpen.value = true }
async function create() {
  await action.run(async () => {
    const conversation = await api.createConversation(org,{ ...(newTitle.value.trim() ? { title:newTitle.value.trim() } : {}), kbIds: kbIds.value })
    createOpen.value = false; await router.push(orgPath(org,'chat/' + conversation.id))
  })
}
async function send() {
  if (!content.value.trim() || pending.value || !detail.value || !modelAvailable.value) return
  await action.run(async () => {
    const result = await api.sendMessage(org,conversationId,{ content: content.value.trim(), model: model.value })
    detail.value!.messages.push(result.userMessage,result.assistantMessage); content.value = ''
    threadTask.error.value = null
  })
}
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
        if (e.status === 409) sourceNotice.value = '此引用版本已被新版本取代（VERSION_SUPERSEDED）。請重新提問以取得目前版本的來源；這裡不會顯示新版本內文。'
        else { sourceNotice.value = '來源已刪除、到期或您已無權讀取（' + e.status + '），無法檢視原文。'; citation.value = { ...item, state: 'UNAVAILABLE', quote: '' } }
      }
      throw e
    }
  })
}
onMounted(load)
usePolling(refreshThread, () => pending.value && !threadTask.error.value && !action.busy.value, 2000)
</script>
<template><PageHeader title="知識對話" description="從指定知識庫取得回答，並逐一查證引用來源。"><button :disabled="busy" @click="load">重新整理</button><button class="primary" :disabled="busy || !kbs.length" :title="!kbs.length ? '需先取得可讀知識庫' : ''" @click="openCreate">新增對話</button></PageHeader><ErrorBanner :error="error" /><Spinner v-if="busy" /><div class="chat-grid"><aside class="panel"><h2>我的對話</h2><nav v-if="conversations.length" class="conversation-list" aria-label="對話清單"><RouterLink v-for="item in conversations" :key="item.id" :to="orgPath(org,'chat/' + item.id)">{{ item.title || '未命名對話' }}<small>{{ date(item.updatedAt) }}</small></RouterLink></nav><p v-else-if="!busy" class="muted">尚無對話。</p></aside><section><template v-if="detail"><div class="actions"><h2>{{ detail.conversation.title || '知識對話' }}</h2><button class="danger" :disabled="action.busy.value || pending" :title="pending ? '請等待回答完成' : ''" @click="action.error.value = null; deleteOpen = true">刪除對話</button></div><p class="muted">知識庫：{{ detail.conversation.kbIds.map(id => kbs.find(k => k.id === id)?.name ?? id).join('、') || '未指定' }}</p><ErrorBanner :error="threadTask.error.value"><button :disabled="threadTask.busy.value" @click="refreshThread">重新讀取對話</button></ErrorBanner><div class="chat-messages" role="log" aria-label="對話訊息" aria-live="polite"><EmptyState v-if="!detail.messages.length" title="開始第一個問題" description="提出問題後，回答與引用來源會顯示在這裡。" /><article v-for="message in detail.messages" :key="message.id" class="message" :class="{ user: message.role === 'USER' }"><header><strong>{{ message.role === 'USER' ? '您' : 'Atlas' }}</strong><StatusBadge :state="message.status" /><small class="muted">{{ date(message.createdAt) }}</small></header><p class="message-content">{{ message.content }}</p><Spinner v-if="message.status === 'PENDING'" label="正在整理回答，每 2 秒更新…" /><p v-if="message.meta.error" class="error-banner">{{ message.meta.error.code }}：{{ message.meta.error.message }}</p><p v-if="message.role === 'ASSISTANT' && message.status === 'REFUSED' && !message.content" class="notice">目前證據不足，無法提供有來源支持的回答。</p><div v-if="message.citations.length" class="citation-list"><button v-for="item in message.citations" :key="item.ordinal + ':' + item.chunkId" class="citation-chip" :aria-label="'引用 ' + item.ordinal + '：' + item.title" @click="openCitation(item)">[{{ item.ordinal }}] {{ item.title }}{{ item.state === 'SUPERSEDED' ? ' · 舊版本' : item.state === 'UNAVAILABLE' ? ' · 無法存取' : '' }}</button></div><p v-else-if="message.role === 'ASSISTANT' && message.status === 'COMPLETE'" class="muted">此回答未附引用，請另行查證。</p><details v-if="message.meta.pipeline?.length"><summary>處理流程</summary><p v-for="(stage,index) in message.meta.pipeline" :key="index" class="muted">{{ stage.name }} · {{ stage.status }} · {{ stage.elapsedMs }} ms</p></details></article></div><form class="panel form-stack" @submit.prevent="send"><label>向知識庫提問<textarea v-model="content" required :disabled="action.busy.value || pending" placeholder="輸入您想查證的問題…"></textarea></label><label v-if="quality">回答模型<select v-model="model" :disabled="action.busy.value || pending"><option value="fast" :disabled="!status.status?.models.some(m => m.id === 'fast' && m.available)">fast · 快速</option><option value="quality">quality · 高品質</option></select></label><p v-if="!modelAvailable" class="notice">目前選用模型暫不可用，請稍後再試。</p><p v-if="pending" class="muted">請等待目前回答完成後再傳送。</p><ErrorBanner v-if="!createOpen && !deleteOpen" :error="action.error.value" /><button class="primary" :disabled="!content.trim() || action.busy.value || pending || !modelAvailable">{{ action.busy.value ? '傳送中…' : '傳送問題' }}</button></form></template><EmptyState v-else-if="!busy && !error" title="選擇或新增對話" description="新增對話時可選擇本次回答使用的知識庫。" /></section></div>
<Modal v-if="createOpen" title="新增對話" :busy="action.busy.value" @close="createOpen = false"><form class="form-stack" @submit.prevent="create"><label>對話標題（可留空）<input v-model="newTitle" :disabled="action.busy.value"></label><fieldset :disabled="action.busy.value"><legend>選擇知識庫（至少一個）</legend><label v-for="kb in kbs" :key="kb.id" class="check-label"><input v-model="kbIds" type="checkbox" :value="kb.id">{{ kb.name }}</label></fieldset><ErrorBanner :error="action.error.value" /><button class="primary" :disabled="action.busy.value || !kbIds.length">建立對話</button></form></Modal>
<ConfirmDialog v-if="deleteOpen" title="刪除對話" message="確定刪除此對話及其訊息？" :busy="action.busy.value" :error="action.error.value" @close="deleteOpen = false" @confirm="remove" />
<Modal v-if="citation" title="引用來源" side :busy="sourceTask.busy.value" @close="citation = null"><h3>[{{ citation.ordinal }}] {{ citation.title }}</h3><div class="actions"><StatusBadge :state="citation.state" /><span>v{{ citation.versionNo }}</span></div><p class="muted">{{ locator(citation.locator) }}</p><blockquote v-if="citation.quote">{{ citation.quote }}</blockquote><p v-if="citation.state === 'UNAVAILABLE'" class="notice">此來源目前無法存取，引用原文已隱藏。</p><p v-if="citation.state === 'SUPERSEDED' && !sourceNotice" class="notice">此引用來自舊版本，請查證版本狀態。</p><p v-if="sourceNotice" role="status" class="notice">{{ sourceNotice }}</p><button :disabled="sourceTask.busy.value || citation.state === 'UNAVAILABLE'" :title="citation.state === 'UNAVAILABLE' ? '來源目前無法存取' : ''" @click="viewSource">檢視來源原文</button><Spinner v-if="sourceTask.busy.value" /><ErrorBanner :error="sourceTask.error.value" /><section v-if="source"><h3>{{ source.heading || source.title }}</h3><p class="muted">v{{ source.versionNo }} · {{ locator(source.locator) }}</p><pre>{{ source.content }}</pre></section></Modal></template>

