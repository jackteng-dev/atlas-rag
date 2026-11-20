<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { RouterLink, RouterView, useRoute, useRouter } from 'vue-router'
import { useSessionStore } from '../stores/session'
import { useToastStore } from '../stores/toast'
import { api } from '../api/client'
import { useTask } from '../composables/useTask'
import Modal from '../components/Modal.vue'
import ErrorBanner from '../components/ErrorBanner.vue'
const session = useSessionStore()
const route = useRoute(); const router = useRouter(); const toast = useToastStore()
const collapsed = ref(false); const drawer = ref(false); const passwordOpen = ref(false)
const { busy, error, run } = useTask()
const passwords = ref({ currentPassword: '', newPassword: '' })
const currentOrg = computed(() => session.me?.memberships.find(m => m.orgId === session.currentOrgId))
const links = computed(() => [
  ['overview', '總覽', '◈'], ['chat', '知識對話', '◎'], ['knowledge-bases', '知識庫', '▦'], ['documents', '文件', '▤'], ['agents', 'Agents', '◇'],
  ...(session.canApprove ? [['approvals', '審核', '✓']] : []),
  ...(session.canAdmin ? [['connectors', '連接器', '⇄']] : []), ['evaluation', '評估', '△'],
  ...(session.canAdmin ? [['usage', '用量', '◷'], ['audit', '稽核', '≡'], ['settings', '組織設定', '⚙']] : []),
])
watch(() => route.fullPath, () => { drawer.value = false })
function switchOrg(e: Event) {
  const value = (e.target as HTMLSelectElement).value
  if (session.switchOrg(value)) void router.push('/o/' + encodeURIComponent(value) + '/overview')
}
async function logout() { await run(async () => { await session.logout(); await router.replace('/login') }) }
async function changePassword() {
  await run(async () => { await api.changePassword(passwords.value); passwordOpen.value = false; toast.show('密碼已更新，其他登入工作階段已撤銷。') })
  passwords.value = { currentPassword: '', newPassword: '' }
}
function openPassword() { passwords.value = { currentPassword: '', newPassword: '' }; error.value = null; passwordOpen.value = true }
</script>
<template><div class="app-layout" :class="{ compact: collapsed }">
  <a class="skip-link" href="#platform-main">跳至主要內容</a>
  <button v-if="drawer" class="drawer-backdrop" aria-label="關閉導覽" @click="drawer = false"></button>
  <aside id="platform-sidebar" class="app-sidebar" :class="{ open: drawer }" @keydown.esc="drawer = false">
    <div class="sidebar-brand"><span class="atlas-mark" aria-hidden="true">A</span><strong class="nav-label">Atlas<small>KNOWLEDGE PLATFORM</small></strong></div>
    <button class="collapse-toggle" :aria-expanded="!collapsed" aria-label="切換側欄寬度" @click="collapsed = !collapsed">{{ collapsed ? '展開' : '收合側欄' }}</button>
    <button class="mobile-only" @click="drawer = false">關閉導覽</button>
    <label class="org-switch nav-label">目前組織<select :value="session.currentOrgId" :disabled="!session.me?.memberships.length" @change="switchOrg"><option v-if="!session.me?.memberships.length" value="">尚未加入組織</option><option v-for="org in session.me?.memberships" :key="org.orgId" :value="org.orgId">{{ org.orgName }}</option></select></label>
    <nav aria-label="主要導覽"><template v-if="session.currentOrgId"><RouterLink v-for="[path, label, icon] in links" :key="path" :to="'/o/' + encodeURIComponent(session.currentOrgId) + '/' + path" :title="label"><span aria-hidden="true">{{ icon }}</span><span class="nav-label">{{ label }}</span><span class="sr-only" v-if="collapsed">{{ label }}</span></RouterLink></template>
    <RouterLink to="/orgs/new" title="建立組織"><span aria-hidden="true">＋</span><span class="nav-label">建立組織</span><span v-if="collapsed" class="sr-only">建立組織</span></RouterLink>
    <RouterLink v-if="session.isSystemAdmin" to="/admin" title="系統管理"><span aria-hidden="true">⊞</span><span class="nav-label">系統管理</span><span v-if="collapsed" class="sr-only">系統管理</span></RouterLink></nav>
    <p class="sidebar-footer nav-label">可靠知識，皆有依據。<br><small>ATLAS PLATFORM</small></p>
  </aside>
  <div class="app-main"><header class="app-topbar"><button class="mobile-only" aria-controls="platform-sidebar" :aria-expanded="drawer" @click="drawer = !drawer">☰ 導覽</button><span class="org-name">{{ currentOrg?.orgName ?? 'Atlas' }} <small>{{ session.currentRole }}</small></span><details class="user-menu"><summary>{{ session.me?.user.displayName || session.me?.user.email }} ▾</summary><div class="user-menu-panel"><small>{{ session.me?.user.email }}</small><button @click="openPassword">變更密碼</button><button :disabled="busy" @click="logout">登出</button></div></details></header>
    <main id="platform-main" tabindex="-1" class="page-content"><slot name="header" /><ErrorBanner v-if="!passwordOpen" :error="error" /><RouterView v-slot="{ Component }"><component :is="Component" :key="route.fullPath" /></RouterView></main>
  </div>
  <Modal v-if="passwordOpen" title="變更密碼" :busy="busy" @close="passwordOpen = false"><form class="form-stack" @submit.prevent="changePassword"><label>目前密碼<input v-model="passwords.currentPassword" type="password" autocomplete="current-password" required :disabled="busy"></label><label>新密碼<input v-model="passwords.newPassword" type="password" autocomplete="new-password" required :disabled="busy"></label><ErrorBanner :error="error" /><button class="primary" :disabled="busy">{{ busy ? '更新中…' : '更新密碼' }}</button></form></Modal>
</div></template>

